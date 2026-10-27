# 基础模块

这些模块是随应用打包的 Java 库，不增加本地进程。应用沿用 `edu.czu.cinema` 扫描范围，因此移动文件时保留包名与配置行为，HTTP 契约不变。

| 模块 | 职责 |
| --- | --- |
| cinema-core | 响应、异常、请求上下文、操作编号与纯工具；不依赖 Spring、数据源或领域实体 |
| cinema-starter-web | MVC、校验、JSON、异常响应、请求 traceId、OpenAPI |
| cinema-starter-security | JWT、内部认证、账户状态验证与安全配置 |
| cinema-starter-data | MyBatis-Plus、Hikari、Flyway、操作幂等日志；内部日志查询入口保留 |
| cinema-starter-redis | Redis 客户端与 Caffeine 展示缓存 |
| cinema-starter-remote | Feign 契约、内部请求头、发现与治理依赖；禁止自动重试写请求 |
| cinema-starter-observe | Actuator、Prometheus 端点、日志与迁移期公共 YAML 配置 |
| cinema-starter-messaging | 可靠事件写入、持久化租约、RocketMQ 投递与隔离调度；仅业务使用者引入 |
| cinema-starter-job | XXL-JOB 接入、模式选择、内部 Redisson 任务互斥；默认 local 模式不创建远程客户端 |
| cinema-starter-seata | AT 数据源代理、内部 RPC 的 XID 传播；仅 Trade / Benefit 参与者引入 |
| cinema-file-support | 图片业务 Interface 与唯一的阿里云 OSS 实现；仅 Identity、Catalog 引入 |

当前 starter 由既有应用包扫描装配，并非面向任意外部工程发布的通用自动配置。公共 `cinema-common.yml` 为保持配置兼容而保留名称；其中预留配置项不等于启动了对应客户端。

`cinema-common` 只作为迁移期兼容聚合，各可执行应用已不再依赖它。业务应用按能力显式声明依赖；Gateway 保持 WebFlux，不能引入 MVC、JDBC 和 OSS。

`cinema-api` 仍保留 24 个存量实体形状，因为当前部分 HTTP/Feign 契约和 VO 使用它们。清单冻结在 [架构规则](../tools/architecture-policy.json)，禁止继续向 API 新增持久化实体。后续逐域替换为 DTO 后再迁移实体，避免这次同时修改全部前后端字段。

## 文件业务

调用者只使用 `ImageFiles`：

```java
String avatar = files.uploadImage(bytes);
String poster = files.storeMovieImage(
    new MovieImageSource("maoyan", movieId, MovieImageSource.Kind.POSTER, sourceUrl),
    () -> fetcher.downloadPicture(sourceUrl).bytes());
```

模块集中处理 10 MiB 限制、JPEG/PNG/WEBP 文件头、对象名、来源 hash、已有对象复用、Content-Type、公开图片 ACL、HTTPS 地址与 OSS 客户端关闭。客户端扩展名和 MIME 不决定存储类型。下载器仅在对象缺失时执行，来源站地址仍由 Catalog 的白名单下载器验证。

继续使用 `.env` 的 `ALIYUN_OSS_*` 配置、现有 Bucket 和路径，不迁移数据，不部署本地 S3，不新增 File 应用进程。`/api/file/upload` 仍返回 `R<String>`，上传配额和鉴权仍由 Identity 管理。

验证：根目录运行 `mvn clean verify`、`python tools/check_architecture.py`。真实上传及存量 URL 验收运行 `python tools/file_local_acceptance.py`，需要 JDK 21 和有效 OSS 配置；脚本删除自身临时对象与测试用户。
