# 本地 XXL-JOB 与 Redisson 订单补偿链

日期：2026-10-22。状态：订单补偿链已通过真实调度、互斥、失败、切换与浏览器验收；本阶段只覆盖订单补偿，未宣称所有定时任务都已迁移。

## 任务与职责

采用 XXL-JOB admin/core 3.2.0。管理端在本机运行一个实例，使用同一 MySQL 实例中的独立 `cinema_job` schema / 用户；用户名为 admin，初始密码和回调 token 由 `tools/init-jobs.py` 生成到本机 `.env`。初始化只增加缺失任务，不覆盖已有口令、任务配置或日志。

实际任务 `orderExpiryReconcile` 执行 Trade 原有的过期关单和孤儿票对账；每笔订单仍使用原有条件更新与独立事务，成功项不会因另一笔失败一起回滚。业务处理返回关闭数、对账数和失败数；XXL 记录这些数值，有失败时产生失败回执，供平台重试。

`cinema.jobs.mode=local` 为默认模式，使用 `LocalOrderScheduler`，不创建 XXL 执行器或 Redisson 网络客户端；`xxl` 模式只注册远程执行器，关闭本地扫描入口。拼错模式会绑定失败并阻止启动，不会静默停掉补偿。消息延迟关单和用户访问时的惰性关闭继续保留原有业务职责。

Redisson 3.52.0 是 Job 模块内部的独立客户端，未替换 Lettuce / RedisConnectionFactory，只进入当前接入任务的 Trade。`JobMutex` 对同一处理器的手动和定时触发加互斥锁，使用 watchdog 续期；未获取到锁时记录 SKIPPED_ALREADY_RUNNING。Redis 不可用时不执行无锁回退。失败、超时和取消均释放本线程的锁，并保留线程中断信号；不 forceUnlock 其他执行者。

## 运行与恢复

```powershell
python tools/init-jobs.py
./tools/local-stack.ps1 -Action Build -Group jobs
./tools/local-stack.ps1 -Action Up -Group jobs
./tools/local-stack.ps1 -Action Status -Group jobs
python tools/jobs_local_acceptance.py
```

管理端入口为 `http://127.0.0.1:11880/xxl-job-admin`。执行器只在 Compose 私有网络 `http://trade:9999/` 接收带 token 的回调，不映射到宿主机。执行器日志存于独立 `job-executor-logs` 卷，初始化只将该卷根目录交给应用 UID 10001；任务记录、状态和回执保存在管理端自己的数据库。

jobs 组沿用事务组并增加一个 XXL admin，十五个长期角色各一个，Search / ES 按预算暂停。停止 jobs 组时，工具停止 admin 并将 Trade 切回 local 模式，保留原到期补偿；切换到其他启动组也会暂停该项目的调度管理端，防止它继续向 local 执行器发任务。日志卷、管理库与业务库都保留。

源码构建默认使用 Temurin 21 的当前运行镜像重新打包官方 admin JAR。本机曾遇到 Docker Hub 运行层授权接口不可达，实际本地构建通过 `RUNTIME_IMAGE=cinema/identity:before-local-foundation-20261022` 复用已缓存的 Java 21 运行层，并替换为官方 admin JAR；这不是启动第二个 Identity 应用。原 XXL 官方容器内置的是较早的 Java 21 GA，未直接作为本机最终运行层使用。管理端改为控制台日志，避免依赖其默认 `/data/applogs` 写权限。

执行器注册心跳与 admin 更新路由快照分别执行。验收与恢复操作需同时等待新注册和非空地址列表，不能仅以端口开放或 registry 行存在证明可以触发任务。

## 验证范围

验收使用临时用户、场次及手动 probe 任务；本地 probe 只修改带随机编号的 Redis 技术计数，不修改订单金额、库存或用户资料。实际业务验收使用已创建且只回拨自身创建时间的待支付订单。

首先暂停平台定时任务，等待超过原本地扫描间隔，确认 xxl 模式没有重复扫描；再由平台手动触发并核对数据库中关单与放票。两个不同平台任务同时竞争同一个 probe 锁，只有一次实际执行；主动失败、XXL 超时、Redis 停机、切回 local、执行器重新创建与日志卷读取分别验证。

原定时状态、Redis、XXL 模式和停止的项目角色在 finally 恢复；临时任务、技术计数和业务资料按自己的编号清理。核心对账任务和执行历史保留。报告与截图放在 `.work/jobs-acceptance/`，不保存登录 cookie 或密码。

| 检查 | 实际结果 |
| --- | --- |
| 全模块 Maven verify | 366 项，361 通过，5 项既有外部数据源测试跳过，失败 / 错误 0 |
| 新增定向测试 | JobMutex 5 项、模式装配 2 项，含 Redis 故障、失败释放与中断清理 |
| `jobs_local_acceptance.py` | 10 组通过，completed=true，浏览器脚本错误 0 |
| Job 运行层加入后的 AT 回归 | 5 组通过：相同 XID、幂等、券包、支付 / 退款和取消释放 |
| 架构 / Compose / PowerShell / Python 检查 | 通过；Job / Redisson SDK 当前只进入 Trade |

真实实验还核验了 XXL 的 502 超时回执、同一资源跨任务互斥、失败后的手动再触发、Redis 恢复、LOCAL 与 XXL 模式切换、注册与路由快照的异步恢复，以及 admin 页面实际查询执行日志。临时业务资料和 probe 任务已清理，核心对账任务恢复为启用；当前 jobs 组十五个单例继续运行。

XXL 对影片同步、AI 历史清理和搜索索引清理的迁移仍待逐项实施。阶段 5 的 Sentinel 持久规则、RBAC、Knife4j、MapStruct 和 Fesod 也未由本条链路的成功自动视为完成。

## 官方依据

- [XXL-JOB 3.2.0 POM](https://github.com/xuxueli/xxl-job/blob/3.2.0/pom.xml)、[管理端 SQL](https://github.com/xuxueli/xxl-job/blob/3.2.0/doc/db/tables_xxl_job.sql)：版本及表结构来源；不导入样例用户口令或外部 AI 任务。
- [XXL 执行器生命周期](https://github.com/xuxueli/xxl-job/blob/3.2.0/xxl-job-core/src/main/java/com/xxl/job/core/executor/impl/XxlJobSpringExecutor.java)：处理器注册与回调服务。
- [Redisson 3.52 RLock](https://github.com/redisson/redisson/blob/redisson-3.52.0/redisson/src/main/java/org/redisson/api/RLock.java)：锁获取、所有权与释放语义。
