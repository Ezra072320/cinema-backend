# 本地 Sentinel / Nacos 规则验收

2026-10-23，阶段 5 的流量治理子项已通过真实运行验收。XXL-JOB / Redisson 另见[调度验收](local-jobs-acceptance-2026-10-22.md)；阶段 5 其他子项继续实施。

## 业务与持久化边界

`trade:seat-query` 控制 Trade 的选座查询，入口位于 `ScheduleServiceImpl.seatsOfSchedule`，普通页面和内部 AI 只读查询共用同一资源。超限返回 HTTP 429 和 `Retry-After: 2`。下单、支付、退款不在此资源中，不对远程写操作自动重试。

管理员在 `/manage/governance` 保存整数 QPS（1–1000），Trade 发布到 Nacos `CINEMA_GOVERNANCE / cinema-trade-seat-flow.json`，Sentinel NacosDataSource 订阅并更新内存规则。页面分别显示持久配置与实际生效值，异步下发后轮询确认。初始化只补充缺失的默认 30 QPS，保留已有配置。非法资源、策略、规则数量和阈值被拒；推送格式错误时继续使用最后一份有效规则。

Sentinel Dashboard 是带登录的本地观察入口，地址 `http://127.0.0.1:18880`，账号 `cinema`，随机密码只在忽略的 `.env`。原版 Dashboard 的直接编辑是临时内存操作；长期修改应走项目管理页和 Nacos，不能把原版控制台描述为具备本项目持久化发布能力。

启动 `tools/local-stack.ps1 -Action Up -Group governance`：基础、消息、事务、任务和治理角色合计 **16 个运行单例**，Search / ES 暂停。Dashboard 停机不影响内存规则；`Stop governance` 停止观察控制台，规则仍在 Trade 生效。通过其他启动组重建 Trade 会恢复相应组的功能开关。所有共用中间件仍各一个实例。

## 实际验证

`python tools/governance_local_acceptance.py` 的报告 `.work/governance-acceptance/report.json` 为 `completed: true`，九组通过，Chrome 脚本错误为零：

1. 真实验证码、临时用户注册和登录。
2. 治理开关生效，普通用户不能修改管理员规则。
3. Chrome 中设置 2 QPS，Nacos 保存相同值，真实查询出现 429 和重试提示；1440 与 390 像素界面验证。
4. API 非法阈值不落配置；Nacos 推送错误内容不清空有效规则。
5. 临时停止 Nacos，已加载规则继续限制查询，恢复后配置重新可读。
6. 强制重建 Trade，从 Nacos 重新加载同一阈值并继续限制查询。
7. 临时停止 Dashboard，业务规则继续生效，随后恢复控制台。
8. 真实 Chrome 登录 Dashboard，发现 Trade 并查看实际资源规则。
9. 检查 Trade 的 Sentinel record 日志不包含 Nacos 密码。

脚本只创建并清理自己的用户、场次和票；最终恢复原始 Nacos 内容并等待生效，恢复所有临时停止的角色。没有改变存量业务资料。桌面、手机和 Dashboard 截图保存在同一忽略目录。

Sentinel 子项完成时，全量 Maven verify 共 **370 项：365 通过、5 项既有外部数据源测试跳过，零失败**。新增四项治理测试覆盖严格规则转换和真实 Sentinel slot 限制。前端 lint/build 通过，保留既有 vendor chunk 大小提示。

## 运行中发现并修复的问题

最初把容器名 `trade` 配成客户端心跳 IP。Dashboard 返回 HTTP 200，但响应体明确拒绝非 IP 地址，导致应用列表为空。已移除该覆盖，让客户端探测容器 IP；验收同时检查已发现的机器地址和实际规则，不能只检查 HTTP 状态。依据为 [1.8.9 MachineRegistryController](https://github.com/alibaba/Sentinel/blob/1.8.9/sentinel-dashboard/src/main/java/com/alibaba/csp/sentinel/dashboard/controller/MachineRegistryController.java)。

官方 NacosDataSource 的 INFO 日志会输出连接属性。Trade 使用 `sentinel.properties` 将 record 日志级别设为 WARNING，保留故障诊断并避免记录认证属性；容器重建后重新检查。依据为 [NacosDataSource](https://github.com/alibaba/Sentinel/blob/1.8.9/sentinel-extension/sentinel-datasource-nacos/src/main/java/com/alibaba/csp/sentinel/datasource/nacos/NacosDataSource.java) 和 [LogConfigLoader](https://github.com/alibaba/Sentinel/blob/1.8.9/sentinel-core/src/main/java/com/alibaba/csp/sentinel/log/LogConfigLoader.java)。

另一次验收失败来自测试选择器：Dashboard 应用链接还包含实例计数，不等于纯应用名。修正为链接角色与名称前缀匹配后，完整故障实验重新执行通过。

Dashboard 1.8.9 官方发布 JAR 已校验 SHA-256；使用 Java 21 本地运行镜像。本地构建缓存复用方式与调度镜像相同。该实验验证单例故障时的规则恢复行为，不代表部署了高可用集群。
