# 本地营收 Excel 导出

日期：2026-10-23。状态：实现、构建、实际部署与七组真实接口/浏览器验收通过。

## 业务与边界

营收页新增“下载 Excel”，使用同一日期区间、趋势粒度及现有 `RevenueService.report` 快照。服务器最多汇总366天，影片和影厅仍为净营收前十；保留原 CSV 导出。工作簿包含说明、汇总对比、每日明细、趋势汇总、影片排行、影厅排行六张表。说明明确订单日期口径、退款回计原单、旧退款待核实和生成时间。

`GET /api/statistic/revenue/export` 需管理员身份和 `report:export`，与只读统计权限分开。权限仍由 Identity 每次核验，不信任客户端保存的授权。验证与报表聚合先完成，再由 Fesod 完整渲染后返回字节，避免写出错误时留下半个下载文件；响应禁止缓存。数据规模有明确上限，不宣称验证过海量订单导出。

MapStruct 1.6.3 编译生成 API 报表到 Trade 内部平面模型的映射，未映射目标字段报错，Lombok binding 只在 Trade 配置。Apache Fesod 2.1.0-incubating 使用原生数值单元格和两位小数格式，用户可控名称以文本写入；未新增本地或云存储，文件上传继续使用阿里云 OSS。

前端复用统一 HTTP 身份和错误处理。二进制下载只有收到 Excel 类型才交给保存操作；JSON 失败响应会解析并显示，不能保存成 xlsx；401、403、503继续走统一流程。

## 已取得的证据

- `.work/export-full-verify-retry.log`：全模块387项，382通过，5项既有外部源测试跳过，失败/错误0。
- `RevenueWorkbookTest` 两项实际写入并用 POI 重新读取：金额及退款、日期、格式、旧退款计数、空表头、以等号开头的影片名称仍是文本。
- 统计控制器测试覆盖匿名/用户拒绝、成功下载头和验证失败JSON；权限测试证明 `report:read` 不能替代 `report:export`。
- 前端生产构建通过，原营收CSV四项测试通过；架构检查通过。
- `.work/export-dependency-boundary.json`：八个可执行业务/网关JAR中仅Trade含新映射和表格依赖。实际POI 5.5.1、XMLBeans 5.3.0、Commons IO 2.20.0、Commons Compress 1.28.0，与Java21组合已通过实际写读。

`.work/export-acceptance-verified/report.json` 七组通过，completed和cleanup_completed均true，浏览器脚本错误零：真实六表金额/退款/每日行数与接口一致；匿名401和日期400；默认VIEWER导出403；Chrome实际下载；JSON失败不产生下载；VIEWER页面拒绝；临时账户授予FINANCE后，原JWT下次请求即可导出。1440与390像素截图已人工查看，下载入口和内容无遮挡。

验收脚本首轮临时账号超过既有长度限制，缩短到契约范围；另一轮URL断言把403页的from查询参数误当作仍停留原路由，改为匹配403页面路径。两者均为脚本问题，未修改生产校验或权限。所有临时管理员和其授权审计已按自身编号清理。

新镜像上的 `.work/export-browser-regression/report.json` 八组基础浏览器回归全部通过，包括十个管理页和原CSV导出；API与脚本错误均零。

## 依据

[MapStruct 1.6.3参考手册](https://mapstruct.org/documentation/stable/reference/html/)与[Fesod官方示例](https://github.com/apache/fesod/blob/main/README_CN.md)；具体运行结果以上述本项目证据为准。
