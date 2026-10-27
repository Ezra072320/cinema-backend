# Prometheus / Grafana 本地指标（2026-10-23）

当前状态：已部署并通过六组真实指标验收及八组业务浏览器回归；默认关闭的实际部署检查也已通过，随后恢复 metrics 组健康状态。

## 实现与运行

Prometheus 3.15.1、Grafana 13.2.4 各部署一个实例，镜像同时固定版本标签及本次实际拉取的摘要。七个采集目标为 Gateway / Identity / Catalog / Trade / AI / Notify / Benefit。指标携带 application 标签，采集目标附加 service 标签。

```powershell
./tools/local-stack.ps1 -Action Up -Group metrics
python tools/metrics_local_acceptance.py --output .work/metrics-acceptance
./tools/local-stack.ps1 -Action Stop -Group metrics
```

metrics 组共 16 个常驻角色。为适应本地机器预算，该组暂停 Search / Elasticsearch / XXL-JOB / Sentinel 控制台；Trade、AI、Catalog 使用 LOCAL 调度补偿，保留 Seata / Benefit / MQ / Notify。它不是集群或高可用部署。

- Prometheus：http://127.0.0.1:19090，仅绑定本机；15 秒采样，5 秒采样超时，保留 24 小时且设置 128 MB 数据保留阈值，容器上限 384 MB。保留阈值不是精确目录配额，WAL 等仍占额外空间。
- Grafana：http://127.0.0.1:13000，仅绑定本机；账号 cinema，密码在私有 .env 的 GRAFANA_ADMIN_PASSWORD，不写入文档。关闭匿名、注册、统计上报及自动更新检查；容器上限 384 MB。
- 持久化分别使用两个独立命名卷，切换启动组不删除卷；默认预置只读 Prometheus 数据源和八面板。
- init-metrics.py 只补齐缺失凭据，生成私有 .work/observe/metrics-key 文件。已有凭据保留，Grafana 已初始化数据库后变更环境密码不会自动重置账户密码。

## 权限与指标含义

`/actuator/prometheus` 默认关闭并返回 404；metrics 组显式打开，要求独立 X-Metrics-Key，最少 32 字符。用户 JWT 不能代替该凭据。采集不需要用户登录，也不通过 Identity 复核账号；Prometheus 配置只引用挂载的凭据文件，不把密钥写入可见配置文本。健康探针沿用原有语义。

Gateway 的 WebFlux 过滤器与业务应用 MVC 过滤器分别保护指标端点。应用使用常量时间比较，禁止缓存，覆盖编码与尾斜杠路径。未开放 env、configprops 等管理端点。

面板包括可采集目标数、各应用 HTTP 速率、HTTP p95、5xx 速率、堆使用率、Hikari 活跃连接、进程 CPU 和 GC 暂停。p95 排除 AI 对话流及 Actuator；各跳 HTTP 分别计数，不等于独立用户请求数。没有用户、订单、聊天内容或 URL 查询参数标签，也不配置外发通知。

## 验证记录

Maven verify：414 项测试，409 通过，5 跳过，失败/错误均 0。指标入口新增八项针对独立凭据、默认关闭及路径保护的测试。promtool check config 和 Compose config --quiet 均通过。真实结果：`.work/metrics-verified/report.json` 六组通过，completed=true、page_errors=[]、console_errors=[]：七个应用鉴权与编码路径保护、七目标采集与配置不泄密、业务 HTTP/JVM/连接池数据、Grafana 数据源健康及真实面板显示 7、Trade 单目标故障与恢复、监控停机时业务仍可读。

`.work/metrics-business/report.json` 八组回归通过：公共页面、路由切换、排行榜局部失败及重试、注册校验、真实验证码管理员登录、十个管理页面、CSV 导出、CORS；API 和脚本错误均 0。当前组 Search 未运行，两个降级访问按既定可选搜索契约验收。

首次截图发现 Grafana 默认后台更新预装插件，产生插件加载失败；已显式关闭 preinstall 和 preinstall_auto_update。最终验收增加数据源健康、面板实际显示七目标、控制台错误和错误提示检查，修复后的截图已人工检查。初次宽松脚本的结果不作为最终面板可用证据。

## 版本和配置依据

- [Prometheus 3.15.1 发布](https://github.com/prometheus/prometheus/releases/tag/v3.15.1)
- [Prometheus 配置参考](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)，使用 http_headers 的 files 字段。
- [Grafana 13.2.4 发布](https://github.com/grafana/grafana/releases/tag/v13.2.4)

后续阶段仍需实现 SkyWalking 调用链和 Loki / Alloy 日志，并分别完成验收；HTTP/JVM 指标通过不代表阶段 6 全部完成。

实际开关证据：`.work/metrics-default-off/report.json` completed=true，Identity 不带 metrics overlay 重新部署后，即使携带保存的正确凭据也返回 404；随后按 `.work/metrics-final-restore.log` 恢复并健康。
