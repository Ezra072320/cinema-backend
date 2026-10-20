# cinema-server · 猫头鹰影院后端

单影院订票系统的后端服务。提供用户与管理员鉴权、影片资料、影厅座位版本、排片、订单售后、独立评价、营收统计和AI会话接口。

[项目总览](../README.md) · [前端说明](../cinema-web/README.md)

更新基准：2026-10-20的当前源码。支付和退款为模拟流程；此文不把历史设计稿中的功能当作已实现功能。

## 环境与依赖

| 依赖 | 说明 |
| --- | --- |
| Java | Maven编译目标为21，使用JDK 21及以上 |
| Maven | 建议3.9；首次运行需要能下载依赖 |
| MySQL | 8.x，默认库名cinema，连接时区Asia/Shanghai |
| Redis | 用于验证码等缓存；完整本地联调需可连接的Redis |
| 通义千问 | 客服真实对话需要自己的DASHSCOPE_API_KEY；普通订票接口不依赖模型请求 |
| 阿里云OSS | 文件上传、头像和影片媒体转存需要自己的配置 |
| TMDB | 可选资料辅助源，配置read access token或API key |

当前主要版本：Spring Boot 3.5.6、MyBatis-Plus 3.5.9、Spring AI 1.0.0、SpringDoc 2.8.4、jjwt 0.12.6。完整依赖见 [pom.xml](pom.xml)。

## 目录

```text
src/main/java/edu/czu/cinema/
├── CinemaServerApplication.java
├── AiTool.java                 # 模型可调用的实时查询与拟单工具
├── ai/                         # 会话、消息、身份上下文与待确认服务单
├── common/                     # R、分页、业务异常、JWT与通用工具
├── config/                     # JSON、MyBatis、AI与外部资料配置
├── security/                   # JWT过滤器和路由权限
└── domain/
    ├── admin/ user/ captcha/   # 账号与验证码
    ├── movie/                 # 资料查询、同步、补缺及人工保护
    ├── hall/                  # 影厅、座位图草稿、发布与版本
    ├── schedule/ ticket/       # 排片及按座生成的票
    ├── order/                 # 下单、支付、取消、退款与超时清理
    ├── score/                 # 独立评价与公开统计
    ├── statistic/             # 真实概览与营收聚合
    └── file/                  # OSS上传
src/main/resources/
├── application*.yml
├── db/migration/              # Flyway迁移（V1~V24，启动自动执行）
├── db/migrate/                # 历史归档：接入Flyway前的手工脚本
└── rag/theater-service.txt    # 客服本地业务指南
src/test/java/                 # 领域、SQL、控制器和外部集成测试
docs/                          # 专题业务说明
tools/                         # 本地运维辅助脚本
```

入口启用Mapper扫描、缓存和定时任务。订单超时清理每分钟扫描一次，业务入口也会检查超时。

## 数据库初始化

DDL迁移已接入Flyway（`src/main/resources/db/migration/`，V1~V24），迁移历史记录在表`flyway_schema_history`；`src/main/resources/db/migrate/`仅存历史归档，不要再往那里加脚本。

**备份先行**：初始化或升级前保留可恢复备份。早期迁移含直接ALTER语句，**部分脚本不可重复执行，勿手工重跑整套迁移**；旧列清理还会删除遗留表或列。

### 新建开发库

- **推荐（Flyway）**：创建空库后直接启动应用（`mvn spring-boot:run`），Flyway按版本顺序自动执行V1~V24。迁移脚本不含`CREATE DATABASE`／`USE`，在连接的schema上执行；自定义库名时改数据源URL即可。
- **手动（无Flyway时）**：连接到已建好的空库，按版本号顺序导入`db/migration/`下的脚本，例如：

```sh
mysql -u root -p --default-character-set=utf8mb4 cinema < src/main/resources/db/migration/V1__movie_maoyan_redesign.sql
mysql -u root -p --default-character-set=utf8mb4 cinema < src/main/resources/db/migration/V2__seat_template.sql
# 依次执行到 V24__couple_group_seat_unique.sql
```

使用MySQL客户端（`SOURCE`或`mysql <`）能够正确处理DELIMITER与存储过程；不要用简单的分号切分器执行整套迁移。

需要演示账号时，仅在新的开发库执行：

```sql
SOURCE src/main/resources/db/seed.sql;
```

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | admin | admin123 |
| 普通用户 | test | test123 |

seed.sql会先删除同名演示账号再写入，不用于有业务数据的库。它不创建可购票场次；需在管理端准备影片、影厅座位图和未来排片。

### 已有数据库升级

- **推荐（Flyway）**：直接启动应用。配置为`baseline-on-migrate`、`baseline-version=21`：没有`flyway_schema_history`的存量库启动时自动baseline到21（视为V1~V21已执行），随后只执行V22+；全新空库则完整执行V1~V24。执行记录见迁移历史表`flyway_schema_history`。
- 需要人工补迁时，先核对已有表、字段和`flyway_schema_history`，只执行缺失部分；不要将空库流程完整重跑到现有库。
- 部分脚本不可重复执行，勿手工重跑；升级前保留可恢复备份。
- schema.sql与schema_new.sql保留为基础结构参考，未汇总全部后续迁移。
- 历史工具和`db/migrate`脚本可能固定使用cinema库，不应直接照搬。

### 历史方案（无Flyway时）

`src/main/resources/db/migrate/`下的21个脚本是接入Flyway之前的手工迁移流程，已在独立MySQL 8空库验证全部通过（执行时工作目录必须是cinema-server）。仅在无Flyway环境手工初始化时按下面**已验证顺序**执行；容量、座位版本及批次迁移存在依赖，不能直接按文件名字典序执行。这些脚本已按同序复制为`db/migration/`的V1~V21（内容仅剥离`CREATE DATABASE`／`USE`）。

先打开MySQL客户端：

```sh
mysql -u root -p --default-character-set=utf8mb4
```

然后在MySQL客户端内依次执行：

```sql
SOURCE src/main/resources/db/migrate/2026_10_16__movie_maoyan_redesign.sql;
SOURCE src/main/resources/db/migrate/2026_10_16__seat_template.sql;
SOURCE src/main/resources/db/migrate/2026_10_16__hall_seat_version.sql;
SOURCE src/main/resources/db/migrate/2026_10_16__hall_total_seats.sql;
SOURCE src/main/resources/db/migrate/2026_10_16__seat_version_draft_lock.sql;
SOURCE src/main/resources/db/migrate/2026_10_16__seat_couple_group.sql;
SOURCE src/main/resources/db/migrate/2026_10_16__schedule_seat_version.sql;
SOURCE src/main/resources/db/migrate/2026_10_16__schedule_price_multiplier.sql;
SOURCE src/main/resources/db/migrate/2026_10_16__seat_ticket_index.sql;
SOURCE src/main/resources/db/migrate/2026_10_16__hall_capacity_from_seatmap.sql;
SOURCE src/main/resources/db/migrate/2026_10_17__mainland_movie_sync.sql;
SOURCE src/main/resources/db/migrate/2026_10_17__movie_metadata_repair.sql;
SOURCE src/main/resources/db/migrate/2026_10_17__tmdb_movie_support.sql;
SOURCE src/main/resources/db/migrate/2026_10_17__movie_drop_legacy_columns.sql;
SOURCE src/main/resources/db/migrate/2026_10_17__movie_insert_only_cleanup.sql;
SOURCE src/main/resources/db/migrate/2026_10_18__schedule_batch_id.sql;
SOURCE src/main/resources/db/migrate/2026_10_18__schedule_batch_id_backfill.sql;
SOURCE src/main/resources/db/migrate/2026_10_19__ai_chat_persist.sql;
SOURCE src/main/resources/db/migrate/2026_10_19__ai_service_actions.sql;
SOURCE src/main/resources/db/migrate/2026_10_19__order_refund_and_close.sql;
SOURCE src/main/resources/db/migrate/2026_10_20__review_module.sql;
```

第一份脚本已包含基础建库与建表，不需要先额外执行schema.sql。

## 配置与启动

默认激活dev配置，主配置文件见 [application.yml](src/main/resources/application.yml)。所有示例只使用占位符；不要依赖仓库内的本地开发凭据。

### 配置项

| 环境变量 | 用途 |
| --- | --- |
| SPRING_PROFILES_ACTIVE | dev或prod（遗留 local/H2 配置已移除，H2 仅用于测试） |
| SPRING_DATASOURCE_URL | 覆盖数据库地址、端口、库名及连接参数 |
| SPRING_DATASOURCE_USERNAME | 覆盖数据库用户名 |
| MYSQL_PASSWORD | 数据库密码；dev有本地默认值，**prod 必须注入**（无默认值）。也可直接用SPRING_DATASOURCE_PASSWORD覆盖 |
| SPRING_DATA_REDIS_HOST / SPRING_DATA_REDIS_PORT / SPRING_DATA_REDIS_PASSWORD | Redis连接；使用Spring标准环境变量可覆盖dev中的固定主机与端口 |
| JWT_SECRET | JWT签名密钥，至少32字节随机串；dev有本地默认值，**prod 必须注入**。缺失/过短/命中历史默认串会拒绝启动。令牌默认12小时过期，冻结/改密后旧令牌立即失效 |
| CAPTCHA_REQUIRED | 登录/注册是否强制图形验证码；dev 默认 false（保留旧本地体验），prod 强制 true |
| CORS_ALLOWED_ORIGINS | 允许的前端源，逗号分隔；dev默认*，prod必须注入显式域名白名单 |
| DASHSCOPE_API_KEY | 通义千问兼容接口的密钥 |
| ALIYUN_OSS_AK / ALIYUN_OSS_SK / ALIYUN_OSS_BUCKET | OSS身份与存储桶 |
| TMDB_READ_ACCESS_TOKEN / TMDB_API_KEY | TMDB资料源身份 |
| TMDB_PROXY_URL | 可选TMDB代理地址 |
| SERVER_PORT | 覆盖HTTP端口，默认8080 |

dev profile 为本地开发保留了可直接启动的默认值（本地数据库口令、dev-only JWT 密钥），设置环境变量即自动覆盖；**prod profile 不提供任何默认凭据**，缺失即启动失败。OSS/模型/TMDB 密钥在仓库中不预设（未配置时仅对应功能不可用）。已泄露过的历史云凭据必须在云控制台轮换。

OSS endpoint、模型名、同步地区和限速等参数可通过Spring配置覆盖。微信配置仍保留，但并不构成微信支付接入。

应用会按当前工作目录可选加载 config/tmdb-local.yml。该文件应只放本地配置，不提交凭据。

### PowerShell运行示例

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:3306/cinema?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8"
$env:SPRING_DATASOURCE_USERNAME = "root"
$env:MYSQL_PASSWORD = "<你的数据库密码>"
$env:SPRING_DATA_REDIS_HOST = "127.0.0.1"
$env:SPRING_DATA_REDIS_PORT = "6379"
$env:JWT_SECRET = "<至少32字节的自定义密钥>"
$env:DASHSCOPE_API_KEY = "local-placeholder"
mvn spring-boot:run
```

使用有效模型密钥后才能进行真实客服问答。占位值用于不调用模型的本地业务运行，不代表AI已联通。不要直接禁用ChatModel自动配置：当前AiConfig依赖OpenAiChatModel Bean。

打包运行：

```sh
mvn test
mvn -DskipTests package
java -jar target/cinema-server.jar
```

本地接口：[http://localhost:8080](http://localhost:8080)。接口文档：[Swagger UI](http://localhost:8080/swagger-ui.html)、[OpenAPI JSON](http://localhost:8080/v3/api-docs)。

首次构建不要添加离线参数。已有完整依赖缓存时，可以使用mvn -o运行相同任务。

## 接口约定

普通接口使用R封装，核心字段如下：

```json
{
  "code": 1,
  "msg": "操作成功",
  "data": {}
}
```

code=1表示成功；失败应检查code和msg，不能只依据HTTP 200判断。部分控制器返回明确的400／401／403／404／409，部分沿用全局业务码。分页通常为data.total与data.rows；没有数据的成功响应可能省略data或返回null。

登录成功返回JWT字符串，后续请求使用Authorization请求头，支持原始令牌或Bearer形式。前端接口层统一解开R，页面直接获得data。

| 范围 | 权限 |
| --- | --- |
| 登录、注册、验证码、公开片单／详情／媒体、影厅列表、公开场次座位、公开评价 | SecurityConfig明确放行的端点 |
| /api/admin/**、/api/statistic/** | ADMIN |
| /api/user/**、/api/ai/** | USER |
| 其他业务端点 | 至少登录，具体服务再做归属或角色检查 |

最终权限以 [SecurityConfig](src/main/java/edu/czu/cinema/security/SecurityConfig.java) 和对应控制器为准。“可公开查看页面”不等于所有关联接口都免登录。

### 主要接口索引

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | /api/user/login、/api/user/register | 用户登录与注册 |
| POST | /api/admin/login | 管理员登录 |
| GET | /api/captcha/image?ticket=... | 获取图形验证码 |
| GET | /api/user/info | 本人账户资料 |
| GET | /api/movie/list、/api/movie/info/{id} | 影片分页与详情 |
| GET | /api/movie/{id}/images、/api/movie/{id}/videos、/api/movie/{id}/crew | 剧照、预告片与演职资料 |
| DELETE | /api/movie/want/{id} | 当前门户想看状态切换 |
| GET | /api/schedule/byHall/{hallId} | 影厅有效排片 |
| GET | /api/schedule/{id}/seats | 场次锁定版本座位与ticketId |
| POST | /api/user/createOrder | 使用scheduleId与ticketIds下单 |
| POST | /api/order/pay | 使用orderId与channel模拟付款 |
| POST | /api/user/cancelOrder/{id} | 取消本人待付款订单 |
| POST | /api/user/refund/{id} | 本人订单模拟退款 |
| GET | /api/order/{id}、/api/user/orders/{status} | 本人订单详情与列表 |
| POST | /api/admin/movie/sync、/api/admin/movie/repair | 新片同步与全片库补缺 |
| PUT | /api/admin/movie/{id}/metadata | 人工核实、补录及字段保护 |
| PUT／POST | /api/admin/hall/{hallId}/seats/draft、/draft/apply | 保存草稿、发布座位版本 |
| POST | /api/admin/schedule/add、/batchAdd、/batchMove | 单场／批量排片及同批改期 |
| POST | /api/admin/schedule/cancel/{id} | 取消场次并联动关闭／模拟退款 |
| GET | /api/admin/order/list | 管理端分页，关键词当前按订单号匹配 |
| GET | /api/user/score/context/{orderId}、/api/user/scores | 本人评价上下文及分页 |
| POST／DELETE | /api/user/score、/api/user/score/{orderId}?version=... | 保存评价、按版本删除 |
| GET | /api/movie/{id}/reviews | 公开评价及本店五分制统计 |
| GET | /api/statistic/getAll、/api/statistic/revenue | 管理概览与区间营收 |
| POST | /api/ai/chat | 带question和chatId的SSE聊天 |
| GET | /api/ai/sessions、/api/ai/history/{chatId} | 本人会话与历史 |
| POST | /api/ai/action/{id}/confirm、/dismiss | 确认或撤销服务单 |
| POST | /api/file/upload | 已登录用户上传multipart的file字段，返回OSS URL |

完整参数与单个映射见控制器及运行时OpenAPI，不将此表当作独立的全量协议定义。

## 领域规则

| 模块 | 当前规则 |
| --- | --- |
| 影片 | 新片同步仅新增库中没有的大陆未来待映影片；已有待映影片另行刷新状态等资料，已上映资料不在该新增流程覆盖；全片库补缺与人工修订有独立入口，人工确认字段受保护 |
| 时长 | runtimeMin=0表示待映时长待公布；排片要求时长大于0 |
| 座位版本 | 场次锁定排片时的版本；新发布版本不改变旧票座位对应关系；capacity从有效座位图计算 |
| 票价 | 根据基础票价和座型倍率在建票时确定；默认VIP为3.00倍，情侣每个实体座位为2.50倍，可在新增场次时设置 |
| 排片 | 有效场次按影片时长加30分钟清场进行同厅冲突检查；改期不换厅、不重新定价 |
| 订单 | 一次1～6张实体票，票号去重、情侣对完整性和场次归属由服务端检查；15分钟未付款关闭并释放座位 |
| 退款 | 用户只退状态2订单；完整分钟数>360全额、180～360退70%、<180拒退；管理员取消场次时对2／6全额模拟退，对1关闭 |
| 评价 | 本人有效订单散场后开放；一单一评，1～5分、短评最多1024字符单元；版本校验修改与删除，独立于订单状态和外部评分 |
| 营收 | 按下单日期汇总当前状态，退款回计原日期；BigDecimal聚合；缺失／异常旧退款单独列示，不臆造金额 |

订单状态：1待支付、2已支付、3已退款、4已关闭、6旧已完成；5只表示列表查询“全部”。不要再将4理解成“退款中”，也不要通过新评价将2改成6。

营收查询默认含今天的近30天，最多366天，支持day／week／month，读取一致的只读事务；周从周一开始，首尾只覆盖筛选区间。净营收不等于现金流或利润。

## AI会话与服务单

- 模型通过AiTool获得当前数据，身份从服务端上下文注入，不接受模型指定userId。
- 会话、消息与服务单分别持久化；最近20条消息进入上下文，同实例同会话限制并发生成。
- 写业务先拟单，5分钟有效；用户点击确认时重查条件，事务内执行领域服务，重复确认返回已办理结果。
- 本地业务指南直接加入上下文，当前实现不要求远程向量检索命中。
- SSE以JSON字符串token输出，另有显式错误帧；客户端需保留转义、换行并处理网络中断。
- 当前仍保留旧评价拟单及“评价后订单已完成”等旧文案，与新评价语义不完全一致。独立评价模块以普通接口为准，不在本次文档中宣称已实现AI评价闭环。

细节见 [AI客服说明](docs/ai-customer-service.md)，旧评价描述以当前评价服务为准。

## 测试与排查

```sh
mvn test
```

最近一次完整结果：237项，232通过、5项外部集成测试按配置跳过。默认测试覆盖SQL聚合、订单状态、退款边界、座位版本竞态、避免静默解冻的白名单更新、BCrypt迁移与令牌失效、验证码与登录锁定、上传校验、评价并发、统计权限、会话归属与工具注册等。真实资料源测试需要显式系统属性和自己的配置，部分会写入资料库，具体以对应测试类为准。

| 现象 | 优先检查 |
| --- | --- |
| 启动或业务SQL缺表／缺列 | 查看 flyway_schema_history 是否成功；不要只导入schema.sql |
| 验证码加载或校验失败 | Redis连接、票据是否过期或已消费、前端/api代理 |
| JWT初始化失败 | 是否设置自己的JWT_SECRET，是否仍使用代码拒绝的历史默认值 |
| 401／403 | 请求头是否有令牌、是否登录正确角色、资源是否属于本人 |
| 影片有资料但不能购票 | 是否有未来有效场次、影厅是否已发布有效座位图、影片时长是否大于0 |
| 退款金额与原价不同 | 是否处于180～360分钟档位；查看refund_amount |
| 评价未公开 | 是否已散场、订单／场次是否有效、旧评分是否为1～5 |
| 报表与全国票房不一致 | 两者来源和口径不同；本店报表只统计本系统订单及实际退款 |
| 客服无回复／模型错误 | 模型密钥、外部网络、SSE代理缓冲及超时 |
| 上传／媒体补全失败 | OSS身份、bucket、endpoint及外部资源可达性 |

## 当前边界

- 支付、退款不接真实资金通道，没有支付回调或对账单（已预留 pay_time/pay_channel 与 order_action 审计流水，接真实通道时作为对账挂载点）；也没有检票核销、积分、券、会员余额、发票和人工在线转接。
- 遗留 local/H2 配置已移除（H2 依赖收窄为 test scope）；完整业务必须连接 MySQL + Redis。
- 密码已迁移到 BCrypt（新口令只写 BCrypt，历史 MD5 在登录成功时惰性重算）；仍建议部署侧启用 HTTPS、按环境配置 CORS 白名单并限制管理端来源。
- 图形验证码：dev 默认关闭（保留旧本地体验），prod 强制开启并依赖 Redis 可用；Redis 故障时生产登录会被拒绝（可用 CAPTCHA_REQUIRED=false 临时降级，但不建议）。
- 管理端订单抽屉沿用用户订单详情回查接口，可能受到本人归属检查限制；页面会保留列表行数据，不应将其描述为独立的管理员完整详情接口。
- 改密等个别页面的前端校验范围与服务端不完全一致，业务判定以领域服务和DTO校验为准。
- 历史工具脚本和专题文档可能包含本地路径、固定库名或旧描述；使用前核对当前参数与源码。`tools/dbrun.sh` 已改为失败即退出，但新环境应优先使用 Flyway。

## 专题文档

- [影片同步、补缺与人工核实](docs/mainland-movie-sync.md)
- [取消场次与自动退款背景](docs/schedule-cancel.md)
- [AI客服与服务单](docs/ai-customer-service.md)
- [独立评价模块](docs/review-module.md)
- [真实营收报表](docs/revenue-report.md)
