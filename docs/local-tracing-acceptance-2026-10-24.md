# 本地 SkyWalking HTTP 追踪验收（2026-10-24 定时轮次）

执行跨越 10 月 24 日晚至 25 日凌晨。HTTP 主验收九组与真实管理员认证隐私补充验证均通过，两份报告均为 `completed=true`。已恢复常用 metrics 组：16 个常驻单例、七个指标目标全部为 up、五个业务应用无 Agent 挂载。最后八组业务页面回归通过，API / 页面脚本错误均为零，包含十个管理页面、CSV 下载和 CORS 检查。

## 运行方式

应用与中间件仍为本地单实例。Agent 9.8.0 通过 SHA512 校验后挂载进现有 Java 应用，OAP 11.0.0 和 Horizon 1.0.0 使用固定镜像摘要。追踪组共 12 个常驻角色：MySQL、Redis、Nacos、Elasticsearch、OAP、Horizon、Identity、Catalog、Trade、AI、Gateway、Web。追踪存储复用现有 ES，以 `cinema-observe` 命名空间隔离；Search、MQ、优惠券事务和指标控制台在该实验组暂停，数据卷保留。

在 `cinema-server` 目录运行，需要 JDK 21+、Docker、Python 与现有浏览器验收依赖：

```powershell
./tools/local-stack.ps1 -Action Up -Group tracing
python tools/tracing_local_acceptance.py --output .work/tracing-accepted-20261024 --browser --auth-probe --outage
python tools/tracing_local_acceptance.py --output .work/tracing-auth-final-20261024 --application-auth-only
```

Horizon 在 `http://127.0.0.1:18080`，用户名 `cinema`，密码为本机忽略文件 `.env` 中的 `SKYWALKING_UI_PASSWORD`。OAP 的 gRPC、查询和管理端口仅在项目 Docker 网络内使用。OAP record TTL 与 metrics TTL 均为两天；该版本不接受一天的 record TTL。

实验结束恢复常用指标组：

```powershell
./tools/local-stack.ps1 -Action Up -Group metrics
```

该操作暂停 OAP / Horizon / ES，并恢复没有 Agent 挂载的业务容器。业务入口为 `http://localhost:8088`，Grafana 为本机 13000 端口，Prometheus 为本机 19090 端口。

## 接入与隐私边界

真实只读请求会产生同一 trace ID 下的 Gateway → Catalog → Feign → Trade → JDBC 调用链，并校验跨进程父片段引用。该 trace ID 与原有 `X-Trace-Id` 请求关联号目前各自独立，业务日志桥接仍待接入。

本地 HTTP 实验将 `SW_AGENT_SAMPLE=0`，即关闭数量采样限额、完整采集请求；队列仍有上限，存储仍按 TTL 清理。此前 Reactive Redis 轮询产生了大量无关片段，配合较低采样限额出现不完整链路，因此该组排除了两个 Lettuce 插件。Redis 缓存功能继续使用，Redis 调用追踪不在本轮覆盖范围。健康检查忽略规则写入 Agent 实际读取的 `apm-trace-ignore-plugin.config`，覆盖 MVC 与 Gateway 端点格式。显式带上游已采样上下文的健康请求与普通 Docker 健康检查的采样语义不同，验收使用无 `sw8` 的实际健康探测。

HTTP 参数收集开关关闭时，上游 WebFlux / Gateway 插件仍可能在 `url` 标签中包含查询串。`tools/tracing/CinemaTracePrivacy.java` 因而作为 Agent 内的 BootService SPI，在标准报告器入队前去除 URL 查询串、片段及用户信息，并兜底遮盖参数、请求体、请求头和 SQL 绑定值标签。扩展不进入业务 JAR，不修改上游 Agent JAR。业务追踪关闭后没有该扩展运行时依赖。

扩展固定依赖 Agent 9.8.0 的两个字段：`TraceSegment.spans` 与 `AbstractTracingSpan.tags`。每次初始化都会编译并用实际 Agent 检查 ABI、URL 场景、报告器注册顺序、父子 span 保留和异常片段丢弃；检查不通过会阻止启动。处理异常会丢弃该片段并记录一次不含原始数据的警告。升级 Agent 必须重新检查这条边界。该扩展不改写业务日志、异常堆栈或任意自定义标签；不能据此宣称所有日志均已脱敏。

## 验证项目与证据

| 验证项目 | 验证内容 |
| --- | --- |
| Horizon 登录 | 未登录的身份、追踪与 OAP 信息接口返回 401，私有账号能登录 |
| Agent 鉴权 | 真实 Java 探针携带错误 token，被 OAP 返回 `PERMISSION_DENIED`，未注册服务 |
| HTTP 跨服务链 | 实际业务请求包含三域、Feign、JDBC，以及正确跨进程父引用 |
| URL 与数据收集 | 合成查询哨兵值不进入最终 trace，SQL 绑定值和配置凭据不被收集 |
| 真实浏览器 | Chrome 登录并显示三服务调用瀑布图，无页面脚本或控制台错误 |
| 追踪停机 | OAP 与 Horizon 同时停止期间，业务只读链仍返回成功 |
| 追踪恢复与持久化 | 有时限的真实读取探针观察 Agent 重新上报，旧 trace 经 OAP 重启仍可查询 |
| 健康检查忽略 | 五个应用的普通 readiness 探测健康，测量区间没有该端点的新 trace |
| ES 隔离 | `cinema-observe` 片段实际落库，业务电影索引计数与别名保持一致 |
| 认证链隐私 | 真实验证码、管理员密码登录、携带真实 JWT 的读取产生关联 trace，凭据与绑定值未被采集 |

主证据为 `.work/tracing-accepted-20261024/report.json`、`http-trace.json`、`horizon-trace.png` 与 `auth-probe/denied.log`；认证补充证据为 `.work/tracing-auth-final-20261024/report.json` 和 `authenticated-traces.json`。报告仅保存检查结果及脱敏后 trace，不保存登录 token 或验证码。

运行恢复证据为 `.work/tracing-restored-state-20261024.json` 与 `.work/tracing-restore-metrics-20261024.log`；业务页面证据为 `.work/tracing-final-business-20261024/report.json`。本轮另通过了实际 Agent Java 自检、Python 语法检查和架构约束检查，未改动业务 JAR 或前端源码。

OAP 端口健康与 Agent 的异步 gRPC 重连不是同一状态。恢复测试会在 180 秒内用间隔至少十秒的真实只读请求观察新 trace；停机和重连窗口中的 trace 可能缺失，未承诺缓冲补齐。该结果属于本地运行验证，没有进行吞吐量或高可用容量承诺。

MQ 跨消息追踪、日志关联、Loki / Alloy 及阶段 7 仍待实施。千问 API 与现有阿里云 OSS 的业务配置沿用原方案。

官方依据：[Agent 配置](https://skywalking.apache.org/docs/skywalking-java/v9.8.0/en/setup/service-agent/java-agent/configurations/)、[健康检查忽略插件](https://skywalking.apache.org/docs/skywalking-java/v9.8.0/en/setup/service-agent/java-agent/agent-optional-plugins/trace-ignore-plugin/)、[查询协议](https://skywalking.apache.org/docs/main/v11.0.0/en/api/query-protocol/)。具体兼容性与隐私断言以本项目的实际 Agent 自检和运行证据为准。
