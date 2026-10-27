# 下一实施单元：MapStruct 与 Fesod 营收导出

2026-10-23，状态：此实施单元已完成，构建、全量测试、实际部署及七组真实下载验收通过，详情见 [导出验收](local-export-acceptance-2026-10-23.md)。本文保留原选型与边界，不作为新的待办。

采用 MapStruct 1.6.3 与 Apache Fesod 2.1.0-incubating。前者的当前稳定参考手册为1.6.3；后者已在官方发布页和 Maven 示例中列出，不用 SNAPSHOT。两者仅在 Trade 接入，不让 Excel / POI 和注解处理器进入其他应用依赖闭包。[MapStruct 手册](https://mapstruct.org/documentation/stable/reference/html/)、[Fesod 发布](https://github.com/apache/fesod/releases)、[Fesod 示例](https://github.com/apache/fesod/blob/main/README_CN.md)。

业务入口沿用 `/manage/revenue`，新增有 `report:export` 权限的 Excel 下载，保留已有 CSV。服务器导出接口使用 Trade 自有的 `RevenueService.report`，不另写一套营收算法。现有实现限制日期区间不超过366天，电影和影厅各取前十；这是有边界的汇总导出，不宣称扫描全部订单或验证了大容量。

MapStruct 把 API 报表记录映射到 Trade 内部平面导出模型，使用编译期未映射字段报错；结合现有 Lombok 时配置处理器和 binding。Fesod 使用 `FesodSheet` 写汇总说明、每日、影片、影厅等工作表，BigDecimal 保持数值与两位小数格式，中文表头明确单位。导出注明原订单日期口径、退款回计、未知退款统计和生成时间。

接口先验证日期、权限并取得报表快照，再输出工作簿。写出过程中正确释放临时文件与 writer；不将文件上传到任何新存储。用户文件上传仍是阿里云 OSS，导出仅返回下载流。Excel 依赖树检查 POI / XMLBeans / Commons IO 冲突，并记录实际 Java21 组合。

前端下载必须正确处理二进制响应与 JSON 业务失败，不能把错误 JSON 保存成 `.xlsx`；保留统一401、403、503处理。验证实际工作簿的表头、金额、日期、空数据、退款口径及字符串单元格，特别检查以 `=` 开头的影片名不会写成公式。使用真实 Chrome 下载，比较同一参数的接口报表结果，拒绝越权导出并确认原 CSV 与页面功能正常。

完成后阶段5仍有影片同步、搜索重建及AI清理等其他调度场景待迁移；阶段6观测、阶段7扩展、MQ死信/事务消息及TCC实验继续按主路线实施。
