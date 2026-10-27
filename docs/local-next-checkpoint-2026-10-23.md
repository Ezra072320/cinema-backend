# 本地影院接续检查点（2026-10-23）

后续已于 10 月 24 日晚至 25 日凌晨完成 HTTP / Feign / JDBC 追踪验收并恢复 metrics 组。当前状态与下一步以 [最新接续检查点](local-next-checkpoint-2026-10-24.md) 为准；下文保留为历史记录。

本轮已完成领域调度及搜索回归、Prometheus/Grafana 六组真实验收与八组业务浏览器回归。监控组正常使用方式见 local-metrics-acceptance-2026-10-23.md。当前全量 Maven 414 项，失败/错误 0、跳过 5。

继续前检查 `.work/metrics-default-off/report.json` 与 `.work/metrics-final-restore.log`，这是本轮最后的关闭开关及恢复操作；不要在该操作运行时重建应用。当前目标启动组 metrics，16 个常驻单例，私有凭据均在 .env / .work，不打印。

下一步是 SkyWalking。tools/init-tracing.py 已实际完成 Agent SHA512 校验和安全解包，准备 MVC 6 / Gateway 4 可选插件、私有 Argon2 登录配置；compose.tracing.yml 已通过 Compose 静态校验，local-stack.ps1 已增加 tracing 组（12 个角色），但尚未真正启动。UI 版本必须是 apache/skywalking-ui:horizon-1.0.0，OAP 11.0.0 没有同版本 UI 标签。

先启动 tracing 组并验证 ES 隔离命名空间、OAP 存储、Agent 鉴权、真实 HTTP 跨服务追踪及 Horizon 登录。不能只凭进程健康宣称追踪完成；需要从 Gateway 到业务、Feign 和数据库的实际 trace。现有 X-Trace-Id/MDC 还是项目原有请求关联号，尚未接入 SkyWalking trace ID。MQ 跨消息链路、日志桥接、Loki/Alloy、阶段 7、Seata TCC/MQ 独立实验均待实施。追踪实验结束可恢复 metrics 或 governance 组；不删除数据卷。

文件仍使用阿里云 OSS，模型仍使用千问 API，全部应用和中间件继续本地单例，不恢复上云或集群计划。工作区含大量连续变更，不重置，不整仓暂存或提交。
