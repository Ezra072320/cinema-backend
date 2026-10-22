# 影院业务与接口约定

## 接口约定

普通接口使用R封装，核心字段如下：

```json
{
  "code": 1,
  "msg": "操作成功",
  "data": {}
}
```

code=1表示成功；失败应检查code和msg，不能只依据HTTP 200判断。部分控制器返回明确的400／401／403／404／409，部分沿用全局业务码。分页通常为data.total与data.rows；没有数据的成功响应可能省略data或返回null。

登录成功返回JWT字符串，后续请求使用Authorization请求头，支持原始令牌或Bearer形式。前端接口层统一解开R，页面直接获得data。

| 范围 | 权限 |
| --- | --- |
| 登录、注册、验证码、公开片单／详情／媒体、影厅列表、公开场次座位、公开评价 | SecurityConfig明确放行的端点 |
| /api/admin/**、/api/statistic/** | ADMIN |
| /api/user/**、/api/ai/** | USER |
| 其他业务端点 | 至少登录，具体服务再做归属或角色检查 |

最终权限以 [SecurityConfig](src/main/java/edu/czu/cinema/security/SecurityConfig.java) 和对应控制器为准。“可公开查看页面”不等于所有关联接口都免登录。

### 主要接口索引

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | /api/user/login、/api/user/register | 用户登录与注册 |
| POST | /api/admin/login | 管理员登录 |
| GET | /api/captcha/image?ticket=... | 获取图形验证码 |
| GET | /api/user/info | 本人账户资料 |
| GET | /api/movie/list、/api/movie/info/{id} | 影片分页与详情 |
| GET | /api/movie/{id}/images、/api/movie/{id}/videos、/api/movie/{id}/crew | 剧照、预告片与演职资料 |
| DELETE | /api/movie/want/{id} | 当前门户想看状态切换 |
| GET | /api/schedule/byHall/{hallId} | 影厅有效排片 |
| GET | /api/schedule/{id}/seats | 场次锁定版本座位与ticketId |
| POST | /api/user/createOrder | 使用scheduleId与ticketIds下单 |
| POST | /api/order/pay | 使用orderId与channel模拟付款 |
| POST | /api/user/cancelOrder/{id} | 取消本人待付款订单 |
| POST | /api/user/refund/{id} | 本人订单模拟退款 |
| GET | /api/order/{id}、/api/user/orders/{status} | 本人订单详情与列表 |
| POST | /api/admin/movie/sync、/api/admin/movie/repair | 新片同步与全片库补缺 |
| PUT | /api/admin/movie/{id}/metadata | 人工核实、补录及字段保护 |
| PUT／POST | /api/admin/hall/{hallId}/seats/draft、/draft/apply | 保存草稿、发布座位版本 |
| POST | /api/admin/schedule/add、/batchAdd、/batchMove | 单场／批量排片及同批改期 |
| POST | /api/admin/schedule/cancel/{id} | 取消场次并联动关闭／模拟退款 |
| GET | /api/admin/order/list | 管理端分页，关键词当前按订单号匹配 |
| GET | /api/user/score/context/{orderId}、/api/user/scores | 本人评价上下文及分页 |
| POST／DELETE | /api/user/score、/api/user/score/{orderId}?version=... | 保存评价、按版本删除 |
| GET | /api/movie/{id}/reviews | 公开评价及本店五分制统计 |
| GET | /api/statistic/getAll、/api/statistic/revenue | 管理概览与区间营收 |
| POST | /api/ai/chat | 带question和chatId的SSE聊天 |
| GET | /api/ai/sessions、/api/ai/history/{chatId} | 本人会话与历史 |
| POST | /api/ai/action/{id}/confirm、/dismiss | 确认或撤销服务单 |
| POST | /api/file/upload | 已登录用户上传multipart的file字段，返回OSS URL |

完整参数与单个映射见控制器及运行时OpenAPI，不将此表当作独立的全量协议定义。

## 领域规则

| 模块 | 当前规则 |
| --- | --- |
| 影片 | 新片同步仅新增库中没有的大陆未来待映影片；已有待映影片另行刷新状态等资料，已上映资料不在该新增流程覆盖；全片库补缺与人工修订有独立入口，人工确认字段受保护 |
| 时长 | runtimeMin=0表示待映时长待公布；排片要求时长大于0 |
| 座位版本 | 场次锁定排片时的版本；新发布版本不改变旧票座位对应关系；capacity从有效座位图计算 |
| 票价 | 根据基础票价和座型倍率在建票时确定；默认VIP为3.00倍，情侣每个实体座位为2.50倍，可在新增场次时设置 |
| 排片 | 有效场次按影片时长加30分钟清场进行同厅冲突检查；改期不换厅、不重新定价 |
| 订单 | 一次1～6张实体票，票号去重、情侣对完整性和场次归属由服务端检查；15分钟未付款关闭并释放座位 |
| 退款 | 用户只退状态2订单；完整分钟数>360全额、180～360退70%、<180拒退；管理员取消场次时对2／6全额模拟退，对1关闭 |
| 评价 | 本人有效订单散场后开放；一单一评，1～5分、短评最多1024字符单元；版本校验修改与删除，独立于订单状态和外部评分 |
| 营收 | 按下单日期汇总当前状态，退款回计原日期；BigDecimal聚合；缺失／异常旧退款单独列示，不臆造金额 |

订单状态：1待支付、2已支付、3已退款、4已关闭、6旧已完成；5只表示列表查询“全部”。不要再将4理解成“退款中”，也不要通过新评价将2改成6。

营收查询默认含今天的近30天，最多366天，支持day／week／month，读取一致的只读事务；周从周一开始，首尾只覆盖筛选区间。净营收不等于现金流或利润。

## AI会话与服务单

- 模型通过AiTool获得当前数据，身份从服务端上下文注入，不接受模型指定userId。
- 会话、消息与服务单分别持久化；最近20条消息进入上下文，同实例同会话限制并发生成。
- 写业务先拟单，5分钟有效；用户点击确认时重查条件，事务内执行领域服务，重复确认返回已办理结果。
- 本地业务指南直接加入上下文，当前实现不要求远程向量检索命中。
- SSE以JSON字符串token输出，另有显式错误帧；客户端需保留转义、换行并处理网络中断。
- 用户点名“N排M座”时按选座页“排-座”标签精确定位；座号不存在会要求核对，不猜测已售。情侣座只有两半都可售才算可售，拟单会带上两张票。
- 服务单“已办理”只代表订单已创建/服务单已执行，不代表已支付；支付状态必须现查订单。取消/退票由客服根据实时状态自动选择取消单或退票单，同一会话可以有多张服务单。
- AI 可查询影片公开口碑/平均分/分布和本人评价列表，并可按订单拟单提交评价；公开评价仅返回脱敏昵称。评价独立于订单状态，修改和删除仍在评价页办理。

细节见 [AI客服说明](docs/ai-customer-service.md)，旧评价描述以当前评价服务为准。


部署、目录和数据库初始化以 [微服务说明](microservices.md) 为准。
