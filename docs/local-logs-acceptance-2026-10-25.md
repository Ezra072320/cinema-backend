# 本地集中日志与当前步骤收尾验收（2026-10-25）

用户要求只完成暂停时的当前步骤，不再增加技术栈。本次已完成集中日志的部署、前后端回归和停机收尾；原持续实施目标保持暂停。所有应用、中间件仍为本地单例，千问 API 和阿里云 OSS 沿用原配置。

## 已交付能力

应用通过 `local-logs` profile 输出固定字段的 JSON 文件；Gateway、MVC、Feign 使用请求关联号，加载 SkyWalking Agent 时另记录 trace ID。异步 SSE 在终态记录一次，路由使用模板或网关 route ID。订单与优惠券预占日志记录 order_id / XID，并明确标为事务内 staged 事件，不代表全局事务最终提交。

Alloy 只读挂载专用日志卷，不挂 Docker socket；Loki 采用 TSDB v13 / filesystem，配置 24 小时保留。Grafana 提供日志面板、请求号/trace ID 查询及真实追踪链接。无 Agent 时 trace_id 为空，浏览器不展示不可用的追踪链接。本地文件轮转为单文件 5 MB、归档保留 2 天、归档总上限 20 MB/应用；这不改变业务文件上传 OSS。

日志遮盖已知环境凭据与 JWT，去除 URL 的用户信息、查询和片段，限制消息/异常长度，不序列化任意 MDC、请求体或认证头。它不是任意业务文本的通用个人信息识别器。Alloy 使用标准重试及持久化读取位点；停机补读通过验收，不据此承诺采集器崩溃后绝不丢失或恰好一次。

## 本次修正与验证版本

Trade 调用 Benefit 预占券时，旧的通用 3 秒读取预算先于约 4.3 秒的实际处理结束。仅给 `BenefitClient.reserve` 配置 1 秒连接、8 秒读取预算，其他查询保持 3 秒，不自动重试。真实 HTTP 服务延迟 4200 ms 的回归测试先在旧配置失败，再在修正后通过。完整 Maven verify 为 428 项、失败/错误 0、跳过 5，记录在 `.work/benefit-deadline-verify-20261025.log`。

为避免镜像再次积累多版业务 JAR，Dockerfile 拆出无业务 JAR 的共同运行时层，`tools/build_local_runtime.py` 直接以已有 target 为命名构建上下文，不再复制一份宿主 JAR，也不从旧业务镜像叠加。此次只构建 Trade：843.7 MiB，原镜像约 3.33 GB；容器已核对使用新镜像，JAR SHA-256 与构建产物一致，见 `.work/logs-finish-runtime-build.json`。

源码仍以 Java 21 编译。核对发现原部署运行时实际为 Temurin 25.0.4.1，本次使用已缓存的相同运行时，未升级运行环境；普通 Dockerfile 默认保持 Java 21。Docker Hub 鉴权端点超时，因此本轮未下载新的 Java 21 镜像。

## 实际验收证据

下列路径均相对于 cinema-server；报告、截图不保存密码或 JWT。失败轮次另外保留，不覆盖为通过。

| 项目 | 本次结果 | 证据 |
| --- | --- | --- |
| 优惠券 AT、回滚/超时、TC 重启、响应丢失、MQ 恢复、千问券确认单与订单/XID 日志关联 | 12 组，completed=true，页面错误 0，专用数据已清理 | `.work/logs-finish-transactions-20261025/report.json` |
| 完整 AI 聊天与服务单 | 11 组，completed=true，页面错误 0，专用数据已清理 | `.work/logs-finish-ai-20261025/report.json` |
| 基础前台、十个管理页面、营收 CSV 下载、CORS | 8 组，接口错误 0、JavaScript 错误 0，实际 CSV 2402 字节 | `.work/logs-finish-business-recheck-20261025/report.json` |
| Trade 重启后的管理接口复验 | 三接口各连续 3 次，9 次均 HTTP 200 / code=1；耗时 24～627 ms | `.work/admin-dependency-probe-20261025/report.json` |
| 无 Agent 的日志、四并发关联、认证隐私、真实千问 SSE、Loki/Alloy 停机恢复 | 8 组，completed=true，SSE 临时用户已清理 | `.work/logs-finish-acceptance-20261025/report.json` |
| 实际 Chrome 中的 Grafana 指标和日志面板 | completed=true，7 个健康目标，页面/控制台错误 0，无 Agent 时无追踪链接 | `.work/logs-finish-browser-20261025/report.json` |

AI 回归包含连续不同服务单、补充信息后精确选座、真实 Trade 停机后以同一服务单重试且只创建一单、支付/实时查询/退款、放弃服务单、重新购票与取消、确认评价、首 token 前停止后继续、刷新恢复和移动端布局。

此前加载 Agent 的最终日志九组、HTTP 九组和实际认证隐私补充均已通过。真实 Grafana 链接已点击打开 Horizon 同 ID 的三服务调用链，页面/控制台错误 0，证据分别为 `.work/logs-final-20261025/report.json`、`.work/logs-http-final-20261025/report.json`、`.work/logs-auth-final-20261025/report.json`、`.work/logs-browser-click-20261025/report.json`。本轮无 Agent 组验证与这些证据互补，没有将两种运行模式混写为同时启用。

## 管理页瞬态 503 的排查边界

首次基础浏览器回归失败，证据为 `.work/logs-finish-business-20261025/report.json`。优惠券模板、营收、治理接口返回 503，导致营收导出按钮按设计禁用，该轮没有算作通过。

同关联号日志表明，Trade 请求在鉴权阶段约 4～13 ms 返回 503，并报告负载均衡器没有 cinema-identity 实例；Benefit 请求约 3046 ms 返回 503。同期 Identity 自身登录/权限接口仍成功。这些证据不能支持“网关 5 秒时限导致全部失败”。恢复后核对 Nacos 中 Identity 为 healthy/enabled，Trade 与 Benefit 均能直连其 readiness；基础浏览器八组通过，再重启同一个 Trade 容器，三个管理接口连续三轮全部通过。

本轮没有修改应用源码来掩盖这次失败，也没有扩大通用超时。实例暂不可用的具体触发条件仍未唯一定位；测试时宿主可用内存一度不足 0.5 GiB，资源压力只是候选因素，不写成已证明根因或已永久消除的风险。健康筛选的具体行为参照 [Spring Cloud 4.3.0 源码](https://github.com/spring-cloud/spring-cloud-commons/blob/v4.3.0/spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/HealthCheckServiceInstanceListSupplier.java)。

## 最终停机状态与范围

2026-10-25 19:28（Asia/Shanghai）已成功停止本项目运行容器并关闭 Docker Desktop，验收/容器停止/Desktop 停止退出码均为 0。收尾记录为 `.work/logs-finish-final-shutdown-20261025.json`；记录时 C 盘可用约 26.49 GiB，实际空间随宿主活动变化。未删除数据库、数据卷、源码或配置，也未清空 Docker 数据盘。

`logs` 组为 18 个常驻角色，可按原入口 `./tools/local-stack.ps1 -Action Up -Group logs` 手动启动；本次按用户选择保持关闭。`observability` 组保留 Agent/HTTP 追踪与日志的已有实验配置。MQ 跨消息追踪仅有静态配置、未启动验收；阶段 6 不标为全部完成。阶段 7、TCC、MQ 独立实验等均未在本轮继续实施。
