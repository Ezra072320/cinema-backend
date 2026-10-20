# 后端全面审查报告与完善规划

基准日期：2026-10-20。审查对象：cinema-server（Spring Boot 3.5.6 单体、MyBatis-Plus 3.5.9、MySQL 8 + Redis）。前提约束：**保留单体架构**，不涉及前端改造。本文由安全、订单交易、排片座位、影片与外部集成、数据层横切、评价统计账户六个专项审查交叉汇总去重而成，行号以当前工作区源码为准。

> **实施状态（2026-10-20）**：本报告所列 P0、P1 及可代码化的 P2 已全部修复，Phase 0~3 已实施、Phase 4 完成测试补齐与文档同步。`mvn test` 结果 237 项：232 通过、5 项外部集成测试按配置跳过。仓库内凭据已清零（**仍需在云控制台轮换历史泄露的 OSS/DashScope/数据库口令**），Flyway 已接管迁移（V1~V24），MyBatis-Plus 统一改造完成（报表聚合 SQL 除外，均有注释说明）。以下保留原始审查记录，作为变更依据与回归清单；实施细节以当前源码为准。

---

## 1. 总体评价

系统的**领域设计与并发意识明显优于同规模项目**：条件 UPDATE 做 CAS 贯穿订单写路径、座位版本锁定叙事完整、退款档位纯函数化并有边界测试、营收口径严谨（异常退款单独列示不臆造）、AI 身份注入与待确认服务单闭环是教科书式设计、迁移脚本注释与自检质量高、README 高度诚实。

但存在**三类结构性短板**：

1. **安全基线不足**：真实云凭据提交入库、JWT 密钥可伪造管理员、密码哈希随接口回传、验证码可绕过、管理端 mass-assignment——任一条都可在上线后被直接利用。
2. **交易与版本并发存在真实缺口**：超时关单非原子会产生永久孤儿票、取消场次退款名单用快照读会漏退、排片改期/草稿保存存在静默数据损坏路径。这些不是理论问题，触发窗口清晰。
3. **工程化欠账**：无迁移器（字典序执行会产出错误 schema）、核心表缺索引、缓存体系空转、资金流零审计、无可观测性设施、手写 SQL 与 MyBatis-Plus 混用风格分裂。

工程化成熟度评估（1~5）：

| 维度 | 评分 | 一句话理由 |
| --- | --- | --- |
| 迁移 | 2 | 脚本质量高但纯手工 SOURCE，7/21 不可重跑，顺序靠人记 |
| 索引 | 2 | 已补的有针对性，但订单/票/排片三张核心表最热查询仍缺索引 |
| 缓存 | 1 | @EnableCaching 开了全项目零 @Cacheable，热点数据每请求重算 |
| 可观测性 | 2 | 领域日志有基础，但资金流零审计、无 actuator/traceId |
| 测试 | 3 | 145 例本地可跑、SQL 语义有回归网，但无并发/真实 MySQL/全上下文测试 |
| 安全 | 2 | IDOR/JWT fail-fast/SQL 注入面干净，但凭据与认证链路硬伤明显 |

---

## 2. 关键问题清单

### 2.1 P0 —— 立即处理（可被利用 / 可造成资损与数据损坏）

| 编号 | 问题 | 位置 | 触发场景与影响 |
| --- | --- | --- | --- |
| P0-1 | **真实凭据硬编码进配置默认值**：OSS AccessKey/Secret、DashScope Key、MySQL root 口令以 `${ENV:默认值}` 形式提交，dev 为默认激活 profile | `application-dev.yml:23,34-37`；`application.yml:26,40,12` | 拿到源码即获得 OSS 桶完全读写（可删改全部海报）、模型额度盗刷、数据库口令泄露；忘设环境变量时直接以真凭据起服 |
| P0-2 | **可伪造 ADMIN JWT**：仓库内公开的 dev 密钥 + 默认 profile 即生效；短密钥被 `\0` 补零到 32 字节而非拒绝 | `application-dev.yml:46`；`JwtUtil.java:38-47` | 攻击者用已知密钥自签 `role=ADMIN` 令牌获得全量管理权限；`JWT_SECRET=abc` 也能起服 |
| P0-3 | **超时关单非原子**：`closeIfExpired` 无事务，置 4 与放票两段独立提交，失败后所有释放路径只认 status=1，零自愈 | `OrderServiceImpl.java:142-154`；`OrderCloseJob.java:44-50` | 崩溃/锁超时夹在两步之间 → 订单已关、票永久锁死，选座页座位灰死且无日志可发现 |
| P0-4 | **取消场次退款名单用 RR 快照 SELECT，与 DML 的 current read 口径分裂** | `ScheduleServiceImpl.java:409-431`（:418-420 快照挑单；:390 `voidBySchedule` 全表 DML） | 取消瞬间用户支付提交：关单 UPDATE 跳过它、快照看不见它、票却被作废 → **收钱、无票、无退款** |
| P0-5 | **saveDraft 坐标索引残留 bug**：移动座位后 `byCoord` 旧坐标键悬空，同坐标新增座位被吞、拖拽被静默回滚 | `SeatVersionServiceImpl.java:231-239` | 管理员一次保存内「移动 A 到新位置 + 在旧位置新增 B」→ 数据静默损坏，无报错 |
| P0-6 | **batchMove/update 冲突检查漏判**：先建 RR 快照后 `lockHall`，`listConflict` 读旧快照，看不到窗口内并发改期/排片的新场次 | `ScheduleServiceImpl.java:168-199,324-350` | 两管理员并发改期/排片 → **同一厅时段重叠双卖**。`add/batchAdd` 锁在首句是安全的，漏的恰恰是改期路径 |

### 2.2 P1 —— 重要缺陷（按主题去重合并）

**安全与认证**

| 编号 | 问题 | 位置 | 修复要点 |
| --- | --- | --- | --- |
| P1-S1 | 密码哈希随接口回传（User/Admin 实体无 `@JsonIgnore`），管理员列表一次拿到全库口令哈希 | `User.java:29`、`Admin.java:25`；出口 `UserController.java:59-79`、`UserManagementController.java:29-43`、`AdminController.java:59/67/98` | `@JsonProperty(access=WRITE_ONLY)` 或脱敏 VO |
| P1-S2 | 固定 salt MD5 且 `BCryptPasswordEncoder` 定义后零调用，配置给出「已是 BCrypt」假象；注册仅要求 3 位口令 | `PasswordUtil.java:14-38`；`SecurityConfig.java:44-46` | 登录时 on-login rehash 惰性迁移 BCrypt；口令策略 ≥8 位 |
| P1-S3 | 验证码可完全绕过（不传 ticket 即跳过）+ 全站无登录限流/锁定；验证码消费三步非原子、失败不消耗 | `UserController.java:40-46,61-66`；`AdminController.java:40-46`；`CaptchaService.java:73-88` | prod 强制验证码；Redis 限流与失败锁定；GETDEL/Lua 原子消费 |
| P1-S4 | 管理端「编辑用户」自拷贝 no-op：`BeanUtil.copyProperties(user, user, ...)` ignore 列表不生效，可写入明文口令、篡改 createTime、静默解冻（`status=1` 字段初始化） | `UserManagementController.java:46-52`；`UserServiceImpl.java:131-136` | 专用 DTO + 白名单字段 update，逻辑下沉 Service |
| P1-S5 | 文件上传无类型白名单、Content-Type 信任客户端、两条上传链路 ACL 不一致（`upload` 不设 ACL / `uploadBytes` public-read）、任意登录用户可传 10MB 任意文件 | `FileController.java:25-28`；`AliOssUtil.java:33-104` | 扩展名+魔数双白名单、服务端定 Content-Type、统一 ACL、按用户配额 |
| P1-S6 | Swagger/knife4j prod 未关且 permitAll 放行；CORS 通配源+allowCredentials | `SecurityConfig.java:84-88,107-119`；`application-prod.yml` | prod 关文档；按环境配 origin 白名单 |
| P1-S7 | JWT 角色/账号状态签发后不复核：冻结、降级、改密最长 12 小时内不生效，无注销/黑名单 | `JwtAuthenticationFilter.java:31-60`；`JwtUtil.java:33-60` | 敏感操作回查 DB 状态或 token 版本 claim / jti 黑名单 |
| P1-S8 | 全局异常兜底返回 `ex.getMessage()`（可含 SQL/表结构），且业务/校验/未知异常一律 HTTP 200 | `GlobalExceptionHandler.java:29-50,82-86` | 兜底固定文案+traceId；按 ResultCode 映射 4xx/5xx |

**订单交易**

| 编号 | 问题 | 位置 | 修复要点 |
| --- | --- | --- | --- |
| P1-T1 | 支付零落库痕迹：channel 校验后丢弃，无 pay_time/pay_channel/流水表；退款也只有覆盖写无历史留痕；`schedule-cancel.md` 承诺的取消操作日志不存在 | `OrderServiceImpl.java:264-305`；`schema_new.sql:113-121` | `order` 补 pay_time/pay_channel 或建 payment 流水表；建 `order_action`/`admin_operation_log` 审计表 |
| P1-T2 | 索引缺口（与数据层合并）：`ticket.order_id`、`order(user_id)`、`order(schedule_id)`、`order(create_time)`、`order.number` 无索引/无唯一键 | `TicketMapper.xml:9-27`；`2026_10_19__order_refund_and_close.sql:36` | 见 P1-D2 统一索引迁移 |
| P1-T3 | `pay()` 惰性关单被自身事务回滚（close 后抛 BusinessException → 回滚），「先关再拒付」是死逻辑 | `OrderServiceImpl.java:281-284` | 惰性关单走 REQUIRES_NEW 或前置执行 |
| P1-T4 | 负价/超大价可穿透：`price` 无 `@DecimalMin/@DecimalMax`，乘积未 setScale，DECIMAL(8,2) 溢出直接 500 | `ScheduleAddDTO.java:21`；`TicketServiceImpl.java:84` | DTO+服务双层校验；`setScale(2, HALF_UP)` |
| P1-T5 | 订单号时间戳+6 位随机无 UNIQUE，同秒碰撞静默接受 | `OrderNumberGenerator.java:20-26` | UNIQUE + 冲突重试 |
| P1-T6 | 状态机魔法数（1/2/3/4/6/5 哨兵）约 40 处分散，无枚举；`docs/schedule-cancel.md` 状态语义已过时（4=退款中） | `OrderServiceImpl` 全域 | `OrderStatus` 枚举 + 流转断言；修订文档 |

**排片 / 影厅 / 座位版本**

| 编号 | 问题 | 位置 | 修复要点 |
| --- | --- | --- | --- |
| P1-H1 | `applyDraft` 不持厅锁可绕过「有未放映场次拒绝发布」守卫；忽略 `updateById` 结果，可把 current 指向已删版本（capacity=0 永久挡排片） | `SeatVersionServiceImpl.java:379-431` | 入口 `lockHall`；判断影响行数，0 行抛 409 |
| P1-H2 | `getOrCreateDraft` 并发双插撞 `uk_hall_version` 直接 500 | `SeatVersionServiceImpl.java:89-157` | catch DuplicateKey 回读草稿 / 建草稿也锁厅 |
| P1-H3 | saveDraft×applyDraft 竞态：已发布版本可被继续编辑，「老版本永不变」不变量失守 | `SeatVersionServiceImpl.java:178-268,379-432` | applyDraft 走 `tryLockDraft` 原子发布；saveDraft 写前复查版本状态 |
| P1-H4 | `update` TOCTOU：`selectById` 在 `lockHall` 前，已取消场次可被改期写入时间字段（「复活」） | `ScheduleServiceImpl.java:323-363` | 锁后重读或先锁厅 |
| P1-H5 | `voidBySchedule` 把 order_id 置 -1：已退款订单历史座位永久丢失，「已售数」归零使 delete 守卫失效 | `TicketMapper.xml:50-54` | 作废只改 status 保留 order_id，或拆分「作废/解绑」 |
| P1-H6 | 情侣座 DB 约束可跨列绕过（同座可进不同对的两列）；目前仅服务层内存校验兜底 | `2026_10_16__seat_couple_group.sql:36-38` | 生成列 LEAST/GREATEST + 双唯一键 |

**影片 / AI / 外部集成**

| 编号 | 问题 | 位置 | 修复要点 |
| --- | --- | --- | --- |
| P1-M1 | prod profile 不 fail-fast：未注入 JWT_SECRET/口令仍用明文默认起服（与 P0-1/P0-2 同源，列为独立验收项） | `application-prod.yml` | prod 缺必填配置即中止启动 |
| P1-M2 | AI 生成无用户级限流/额度：开 N 会话即 N 路并发 qwen-max；`running` 互斥仅 JVM 内且无 TTL | `PersistentAiChatService.java:97,125`；`AiController.java:73-77` | 按 userId 信号量+日额度（Redis）；max-tokens 配置；running 加过期 |
| P1-M3 | 同步/工具失败日志吞堆栈（`ex.getMessage()`、`Exception ignored`），外部集成排障无根因 | `MovieSyncService.java:201,214-234`；`AiTool.java:231-234`；`MaoyanClient.java:94,105` | log.warn 带完整堆栈 |
| P1-M4 | 外部 HTTP 全链路无重试/退避：一次瞬时 5xx/闪断整任务 FAILED | `MaoyanClient.java:122-158`；`TmdbClient.java:27-65` | 幂等 GET 加 1~2 次抖动退避重试 |
| P1-M5 | 判重键跨来源绕过窗口：译名不一致/残缺老记录可重复入库；同步互斥仅 JVM 内 | `MaoyanImportServiceImpl.java:154-175` | 判重并入 alt_title+标题归一化；多实例用 GET_LOCK |
| P1-M6 | `saveBasicMetadata` 自身不强制 lockedFields 保护，依赖调用方约定 | `MaoyanImportServiceImpl.java:117-141` | 写入口内聚 `MovieMetadataPolicy.filter` |
| P1-M7 | 简介 HTML 清洗顺序错误（先 clean 后 unescape，实体编码标签被还原入库）；TMDB overview 未处理 | `MainlandMovieFetcher.java:87`；`TmdbMovieService.java:202` | 顺序反转/循环到稳定 + 入库白名单剥离 |
| P1-M8 | `ai_action` 过期行无清理、会话/消息无保留期；SSE 帧契约分叉（token 字符串字面量 vs 错误帧裸对象） | `AiActionService.java:89,218-223`；`PersistentAiChatService.java:138-151` | 定时清理任务；帧契约统一并写契约测试 |

**数据层与横切**

| 编号 | 问题 | 位置 | 修复要点 |
| --- | --- | --- | --- |
| P1-D1 | 无迁移器：21 脚本手工 SOURCE、7 个不可重跑、**按字典序执行会产出错误 schema**（hall_capacity 先于 hall_seat_version 会把 capacity 置零且 total_seats 被加回）；`dbrun.sh` 按分号切分对 8 个含 DELIMITER 脚本静默失效且 `\|\| true` 吞错 | `README.md:67-91`；`tools/dbrun.sh:13-24` | Flyway 基线化（V1~V21 按已验证顺序 + baseline），详见 §4 |
| P1-D2 | 核心表索引缺口：`ticket(order_id)`、`order(user_id,status,create_time)`、`order(schedule_id)`、`order(create_time)`、`order.number UNIQUE`、`schedule(hall_id,start_time)`、`schedule(movie_id,start_time)`、`schedule(movie_id,status,end_time)`、`score(user_id)` | 各 Mapper/SQL 调用点 | 一条补齐迁移 + EXPLAIN 验证 |
| P1-D3 | Druid 零调优全默认：`maxWait=-1`（连接泄漏时业务线程无限挂起）、无 validation/keepalive、URL 无 connect/socketTimeout | `application.yml:21-26` | 显式配置 Druid 参数或切 Hikari；URL 补超时 |
| P1-D4 | MyBatis `StdOutImpl` 全局 SQL+参数明文打 stdout，prod 未覆盖（含 password 哈希、AI 消息） | `application.yml:49-52` | Slf4jImpl + 按 profile 控级别 |
| P1-D5 | 缓存空转：@EnableCaching 全项目零 @Cacheable；spring-session-data-redis 无消费者；热点（影片详情/座位标签/概览）每请求重算 | `CinemaServerApplication.java:21-22` | 见 §5 Phase 3 缓存清单 |
| P1-D6 | 无 actuator/指标/traceId/日志滚动；多处吞异常不留栈 | `pom.xml` | actuator health+prometheus、MDC traceId、logback 滚动 |
| P1-D7 | 订单列表 VO 重度 N+1（一页 20 单约 300+ SQL，叠加 ticket 全表扫）；`pay` 在持行锁事务内调 `detail()`（~10 条 SQL）放大锁窗口 | `OrderServiceImpl.java:351-399,304` | 批量预取（照搬 `ScheduleServiceImpl.java:493-502` 模式） |
| P1-D8 | 自定义 ObjectMapper 覆盖 Boot 默认：FAIL_ON_UNKNOWN_PROPERTIES=true（多传一字段即报错）、时间序列化三重副作用 | `JsonConfig.java:20-34` | 改 Jackson2ObjectMapperBuilderCustomizer |

**评价 / 统计 / 账户**

| 编号 | 问题 | 位置 | 修复要点 |
| --- | --- | --- | --- |
| P1-R1 | AI 评价服务单资格/文案仍是旧状态机：preview 只认 status==2（拒 6）、不查散场/已有评价、四处文案承诺「评价后订单已完成」 | `AiActionService.java:128-133,189-195`；`AiTool.java:155,205`；`rag/theater-service.txt:15,25-26` | preview 复用 `ReviewPolicy.check`；同步修正文案与指南 |
| P1-R2 | 改密 `newPassword=null` 触发 NPE（只有 @Pattern 无 @NotBlank） | `UserServiceImpl.java:92`；`AdminController.java:89` | `@NotBlank` + 服务端防护 |
| P1-R3 | `/api/admin/user/add` 无任何入参校验（@RequestBody User 直入） | `UserManagementController.java:37-43` | 复用 register DTO + @Valid |
| P1-R4 | 时区/时钟混用：散场判定与超时用 JVM 时钟，营收用 Asia/Shanghai，SQL 用 NOW()——JVM 时区非东八区时错位 | `ReviewPolicy.java:19`；`RevenueService.java:27,46`；`ScheduleMapper.xml:48` | 统一注入 `Clock`（Asia/Shanghai），启动打印时区 |
| P1-R5 | 管理员冻结无自我/最后一名保护，status 无白名单（可写 99） | `AdminController.java:103-113` | status∈{0,1}；禁冻自己；保至少一名启用管理员 |

### 2.3 P2 —— 改进项（择要）

- 时钟回拨依赖 `LocalDateTime.now()`（超时/退款边界）；统一 Clock 后可顺带解决。
- 超时扫描 `LIMIT 200` 无 ORDER BY，热门场次集中到期时座位释放滞后。
- `toVO`/`countByStatus` 循环 COUNT 5 次；`listByUser` 无分页；admin 关键词 `LIKE '%kw%'` 不走索引。
- `mine()` 缺只读事务；`refund_amount=0` 边界进「有效退款」的口径待产品确认。
- 实体直出 API（movie 内部治理字段外泄）；DTO/VO 边界缺失。
- 死代码/冗余依赖：`BeanCopier`、`SqlInitIgnore`、pagehelper、fastjson2、spring-ai vector-store、H2 进 fat jar、无 @Version 却注册乐观锁拦截器。
- 硬删用户产生孤儿业务数据（评价侧已兜底显示「观影用户」，但无级联策略）。
- 注册重名并发 500（DuplicateKey 未转业务码）。
- `batchAdd` 非 BusinessException 整批回滚与「部分成功」文档承诺不符。
- 同步 `results` 无上限、每次对全部待映影片逐片网络刷新无条件节流；图片下载绕过限速器。
- `config/tmdb-local.yml` 依赖启动 CWD，换目录静默降级无告警。
- 仓库卫生：`app.log`/`.admin_token` 未进 .gitignore；目录非 git 仓库、无 CI。
- 文档漂移：`schedule-cancel.md` 状态模型过时、`mainland-movie-sync.md` 自相矛盾、schema.sql/schema_new.sql 落后真实结构应标 DEPRECATED。

---

## 3. 做得好的地方（改造中必须保留）

1. **条件 UPDATE 做 CAS** 贯穿 pay/refund/cancel/close（`WHERE status=?` + 影响行数判定），锁票「影响行数==请求数」不足整单回滚。
2. **DB 约束真落地**：`uk_schedule_seat`、`uk_version_xy`、`uk_hall_version`、`score.order_id UNIQUE` 兜住服务层 bug。
3. **OrderRefundPolicy 纯函数 + 179/180/360/361 边界测试**；退款 `setScale(2, HALF_UP)`；营收 unknown 退款单独列示不臆造。
4. **座位版本叙事完整**：场次锁版本 → 新版本不动旧票 → capacity 从座位图单源计算；`ScheduleUpdateDTO` 显式拒收 hallId/price/multiplier。
5. **AI 身份注入教科书式**：身份只从服务端 ToolContext 进入，17 个工具 schema 均无 userId；服务单 confirm 时 FOR UPDATE + 重查比对 summary + 幂等 + 5 分钟过期。
6. **影片同步「绝不写坏数据」**：insert-only + 外部 ID 唯一键 + lockedFields 保护 + 防御性解析（分页完整性/魔数/域白名单）。
7. **IDOR 系统性修复**：订单/评价/会话/服务单归属校验全覆盖并有注释与测试。
8. **SQL 注入面干净**：全部参数绑定、sort/granularity 白名单、无 `${}`。
9. **迁移脚本工程素质高**：幂等判断、自检 SELECT、动机注释。
10. **测试可重复**：H2 真 SQL 语义测试 + 并发测试（评价模块）+ 外呼测试系统属性门控，默认 `mvn test` 离线 20-30 秒。

---

## 4. 持久层统一：MyBatis-Plus 优先原则（本次规划硬性要求）

**原则：能用 MyBatis-Plus 表达的，一律不写 SQL。** 手写 SQL（XML、@Select、JdbcTemplate）只保留 MP 无法清晰表达的场景，并集中在少数查询类中。

### 4.1 现状盘点

| 现存手写 SQL 形态 | 位置 | 处置 |
| --- | --- | --- |
| Mapper XML 单表 CRUD/条件更新 | `TicketMapper.xml`（listByOrder/lockTickets/releaseTickets/voidBySchedule/countOrderedBySchedule）、`ScheduleMapper.xml` 等约 15 个 XML | **改写为 MP** LambdaQueryWrapper/LambdaUpdateWrapper |
| Mapper XML 区间重叠查询 | `ScheduleMapper.xml` listConflict（`start_time < ? AND end_time > ?`） | **改写为 MP** 嵌套 gt/lt 条件 |
| @Select（含 FOR UPDATE） | `OrderMapper.java:7-8` 等 | **改写为 MP** `.last("FOR UPDATE")`（唯一允许的 SQL 片段） |
| JdbcTemplate 聚合/报表 | `RevenueService`（按日/周/月聚合、排名）、`ReviewQueryService`（可见性 CASE + 五分制统计）、`AiCatalogService` | **保留 SQL**（见 4.2 判定标准），但收敛到独立查询组件、全部参数绑定 |
| MP Wrapper 里的 `last("LIMIT n")` | 分页类 | 改用 MP 分页或 `Page` 参数，去掉 last 拼接 |

### 4.2 判定标准（什么允许保留手写 SQL）

保留手写 SQL 必须同时满足：① 多表 JOIN/窗口/日期分桶/条件聚合等 MP Wrapper 表达会显著降低可读性或可维护性；② 需要精确控制 EXPLAIN 执行计划；③ 集中在 `*QueryService` / `*Mapper` 且有 SQL 语义测试钉住。**不满足则一律改 MP。** 典型保留项：营收报表聚合、评价公开统计、影片榜单；典型改写项：一切单表查询、条件更新、存在性检查、计数。

### 4.3 MP 统一写法规范（改造与新代码共同遵守）

1. **类型安全**：一律 `LambdaQueryWrapper`/`LambdaUpdateWrapper`，禁止字符串列名的 `QueryWrapper`（防列名拼错与 `${}` 误用）。
2. **条件更新 = CAS**：`lambdaUpdate().set(...).eq(Entity::getStatus, x).eq(Entity::getId, id)` + 判断 `update()` 返回行数，保留现有并发语义。
3. **归属过滤内聚**：查询统一带 `eq(Entity::getUserId, uid)` 条件，替代 service 里先查后比对。
4. **分页统一** `Page<T>` + `PaginationInnerInterceptor`，设置 `maxLimit`；count 交给拦截器，禁止手写 count SQL。
5. **IN 批量**：列表组装一律 `in(Entity::getId, ids)` 批量预取替代循环单查（消灭 N+1）。
6. **禁止**：XML 里新增 CRUD；`@Select` 新增单表查询；service 内嵌 SQL 字符串；`apply()` 拼接未绑定参数。
7. **实体约定**：查询用 `selectList/selectOne/selectCount`，更新优先 `updateById`+白名单或 `lambdaUpdate`；`selectMaps` 仅限聚合查询返回。
8. **逻辑删除评估**：`user`/`admin` 硬删改软删时启用 `@TableLogic`，与 MP 查询自动过滤统一，不再手写 `status != -1` 类条件。

### 4.4 改造顺序（并入 Phase 2/3，见 §5）

1. 先改**本次要修的查询**（ticket/order 索引配套改写、closeIfExpired 集合式条件更新、listConflict 锁定读）——修 bug 与 MP 化一次完成，避免同一段代码改两遍。
2. 再改订单/票/排片域剩余 XML → MP。
3. 最后评估报表类 JdbcTemplate 保留或迁移；每保留一处，在类注释写明「为何不用 MP」。
4. 每次改写跑对应 SQL 语义测试（`ScheduleSqlTest`、`RevenueServiceTest`、`ReviewModuleTest` 等）作为回归网；改写前后对同一数据集比对结果集。

---

## 5. 完善规划（分阶段）

单体架构保留；每阶段可独立交付与回滚；P0 先行。

### Phase 0 —— 立即止血（1~3 天）

| # | 事项 | 验收标准 |
| --- | --- | --- |
| 0.1 | **轮换全部已泄露凭据**（OSS AK/SK、DashScope、MySQL 口令、JWT 签名密钥），从当前及历史配置移除，改为无默认值环境变量；启动校验必填非占位 | 仓库内 grep 无真凭据；缺 env 直接启动失败 |
| 0.2 | `JwtUtil` 密钥 <32 字节直接拒绝（去掉补零）；默认 profile 改 prod 或 dev 不含任何密钥默认值 | `JWT_SECRET=abc` 启动失败；伪造令牌测试失败 |
| 0.3 | User/Admin 的 password/openid 加 WRITE_ONLY，全部出口脱敏 | 接口响应无哈希字段（写断言测试） |
| 0.4 | 修复 UserManagementController.save 自拷贝（DTO+白名单），移除 `status=1` 字段初始化 | 保存请求带 password 被拒/忽略；冻结用户不被静默解冻 |
| 0.5 | GlobalExceptionHandler 兜底不再回传 `ex.getMessage()` | 异常响应为固定文案 |
| 0.6 | prod 关闭 Swagger/knife4j、StdOutImpl | prod 启动无 SQL 明文日志、/v3/api-docs 404 |
| 0.7 | 仓库卫生：凭据类文件移出、.gitignore 补 app.log/.admin_token/data | git status 干净 |

### Phase 1 —— 正确性与资损（1~2 周）

| # | 事项 | 对应问题 | 验收标准 |
| --- | --- | --- | --- |
| 1.1 | `closeIfExpired` 加事务原子化 + 孤儿票对账扫描（order 已关而票仍占用 → 修复+告警） | P0-3 | 杀进程注入测试不再产生孤儿票；对账任务可发现历史脏数据 |
| 1.2 | 取消场次退款/关单改为**集合式条件 UPDATE（current read）**，`UPDATE schedule SET status=2` 提到事务首句；pay 后复核场次状态 | P0-4 | 两事务交错集成测试：不再出现「已付+票作废+无退款」 |
| 1.3 | 修复 saveDraft 坐标索引残留（先 remove 旧键再放新键） | P0-5 | 「移动+同坐标新增」保存正确；补单测 |
| 1.4 | batchMove/update/applyDraft 统一**先 lockHall 再读**，listConflict 加锁定读（MP `.last("FOR UPDATE")`） | P0-6、P1-H1、P1-H4 | 并发改期×排片测试不再双卖 |
| 1.5 | applyDraft 影响行数校验 + tryLockDraft 原子发布；getOrCreateDraft 捕获 DuplicateKey 回读 | P1-H1~H3 | 并发发布/建草稿测试；发布后不可再编辑 |
| 1.6 | 索引与约束补齐迁移：ticket(order_id)、order(user_id,status,create_time)/(schedule_id)/(create_time)、order.number UNIQUE、schedule(hall_id,start_time)/(movie_id,start_time)/(movie_id,status,end_time)、score(user_id) | P1-T2、P1-D2 | EXPLAIN 全走索引；订单号碰撞重试 |
| 1.7 | 价格校验（@DecimalMin/Max + 服务兜底）+ 票价乘积 setScale(2,HALF_UP) | P1-T4 | 负价/溢出被 400 拒绝 |
| 1.8 | 支付落账（pay_time/pay_channel 或 payment 流水表）+ `order_action`/`admin_operation_log` 审计（pay/refund/close/cancel 各落一条） | P1-T1 | 每笔资金状态变更可追溯 |
| 1.9 | `pay()` 惰性关单移出主事务（REQUIRES_NEW/前置） | P1-T3 | 「超时拒付」时订单真实关闭 |
| 1.10 | voidBySchedule 保留 order_id（仅置 status）；情侣座生成列唯一键 | P1-H5、P1-H6 | 退款订单可回看座位；跨列配对被 DB 拒绝 |
| 1.11 | OrderStatus 枚举收敛状态机（顺带 MP 化相关条件更新，按 §4.4-1） | P1-T6 | 全库无裸状态字面量（除枚举定义） |

### Phase 2 —— 安全加固与工程化（2~4 周）

| # | 事项 | 对应问题 |
| --- | --- | --- |
| 2.1 | 密码迁移 BCrypt（登录时 on-login rehash，老口令零感知）；口令策略 ≥8 位；改密后旧令牌失效（token 版本 claim 或 jti 黑名单 + 登出接口） | P1-S2、P1-S7 |
| 2.2 | 登录风控：prod 强制验证码（原子消费 GETDEL）、IP+账号限流、连续失败锁定 | P1-S3 |
| 2.3 | 文件上传：扩展名+魔数双白名单、服务端定 Content-Type、统一对象 ACL、按用户配额/频率 | P1-S5 |
| 2.4 | CORS 按环境白名单；`/api/admin/user/add` 等补齐 @Valid；StatusDTO 白名单；管理员冻结自我/最后一名保护 | P1-S6、P1-R3、P1-R5 |
| 2.5 | 异常体系：ResultCode → HTTP 语义映射（400/401/403/404/409/500）+ traceId；收敛各控制器手工映射 | P1-S8 |
| 2.6 | **Flyway 基线化**：21 个脚本按 README 已验证顺序改编 V1~V21（剥离 CREATE DATABASE/USE），存量库 `flyway baseline`；此后变更走 V22+；`dbrun.sh` 废弃或改严格模式；schema.sql 标 DEPRECATED | P1-D1 |
| 2.7 | Druid 调优（max-wait 30s、validation、keepalive）或切 Hikari；URL 补 connect/socketTimeout、rewriteBatchedStatements；MyBatis 日志改 Slf4jImpl | P1-D3、P1-D4 |
| 2.8 | actuator（health/info/prometheus 按 profile）+ MDC traceId 过滤器 + logback 滚动；资金/管理操作 log.info 打点 | P1-D6 |
| 2.9 | JsonConfig 修复（删自定义 ObjectMapper，改 BuilderCustomizer；放开未知字段、统一日期格式） | P1-D8 |
| 2.10 | 时区/时钟统一：注入 `Clock`（Asia/Shanghai），SQL 时间参数由 Java 传入替代 NOW() 双源 | P1-R4 |
| 2.11 | **MP 统一第一批**（§4.4-1/2）：订单/票/排片域 XML → LambdaWrapper，FOR UPDATE 走 `.last`；报表 SQL 保留并注释理由 | §4 |

### Phase 3 —— 性能与稳定性（1~2 月）

| # | 事项 | 对应问题 |
| --- | --- | --- |
| 3.1 | 消灭 N+1：订单列表批量预取（schedule/movie/hall/seatLabel/ticket 按 id IN 分组）；`pay` 返回轻量 VO；countByStatus 一条 GROUP BY；电影「在映」判断改一次预取 | P1-D7 |
| 3.2 | 缓存落地：movie.info（变更时 evict）、SeatLabelService.labelsOf（版本冻结后长 TTL）、统计概览 30-60s；删除未用 spring-session | P1-D5 |
| 3.3 | AI 治理：用户级并发/日额度（Redis）、max-tokens、上下文按字符截断、running 带 TTL、ai_action/消息保留期清理任务、SSE 帧契约统一+契约测试 | P1-M2、P1-M8 |
| 3.4 | 外部集成韧性：幂等 GET 重试退避、图片下载并入限速、同步 results 限长、判重并入 alt_title+归一化、saveBasicMetadata 内聚 lockedFields、HTML 清洗顺序修复、失败日志带堆栈 | P1-M3~M7 |
| 3.5 | 超时扫描改进：按 create_time ASC 排序、动态批量、积压指标 | P2 |
| 3.6 | **MP 统一第二批**（§4.4-3）：剩余 Mapper XML/@Select 清零（报表除外）；PaginationInnerInterceptor 设 maxLimit；评估 @TableLogic 软删 | §4 |
| 3.7 | 线程池纳管：具名 ThreadPoolTaskExecutor（同步/AI/清理分池）、MovieSyncService 改 Spring 生命周期+优雅停机 | P2 |

### Phase 4 —— 质量与治理（持续）

| # | 事项 | 对应问题 |
| --- | --- | --- |
| 4.1 | 并发测试补齐（真实 MySQL/Testcontainers）：同座双下单、pay/cancel/超时竞态、取消场次×支付（P0 复现）、saveDraft×applyDraft、双 batchMove；复用 ReviewModuleTest 并发模式 | 各专项测试缺口 |
| 4.2 | 单测补齐：pay() 全路径、refund 成功路径、saveDraft 坐标、SeatVersionService 全生命周期、情侣对正例、OrderNumberGenerator | 同上 |
| 4.3 | `@SpringBootTest` 全上下文冒烟 + 测试 DDL 统一（引用迁移产物或 test-schema.sql，消除三套结构漂移）；H2/MySQL 语义差异验证 | P2 |
| 4.4 | CI 流水线：mvn test + secret 扫描（gitleaks）+ 依赖 CVE 扫描；live 测试门控显式 assumeTrue | P2 |
| 4.5 | 文档同步：schedule-cancel.md 状态模型、mainland-movie-sync.md、theater-service.txt 评价语义（P1-R1）、README 边界章节按本报告更新；建立「AI 指南随规则变更同步清单」 | 文档漂移 |
| 4.6 | API 边界收敛：实体直出改 VO（movie 内部字段、user 敏感字段）；分页参数统一校验 | P2 |
| 4.7 | 死代码清理：BeanCopier、SqlInitIgnore、pagehelper、fastjson2、spring-ai vector-store、H2 改 test scope、乐观锁拦截器 | P2 |
| 4.8 | 多实例部署前置评估：同步互斥、AI running、限速器均 JVM 内状态——上多副本前引入 Redis/DB 锁（GET_LOCK/SETNX+TTL），或在部署文档明确单实例约束 | 各专项存疑 |

---

## 6. 测试与验收策略

1. **每项 P0/P1 修复必须带回归测试**；并发类问题必须用真实 MySQL（Testcontainers）复现窗口，Mockito stub 测不出快照语义（现有 ScheduleServiceImplTest 即因此漏掉 P0-4/P0-6）。
2. **MP 化改写以结果集比对为准**：改写前后同数据集跑 SQL 语义测试（ScheduleSqlTest/RevenueServiceTest/ReviewModuleTest/MovieTopSqlTest）。
3. **安全修复以攻击用例验收**：伪造 JWT、绕验证码、mass-assignment 写口令、上传 html、响应含哈希——各写一条应失败的测试。
4. 基线保持 `mvn test` 离线可跑（当前 145 例）；新增 Testcontainers 模块可单独 profile 触发，不拖慢默认构建。

## 7. 风险与边界说明

- 本报告为**静态审查**结论：P0-4/P0-6 的 RR 快照推演基于 MySQL 默认 REPEATABLE_READ（配置未显式声明隔离级别），落地前先写两事务交错集成测试坐实。
- 存量库执行迁移前先备份；`uk_schedule_seat`、`score.order_id UNIQUE` 等唯一键需确认历史库是否成功建成（迁移脚本自述「有重复数据会建失败」）。
- 支付/退款仍为模拟通道；接真实支付时**不可沿用** `pay()` 路径，需服务端下单+回调验签，审计表（Phase 1.8）届时作为挂载点。
- 演进中保持「单体保留」前提；模块边界继续按 domain 划分，跨域调用已有事务边界注释的写法保持一致。
