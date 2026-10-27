# 单台云主机多副本实验：官方规格与故障边界核验

> 本记录服务于此前的五万并发与云端设想。当前已全部取消，改为 [本地单实例技术栈学习](local-singleton-stack.md)。下列云服务器规格不再是采购建议，官方机制资料仅保留参考。

核验日期：2026-10-22。范围为影院项目 gateway、identity、catalog、trade、ai 五个业务服务的单台 ECS 实验环境。本文只记录官方资料和据此作出的选型建议，未购买、部署或压测云资源，也未验证本项目对新组件版本的兼容性。

## 1. 选型结论与适用边界

- **实验起点：16 vCPU / 64 GiB；资源更宽裕的选项：32 vCPU / 128 GiB。** 这是本项目多 JVM、多数据库进程同机运行的工程建议，不是官方业务容量认证。g8i 型号仍在阿里云当前规格表中，g9i 也提供相同 CPU/内存档位。[阿里云通用型实例规格](https://www.alibabacloud.com/help/zh/ecs/user-guide/general-purpose-instance-families)
- **单台 ECS 上的多副本只能演练进程故障、实例重启和主从切换。** 宿主 ECS、操作系统、容器运行时、挂载盘或公网入口失效会影响同机副本；它们不构成独立主机故障域。此处是结合部署拓扑和下文多数派要求作出的推论。
- **“按 5 万并发目标设计”属于设计目标。** 官方 VM 网络连接数、云盘 IOPS、容器副本数均不能证明 5 万真实业务并发或吞吐；小规模压测结果也不能直接外推。验收应记录并发模型、请求组合、响应体大小、持续时间、成功率、延迟与资源用量。

## 2. 阿里云 ECS、ESSD 与公网

### 2.1 可核验的实例规格

以下均为 x86 通用型。网络、云盘带宽单位为 **Gbit/s**，不是公网 EIP 的购买带宽，也不是磁盘 MB/s。

| 规格 | vCPU | 内存 GiB | 实例网络基础/突发 Gbit/s | 实例云盘 IOPS 基础/突发 | 实例云盘带宽基础/突发 Gbit/s |
| --- | ---: | ---: | --- | --- | --- |
| ecs.g8i.4xlarge | 16 | 64 | 12 / 最高 25 | 100,000 / 最高 200,000 | 6 / 最高 10 |
| ecs.g8i.8xlarge | 32 | 128 | 20 / 最高 25 | 200,000 / 无 | 10 / 无 |
| ecs.g9i.4xlarge | 16 | 64 | 12 / 最高 25 | 100,000 / 最高 200,000 | 6 / 最高 10 |
| ecs.g9i.8xlarge | 32 | 128 | 20 / 最高 32 | 200,000 / 最高 300,000 | 10 / 最高 12 |

来源：[阿里云通用型实例规格，g8i/g9i 对应表](https://www.alibabacloud.com/help/zh/ecs/user-guide/general-purpose-instance-families)。地域、可用区库存和可购买方式需以购买页为准；本文没有核验账户优惠、库存或报价，因此不提供具体价格。

### 2.2 ESSD PL1 / PL2

设容量为 `C GiB`。以下为单盘规格；阿里云同时说明最终性能受所挂载 ECS 规格限制。[阿里云块存储性能](https://www.alibabacloud.com/help/zh/ecs/user-guide/block-storage-performance)

| 等级 | 可选容量 GiB | 单盘 IOPS 公式 | 单盘吞吐量公式 MB/s |
| --- | --- | --- | --- |
| PL1 | 20～65,536 | min(1,800 + 50 × C, 50,000) | min(120 + 0.5 × C, 350) |
| PL2 | 461～65,536 | min(1,800 + 50 × C, 100,000) | min(120 + 0.5 × C, 750) |

来源：[ESSD 官方规格](https://help.aliyun.com/en/ecs/user-guide/essds)。**等级上限不是任意容量的实际规格。** 按官方公式计算：200 GiB PL1 为 11,800 IOPS、220 MB/s；500 GiB PL1 为 26,800 IOPS、350 MB/s；500 GiB PL2 仍为 26,800 IOPS，吞吐量为 370 MB/s。这些是公式推导值，不是本项目实测结果。

工程建议：先按数据量、三份 MySQL 数据、binlog、Redis AOF、镜像和日志留量选择盘容量，优先验证 PL1 的实际瓶颈，再决定 PL2 或其他盘型。三个数据库目录放在一块数据盘上仍共享同一个性能和故障边界；增加目录或 PVC 不会增加单盘 I/O 上限。

### 2.3 短期实验的计费与 EIP

- ECS 按量付费适用于测试、临时扩展等场景。实例、系统盘/数据盘、公网及可能的镜像费应分别核算。普通停机可能继续计费；节省停机也不会让云盘、EIP 等资源全部停止收费。[ECS 按量付费](https://www.alibabacloud.com/help/zh/ecs/pay-as-you-go-1)
- EIP 可独立持有并绑定/解绑云资源，适合需要保留实验访问地址的场景。[什么是 EIP](https://help.aliyun.com/zh/eip/product-overview/what-is-eip)
- **短期、流量不稳定：** 可选择按量付费 EIP 的按使用流量模式，按公网出流量计费；其设置的带宽峰值是上限，资源争抢时可能受限。**需要较稳定的公网带宽实验：** 可考虑按量付费的按固定带宽模式，官方将带宽峰值列为业务承诺指标。不能把按流量 EIP 的峰值当成保证带宽。[EIP 按量付费](https://www.alibabacloud.com/help/zh/eip/pay-as-you-go/)
- 公网带宽需求应由响应数据量估算，再实测。例如平均响应 10 KiB、1,000 次/秒，响应体单方向就约需 81.92 Mbit/s，尚未计入请求、协议及 TLS 开销；此为算术示例，不代表本项目流量。应用和中间件压测若经私网完成，应另做公网入口验证。

## 3. 单节点 K3s 与故障边界

官方明确支持单 server 配置，通常使用嵌入式 SQLite。K3s 的 server 同时管理控制平面、数据存储，并运行 kubelet、容器运行时和 CNI。[K3s Architecture](https://docs.k3s.io/architecture)

采用嵌入式 etcd 的 HA K3s 需要至少三个 server 节点，并依赖多数派；采用外部数据库的控制平面 HA 方案需要至少两个 server 节点及外部数据存储。[K3s HA embedded etcd](https://docs.k3s.io/datastore/ha-embedded)、[K3s HA 架构](https://docs.k3s.io/architecture)

据此，本次单节点 K3s 可用于多副本部署、滚动发布和应用进程恢复实验；同机运行三个控制平面进程也不能消除 ECS 故障这个共同边界。Traefik 两副本和业务多副本不会使单节点控制平面或单台主机具备 HA。

## 4. MySQL：生命周期、三成员单主与 Router

### 4.1 版本生命周期

MySQL 官方公告明确：**2026-04-21 起 MySQL 8.0 进入 Oracle Sustaining Support**，并建议升级至 8.4 LTS 或当前 LTS。[MySQL EOL 公告](https://www.mysql.com/support/eol-notice.html)

Oracle 生命周期表区分了支持阶段：MySQL Database 8.0 的 Premier Support 结束于 2025 年 4 月，Extended Support 结束于 2026 年 4 月；不能笼统写成“2026 年 4 月结束 Premier Support”。表中 8.4 的 Premier / Extended 分别列为 2029 年 4 月 / 2032 年 4 月。[Oracle Lifetime Support 表](https://www.oracle.com/us/support/library/lsp-tech-chart-069290.pdf)

因此建议新云端实验优先评估 **MySQL 8.4 LTS**，先完成 schema、SQL、Connector/J、Flyway、Nacos 数据源和鉴权方式的兼容性测试。8.4 默认不启用 `mysql_native_password`，是需要检查的升级差异之一。[MySQL 8.4 升级差异](https://dev.mysql.com/doc/refman/8.4/en/mysql-nutshell.html)

### 4.2 Group Replication 的必要条件与多数派

MySQL 8.0 Group Replication 的关键约束包括：复制表使用 InnoDB，并具备主键或非空唯一键；成员使用唯一 `server_id`，启用 binary log、ROW 格式、replica updates、GTID 和 GTID consistency；成员之间需双向连通。[MySQL 8.0 GR requirements](https://dev.mysql.com/doc/refman/8.0/en/group-replication-requirements.html)

8.4 仍有对应要求，应按 8.4 文档生成配置；不要原样照搬 8.0 中已弃用或移除的参数。[MySQL 8.4 GR requirements](https://dev.mysql.com/doc/refman/8.4/en/group-replication-requirements.html)

`single-primary` 模式只允许一个 PRIMARY 写入，其余成员设为只读；PRIMARY 离组时可自动选举新 PRIMARY。切换期间存在待应用事务，应验证一致性设置与应用重试行为。[MySQL single-primary](https://dev.mysql.com/doc/refman/8.0/en/group-replication-single-primary-mode.html)

多数派要求为 `n = 2f + 1`：三成员需要两成员形成多数派，可承受一个成员故障；再失去一个成员就无法正常决策。三个同机容器无法承受整台 ECS 故障。[MySQL GR fault tolerance](https://dev.mysql.com/doc/refman/8.0/en/group-replication-fault-tolerance.html)

### 4.3 Router 的接入约束

建议采用 **MySQL 8.4 InnoDB Cluster（MySQL Shell/AdminAPI + Group Replication + Router）** 的完整生命周期方案。官方 8.4 手册涵盖创建、加入、重入、失去多数派恢复、整组重启和升级；单主故障后 secondary 自动晋升，Router 追踪新 PRIMARY。[MySQL Shell 8.4 InnoDB Cluster](https://docs.oracle.com/cd/E17952_01/mysql-shell-8.4-en/mysql-innodb-cluster.html)

官方明确三成员是容忍一个成员故障所需的最小规模；该结论以成员和通信仍可用为前提，不能扩展为本次同机三成员能容忍宿主机故障。[MySQL Shell 8.4 添加成员](https://docs.oracle.com/cd/E17952_01/mysql-shell-8.4-en/add-instances-cluster.html)

MySQL Router 官方推荐配合 InnoDB Cluster；bootstrap 从现有集群读取元数据并生成路由配置。方案应明确用 MySQL Shell/AdminAPI 建立或接管 InnoDB Cluster，并测试 Router 能追踪 PRIMARY 变更，不能假设静态配置三个数据库地址就具备单主角色感知。[Router 与 InnoDB Cluster](https://dev.mysql.com/doc/mysql-router/8.4/en/mysql-router-innodb-cluster.html)

Router 8.4 官方列明支持 MySQL Server 8.0 和 8.4；这只是组件支持范围，不是本项目 JDBC、事务和迁移脚本的兼容性证明。[Router 8.4 general information](https://dev.mysql.com/doc/mysql-router/8.4/en/mysql-router-general.html)

## 5. Redis 7.2：一主两从与三 Sentinel

官方建议健壮部署至少三个 Sentinel，并放置在能够独立失效的计算机或虚拟机。Sentinel 提供监控、自动故障切换和主节点地址发现；客户端必须支持 Sentinel。[Redis Sentinel](https://redis.io/docs/latest/operate/oss_and_stack/management/sentinel/)

三 Sentinel 可用 `quorum=2` 判定主节点客观下线；执行 failover 还要求赢得已知 Sentinel 的多数票。quorum 和切换授权多数派是两个条件。Redis 7.2 官方示例配置也明确少数派不能执行 failover。[Redis 7.2 sentinel.conf](https://raw.githubusercontent.com/redis/redis/7.2/sentinel.conf)

Redis 复制是异步的，故障切换可能丢失已向客户端确认的写入；`WAIT` 也不会把系统变成强一致系统，持久化配置仍影响故障结果。[Redis replication](https://redis.io/docs/latest/manual/replication/)

可评估 `min-replicas-to-write 1` 与合适的 `min-replicas-max-lag`，以在副本不足时拒绝写入，缩小暴露窗口；它们不保证副本收到每一笔写入，并会牺牲部分可用性。[Redis 7.2 redis.conf](https://raw.githubusercontent.com/redis/redis/7.2/redis.conf)

工程边界：三 Sentinel 和三个 Redis 都在一台 ECS 时，仅可验证单进程故障、角色切换及客户端重连；不能宣称主机 HA，也不能以 Sentinel 的存在推导库存扣减、支付状态或分布式锁在故障时零丢失。

## 6. Nacos 3：节点与数据库支持

Nacos 3.0 / 3.1 官方集群部署手册要求配置三个或三个以上节点，推荐 MySQL 数据源，同时提供 Derby 部署方式；MySQL 版本门槛写为 **5.6.5+**，并要求执行对应发行版的数据库初始化脚本。[Nacos 3.0 集群部署](https://nacos.io/docs/v3.0/manual/admin/deployment/deployment-cluster/)、[Nacos 3.1 集群部署](https://nacos.io/docs/v3.1/manual/admin/deployment/deployment-cluster/)

本次核验没有在这些官方部署手册中找到 **MySQL 8.4 专项兼容矩阵或认证声明**。不能把 `5.6.5+` 直接写成“已验证 Nacos 3 与本项目 MySQL 8.4 兼容”。需固定 Nacos、内置 JDBC 驱动和 MySQL 补丁版本，验证 schema 初始化、登录鉴权、配置发布/监听、注册发现、数据库 PRIMARY 切换及连接恢复。

Nacos 三副本仍依赖配置存储和同一宿主机的资源；若 Nacos 和业务数据库共用本次 MySQL 集群，应独立建库和授权并计入共同故障边界。此为本项目部署建议。

### 6.1 生产建议、发行版默认值与实验预算

官方旧部署指南建议 2 核 CPU / 4G 内存及以上、生产至少三个节点；该页面当前位于 3.1 文档归档部分，并标明即将废弃，不能将它解释为 3.1 每个容器必须 4 GiB 的硬性启动下限。[Nacos 旧部署指南](https://www.nacos.io/docs/v3.1/guide/admin/deployment/)

更具体的版本证据是 Nacos 3.1.2 发行源码：`distribution/bin/startup.sh` 的 **cluster 默认堆为 `-Xms2g -Xmx2g`**，standalone 默认 512m；脚本允许用 `CUSTOM_NACOS_MEMORY` 覆盖。发行版默认值也不等于生产容量保证，Docker 镜像的入口脚本和变量还须按所选 tag 核对。[Nacos 3.1.2 启动脚本](https://raw.githubusercontent.com/alibaba/nacos/3.1.2/distribution/bin/startup.sh)

项目当前使用的 Nacos 3.0.3 发行源码也采用相同的 cluster 2 GiB 默认堆。[Nacos 3.0.3 启动脚本](https://raw.githubusercontent.com/alibaba/nacos/3.0.3/distribution/bin/startup.sh)。主方案因此按**每个成员约 3 GiB（包含 2 GiB 堆及运行余量），三个合计约 9 GiB**规划。若为了压缩实验成本改成每容器 1 GiB、堆 512 MiB，将低于 cluster 默认值，需显式设置镜像实际生效的 JVM 参数，并验证启动、配置发布、注册发现及故障恢复期间的 RSS、GC 和 OOMKill；不能称为官方生产推荐或已验证资源配置。该段为工程预算建议。

## 7. 本次资料核验不覆盖的验收项

尚未验证：五个应用服务在上述副本数的总内存和 CPU、MySQL 8.4 与本项目兼容性、Nacos 与 8.4 的组合、真实业务容量、切换恢复时间、公网带宽及云盘实际性能。后续只可将实际完成的兼容性测试、故障演练和压测结果写为已验证成果。
