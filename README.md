# cinema-cloud · 猫头鹰影院微服务后端

账户、影片、票务交易、AI 客服分别部署，Gateway 统一对外。技术栈为 Java 21、Spring Boot 3.5.6、Spring Cloud / Alibaba 2025.0、Nacos 3.0.3、OpenFeign、Sentinel、MySQL 8 和 Redis 7。

当前目标是**本地单实例微服务技术栈学习**，取消并发容量、集群和应用上云计划。应用与新增中间件各一个本地实例，千问 API 与阿里云 OSS 继续使用，上传文件仍写入现有 OSS Bucket；计划以 RocketMQ、Seata、Elasticsearch、XXL-JOB、Redisson、SkyWalking 和监控等能力扩展业务。重新复核后的模块结构、技术清单、启动组和实施顺序见 [当前本地单实例方案](docs/local-singleton-stack.md)，[官方核验记录](docs/research-local-stack-2026-10-22.md)说明维护状态和兼容性。新增能力尚未全部实装，旧容量、RabbitMQ与集群选型为历史设计。

## 模块

| 模块 | 本地 Java 端口 | 职责 |
| --- | ---: | --- |
| cinema-gateway | 8080 | 统一入口、共享限流、超时、熔断 |
| cinema-identity | 8081 | 账户、管理员、验证码、上传 |
| cinema-catalog | 8082 | 影片资料、同步、想看 |
| cinema-trade | 8083 | 影厅、排片、票、订单、模拟支付退款、评价、营收 |
| cinema-ai | 8084 | 对话、确认服务单、业务编排 |
| cinema-notify | 8085 | 消费去重、站内信、WebSocket；消息组启用 |
| cinema-search | 8086 | ES 全文检索、高亮、版本化消费和可恢复重建；mq-search组启用 |
| cinema-benefit | 8087 | 优惠券、冻结与核销；transactions组启用 |
| cinema-api | — | 共享数据契约和存量兼容形状 |
| cinema-framework | — | Core/Web/Security/Data/Redis/Remote/Observe/Messaging/Seata/File Support，按能力引入的库 |
| cinema-common | — | 迁移期兼容聚合；可执行应用已不再引入 |
| cinema-verification | — | 回归与一致性测试 |

各业务服务有独立库、独立用户及 Flyway 历史；旧脚本保存在 `database/legacy/`。

阶段 0/1 已完成基础依赖拆分与 OSS 文件业务整理，说明见 [基础模块](cinema-framework/README.md)、[阶段验收](docs/local-foundation-acceptance-2026-10-22.md)。上传 HTTP 契约、Bucket 和图片路径保持兼容，文件整理未增加新应用进程。阶段 2 后另有 Notify 与 RocketMQ 消息组，启动和验收见 [消息主线](docs/local-messaging-acceptance-2026-10-22.md)。阶段 3 的 Search、ES 与前端搜索已接入，中文检索、故障恢复、乱序保护和重建验收见 [搜索主线](docs/local-search-acceptance-2026-10-22.md)。

## 本地容器运行

需要 Docker Compose，且 `cinema-web` 与本仓库是同一父目录下的两个目录。

```powershell
./tools/init-local-env.ps1
./tools/local-stack.ps1 -Action Build
./tools/local-stack.ps1 -Action Up
# 仅新建空库时初始化首个管理员；已有管理员不会被覆盖。
docker compose run --rm admin-init
```

访问 `http://localhost:8088`。用户可以注册；新库管理员名为 `admin`，初始口令在本机 `.env` 的 `INITIAL_ADMIN_PASSWORD`。初始化工具生成随机密钥，`.env` 被 Git 排除。验证码默认开启，本地需要关闭时设置 `CAPTCHA_REQUIRED=false` 后重新创建账户服务。

Nacos 控制台为 `http://localhost:18848`，用户名 `nacos`，密码在 `.env`。服务端口 8848 / 9848 只绑定本机。初始化容器遵循 [Nacos 官方流程](https://nacos.io/en/docs/v3.0/manual/admin/auth/)。Compose 的基础设施是开发单实例。

模型默认使用占位密钥，基础购票可以运行；对话生成需注入有效 `DASHSCOPE_API_KEY`。OSS、TMDB 使用自己的环境变量；本地 TMDB 配置也可放在被 Git 排除的 `config/tmdb-local.yml`。

AI 客服本地入口为 `http://localhost:8088/ai`。多轮工具线索持久化、服务单完成事件、停止后续问和真实浏览器验收见 [2026-10-22 本地客服联调](docs/ai-customer-service.md)。`python tools/ai_local_acceptance.py` 使用独立测试用户和临时场次，覆盖六类确认式服务单并清理测试数据；报告和截图在 `target/ai-local-acceptance/`。

上传使用 `.env` 中的 `ALIYUN_OSS_AK`、`ALIYUN_OSS_SK`、`ALIYUN_OSS_ENDPOINT`、`ALIYUN_OSS_BUCKET`。修改这些值后运行 `docker compose up -d --no-build identity catalog`，让容器读取新环境变量；`docker compose restart` 不会更新容器配置。上传入口有独立超时和并发限制，普通登录的超时保持不变。统一图片业务在 `cinema-framework/cinema-file-support`，仅 Identity/Catalog 引入 OSS SDK。真实上传验收：JDK 21 下先 `mvn verify`，再 `python tools/file_local_acceptance.py`；脚本清理临时 OSS 对象和用户。

营收页支持 MapStruct / Fesod 六表 Excel 下载，实际金额、权限、浏览器和原CSV回归见 [导出验收](docs/local-export-acceptance-2026-10-23.md)。

AI历史清理已接入同一个XXL平台，逐会话事务、生成租约、模式回退和真实聊天回归见 [AI清理验收](docs/local-ai-retention-acceptance-2026-10-23.md)。内部订单写入时限和故障后原单恢复见 [确认链路](docs/local-ai-confirmation-deadlines-2026-10-23.md)。

## 本地源码运行

使用 JDK 21，先确认 `JAVA_HOME`。项目 settings 使用 HTTPS 仓库，避免继承机器上的旧 HTTP 镜像。

```powershell
mvn -gs .mvn/settings.xml -s .mvn/settings.xml verify
python tools/check_architecture.py
# 只运行基础设施，开放本机开发端口。
docker compose -f compose.yml -f deploy/compose.local-ports.yml up -d mysql redis nacos nacos-init
```

分别开终端运行 `./tools/run-service.ps1 -Service identity`，再运行 `catalog`、`trade`、`ai`、`gateway`。前端 `npm ci`、`npm run dev`，同源 `/api` 转发到 8080 网关。

各模块产物为 `cinema-<服务>/target/cinema-<服务>-0.1.0-exec.jar`。根目录已经是 Maven 聚合工程，新架构不能继续使用原 `target/cinema-server.jar`。

数据库使用 `DB_URL/DB_USERNAME/DB_PASSWORD`；Redis 使用 `REDIS_HOST/PORT/PASSWORD` 或标准 Sentinel 环境变量；Nacos 使用 `NACOS_ADDR/USERNAME/PASSWORD/NAMESPACE`。JWT 和内部调用密钥缺失会拒绝启动。配置明细见公共 `cinema-common.yml` 和各模块 `application.yml`。

Feign 可通过 `CINEMA_IDENTITY_URL`、`CINEMA_CATALOG_URL`、`CINEMA_TRADE_URL` 指向直连地址；网关使用对应 `CINEMA_*_URI` 覆盖。默认使用 Nacos；禁用时设置 `NACOS_ENABLED=false` 并配置直连地址。

浏览器来源默认支持 `localhost`／`127.0.0.1` 的 Vite 5173 和容器 8088 入口。Compose 修改 `WEB_PORT` 时会同步调整默认允许来源。需要其他前端来源时，在 `.env` 设置 `CORS_ALLOWED_ORIGINS`，使用逗号分隔完整来源（协议、域名和端口），再重新创建网关及四个业务服务；仅重启进程不会更新容器环境变量。生产示例使用明确域名白名单，网关与后端使用相同配置。

## 历史部署模板及数据迁移

`deploy/kubernetes/` 是此前的多副本学习模板，保留参考；当前本地方案不启用 Kubernetes、HPA、集群基础设施或云端部署。现有 `compose.yml` 继续是单实例功能联调入口，后续本地扩展配置按功能组引入。

原库需备份并升级到 V24，在维护窗口迁入四个初始化后的空库。`tools/migrate_legacy.py` 默认只读清点，显式追加 `--execute` 才复制；保留 ID、初始化快照、核对行数，拒绝覆盖非空目标。不要把新服务直接指向旧单体库。

- [架构、事务边界、突发保护、高可用、压测及迁移](docs/microservices.md)
- [当前本地单实例结构与技术栈](docs/local-singleton-stack.md)
- [验证记录](docs/microservices-validation.md)
- [293 部电影迁移结果与前后端联调、浏览器复验](docs/movie-migration-integration.md)
- [业务与接口约定](docs/business-guide.md)
- [影片资料](docs/mainland-movie-sync.md)、[独立评价](docs/review-module.md)、[营收报表](docs/revenue-report.md)

`/internal/**` 只用于私网 M2M 调用并校验内部密钥，网关不暴露。监控由私网策略保护。网关、Nginx 和 Feign 不自动重放写请求；前端保留订单幂等编号并提示 429 / 503 后重试。

## AI 客服缺陷与取舍记录（2026-10-21）

### 有票座位被查成不可购买 / 客服买错座位

- **症状**：用户说“场次ID为1的2排2座”时，客服可能回答别的座位不可售，或把 2排2座（VIP，¥120）拟成 2排3座（标准，¥40）；情侣座只查一半也会显示可买。
- **根因**：旧 `availableSeats` 只能每页罗列 40 张，且带可选的 `seatType` 过滤，没有按显示座号精确定位；模型会误填座型筛选、在分页结果里挑“最接近”的座位。票价来自第二次票查询，与座位图不是同一快照；情侣座按单张票判断可售，确认时才因另一半已售失败。
- **修复**：`ScheduleSeatsVO.SeatItem` 直接下发票价；`availableSeats` 取消座型过滤、支持 `seatLabel` 并返回分页/座型汇总；新增 `seatDetails` 精确查座和 `prepareBookingBySeat` 一步拟单；情侣座改成“两半都可售才算可售”；系统提示词明确“用户给出场次ID时直接查该场次”“N排M座必须精确查询”“未拿到可售结果不得猜已售”。
- **取舍**：座号以选座页显示标签为唯一口径，解析失败或找不到时要求用户核对，不做模糊匹配；情侣对共用一个显示座号但有两张票，工具返回两个 `ticketId`，服务单展示两张实体票；精确查询与拟单仍不锁座、不扣款，页面确认才是执行。

### 支付状态不同步 / 第二张服务单不显示

- **症状**：用户支付后追问，客服仍回答“待支付”；用户要求退款，客服口头说已生成退票服务单，但页面没有第二张服务单；再追问时客服说“订单不存在”。
- **根因**：多轮只回放 user/assistant 文本，订单 ID 只存在于上一轮工具结果中，下一轮模型不知道 `/pay/4` 对应订单 4，容易猜 ID；模型还把“服务单 DONE”误当成“订单已支付”，没有现查订单表；取消/退票是两个工具，模型先自行判断状态，判断错时工具失败却不承认，第二张 `ai_action` 实际没有落库。
- **修复**：新增 `prepareOrderAfterSale(orderId)`，服务端现查订单状态后自动生成取消单或退票单；用户明确要求退款/取消时由服务端按本会话订单ID预生成待确认服务单，不再完全依赖模型记得调用工具；每轮注入最近 10 条服务单的类型、状态、`orderId`、path 上下文；提示词明确“DONE≠已支付”“支付状态必须 orderDetails/myOrders 现查”“没有 action.id 不得说已生成”“同一会话允许多张服务单”。前端把会话内全部服务单按“待确认优先”渲染，提供总数/待确认计数、全部/待确认筛选和头部跳转；每次回复结束（包括流式中断）都重新拉取服务单，避免后端已生成但页面不显示。
- **为什么偶发看不到**：① 模型只口头说“已生成”，实际没调用 prepare 工具，数据库没有新行；② 多个刷新请求晚到的旧响应覆盖新列表；③ 用户中断时工具还没落库；④ 浏览器/代理缓存旧列表；⑤ 多张卡片在长消息下方被滚出视口；⑥ 新建会话后服务单按 chatId 隔离。对应修复：服务端确定性预拟单、前端请求序号守卫与按 ID 合并、停止后延迟刷新、`Cache-Control: no-store`、头部计数/定位和新增服务单自动滚到服务单区；模型只说不做时页面显示“未检测到新的服务单记录”警告，而不是伪造卡片。
- **购票补单**：`seatDetails`/带座号的 `availableSeats` 会记录最近确认可售的座位；模型声称已生成购票服务单但没落库时，流结束按该座位补单；用户说“没看见/没有生成/还是没有”且上一条回复确实声称购票成功时，下一轮按最近座位补单。退票文案不会被误判成购票。
- **取舍**：上下文只带订单 ID 和服务单状态，不带完整摘要；订单支付状态不缓存，始终以交易服务实时查询为准。对同 payload 且仍 PENDING 的服务单继续去重，这是幂等而不是“一会话只能一张”。

### 流式生成并发计数被重复释放

- **风险**：`streamChat` 完成时内部 `doFinish` 与外层 `doFinally` 各释放一次；在竞态窗口内会把同一用户新一代生成的并发计数/会话锁减掉，等于放宽并发限制。
- **修复**：只保留 `streamAndPersist` 内部 `doFinally` 的统一收尾，移除重复释放和冗余 `doOnComplete`；`PersistentAiChatServiceTest` 继续覆盖限流与锁 TTL。

### 评价业务覆盖

- 新增交易服务内部评价查询接口与 AI `movieReviews`/`myReviews` 工具：影片口碑按片名解析并校验 movieId，返回平均分、分布和脱敏评价；本人评价返回可编辑状态与 `/me/reviews` 入口。前端链接白名单同步允许 `/me/reviews`。

阶段 4 的优惠券与 Seata AT 已验收，券包 `/me/coupons`、后台 `/manage/benefits`、选座抵扣与 AI 确认单均已联调。使用 `./tools/local-stack.ps1 -Action Up -Group transactions` 启动本地事务组，恢复与故障实验见 [事务主线](docs/local-transactions-acceptance-2026-10-22.md)。

阶段 5 已完成 XXL-JOB / Redisson 的订单补偿链，`./tools/local-stack.ps1 -Action Up -Group jobs` 启动十五角色本地组。管理端仅本机 `http://127.0.0.1:11880/xxl-job-admin`，初始密码位于 `.env` 的 `XXL_JOB_ADMIN_PASSWORD`。十组故障 / 浏览器证据见 [调度验收](docs/local-jobs-acceptance-2026-10-22.md)。

Sentinel / Nacos 持久规则已通过九组真实实验，`./tools/local-stack.ps1 -Action Up -Group governance` 启动十六角色本地组，规则管理页 `/manage/governance`，Dashboard 在本机 `http://127.0.0.1:18880`。恢复边界和日志处理见 [治理验收](docs/local-governance-acceptance-2026-10-23.md)。管理员四类角色与即时权限复核已通过十一组真实验收，见 [权限验收](docs/local-rbac-acceptance-2026-10-23.md)。Knife4j 七域聚合、真实 JWT 调试及默认关闭已验收，使用 `-EnableDocs` 开启所选组文档，见 [接口文档验收](docs/local-api-docs-acceptance-2026-10-23.md)。阶段 5 其余子项继续推进。

- [Catalog / Search 领域任务验收（2026-10-23）](docs/local-domain-jobs-acceptance-2026-10-23.md)

- [本地 Prometheus / Grafana 指标验收](docs/local-metrics-acceptance-2026-10-23.md)

- [本地 SkyWalking HTTP / Feign / JDBC 追踪验收](docs/local-tracing-acceptance-2026-10-24.md)：`./tools/local-stack.ps1 -Action Up -Group tracing` 启动十二角色实验组；私有 UI、URL 隐私、真实认证及故障恢复已验收。

- [本地集中日志与当前步骤收尾验收](docs/local-logs-acceptance-2026-10-25.md)：Loki / Alloy、真实 Grafana 关联、事务 12 组、AI 11 组及基础浏览器 8 组通过；当前项目容器与 Docker 已关闭，其余扩展暂停。
