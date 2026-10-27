# AI 确认办理的内部调用时限与恢复

2026-10-23。状态：修复、全量验证及真实冷启动/停机恢复聊天回归通过。

AI 历史清理接入后的真实千问回归在购票确认处失败。AI→Trade创建订单沿用3秒Feign默认时限，返回读取超时；Trade在AI返回异常后仍提交了该测试订单。网页直购此前单独延长过入口时限，未覆盖这一条内部调用链。

当前时限关系：

| 环节 | 时限与作用 |
| --- | --- |
| Trade优惠券Seata AT | 既有15秒全局事务窗口 |
| OrderClient创建、取消、退款 | 单次请求读取18秒，连接1秒 |
| OrderClient查询与结果核对 | 保留原3秒默认读取时限 |
| AI本地确认事务 | 25秒，覆盖预览核验、内部写入与结果持久化 |
| 前端普通HTTP | 既有30秒 |
| Gateway AI路由 | 既有120秒，兼容同一路由下的SSE |

使用Feign单次请求的 `Request.Options`，通过保留原二参数方法的默认包装传给命令请求。Options不会进入HTTP请求体，查询不被统一延长；真实Feign/Spring MVC契约与跨线程测试已验证。保留 `Retryer.NEVER_RETRY`，不自动重发写操作。

确认过程中遇到网络不确定结果或远端服务错误，返回HTTP503和 `Retry-After: 2`，提示稍后再次确认原单；不标记办理成功。后续仍按同一服务单ID先查询目标域操作日志，已提交的结果直接恢复，甚至原预览已过期也不再次创建订单。未知结果的处理继续依赖幂等日志和核对，扩大时限不能替代它们。

`AiActionServiceTest` 新用例验证超时后保持PENDING、清理OperationContext、过期后恢复原订单且只调用一次创建；`OrderClientDeadlineTest` 用实际Feign契约检查三种写调用18秒、查询3秒以及请求体无Options字段。`.work/ai-confirm-deadline-verify.log` 全模块397项，392通过、5项既有外部源测试跳过，失败/错误0。

首轮失败的旧验收脚本提前清理了其临时账号和订单，留下迟到的两条Outbox与一条通知。已确认测试归属、账号/订单不存在、TC与两库undo均为空，保存私有快照后只清理这些测试技术记录。现 `ai_local_acceptance.py` 在确认响应不确定时保留fixture与编号，不再提前删除可能仍在执行的测试资料。

重建Trade、Benefit、AI后的首次购票确认通过。继续回归发现“现在帮我退款，生成退票服务单”被“服务单不是订单”的过宽判断拦截，导致仅有模型文字。已允许明确创建退票/退款服务单，同时保留撤销、否定和规则咨询保护；新增正负用例并核对真实预拟单。最终 `.work/ai-refund-slip-verify.log` 全模块398项，393通过、5项既有跳过，失败/错误0。

`.work/ai-confirm-outage-complete/report.json` 十一组真实千问/Chrome验收全部通过：实际停止Trade时收到503和Retry-After 2，卡片保持“待你确认”、订单数零；启动同一Trade容器后原单完成，重复确认仍只有一笔订单。随后支付、实时支付查询、确认退款、撤销重拟、取消、评价原文、停止后续问、刷新历史和移动布局全部通过。completed与test_data_cleaned均true，浏览器错误0。`.work/export-after-ai-deadline/report.json` 的七组导出回归也通过。
