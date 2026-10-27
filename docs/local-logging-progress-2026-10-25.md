# 日志关联与集中采集过程记录（2026-10-25，当前步骤已收尾）

最终状态：当前集中日志步骤及完整业务回归已完成，详见 [最终验收记录](local-logs-acceptance-2026-10-25.md)。优惠券事务 12 组、AI 11 组、基础浏览器 8 组、重启后管理接口 9 次、最终无 Agent 日志 8 组及 Grafana 浏览器均通过。项目容器与 Docker Desktop 已于 19:28 关闭，C 盘记录约 26.49 GiB 可用。

**范围已结束**：用户要求只收尾、不新增能力、结束后关闭 Docker，均已执行。最后完整 Java 验证 428 项、无失败/错误（5 跳过），Trade 超时修复已实际部署。管理页瞬态 503 在恢复后通过基础页面及 Trade 重启复验，其唯一触发原因尚未证明；不写为已永久消除。原无限持续实施目标保持暂停，MQ 追踪和后续阶段未继续。

## 历史过程

以下为各次实施时的现场记录，包含当时的“待验收/进行中”状态；后续工作以顶部状态及最终验收记录为准，不能按历史待办自动恢复启动。

本轮 Trade 镜像直接读取已有 target JAR，使用不含业务 JAR 的共同运行时层，镜像为 843.7 MiB（原约 3.33 GB），JAR SHA-256 已核对。未再创建 host JAR 副本，构建后 C 盘仍约 17 GiB。已有容器实际使用 Temurin 25.0.4.1，源码以 Java 21 编译；本轮沿用同一缓存运行时，普通 Dockerfile 默认仍为 Java 21。

`.work/logs-finish-transactions-20261025/report.json` 为 completed=true、12 组、页面错误 0、test_data_cleaned=true；实际订单 ID / XID 能定位 Trade 与 Benefit 的两端日志。`.work/logs-finish-ai-20261025/report.json` 为 completed=true、11 组、页面错误 0、test_data_cleaned=true。首次流水线在基础页面报表导出前失败，已停止容器并关闭 Docker，记录为 `.work/logs-finish-shutdown-20261025.json`。未将按钮禁用或自动收尾误记为验收通过。

用户明确授权临时恢复 Docker 排查，完成后仍关闭。组合启动命令两次被自动审批拒绝，提示仅为 blocked by policy；之后独立的 `docker desktop start --timeout 120` 成功。已有 Loki 日志证实：Trade 的三个管理请求在鉴权阶段 4～13 ms 返回 503，同关联号提示没有 cinema-identity 实例；Benefit 管理请求约 3046 ms 返回 503。同期 Identity 自身登录/权限请求成功，尚不能归因于网关 5 秒时限。当前仅恢复既有 logs 组，核查注册发现及健康探测，不扩大通用超时、不进入 MQ 追踪等新步骤。

新增 observe 中的 JSON 日志编码、已知凭据与 JWT 遮盖、固定输出字段、请求完成日志。MVC API / internal 接口使用路由模板，异步 SSE 只在终态记录一次；Gateway 使用 Agent 9.8.0 对应的 apm-toolkit-webflux-6.x 捕获上下文。请求关联号与 SkyWalking trace ID 分开存储，未加载 Agent 时 trace_id 为空。

local-logs profile 增加本地轮转日志。Alloy 只读挂载专用日志卷，未挂 Docker socket；Loki 配置 TSDB v13 / filesystem / 24h 保留。日志本地文件只是观测数据，不改变业务文件继续上传阿里云 OSS 的要求。

新增 logs 组（18 个常驻角色）和 observability 组（16 个角色，包含指标/HTTP追踪/日志，暂停 MQ/Seata/Notify/Search 等可选角色）。两组 Compose、Loki、Alloy 静态配置均已通过检查。采集端使用标准重试及文件读取位点，不启用 Alloy 实验性 WAL；不能承诺采集器崩溃时无丢失或日志恰好一次。

接下来运行 tools/logs_local_acceptance.py（支持 --outage --sse-probe），验证三服务同 ID、四个并发请求、真实管理员登录隐私、真实千问 SSE、Loki 停机重试、Alloy 停机后补读；再按 webapp-testing 流程在实际 Grafana/Horizon 中检查关联查询和链接。之后切换 logs 组验证不加载 Agent 的基础业务和完整 AI 回归，最后恢复 metrics 组。

运行日志保存在 .work/logging-internal-verify.log、logging-images-final.log、observability-up-final.log。新 UI 与故障验收通过前保持本文件为实施中，避免将静态配置或单元测试视为最终完成。旧有大量变更和数据卷均保留，不整仓暂存或提交。

最新验证：.work/logging-internal-verify.log 全量成功，422 项、失败/错误 0、跳过 5；八个应用的最新 JAR 镜像已更新，综合组正在串行启动。异步请求发生 onError/onTimeout 后的最终响应状态还需要补充验证，不把正常 SSE 的检查扩展为所有错误终态已验证。

官方依据：[Spring Boot 3.5 结构化日志](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html)、[SkyWalking WebFlux toolkit](https://github.com/apache/skywalking-java/blob/v9.8.0/docs/en/setup/service-agent/java-agent/Application-toolkit-webflux.md)、[Alloy 文件与写入组件](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.write/)、[Loki 保留策略](https://grafana.com/docs/loki/latest/operations/storage/retention/)。Loki 3.7.9、Alloy 1.21.2 由官方 Releases 核对，并固定实际镜像摘要。


运行阶段更新：`.work/logs-stream-20261025/report.json` 九组完成、completed=true、sse_fixture_cleaned=true；包括真实千问只读资料 SSE、三服务同 trace、四并发、真实管理员认证隐私、Loki 重启恢复与 Alloy 文件补读。Grafana 真实页面已显示关联日志，链接点击验收仍待完成。

首次真实验证码在 Identity 渲染耗时 7513 ms，Gateway 5 秒时限先回 503；后续请求 19 ms。已在源码增加服务就绪前的纯本地渲染预热，不发 Redis 票据；同时让异步错误/超时只标记结果，在 onComplete 取最终响应状态。`.work/logging-captcha-final-verify.log` 已通过 424 项测试（5 跳过），八个镜像已构建，但当前运行容器还未切换到这次修正版。

订单审计日志原来包含自由文本原因。当前源码已保留数据库完整审计，INFO 日志改为事务内 staged 事件、订单 ID / 订单号 / XID，避免误写为全局已提交；正在 `.work/audit-log-final-verify.log` 验证并单独构建 Trade 镜像，完成前不要部署旧镜像。阶段验收最后仍需重新部署、冷验证码、最终日志/浏览器、无 Agent 日志组业务和 AI 全回归、恢复 metrics 组。不能只凭前一运行版本九组通过就宣告所有新修正已生效。

Loki 官方镜像无 shell/wget，健康探针第一次因此无法执行。现在 deploy/local/loki/Dockerfile 从固定 Prometheus 镜像复制已核对为静态链接的 BusyBox，探测真实 /ready，Loki/Alloy 已在实际运行中健康；不是以进程存在代替就绪。


最新代码已实际部署，`.work/cold-captcha-verified-20261025/report.json` 显示重启同一 Identity 容器后的首张图为有效、不缓存的 PNG，387.79 ms，票据已清理。最终 `.work/logs-final-20261025/report.json` 九组 completed=true，临时 SSE 用户清理完成；`.work/logs-browser-click-20261025/report.json` 在真实 Chrome 中看到 5 个采集目标，并点击实际 Grafana 链接打开同 ID 三服务瀑布图，脚本/控制台错误 0。Horizon 的持续轮询不能用无限 networkidle 判定，脚本尝试有界空闲等待后，以认证状态、实际面板数据和三服务 trace 为准。

HTTP 九组回归 `.work/logs-http-final-20261025` 与实际认证隐私补充 `.work/logs-auth-final-20261025` 均完成。额外为 Benefit 的事务内优惠券预占记录订单 ID / XID（staged，并非全局最终决议）；模块验证与新镜像完成。现在切换日志组（无 Agent），顺序执行真实无 Agent 日志、浏览器、优惠券 AT/MQ 全故障与千问券服务单、完整 AI 及基础页面回归。相关日志统一为 .work/logs-no-agent-*、logs-transactions-*、logs-ai-final-*、logs-business-final-*，这些回归尚未完成前，不恢复 metrics 或启动其他实验。

无 Agent 日志八组及实际浏览器已通过：`.work/logs-no-agent-20261025/report.json`、`.work/logs-no-agent-browser-20261025/report.json`；后者看到七个健康指标目标、无 Agent 时无追踪链接，页面/控制台错误 0。

完整券事务回归暴露两次不同的运行超时：第一轮模板创建已落库但 Gateway 5 秒返回 503，唯一验收模板已核对清理。Benefit 两次同容器重启后首次创建分别 1208/1270 ms 成功（`.work/benefit-cold-20261025/report.json`），尚不能证明第一次延迟的具体根因。第二轮正常创建/领券后，下单本地事务超时并回滚，Hikari / 定时任务同一时间也出现等待超时。已核对 TC / 订单 / 预占记录为空，再清理该轮用户、场次与券模板；失败报告保留，未把重试或清理当作业务通过。

当时 Windows 可用物理内存约 470 MB；基础五个应用继承镜像 70% 堆占比（容器上限 1 GiB），与追踪组的 256 MiB 明确预算不同。现已在本地 Compose 的公共环境中统一 `-Xms96m -Xmx256m`、直接内存 64 MiB、两可见 CPU；保留容器余量和原有超时时限。正在串行更新应用，随后运行 `.work/logs-transactions-bounded-20261025`、完整 AI 和基础浏览器回归。成功前不能将资源压力视为已证明的唯一根因。

`benefit_local_acceptance.py` 已在创建模板前保存唯一名称；若响应丢失，先核查只属于本轮的数据库记录再清理，原 HTTP 失败仍使验收失败，无法确认则保留现场。为后续 MQ 追踪增加的 `mq-observe` 启动组和配置仅已静态检查，尚未启动或验收；不会替换 v1 事件中的请求关联号。

进一步定位：明确 JVM 预算后的首次券订单仍失败，Trade 为 `feign.RetryableException: Read timed out`，Benefit 的预占处理约 4343 ms。现有 `BenefitClient.reserve` 继承通用读取超时 3000 ms，因此 Trade 先触发全局回滚，Benefit 随后提交失败。不能把此轮失败仅归因为内存压力。该轮 TC、订单和券预占均回滚为空后，验收专用数据已清理，报告为 `.work/logs-transactions-bounded-20261025/report.json`。

新增真实 JDK HTTP 服务的 `BenefitClientDeadlineTest`：预占响应延迟 4200 ms，旧配置稳定出现一次读取超时；券查询和报价保持短超时、无重复请求。失败报告已保存为 `.work/benefit-deadline-red-20261025.xml`。修正仅给预占写调用增加 1000 ms 连接 / 8000 ms 读取预算，小于 15 秒全局事务时限；不扩大所有 Feign 查询、Gateway 或全局事务时限。全量构建 `.work/benefit-deadline-verify-20261025.log` 进行中，尚待新镜像与全链路验收。
