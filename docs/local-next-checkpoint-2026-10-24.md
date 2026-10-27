# 影院项目接续检查点（2026-10-24 定时轮次）

本轮继续阶段 6 的 SkyWalking HTTP 追踪，执行跨越 10 月 24 日晚至 25 日凌晨，现已收尾。九组主验收 `.work/tracing-accepted-20261024/report.json` 与真实管理员认证隐私补充 `.work/tracing-auth-final-20261024/report.json` 均为 `completed=true`。已恢复常用 metrics 组，当前没有构建或恢复操作运行。

已修正 OAP 11 最少两天的 record TTL、健康检查忽略配置的实际加载方式，以及 WebFlux/Gateway 默认 `url` 标签仍含查询串的问题。`tools/tracing/` 是固定 Agent 9.8.0 的 agent-only SPI 隐私扩展，初始化时编译并检查 ABI、URL 清理、上报顺序和失败关闭；不改变业务 JAR 依赖。固定 agent 的 `TraceSegment.spans` 与 span 的 `tags` 是仅有的反射边界，升级前必须重验。

最终配置将本地采样限额设为 0（完整采集）并排除 Lettuce 追踪，解决原配置下 Redis 孤立片段和低采样限额中的不完整链路；Redis 追踪不在本轮覆盖范围。真实 Gateway → Catalog → Feign → Trade → JDBC、私有 Horizon / Agent 鉴权、浏览器瀑布图、URL 隐私、健康检查忽略、ES 隔离均已通过。OAP 停机期间业务可用，恢复探针观察到新 trace，原 trace 经重启仍可查询。显式采样的上游 `sw8` 会改变健康请求的采样语义，健康忽略测试已改为普通无头探测与区间落库检查。

当前基线已验证：

```powershell
# 本轮已执行并通过；后续业务变更时按需复验
python tools/browser_smoke.py --allow-search-offline --output .work/tracing-final-business-20261024
```

业务页面脚本退出 0，八组通过、API / 脚本错误为零。`.work/tracing-restored-state-20261024.json` 为 `completed=true`：七个指标目标正常、16 个常驻单例，Identity / Catalog / Trade / AI / Gateway 均无 Agent 挂载。主验收与具体边界见 [追踪验收](local-tracing-acceptance-2026-10-24.md)。追踪工具今后复验时应将主故障实验与管理员认证补充顺序执行，避免重连窗口影响认证隐私探针。

历史失败证据保存在 `.work/tracing-acceptance-20261024/pre-fix-report.json`、`single-recovery-probe-report.json` 及对应日志中。它们不是最终验收结果。业务验证仅使用既有管理员登录与查询，没有新增账户、订单、修改业务 JAR / 前端源码或删除数据卷。

下一步继续阶段 6：先接入 SkyWalking trace ID 与业务日志关联，再推进 Loki/Alloy 与轻量 MQ 追踪组，分别验证开关、敏感信息处理、故障恢复和关联查询。HTTP 组已排除 Lettuce 追踪，不能把本轮结果扩展为 Redis 或 MQ 已验收。阶段 7、独立 Seata TCC / RocketMQ 事务消息实验也仍待实施。文件继续使用现有阿里云 OSS，模型继续千问，全部应用和中间件均为本地单例。保留两仓既有大量未提交变更，不重置、不整仓暂存或提交。
