# 取消场次与自动退款

管理员在「排片管理」点「取消场次」。语义一句话：**status 1→2，停止售票，挂单订单自动全额退款，全场票作废。** 允许取消任意场次（含已开场/已放映）——影厅故障等场景必须能取消，已看片的退款风险靠管理员权限 + 操作日志兜底。

## 状态模型

| 值 | 含义 | 说明 |
|---|---|---|
| 1 | 正常 | 唯一可售票、可编辑的状态 |
| 2 | 已取消 | `cancel()` 是全代码库唯一写入点 |

"已放映"不落库，由 `endTime < now` 推导（与 `movie.marketStatus` 的推导风格一致）。管理端 `GET /admin/schedule/list` 的 `status` 参数契约：**不传 / 0 = 全部，1 = 正常，2 = 已取消**。

## 取消语义表

| 对象 | cancel 动作 | 与用户退票（`releaseTickets`）的差异 |
|---|---|---|
| `schedule` | `status=2`；price/倍率/seatVersionId/hallId 不动 | — |
| `ticket`（全部，含未售/此前退票释放的） | `UPDATE ticket SET status=2, order_id=-1 WHERE schedule_id=?` | 用户退票是 `status=1`（**回可售**）；取消场景整场**作废**，不留可售假象 |
| `order`（status ∈ 1,2,4,6） | → `status=3` 已退款（全额，账务模拟） | — |
| `order`（status=3） | 不动（幂等） | — |

重复取消抛 `2004 场次已是取消状态`（显式暴露双击，不静默成功）。取消后：

| 消费方 | 行为 |
|---|---|
| 门户 `listForMovie` / `publicByHall` | 补了 `status=1` 过滤 → 取消场次从排期消失 |
| 本店热映（`movieShowSummaries` / `ACTIVE_SHOW`） | 本来就只认 `status=1 AND end_time>NOW()` → 自动退出 |
| 冲突检测 `listConflict` | 只查 `status=1` → 取消自动释放时段，可重排 |
| 座位图发布 `countFutureByHall` | `status != 2` → **取消即解锁**（此前"去排片管理下架"是死路，现在闭环） |
| 选座页 | `GET /schedule/{id}/seats` 照常返回但带 `status`，前端 `status=2` 整页停售；作废票（`ticketStatus=2`）不可选 |
| 下单 | 双保险：`createOrder` 对 `status != 1` 的场次拒绝 |

## 订单状态处理

落库枚举（`OrderStatus`）：`1 待支付 / 2 已支付 / 3 已退款 / 4 已关闭 / 5 全部（仅查询哨兵，不落库）/ 6 旧已完成`。**4 表示已关闭（超时未付/用户取消/取消场次关单），不是"退款中"**；评价不再改变订单状态。

| 原状态 | 处理 | 说明 |
|---|---|---|
| 1 待支付 | → 4 | 无钱可退，置「已关闭」 |
| 2 已支付 | → 3 | 全额退款（`totalAmount` 即全额，与手动 refund 一致的账务模拟） |
| 6 旧已完成 | → 3 | 用户口径"任意场次取消全额退款"覆盖；score 保留（影评不撤） |
| 3 已退款 / 4 已关闭 | 不动 | 终态，幂等跳过 |

配套：
- 退款用**集合式条件 UPDATE（current read）**按 `schedule_id + status IN (2,6)` 一次性落账，避免 REPEATABLE READ 快照漏掉事务期间新提交的支付。
- 用户手动退款只认 `status=2`；重复操作由条件 UPDATE 天然幂等。
- 每笔支付/退款/关单/取消退款写入 `order_action` 审计流水；取消场次另写 `admin_operation_log`（操作者/场次/退款笔数）。

## 删除与编辑规则

- **delete**：限**未开场**且无票挂订单。已取消且过期的场次永久留在管理列表作历史（状态筛选可过滤）。
- **update**：已取消/已开场的场次不能编辑；新开场时间必须晚于当前时间；**不能改价**（票价在建票时按基础价×倍率落库，改价对已建票完全无效——要调价请取消后重排）、不能改影厅。
- **add**：开场时间必须晚于当前时间。

## 并发保护

- `add` / `batchAdd` / `update` / `batchMove` / `cancel` / 座位图 `applyDraft` 事务**第一句**对 `hall` 行 `SELECT ... FOR UPDATE`（`ScheduleMapper.lockHall`），串行化同厅写操作。
- 冲突检查 `listConflict` 使用锁定读（`FOR UPDATE`）：先读后锁在 REPEATABLE READ 下会读到加锁前的旧快照，漏判窗口内并发插入/改期的场次，造成同厅时段双卖。
- 超时关单「置 4 + 放票」在 `REQUIRES_NEW` 事务中原子完成；定时任务另有孤儿票对账扫描。

## 取舍

1. **取消任意场次（含已放映）→ 全额退款**：存在"看完再取消薅退款"的滥用面，不在系统层收紧，靠管理员权限 + `admin_operation_log`/`order_action` 审计流水兜底。
2. **退款是账务模拟**：只改状态不动钱（与现有手动 refund 一致），真实支付网关对账不在范围。
3. **未支付订单置 4「已关闭」**：与"自动退款"分开，状态语义清晰。
4. **作废票保留 order_id**：`voidBySchedule` 只置 `ticket.status=2`，已退款订单仍能回看座位；"已售数"与删除守卫按「被订单占用（status=0）」口径统计，作废票不计。
5. **已取消且过期的场次无法删除**（delete 限未开场）：作为放映历史保留，接受。

## 验证清单

1. `mvn test`：`ScheduleServiceImplTest`（add/update/cancel/delete 校验与退款语义）、`OrderServiceImplTest`（createOrder 场次校验、refund 防重复）、`ScheduleSqlTest`（H2：冲突忽略已取消、取消解锁座位图发布、热映排除已取消/过期）。
2. 冒烟顺序：新增排片（过去时间被拒）→ 下单并支付 → 取消场次（返回退款笔数，订单显示"已退款"，门户/热映消失，选座页显示"场次已取消"）→ 同厅同时段重排成功 → 座位图发布解锁 → 已取消未开场场次可删、已开场不可删。
