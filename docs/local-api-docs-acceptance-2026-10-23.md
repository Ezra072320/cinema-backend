# 本地 Knife4j / OpenAPI 文档

2026-10-23。状态：七个业务域的真实 Schema、页面、公共接口标记、JWT 调试、默认关闭开关及业务回归已验收通过。

## 组合与入口

使用 Knife4j UI 4.5.0、springdoc 2.8.4 及现有 Jakarta / Boot 3.5.6 基线。UI 独立消费标准 OpenAPI，网关保留 WebFlux，未引入 Servlet 文档依赖。文档不增加进程或容器。

`./tools/local-stack.ps1 -Action Up -Group governance -EnableDocs` 显式启用当前组文档，入口 `http://localhost:8088/doc.html`；服务列表来自该启动组内的业务应用。搜索组可用相同开关。关闭时不传开关并重新启动相应组；默认 `API_DOCS_ENABLED=false`。脚本的环境变量覆盖仅对本次调用生效，结束后恢复原值。

`/v3/api-docs/swagger-config` 提供域列表，`/v3/api-docs/{domain}` 代理固定的服务名与固定文档路径。域名只接受 Identity、Catalog、Trade、AI、Notify、Search、Benefit，不能把它当作任意 URL 代理。各域只生成 `/api/**`，排除 `/internal/**`，服务地址为 `/`，调试走同一网关。文档关闭时服务端资源和网关入口一起关闭。

调试私有接口时在 `Authorize` 输入登录得到的 JWT，再打开接口的调试页；已打开的请求编辑页可重新打开或刷新以更新全局参数。实际 Chrome 已验证匿名读取被拒、携带 JWT 后 `/api/admin/info` 成功，且响应不包含密码。测试只在一次性浏览器上下文中输入令牌，授权后的界面不截图、不记录请求头。

管理员接口使用管理员 JWT，用户资料、AI、券包和个人通知使用用户 JWT；文档授权不改变原有角色隔离。

## 已确认的兼容性问题

原 Knife4j 4.5.0 Jakarta starter 的服务端增强在实际生成文档时调用 `SpringDocConfigProperties.getGroupConfigs(): List`，与 springdoc 2.8.4 的接口不兼容，返回500。改为 `knife4j-openapi3-ui` 静态界面与 springdoc 原生生成，保留全部需要的查看、域切换和调试能力。

该 UI 只读取操作级 `security`，不会继承文档顶层定义。项目的原生 OpenApiCustomizer 将继承关系展开到操作，同时保留显式覆盖。公共 API 与 Spring Security 使用同一份 JWT 豁免路径，登录、注册和验证码可匿名调试，内部服务路径仍由内部密钥过滤器保护。

本地快速构建脚本曾把完整 Nginx 配置复制到未被主配置引用的 `conf.d`，使文档地址落到 SPA。标准前端 Dockerfile 的路径一直正确；已把本地辅助脚本改为覆盖 `/etc/nginx/nginx.conf`，并从 `nginx -T` 与实际请求确认生效。

## 证据入口

- `tools/docs_local_acceptance.py` 验证聚合范围、实际 Schema、同源路径、JWT、内部路径排除、无凭据值、真实 UI 调试；`--disabled` 检查关闭入口。
- `.work/docs-identity-acceptance/report.json`：单域四组通过，26个路径，Chrome 脚本错误为零，真实调试 HTTP 200、Bearer 请求头。
- 新增两项文档安全测试覆盖 JWT 继承、公开接口和显式替代授权；文档版本全模块383项，378通过、5项既有跳过；随后导出改动的全模块387项也通过，失败/错误0。
- `.work/docs-governance-acceptance/report.json`：治理组六域四组通过。Identity 26、Catalog 17、Benefit 4、Trade 42、Notify 5、AI 8 个路径，真实授权调试200，脚本错误零。
- `.work/docs-search-acceptance/report.json`：搜索组四域四组通过，Search 3个路径；合计覆盖七个域，公开接口无JWT要求、私有接口操作级授权正确。
- `.work/docs-disabled-acceptance/report.json`：默认关闭后，三个文档入口均404。
- 首轮基础浏览器回归完成十个管理页、CSV、验证码与CORS，但记录一条排行榜503，未将它视作通过。随后项目全部容器停止（所查状态OOMKilled=false），第二轮因入口拒绝连接失败；保留失败报告，不据此修改业务或推断停机原因。
- 恢复治理组后的 `.work/docs-browser-restored/report.json`：八组全部通过，含十个管理页、CSV、验证码、CORS；API和脚本错误均零，排行榜复查200。
- `.work/docs-rbac-restored/report.json`：十一组通过，含服务间越权、原JWT权限撤销、真实角色编辑、Identity重启与故障503；completed和cleanup_completed均true。`.work/docs-disabled-restored/report.json` 再次确认默认关闭。
