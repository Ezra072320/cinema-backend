# 本地单实例微服务技术栈：官方核验记录

> 用户已明确要求继续使用阿里云 OSS。RustFS 只保留候选核验资料，不纳入当前选型、部署数量或内存预算；本地部署约束适用于应用和新增中间件，千问 API 与 OSS 保留。

核验日期：2026-10-22。当前基线：Java 21、Spring Boot 3.5.6、Spring Cloud 2025.0.0、Spring Cloud Alibaba 2025.0.0.0、Nacos 3.0.3、MySQL 8、Redis 7.2、Vue 3。目标是丰富影院项目中的技术实践：每种应用和中间件只运行一个本地实例，千问 API 仍远程调用；不包含上云、集群或容量目标。

本文为资料核验和工程建议，未修改代码或 Compose，未启动服务，未验证本项目的新组件兼容性。官方支持范围、源码依赖证据、建议和待测事项分别说明；不以仓库星数或厂商案例推导国内市场占有率。

## 1. 应先处理的维护与兼容性事实

| 项目 | 官方核验结果 | 本项目建议 |
| --- | --- | --- |
| MinIO 社区版 | `minio/minio` 当前显示已于 2026-04-25 归档只读 | 仅保留候选维护状态核验；本项目继续使用OSS，不涉及MinIO存量迁移。[官方仓库](https://github.com/minio/minio) |
| EasyExcel | 官方仓库于 2025-09-04 归档；README 含进入维护模式公告 | 新功能优先评估其后续路线，不能把旧维护公告理解为仍有活跃发布。[官方仓库](https://github.com/alibaba/easyexcel) |
| FastExcel | `fast-excel/fastexcel` 已重定向到 Apache Fesod，当前 README 标为 Incubating | 新模块优先评估 `org.apache.fesod:fesod-sheet`；不要混淆其他语言的同名 FastExcel。[官方仓库](https://github.com/apache/fesod) |
| XXL-JOB | 3.2.0 源码使用 Boot 3.5.4 / Spring 6.2.10；3.3.2 已使用 Boot 4.0.1 / Spring 7.0.2 | 不能把更新版本的示例依赖直接搬进本项目；先评估同代 3.2.0，或明确测试新 core 对 Spring 6 的运行兼容性。[3.2.0 POM](https://raw.githubusercontent.com/xuxueli/xxl-job/3.2.0/pom.xml)、[3.3.2 POM](https://raw.githubusercontent.com/xuxueli/xxl-job/3.3.2/pom.xml) |
| Knife4j | Boot 3 使用 Jakarta / OpenAPI 3 starter，且内置 springdoc；springdoc 官方矩阵为 Boot 3.5.x → 2.8.x | 必须检查依赖树并统一 springdoc，不能只凭“支持 Boot 3”认定兼容 Boot 3.5.6。[Knife4j 快速开始](https://doc.xiaominfo.com/docs/quick-start)、[springdoc 矩阵](https://springdoc.org/v2/) |

版本策略：保留当前 Spring 三个版本基线，以现有 BOM 管理的版本为先；对 BOM 未管理的依赖固定发布版本。下文中的候选版本是可复核起点，不等于已完成整套兼容性认证。

## 2. 候选组件与业务接入边界

### RocketMQ 5：新增两个长期运行进程

官方定义 **Local mode：Broker 与 Proxy 在同一进程**；NameServer 单独运行。`mqbroker --enable-proxy` 可用于单节点单副本，官方明确该模式适合本地测试，Broker 重启或故障会导致服务不可用。无需额外运行独立 Proxy、Controller 或副本。[官方部署模式](https://rocketmq.apache.org/docs/deploymentOperations/01deploy/)

官方 Docker 快速开始使用过 `apache/rocketmq:5.3.2`，可作为 5.3.x 部署示例依据，实施时需再固定所选补丁版本和镜像摘要。[Docker 快速开始](https://rocketmq.apache.org/docs/quickStart/02quickstartWithDocker/)

Java 侧可评估 RocketMQ 5 gRPC SDK：服务端需至少 5.0 且启用 Proxy，普通、FIFO、延迟和事务消息都有官方示例。[Java SDK](https://rocketmq.apache.org/docs/sdk/02java/)。建议先用普通 SDK Bean 接入，避免未经核验的旧 Boot starter 改写 Spring 依赖；Broker 容器自身的 JDK 与业务 Java 21 可分别固定。Java 21 下发送、消费、关闭与重连仍需本项目测试。

接入建议：选择订单超时关闭、退款/订单事件、搜索索引更新等一条真实链路；实现消息幂等、失败重试、死信观察和状态补偿。消息发送成功与数据库提交不是同一个事务；容器启动或示例消息成功不代表订单链路已完成。

### Seata 2.5：一个 TC 服务，客户端嵌入业务 JVM

保持现有 BOM 的 2.5.0 路线有明确版本基础：官方源码含 `.jdk21` 镜像发布 profile，但这不证明项目所有 ORM、SQL 与数据源包装组合已兼容。[Seata 2.5.0 POM](https://raw.githubusercontent.com/apache/incubator-seata/v2.5.0/pom.xml)

**AT** 依赖 JDBC 数据源代理、`undo_log` 和全局锁；业务写入及回滚日志在第一阶段的本地事务内提交，第二阶段完成提交或补偿。[AT 官方说明](https://seata.apache.org/docs/v2.5/user/mode/at/)

**TCC** 由业务实现 Try / Confirm / Cancel，需处理幂等、空回滚和悬挂等问题；不是加上注解就自动获得正确补偿。[TCC 官方说明](https://seata.apache.org/docs/v2.5/user/mode/tcc/)

单个 `seata-server` 可本地运行；官方部署支持 file / db 存储，db 可复用本地 MySQL 的独立 schema，无需额外 MySQL 实例。[Docker 部署](https://seata.apache.org/docs/v2.5/ops/deploy-by-docker/)

接入建议：先做一条跨服务、跨数据源的 AT 成功与失败回滚链路，再将一个明确需要资源预留的场景作为 TCC 练习。若所有操作原本就在同一本地事务内，Seata 不会凭空增加业务价值。不要让 AT、TCC、RocketMQ 事务消息同时控制同一状态变更而未定义责任边界。

### XXL-JOB：一个 admin，执行器嵌入现有服务

官方 Spring Boot 示例将 `XxlJobSpringExecutor` 声明为 Bean，因此可嵌入 catalog/trade 等已有 JVM，不需要新增独立执行器应用。它仍会监听执行器端口，admin 必须能访问该地址。[官方执行器配置示例](https://raw.githubusercontent.com/xuxueli/xxl-job/3.2.0/xxl-job-executor-samples/xxl-job-executor-sample-springboot/src/main/java/com/xxl/job/executor/config/XxlJobConfig.java)

3.2.0 源码的编译目标为 Java 17，Spring 代际与本项目接近；Java 21 是合理候选运行时，但仍需启动、触发、超时与回调测试。admin 和 core 优先使用相同版本；新版本存在数据库、地址和 token 行为变化，升级需按官方发行说明执行。[3.2.0 源码](https://raw.githubusercontent.com/xuxueli/xxl-job/3.2.0/pom.xml)、[发行记录](https://github.com/xuxueli/xxl-job/releases)

接入建议：影片同步、过期数据清理、报表汇总等任务改为 handler，通过 admin 手动/定时触发并观察日志。重复触发、重试和进程重启仍需业务幂等；不能与原 `@Scheduled` 同时重复执行同一任务。只启动 admin 不等于任务接入完成。

### Redisson：不新增服务进程

Redisson 3.52.0 官方 README 列出 JDK 8+ 至更新版本、Redis 3.0+ 和 Single 部署模式；可连接当前 Redis 7.2。一个线程安全的 `RedissonClient` 可在应用内复用。[固定版本 README](https://raw.githubusercontent.com/redisson/redisson/redisson-3.52.0/README.md)、[官方入门](https://redisson.pro/docs/getting-started/)

建议先以普通 `org.redisson:redisson` 客户端 Bean 增补锁、信号量等能力，保留现有 Lettuce/RedisTemplate。若使用 starter，Boot 3.5 对应 `redisson-spring-data-35`；当前 starter 默认面向更高 Spring Data 版本，需要显式匹配，不能盲追最新版。[Spring 集成矩阵](https://redisson.pro/docs/integration-with-spring/)

接入建议：选择单个有实际互斥需求的管理任务或业务资源操作，验证超时、锁释放和进程退出。锁不代替数据库唯一约束、事务与幂等。

### Dubbo 3：可选，只接一条只读 RPC，不新增独立 JVM

Dubbo 3.3.5 官方源码的 Boot 3 profile 使用 Boot 3.5.0 / Spring 6.2.7，可作为与本项目同代的选型证据；官方文档支持 Spring Boot 3 / Spring 6 和 Nacos 注册中心。[3.3.5 POM](https://raw.githubusercontent.com/apache/dubbo/dubbo-3.3.5/pom.xml)、[Boot 集成](https://dubbo.apache.org/en/overview/mannual/java-sdk/tasks/develop/springboot/)、[升级兼容说明](https://dubbo.apache.org/en/overview/mannual/java-sdk/reference-manual/upgrades-and-compatibility/migration/)

建议 provider/consumer 分别嵌入已有服务，复用单 Nacos；共享 API DTO 模块本身不产生进程。先选影片摘要或场次查询等只读接口，验证服务发现、超时、序列化与异常映射。Java 21、Nacos 3.0.3、当前 Nacos Client 和其他 Netty 使用方组合尚待项目实测；Dubbo Admin 不是调用必需项。

### SkyWalking：OAP + UI 两个服务，复用单 ES 8

OAP 10.2.0 的官方存储文档明确支持 Elasticsearch 8.x，统一使用 `elasticsearch` storage provider；可复用业务 ES 实例并隔离索引 namespace。单节点实验应明确 replicas、索引数量、TTL 与采样，避免追踪数据挤占影片搜索的资源。[OAP 10.2 ES 存储](https://skywalking.apache.org/docs/main/v10.2.0/en/setup/backend/storages/elasticsearch/)

Java Agent 9.8.0 明确支持 JDK 8–25，使用 ZGC 时要求 OAP 9.7.0+。[固定版本 agent README](https://raw.githubusercontent.com/apache/skywalking-java/v9.8.0/docs/en/setup/service-agent/java-agent/README.md)

建议评估 **OAP/UI 10.2.0 + agent 9.8.0**：9.6.0 已加入 Gateway 4.3.3 测试，9.8.0 修复 Gateway 4.1.2+ 并发上下文串线；9.7.0 的发行包存在打包问题，官方说明已撤下并要求升级 9.8.0。这是有具体适配与修复依据的版本选择。[官方 agent 发行记录](https://github.com/apache/skywalking-java/releases)

Agent 嵌入五个业务 JVM，无独立容器；Spring MVC/WebFlux/Gateway 的部分插件需要显式启用。接入完成应看到 gateway→业务→数据库/Redis/MQ 的同一条 trace，并抽查异常和千问出站 HTTP/SSE 链路；OAP/UI 能打开不代表完整链路已采集。[插件支持列表](https://skywalking.apache.org/docs/skywalking-java/latest/en/setup/service-agent/java-agent/supported-list/)

### Prometheus + Grafana：两个服务，应用内 Micrometer

Boot Actuator 自带 Micrometer 的管理与自动配置；加入相应 registry 才形成 Prometheus 数据出口，还需暴露端点并配置抓取。[Spring Boot 3.5 Metrics](https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html)

Prometheus 单实例可用官方容器；Grafana 单实例可用内置 SQLite，不要求再建数据库。当前 Grafana 文档提醒 `grafana/grafana-oss` 自 12.4.0 起不再更新，OSS 镜像应使用 `grafana/grafana` 并固定 tag。[Prometheus 安装](https://prometheus.io/docs/prometheus/latest/installation/)、[Grafana Docker](https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/)

接入建议：先采五个应用的 JVM、HTTP、连接池与订单/AI 调用指标，配置 Grafana 数据源及真实面板。Exporter、Alertmanager 可后续按需增加；它们不在最小双容器数量中。

### Knife4j 与 Excel：均不新增长期运行进程

Knife4j 的 Boot 3 入口为 `knife4j-openapi3-jakarta-spring-boot-starter`，要求 JDK 17+；它包含 springdoc。实施前检查引入的 springdoc 与 swagger 组件，将版本统一到 Boot 3.5 支持范围，再验证 `/v3/api-docs`、`doc.html`、鉴权和网关聚合。官方旧示例版本不能直接作为本项目的全组合兼容证据。[Knife4j](https://doc.xiaominfo.com/docs/quick-start)、[springdoc](https://springdoc.org/v2/)

Apache Fesod 是 FastExcel 的当前官方路线，使用 `org.apache.fesod:fesod-sheet`；README 要求 Java 8+，当前 CI 明确测试 Java 21。它基于 POI，需检查现有 POI 依赖冲突。[Fesod README](https://raw.githubusercontent.com/apache/fesod/main/README.md)、[CI](https://raw.githubusercontent.com/apache/fesod/main/.github/workflows/ci.yml)

建议先评估已发布的 Fesod 2.x incubating 补丁版本，不采用 main/SNAPSHOT；固定版本后验证中文、日期、金额、大批量导出及错误行反馈。Java 21 的 CI 证据不能替代项目导出测试。[发布列表](https://github.com/apache/fesod/releases)

### RustFS：已核验但未采用的候选

RustFS 官方于 2026-09-16 宣布 1.0.0 GA，当前仓库有 1.0.3 正式发布；1.0.3 更新内嵌 Console。因此不能沿用旧资料将其描述为只有 alpha，亦不应未经核验直接使用 `latest`。[GA 公告](https://rustfs.com/blog/announcing-rustfs-1-0-0-ga/)、[1.0.3 发布](https://github.com/rustfs/rustfs/releases/tag/1.0.3)

官方容器支持 **SNSD 单节点单盘**；该模式无纠删码、无冗余，适用于本地测试与小负载。S3 API 和 Console 可由一个容器提供，不需要额外控制台服务。[容器安装](https://docs.rustfs.com/en/installation/container)

当前英文硬件检查单列测试环境 **1 核 CPU+、1 GB 内存+、一个节点**，存储可 HDD/SSD；这是测试起点，不是本项目性能保证。建议初始预留约 1–2 GiB 并实测，保留本地原文件备份。[硬件要求](https://docs.rustfs.com/en/installation/requirement/checklists/hardware-selection)

接入建议：通过 S3 兼容 SDK 实现海报或导出文件上传、下载、删除和预签名 URL；验证 endpoint、path-style、浏览器 CORS 与错误处理。S3 兼容并不等于对所有 MinIO 扩展行为完全兼容。Windows 原生版本官方定位为开发调试，本项目可评估 Docker Desktop/WSL2 的 Linux 容器路径。[安装模式](https://docs.rustfs.com/en/installation)

## 3. 本地进程/容器数量估算

以下按“一个长期运行服务一个容器或本地进程”计，初始化命令不计。SDK、agent、Bean 与共享 API 模块不单独计数；这是部署拓扑推算，不是已经运行的实测清单。

| 类别 | 实例/进程数 | 说明 |
| --- | ---: | --- |
| 五个应用服务 | 5 | gateway / identity / catalog / trade / ai 各一份 |
| MySQL、Redis、Nacos | 3 | 各一实例，无副本、Sentinel 或 Router |
| Elasticsearch 8 | 1 | 业务检索与 SkyWalking 分索引复用 |
| RocketMQ | 2 | NameServer 1；Broker+Proxy 同进程 1 |
| Seata | 1 | 单 TC，复用 MySQL/Nacos；RM/TM 在应用内 |
| XXL-JOB | 1 | admin；执行器嵌入现有服务 |
| SkyWalking | 2 | OAP + UI；agent 在应用内 |
| Prometheus、Grafana | 2 | 各一实例 |
| **中间件小计** | **12** | 不含五个应用；文件使用阿里云OSS，无本地存储进程 |
| Vue 开发服务器或静态 Web 服务 | 1 | 开发时 Vite，构建后可由一个静态服务替代 |
| **完整基线合计** | **18** | 12 个中间件 + 5 个应用 + 1 个前端服务 |

Redisson、Dubbo、Knife4j、Fesod 均增加 **0** 个独立长期进程。若另加 Sentinel Dashboard、RocketMQ Dashboard、Dubbo Admin，各再加 1；三者都加则为 21 个长期进程。宿主/Redis/MySQL exporter 和 Alertmanager 另计。后续业务拆分也会改变进程数，最终采用清单以当前主方案为准。

## 4. 内存与实施范围估算（工程假设）

以下不是官方最低配置、生产推荐或本机检测结果，只用于判断能否同时打开全部组件。按小数据集、短保留期、有限追踪采样估算 RSS 及缓存量级；JVM heap 不是容器总内存。

| 组别 | 本地内存预留范围 |
| --- | ---: |
| 五个应用 JVM（含 SDK/agent） | 4–8 GiB |
| MySQL + Redis + 单机 Nacos | 2–4 GiB |
| Elasticsearch | 2–4 GiB |
| RocketMQ 两进程 | 2–4 GiB |
| Seata + XXL-JOB admin | 1–2 GiB |
| SkyWalking OAP + UI | 2–4 GiB |
| Prometheus + Grafana + 前端服务 | 1–3 GiB |
| **应用及容器合计** | **约 14–29 GiB** |

还需留出操作系统、Docker/WSL2、IDE、浏览器和构建的余量；不能把上表当作 Docker 默认配置即可全部运行的承诺。可按组件组启停，先测启动峰值、GC/RSS 和磁盘占用后再调整。无需为丰富技术栈引入副本。

建议按可验收业务链分批接入：①文档/导出/OSS文件业务整理，②MQ 消费与补偿，③调度与任务幂等，④AT 再 TCC，⑤只读 Dubbo RPC，⑥trace 与指标贯穿前述链路。该排序是工程建议；部署基础设施、加入依赖、接入业务、完成验收应分别记录状态。

## 5. 资料核验结论的使用方式

候选组件均有官方文档、源码或发行资料可供核对；本文不对其市场份额作排名。对象存储按用户要求保留阿里云OSS；本地存储候选仅作资料留存，Excel新能力评估Fesod路线。

2026-10-23 运行补充：Knife4j 4.5.0 Jakarta starter 的 `Knife4jOpenApiCustomizer.addOrderExtension` 在实际请求 `/v3/api-docs` 时出现 `NoSuchMethodError: SpringDocConfigProperties.getGroupConfigs(): List`。本项目 springdoc 2.8.4 的接口签名已经变化，版本对齐仍不能消除该增强类的二进制不兼容。实现改为单独使用 `knife4j-openapi3-ui` 4.5.0 静态界面，由原生 springdoc 2.8.4 生成标准 OpenAPI，聚合和鉴权由本项目负责；保留查看、JWT 调试和域切换，不继续依赖旧服务端增强内部 API。组合的最终结论仍以真实 UI 验收为准。

当前能确认的是组件的官方能力、维护状态和部分版本证据；尚不能写为完成的是本项目 Java 21 / Boot 3.5.6 的完整组合兼容性、真实业务集成、故障恢复正确性及本机资源占用。任何后续“已接入”结论都应附上对应业务成功/失败路径的实际验收结果。


## 2026-10-23 监控与追踪版本补充

指标组合实际锁定 [Prometheus 3.15.1](https://github.com/prometheus/prometheus/releases/tag/v3.15.1) 与 [Grafana 13.2.4](https://github.com/grafana/grafana/releases/tag/v13.2.4)，部署状态和验收见 [指标记录](local-metrics-acceptance-2026-10-23.md)。

追踪候选经官方 GitHub Releases 再次核对为 [OAP 11.0.0](https://github.com/apache/skywalking/releases/tag/v11.0.0) 与 [Java Agent 9.8.0](https://github.com/apache/skywalking-java/releases/tag/v9.8.0)；下载页摘要仍显示较早 Agent 9.7.0，实际 9.8.0 已于 2026-10-08 发布。候选不表示已经部署或已完成 Boot 3.5 / Gateway 组合验证。官方 11.0 文档保留 [Elasticsearch 7/8 存储选项](https://skywalking.apache.org/docs/main/v11.0.0/en/setup/backend/backend-storage/)，可以评估复用同一 ES 实例的隔离索引；Gateway 插件应按 [可选插件说明](https://skywalking.apache.org/docs/skywalking-java/v9.8.0/en/setup/service-agent/java-agent/optional-plugins/) 单独启用，不能因为 Java agent 已加载就认为异步上下文传播完整。
