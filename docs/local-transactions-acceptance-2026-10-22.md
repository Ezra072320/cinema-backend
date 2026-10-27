# 本地优惠券与 Seata AT 验收

日期：2026-10-22。当前状态：优惠券 / Seata AT 主线已完成真实恢复、千问和浏览器验收；TCC 独立实验与后续阶段继续实施。

## 业务与部署

新增 `cinema-benefit`，拥有独立的 `cinema_benefit` schema / 用户。券模板记录名称、满减金额、库存与领取后有效天数；同一用户同一模板只能领取一张。领取与扣库存在本地事务内完成。`user_coupon` 与订单预留流水负责归属、过期、冻结和核销判断，Trade 不直接访问这些表。

网页入口为个人中心 `/me/coupons`、选座页的券选择、支付页的原价/优惠/实付展示，后台为 `/manage/benefits`。前端显示的优惠金额用于确认，实际价格、门槛和归属均在后端再次计算。

购票与冻结券使用 Seata AT：全局入口包裹 Trade 的本地幂等记录、建单、占票、优惠明细和 Outbox，以及 Benefit 的冻结分支。Feign 传播 `TX_XID`，接收端只在内部密钥保护的 RPC 路径绑定该上下文，浏览器不能指定 XID。Trade / Benefit 是仅有的 Seata 客户端应用，其他应用及 Gateway 没有引入该 SDK。

优惠规则：未支付取消、超时关闭、管理员取消未支付场次后，可靠订单事实使券恢复可用；支付后核销，退票不返券，退款基数为实际支付金额。到期的释放券保留 AVAILABLE 数据状态，但查询按当前时间显示 EXPIRED，不能再次用于下单。订单流水保留旧关联，晚到的旧关单消息不能释放下一笔订单已冻结的券。

支付、关闭和退款后的券状态通过原有 Outbox / RocketMQ 最终一致地同步，队列故障时可以短暂显示“订单占用中”，恢复后自动追平。它们不假装是第二条同步 AT 事务。Benefit 订阅 `cinema-benefit-orders`，有独立消费指纹；冷启动工具仅初始化缺失进度，保留已有偏移。

## 全局结果与消息屏障

AT 一阶段提交不等于最终成功。全局上下文内写入的 Trade Outbox 在同一分支标记为 HELD，并记录事件与 XID 的对应关系。Relay 的 PENDING / SENDING 租约查询不会领取 HELD 行，不会修改尚可回滚的消息数据。

对账器通过 TC 协议查询状态；只在 Committed / Finished 且本地对应 undo_log 已清理后放行为 PENDING。不能单凭 TC 查不到会话就推断成功。全局回滚会随分支 undo 一起撤销订单、座位、幂等结果、券冻结、优惠明细和 HELD 事件；失败或未决状态不放行消息。TC 不可用时继续等待。

已可见的一阶段券订单返回 `transactionState=PROCESSING`，支付页禁用操作并轮询；支付、取消、退款与关单入口均拒绝修改未决订单。已有 `REQUIRES_NEW` 的过期旧单清理额外挂起调用方 XID，避免后续新订单失败将旧单清理一起回滚。场次取消会检查未决订单，并补发原来待支付批量关闭路径缺少的关闭事实。

## 本地运行

```powershell
./tools/local-stack.ps1 -Action Build -Group transactions
./tools/local-stack.ps1 -Action Up -Group transactions
./tools/local-stack.ps1 -Action Status -Group transactions
python tools/benefit_local_acceptance.py
```

工具先确保已有 MySQL / Redis / Nacos 健康，再初始化 Benefit / TC 自有 schema、TC 表和参与方 undo_log；不重置数据库、密码或数据卷。事务组暂停本项目 Search / ES，保留数据；恢复搜索组后继续处理原目录积压。事务组共十四个长期角色：MySQL、Redis、Nacos、NameServer、Broker/Proxy、Seata、Identity、Catalog、Benefit、Trade、Notify、AI、Gateway、Web，每种一个。

TC 使用 Apache Seata 2.5.0 的无 Web 服务端镜像及 DB 存储。它没有内嵌控制台，不提供虚构的 7091 控制台入口；本机仅映射 `127.0.0.1:18091` 协议端口，应用走私有网络 `seata:8091`。当前 AT 验收使用完整 Compose 事务组，未宣称容器 TC 与 Windows IDE 的混合地址场景已经通过验收。

该镜像不捆绑 MySQL 驱动，构建文件以固定 SHA-256 下载与当前 Boot 依赖一致的 Connector/J 9.4.0。使用镜像实际支持的 `JVM_XMS/JVM_XMX/JVM_MaxDirectMemorySize/JAVA_OPT` 设置预算，不能将 JAVA_TOOL_OPTIONS 输出误当成版本探测结果。

首次启动注意两点：Seata 创建 DataSource 代理时就检查 undo_log，早于 Flyway；初始化器仅预建 undo_log，Benefit 以 baseline version 0 执行 V1，Trade 继续原 V5。Seata 示例 `lock_table.pk` 仅 36 字符，不能保存本项目最长 64 字符幂等键，初始化器将 TC 的 pk / row_key 扩展为 128 / 256 字符，保留业务操作编号和原事务数据。

## AI 客服

增加真实用户券查询 `myCoupons` 与 `prepareBookingWithCoupon` 拟单工具；身份来自服务端上下文，模型不能提供 userId。服务单展示优惠与实付金额，确认时再次查询座位、价格与优惠条件；实际执行入口仍仅由用户界面调用，模型工具不执行写操作。网络响应丢失后使用原服务单 / 幂等键查询已有结果。

## 自动验证

`tools/benefit_local_acceptance.py` 使用临时用户、场次和券模板；内部故障入口只在本地 transactions overlay 显式开启，仍要求内部调用密钥，Gateway 不暴露 `/internal/**`。脚本可在两分支一阶段提交后主动失败或延迟，验证跨库补偿、处理中状态、TC 重启与 MQ 恢复。`--skip-ai` 仅跳过外部千问对话，默认执行真实模型和浏览器确认。

报告与桌面/手机截图位于 `.work/benefit-acceptance/`，不保存登录凭据。技术消费指纹与队列历史保留；业务临时资料在 finally 清理，不删除原主题、消费者或偏移。无凭据的 fixture manifest 用于异常退出后的精确恢复。

| 检查 | 实际结果 |
| --- | --- |
| Maven 全模块 verify | 359 项：354 通过、5 项既有外部数据源测试跳过，失败 / 错误 0 |
| 新增有针对性的验证 | Benefit 8 项、全局消息屏障 3 项、AI 券查询 / 确认 2 项；实际 SDK / SQL 兼容问题另由真实 AT 脚本覆盖 |
| 优惠券 / AT / 真实千问与浏览器 | 11 组通过，`report.completed=true`，浏览器脚本错误 0 |
| 原消息主线回归 | 9 组通过，含真实支付 / 退款、MQ 停机恢复、WebSocket 与超时补偿 |
| 基础浏览器回归 | 8 组通过，含九个后台页面、CSV、CORS；API / 脚本错误 0，事务组中两次预期的 Search 离线请求走受控展示 |
| 前端检查 | ESLint、Vue / TypeScript / Vite 构建、AI 11 项与营收 4 项 Node 测试通过；保留原 vendor chunk 提示 |
| 架构 / Compose / diff | 通过；Seata 客户端只进入 Trade 与 Benefit |

真实实验覆盖了相同 XID 的两个 schema、金额抵扣、同键重试、双分支一阶段提交后的主动失败、15 秒全局超时、TC 带持久化分支记录重启、丢弃 HTTP 响应体后的同键找回、Broker 中断后的释放追平。券服务单由真实千问连续对话生成，页面上点击确认才冻结券与锁票。

验收同时发现并修正了兼容性细节：幂等 SQL 不能对主键做原值赋值，已改为非主键的等价更新；Connector/J 的 DATETIME 可以返回 LocalDateTime，不能强转 Timestamp。手机个人中心的 grid 最小宽度也已修复，并增加子元素边界检查，避免被 body overflow 隐藏的裁切漏检。

早期验收脚本曾在 Trade 回滚后、Benefit 补偿结束前清理临时资料，引起该临时实验的 dirty undo。已保存私有诊断资料，核对受影响临时用户、订单、券和预留均已删除后，仅校准该失败实验的 undo / TC 技术记录；没有操作原业务数据。脚本现记录实验 XID，等待 TC 与两个参与库全部结束后才清理。修正后的完整验收未进行手工干预，最终 TC 与两库 undo 均无残留。

TCC 的独立资源预留实验、RocketMQ 死信与事务消息实验、后续调度和可观测性仍需分别实现，不能由 AT 成功推断为已经完成。

## 官方依据

- [Seata 2.5 服务端 SQL](https://github.com/apache/incubator-seata/blob/v2.5.0/script/server/db/mysql.sql)、[AT undo_log SQL](https://github.com/apache/incubator-seata/blob/v2.5.0/script/client/at/db/mysql.sql)：以对应版本为基础，TC 锁表列宽按本项目实际键长调整。
- [Seata 事务隔离](https://seata.apache.org/docs/user/appendix/isolation)：AT 一阶段与全局锁 / 本地事务的关系。
- [Seata 2.5 FAQ](https://seata.apache.org/zh-cn/docs/v2.5/overview/faq/)：本地事务与全局事务的嵌套、数据源代理及超时处理。
