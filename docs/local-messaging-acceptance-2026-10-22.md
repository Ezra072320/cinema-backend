# 本地技术栈实施：阶段 2 消息主线

后续变更：阶段 3 故障测试修正了 Broker/Proxy 的直接内存和线程预算，当前配置见 [搜索阶段记录](local-search-acceptance-2026-10-22.md)。下文保留阶段 2 当时的验收数据。

日期：2026-10-22。RocketMQ、Trade Outbox、Notify、站内消息与 WebSocket 已接入业务并通过本地验收。Catalog 事件随下一阶段搜索接入；死信队列与事务消息的专项实验仍待补充，不将配置存在当作这些实验已验收。

## 当前运行

原五个应用各一个实例，新增 Notify 一个实例。RocketMQ NameServer 一个、Broker/Proxy 同进程一个；初始化角色执行后退出，不是副本。仍复用原 MySQL/Redis/Nacos、千问 API 和阿里云 OSS。

版本锁定为 `apache/rocketmq:5.3.4`、Java gRPC SDK `rocketmq-client-java:5.0.8`。使用原 Java 21 工具链编译；当前本地缓存运行时镜像实际为 Temurin 25.0.4.1，已在该运行时执行验收。标准 Dockerfile 的目标仍为 Java 21；没有声称本次已重新拉取该基础镜像并验收。

部署参考 [Apache Docker 部署说明](https://rocketmq.apache.org/docs/quickStart/02quickstartWithDocker/)，5.3.4 发布物见 [Apache 下载目录](https://downloads.apache.org/rocketmq/5.3.4/)，SDK 发布物见 [Apache Java SDK 目录](https://downloads.apache.org/rocketmq/rocketmq-clients/rocketmq-client-java/5.0.8/)。部署方式和方法签名另以本机实际镜像、依赖及运行结果核验。

## 行为与数据

1. 订单创建、支付、退款、取消等现有事务内，记录事件到 Trade 的 `event_outbox`；创建订单额外记录支付期限提醒。Outbox 写入失败会使业务事务回滚。发送不占用用户业务事务。
2. Relay 通过持久化租约领取事件，发送后按租约更新 SENT。网络失败按退避重试，进程中止后可重领；不承诺消息只投递一次。MQ 网络工作使用独立调度线程，避免阻塞原超时扫描。
3. Notify 将消费指纹与站内消息在同一事务提交后确认消费；列表按业务发生时间排列，重连后的旧事件不覆盖新结果的时间顺序。同一事件重复发送不产生第二条消息；同 ID、不同内容拒绝处理。
4. WebSocket 只发送刷新提示，消息正文通过本人鉴权接口查询。连接使用 30 秒有效、原子消费的一次性票据；连接最多维持 5 分钟后重新验证。前端同时保留补读、刷新和重连能力。
5. 延迟消息只作为提醒。Trade 仍读取数据库中的当前订单状态和创建时间；已支付、已退款或未到期的订单不会因旧消息被关闭。关单复用原原子处理器，原每分钟扫描继续兜底。

本阶段尚无 Seata 全局事务，以上事件以本地事务提交为准。后续接入 AT 时必须增加全局结果门控，不能直接沿用当前 Relay 发布未决分支的成功事实。

新增数据为 Trade Flyway V4 与独立 `cinema_notify` schema（V1 表结构、V2 时间线索引）。初始化工具只创建新域库/用户、保留已有密码；没有重建存量库。改动前 Trade 备份保存在本机 `.work/backups/before-messaging-trade-20261022.sql`。

## 启动与页面

```powershell
# cinema-server，先启动现有基础环境
./tools/local-stack.ps1 -Action Up
python tools/init-domain.py notify
./tools/local-stack.ps1 -Action Build -Group messaging
./tools/local-stack.ps1 -Action Up -Group messaging
./tools/local-stack.ps1 -Action Status -Group messaging

# 只停止新增角色，不删除任何卷；Trade 可继续写入待投递事件
./tools/local-stack.ps1 -Action Stop -Group messaging
```

`Up -Group messaging` 会确保新域已初始化，等待中间件后串行启动应用。消息组使用 `compose.yml` 加 `deploy/local/compose.messaging.yml`；它开启 Trade 的 Relay 和延迟消费。普通 base 模式默认关闭这两项网络工作，但业务仍持久化 Outbox，之后可以补发。基础业务不把 MQ 纳入所有应用的就绪依赖。

入口为 <http://localhost:8088/me/notifications>，使用普通用户登录；个人侧边栏与用户菜单均有入口。提供全部/未读、全部已读、订单跳转和实时刷新。新 API 为 `/api/notify/messages`、`unread-count`、`messages/{id}/read`、`messages/read-all`、`socket-ticket` 与 WebSocket `socket`。

Proxy gRPC 只对本机开放 18081；Broker/NameServer 管理端口留在项目网络。新角色设置内存与缓存限制。实测本组十二个长期容器总读数约 5.8 GiB，随请求改变，不作为所有后续组件可同时常驻的承诺。

## 实际验收

| 场景 | 结果 |
| --- | --- |
| 完整 Maven verify | 330 项完整回归，加 1 项消息乱序的定向回归；合计 326 通过、5 项原外部数据源测试跳过，0 失败/错误 |
| 回滚、租约、重领、退避 | Spring 真实事务与 H2 回归通过 |
| 消费去重和读状态 | 同事件不重复、内容冲突拒绝、插入失败回滚去重标记、用户隔离及按业务时间排序通过 |
| 超时权限来源 | 消息伪造的旧期限不能关闭新订单；延迟/重复消息不能关闭已支付订单 |
| 实际订单事件 | 下单后产生一条本人通知；重复发送原 eventId 仍只有一条 |
| Broker 停止/重启 | 停止时支付仍成功，事件留在 Outbox；重启后恢复通知，没有再次支付 |
| 浏览器推送 | 经 Nginx/Gateway 建立已认证连接，退款后页面收到新通知 |
| 票据重放 | 首次收到认证完成帧；重放无法获得认证完成帧或业务消息并关闭 |
| 页面与数据 | 全部已读、未读筛选、390px 布局通过；JavaScript 错误为 0 |
| 超时关单 | 加速独立样本的时钟与延迟消息后，过期单关闭且无占用票；扫描补偿保留 |
| 前端检查 | AI/营收 15 项测试、ESLint、TypeScript/Vite 构建通过 |

网关会先建立浏览器侧 WebSocket，再连接上游，因此浏览器 `open` 事件本身不代表业务认证完成。验收据服务端认证完成帧判定，避免误把被上游拒绝的连接当作票据重放成功；后端对无效票据明确返回 403。

实际执行脚本：`python tools/messaging_local_acceptance.py`。它短暂停止本项目 Broker、创建独立用户/场次/订单，结束后清理业务样本。消费幂等指纹及 MQ 追加式消息历史保留，用于防止晚到重发恢复已清理的通知，不删除既有 Topic、消费组或卷。

本机证据：[消息验收 JSON](../target/messaging-local-acceptance/report.json)、[桌面截图](../target/messaging-local-acceptance/notifications-desktop.png)、[移动截图](../target/messaging-local-acceptance/notifications-mobile.png)、各模块测试 XML 与 `.work/messaging-final-verify.log`。

原五应用的回退镜像仍保留；本阶段 Trade/Gateway/Web 另有 `before-messaging-20261022` 标签。回退应用时保留新增表和卷，不通过删除事件数据回退。
