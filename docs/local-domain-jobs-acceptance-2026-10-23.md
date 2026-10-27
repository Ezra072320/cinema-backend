# Catalog / Search 领域任务验收（2026-10-23）

状态：已部署、接入真实业务并通过验收。全部组件本地单例，文件仍写入阿里云 OSS。

## 实现边界

XXL-JOB 执行器现在覆盖 Trade、AI、Catalog、Search。Catalog `movieUpcomingSync` 调用原有同步 worker，共用 Redis 租约和手动同步入口的互斥；参数为空默认 1，显式参数限制 1–50。这个参数限制拉取批次，不承诺只更新一条数据库记录，现有同步流程也会刷新已知影片的市场状态。

Search `movieIndexRebuild` 调用版本化重建协调器，只有新索引完整构建并切换为活动版本后才回报成功。ES 故障回报失败，保留原别名及持久进度，恢复后可重试。执行器关闭或等待中断会保留 worker 的恢复机制，不重置游标。

保留 Catalog 租约续期、Search 持久游标推进及 MQ 消费等内部 worker。它们是业务执行机制，不是重复发起同一领域任务的调度来源。平台初始化只插入缺失任务，不覆盖用户已调整的参数、启停状态。涉及外部同步和全量重建的两个新增周期任务默认暂停，执行失败不自动重试。

## 实际验收

`.work/domain-jobs-complete/report.json` 七组通过，`completed=true`、`cleanup_completed=true`，浏览器脚本错误 0：

1. 实际注册和路由地址可用；重复初始化不改变既有任务设置。
2. 手动触发 Search 完成真实重建并切换版本。
3. ES 停止时任务失败、原别名保留；恢复后重试成功。
4. 临时 15 秒 Cron 真实触发并取得业务成功回执。
5. Chrome 中可查看 Search 的别名及游标执行日志。
6. Catalog 参数 51 在发起外部同步前失败。
7. Catalog 批次 1 真实同步完成，经原有 worker 回报结果。

最终 Catalog 结果：`COMPLETED / total=1 / done=1 / created=0 / updated=0 / skipped=1 / partial=0 / fail=0`。较早的一次真实小批同步更新过 8 条已有影片市场状态；业务结果保留，事前私有快照为 `.work/domain-jobs-before.sql`。临时平台任务已清理，没有重置索引、消费位点或数据卷。

`.work/domain-jobs-search-regression/report.json` 与同名日志记录八组搜索回归：目录事务到 ES、中文及筛选、重复乱序与隐藏墓碑、真实桌面和移动端浏览器、ES 故障降级、Broker 恢复、进程重启续跑与别名切换、旧事件不能复活隐藏数据，全部通过。

联调脚本曾发现 XXL 管理接口必须显式传递 `executorParam`，空值会覆盖保存的参数；脚本已改为读取并传递任务的实际参数，最终上述结果由修正后的脚本取得。

## 运行与后续

```powershell
./tools/local-stack.ps1 -Action Up -Group jobs-search
python tools/domain_jobs_acceptance.py --output .work/domain-jobs-acceptance
./tools/local-stack.ps1 -Action Stop -Group jobs-search
```

`jobs-search` 共 14 个常驻角色，暂停 Benefit / Seata / Notify / Sentinel 控制台以控制本机开销。停止该组后 Trade、AI、Catalog 恢复 LOCAL 调度。验收结束已恢复治理启动组，后续监控实验可切换到 metrics 组。

阶段 5 的 [AI 历史清理](local-ai-retention-acceptance-2026-10-23.md)、[接口文档](local-api-docs-acceptance-2026-10-23.md)、[营收 Excel](local-export-acceptance-2026-10-23.md) 也已通过各自真实验收。TCC 和 MQ 事务/死信独立实验仍列为后续工作，不以 Seata AT 和 Outbox 的通过代替。
