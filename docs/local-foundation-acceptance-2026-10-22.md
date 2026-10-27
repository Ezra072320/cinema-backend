# 本地技术栈实施：阶段 0/1

日期：2026-10-22。范围：本地基线、基础模块与 OSS 文件业务。应用仍是 Gateway、Identity、Catalog、Trade、AI 各一个实例，千问 API 与阿里云 OSS 保留。

## 已落地内容

- 新建 `cinema-framework` 聚合与 Core、Web、Security、Data、Redis、Remote、Observe、File Support 八个库；移动实现保留 Java 包名，已有 HTTP/Feign 字段及事务行为不变。
- 五个应用不再依赖兼容聚合 `cinema-common`。打包检查确认 Gateway 没有 MVC/JDBC/OSS，Trade/AI 没有 OSS SDK；仅 Identity/Catalog 使用文件库。
- 纯响应、异常、上下文移出 API；24 个被存量契约使用的实体形状保留并冻结，后续逐域转换 DTO。未将此项描述为全部实体迁移完成。
- `ImageFiles` 集中用户上传、影片稳定对象转存与已有地址判断。唯一实现仍是 OSS，统一大小、图片头、命名、元数据、超时和客户端关闭；图床下载继续执行来源白名单。
- `/api/file/upload` 继续返回 `R<String>`；现有 Bucket、日期目录、影片来源 hash 与 `.image` 路径保留。不引入本地 S3，不迁移存量对象，不增加 File 进程。
- `tools/local-stack.ps1` 支持 base/media 的状态、检查、构建、启动和停止。先等 MySQL/Redis/Nacos，再串行启动 JVM；仅管理本项目，不提供删除卷操作。
- 架构检查递归解析 Maven 依赖，阻止 Gateway 引入阻塞基础栈、无关应用引入 OSS、服务实现互相依赖、重复类、新增共享持久化实体。

## 验收

| 项目 | 实际结果 |
| --- | --- |
| 改造前 Maven 基线 | 309 项：304 通过、5 项外部数据源测试跳过 |
| 改造后完整 `clean verify` | 318 项：313 通过、5 项外部数据源测试跳过；0 失败/错误 |
| 新增文件模块测试 | 上传元数据、类型/大小拒绝、稳定 key、复用对象、不重复下载、异常关闭、来源隔离、旧 endpoint 格式 |
| 前端 AI/营收测试 | 11 + 4 项通过；TypeScript/Vite 构建通过 |
| 真实 OSS 链路 | 匿名拒绝、HTML 伪装拒绝、通过 Gateway 上传有效 PNG、服务器确定 MIME/扩展名、写入原 Bucket、旧海报 URL 可读 |
| 临时文件清理 | 使用 OSS SDK 删除自身随机对象，并查询确认不存在；测试用户和配额清理 |
| Chrome 基础回归 | 8 组检查全部通过，API/JavaScript 非预期错误为 0 |
| 真实千问客服回归 | 10 组检查全部通过，含连续拟单、确认幂等、购票/支付/退款/取消/评价、停止后续问、刷新及移动端 |
| 当前运行 | 新镜像已替换；五个应用与 MySQL/Redis/Nacos 就绪，前端入口 8088 可用 |

启动复验时发现基础容器已停止，曾导致应用报告 Nacos DNS/连接失败。恢复原项目基础容器并等待就绪后，各应用恢复；没有更改 Nacos 地址绕过发现，也没有重置数据库或数据卷。后续启动入口采用分阶段冷启动，降低本机资源峰值。

镜像回退标签：`cinema/<service>:before-local-foundation-20261022`。服务名为 identity/catalog/trade/ai/gateway。回退不需要恢复数据库，因为本阶段没有表结构迁移。

## 复验

```powershell
# JDK 21，cinema-server
mvn -gs .mvn/settings.xml -s .mvn/settings.xml clean verify
python tools/check_architecture.py
./tools/local-stack.ps1 -Action Check
./tools/local-stack.ps1 -Action Status
python tools/file_local_acceptance.py
python tools/browser_smoke.py
python tools/ai_local_acceptance.py
```

`file_local_acceptance.py` 需要 JDK 21、有效 OSS 配置和 Maven 测试生成的 SDK classpath。浏览器脚本依赖 `tools/requirements-browser.txt` 与 Chrome；模型脚本实际调用当前千问 API。凭据从私有 `.env` 读取，不写入报告或命令参数。

本机证据：

- [文件验收](../target/file-local-acceptance/report.json)
- [基础浏览器验收](../target/browser-smoke/report.json)
- [客服验收](../target/ai-local-acceptance/report.json)
- 各模块 `target/surefire-reports/`；`.work/local-stack-verify.log`、`.work/local-stack-final-verify.log`。

以上构建产物被 Git 排除。下一阶段为 RocketMQ、事件/Outbox 与 Notify；搜索、优惠券/Seata、治理调度、可观测性及扩展实验仍未完成，以 [实施顺序](local-singleton-stack.md#8-实施顺序与完成标准) 继续。
