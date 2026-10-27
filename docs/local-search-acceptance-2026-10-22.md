# 本地搜索主线：Search / Elasticsearch / Catalog 事件

验收状态：搜索主线、消息回归与浏览器检查均通过。应用和中间件均在本地各运行一个实例；千问 API、现有阿里云 OSS Bucket 与上传接口继续保留。此阶段对应实施顺序 3，Seata / Benefit 属于下一阶段。

## 实现范围

新增 `cinema-search`，通过 Nacos / OpenFeign 调用业务所有者，经 Gateway 提供 `/api/search/movies`。前端沿用 `/movie?q=...`，支持片名、原名、别名、演职员与简介全文检索、类型/地区/本店热映筛选、评分/上映日排序和分页。高亮只允许无属性的 `mark` 标签，ES 使用 HTML 编码，前端通过 DOMPurify 再处理。

影片管理页提供“重建搜索资料”，管理员接口为 `GET /api/admin/search/status`、`POST /api/admin/search/rebuild`。普通用户和匿名访问不能执行重建。市场状态仍表示经典/上映/待映，本店热映与场次来自 Trade 实时查询。Trade 暂不可用时，热映筛选返回明确的失败提示，普通结果的排片信息可以为未知。

| 组件 | 本地部署 | 业务作用 |
| --- | --- | --- |
| Elasticsearch / Java API Client | 8.18.6，客户端沿用 Boot BOM | 独立搜索投影、中文全文与高亮 |
| ES 索引 | 一个主分片、零副本 | 单节点学习，内置 `cjk` 中日韩双字分析 |
| RocketMQ | 复用既有 NameServer、Broker/Proxy | `cinema-movie-events` NORMAL，`cinema-search-movies` 消费组 |
| Catalog MySQL | 原 `cinema_catalog` 与原用户 | 业务事实、源版本、当前搜索快照、Outbox |
| Search MySQL | 新 `cinema_search` 与独立用户 | 当前索引、在建索引、重建游标、任务状态 |

新增迁移为 Catalog `V3__movie_search_outbox.sql`、Search `V1__search_index_state.sql`。存量业务库、ID、OSS URL 和 MQ 偏移量保留；首次启动只为缺少搜索投影的既有影片生成初始事实。ES 使用独立的 `cinema-cloud_elasticsearch-data` 卷，未复用其他项目的 ES 数据。

## 一致性与恢复

Catalog 的导入、补缺、状态刷新和管理员补录入口在原本地事务内调用 `MovieProjectionWriter`，将业务变更、搜索快照与 Outbox 一起提交。事务失败不会留下搜索事件。源版本由 Catalog 的数据库计数器在事务中加锁分配，锁持有到提交；这里选择简单的串行版本分配以便学习和验证。

Relay 使用既有租约、重试和过期回收机制发送事件，业务提交不等待 MQ。Search 按 `movieId` 写入 ES，并使用 `version_type=external` 原子拒绝相同或更旧的版本。仅确认的 `version_conflict_engine_exception` 可当作重复投递成功；其他 HTTP / SDK 错误继续让 MQ 重试。

RocketMQ 5.3.4 的 gRPC Proxy 首次 POP 使用 MAX 起点，单独设置消费组的 `consumeFromMinEnable` 不足以保证读取首次订阅之前的事实。因此 `rocketmq-init` 通过镜像自带 SDK 查询各队列进度，仅在明确不存在时初始化到当前保留数据的最小位置，已有进度直接保留。Notify、Trade 消费应用和 Search 的启动依赖初始化完成。该步骤不回退既有进度、不调用重置进度命令。独立冷启动检查已验证“缺失进度初始化”和“推进后再次执行仍保持原进度”；验证生成的 `cinema-offset-bootstrap-probe-*` 仅为技术消费组，没有业务消费者。

现有片库业务永久保留影片，没有物理删除接口。移出/恢复搜索使用 `PUT /api/admin/movie/{id}/search-visibility?hidden=true|false`，隐藏记录在 Catalog 快照与 ES 中保留版本，查询排除它们。资料后续更新继续保留隐藏状态。将来若新增业务物理删除入口，须先在同一事务记录版本化的隐藏事实；不能直接删除源快照或重置版本计数器。

重建创建新的 `cinema-movies-<uuid>` 索引，先持久化在建索引，再按源版本每批读取 100 个当前快照。并发更新只能移向更大的源版本，隐藏事实也参与扫描。消费线程同时写当前索引与在建索引；扫描和消息都受同一 ES 外部版本检查。重建游标持久化，进程重启后继续。完成时与消费共用数据库行锁，先刷新新索引，再原子切换 `cinema-movies` 别名，最后更新任务状态。别名切换后响应丢失或数据库提交失败可以再次执行切换。

旧索引保留用于人工核对；本阶段没有自动清理索引任务。重复进行大量实验时应按本项目索引名核对后保留当前及所需历史版本，后续调度阶段再接管清理。禁止删除当前别名指向的索引、数据卷或消费者偏移量来“恢复”。

ES 故障时 Search 回退到 Catalog 的受控基础检索，返回 `mode=BASIC`，关键词匹配片名、原名、别名与演职员的连续文本，保留类型、地区、热映和分页条件；前端显示基础检索提示，高亮和相关度排序暂不可用。Search 未启动的 base/messaging 组中，前端回退原 Catalog 片名接口；高级筛选要求搜索组运行。ES / MQ 不加入普通业务的 readiness 硬依赖。

查询参数限制为关键词 100 字符、每页最多 50、结果窗口最多 10000、排序枚举白名单。SQL 全部使用参数绑定；客户端文本不作为 ES Query String 或 SQL 表达式执行。ES 禁止自动创建 `cinema-movies-*`，索引必须由重建入口显式创建。

## 本地运行

先配置原 `.env`，构建 Maven 与前端。新域初始化只创建自身库和用户，不覆盖旧数据和已有密码。

```powershell
python tools/init-domain.py notify
python tools/init-domain.py search
./tools/local-stack.ps1 -Action Build -Group mq-search
./tools/local-stack.ps1 -Action Up -Group mq-search
./tools/local-stack.ps1 -Action Status -Group mq-search
python tools/search_local_acceptance.py
```

`mq-search` 包含十二个长期容器：MySQL、Redis、Nacos、NameServer、Broker/Proxy、ES、Identity、Catalog、Trade、Search、Gateway、Web。启动工具先等待基础设施，再串行启动 JVM；在本机内存预算下暂停本项目 AI、Notify 应用，数据和 MQ 角色保留。切回 base/media/messaging 时工具先暂停 Search/ES，防止 Catalog Relay 关闭后继续展示旧搜索索引。恢复搜索组会继续投递积压事实。

本机已恢复全部十四个单例角色，AI 与 Notify 同时可用；实测容器内存读数合计约 6.2 GiB，Docker 上限约 7.64 GiB。这是本次学习负载的读数，默认搜索组仍保留较小的常驻组合。需要同时启用 AI 和通知时使用：

```powershell
./tools/local-stack.ps1 -Action Up -Group mq-search -IncludeOptionalApps
```

```powershell
./tools/local-stack.ps1 -Action Stop -Group mq-search
./tools/local-stack.ps1 -Action Up -Group messaging
```

ES HTTP 绑定 `127.0.0.1:19200`，本地开发配置关闭 ES 内建认证，应用通过项目私有网络访问；Search 不新增宿主映射端口。IDE 运行入口 `./tools/run-service.ps1 -Service search` 使用 8086，需要按源码运行说明开放 MySQL/Redis 本机端口。MQ 继续使用原本机 18081 gRPC 入口，没有创建第二套队列服务。

标准 Dockerfile 使用 Java 21。本机复用已有运行层更新镜像，其实际 JVM 为 Temurin 25；源码与 Maven 测试使用 JDK 21。构建和运行版本应分别核对，不能仅凭编译目标推断容器 JVM。

本阶段故障注入发现原 Broker/Proxy 的 128 MiB 直接内存耗尽，失败消息的 ChangeInvisibleDuration 请求无法完成。修复为 256 MiB 直接内存、384 MiB 堆、1400 MiB 容器上限、`ActiveProcessorCount=2`，并通过 `rmq-proxy.json` 将 gRPC 请求池限制为 4、主要工作池限制为 2。已核对实际加载的配置。这是对原消息组资源配置的修正，探活端口开放本身不能证明重试正常。

## 验收记录

| 验证 | 实际结果 |
| --- | --- |
| Maven 全模块 verify 与后续新增 Search 检查 | 合计 346 项：341 通过、5 项原有外部数据源测试跳过，失败/错误为 0 |
| 架构约束、Compose、两个仓库 diff 检查 | 通过；ES SDK 仅在 Search，跨库 SQL / 应用实现依赖检查通过 |
| 前端 ESLint、Vue/TS/Vite 构建、AI 11 项与营收 4 项 Node 测试 | 通过；已有大体积 vendor chunk 提示保留 |
| `search_local_acceptance.py` | 8 组通过，含中文标题/别名/演职员、筛选/分页、高亮、重复/乱序、隐藏、ES 故障降级、MQ 故障补发、重启续建、切换别名；浏览器脚本错误 0 |
| 同时运行 AI / Notify 后的搜索恢复 | 同样 8 组通过，十四角色状态下完成重试、续建和清理 |
| 调整 Broker 后的消息链回归 | 9 组通过，含提交/支付/退款、Outbox、去重、一次性 WebSocket 票据、前端推送、延迟关单与补偿 |
| 基础浏览器回归 | 8 组通过：公开页、详情切换、榜单失败隔离、表单、管理员页面、CSV、CORS；脚本错误 0 |
| 冷消费进度初始化 | 隔离组两个队列均从缺失状态初始化；推进进度后再次执行初始化数为 0，两个队列的进度保留 |
| 清理与状态 | 临时影片、临时用户已清理，索引状态 READY，无重建错误；原业务资料保留 |

自动化脚本使用独立的临时影片、别名、演职员资料和自身 Outbox 记录；结束时清理业务资料，保留隐藏的源/ES 技术记录，使晚到消息不能重新显示已清理影片。故障注入仅停止当前 Compose 项目的 ES、Broker 或 Search，finally 中恢复被停止的服务。搜索报告/桌面与手机截图在 `.work/search-acceptance/`，消息回归在 `.work/search-messaging-regression/`，基础浏览器报告在 `.work/search-browser-regression/`；这些本机验收产物不作为密钥或登录信息的载体。

本次联调修复了两处真实客户端问题，均先用实际传输层测试复现：一是版本冲突可能从底层 `ResponseException` 抛出；二是 Java 客户端 `withJson` 不能解析 REST 接受的排序简写，需要完整排序对象。对应回归覆盖重复版本、其他 409/503 不得确认，以及三种排序、高亮与文档反序列化。

## 官方依据

- [Java Client 兼容策略](https://www.elastic.co/docs/reference/elasticsearch/clients/java)：客户端与服务端按相同主/次版本接入，本次两者均 8.18.6。
- [Index API 外部版本](https://www.elastic.co/guide/en/elasticsearch/reference/8.18/docs-index_.html)：使用更大的源版本写入，保留隐藏文档以持续保留版本屏障。
- [Aliases 原子切换](https://www.elastic.co/guide/en/elasticsearch/reference/8.18/aliases.html)：一个请求提交移除与增加动作。
- [内置语言分析器 / cjk](https://www.elastic.co/guide/en/elasticsearch/reference/8.18/analysis-lang-analyzer.html#cjk-analyzer)：本阶段中文分析器；自定义中文词库后续独立扩展。
- [RocketMQ 5.3.4 Proxy 配置源码](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.4/proxy/src/main/java/org/apache/rocketmq/proxy/config/ProxyConfig.java)：默认线程数随处理器数量变化，本地覆盖相应工作池和直接内存预算。
- [gRPC 首次 POP 起点](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.4/proxy/src/main/java/org/apache/rocketmq/proxy/grpc/v2/consumer/ReceiveMessageActivity.java) 与 [Broker 已有/缺失进度处理](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.4/broker/src/main/java/org/apache/rocketmq/broker/processor/PopMessageProcessor.java)：初始化仅覆盖不存在的进度，后续继续由消费确认推进。
