# 存储紧急清理与暂停记录（2026-10-25）

最终状态：用户随后授权的当前步骤收尾已完成，见 [集中日志与收尾验收](local-logs-acceptance-2026-10-25.md)。Trade 修复镜像已部署，事务 12 组、AI 11 组、基础浏览器 8 组、最终日志 8 组及 Grafana 浏览器均通过，专用测试数据已清理。19:28 已停止项目容器并关闭 Docker Desktop，C 盘可用记录约 26.49 GiB。持续实施目标保持暂停；以下保留清理与恢复期间的历史记录，不据此启动新工作。

后续用户授权：仅检查并完成暂停时当前日志步骤的尾项，不新增能力；验收完成后关闭 Docker 释放资源。该授权不恢复原来的无限持续实施目标。本轮只部署已验证的 Trade 超时修复、完成事务日志/AI/基础页面回归。已将正常 Dockerfile 分出不含业务 JAR 的 runtime-base，定点构建直接读取现有 target JAR；不再使用历史镜像叠加方式或重复暂存 JAR。

运行时复核发现已有 Trade 容器实际为 Temurin 25.0.4.1，源码仍以 Java 21 编译；本轮沿用缓存中相同的 Temurin 25 运行时，以维持已有运行环境。普通 Dockerfile 默认仍为 Java 21，可通过明确的 RUNTIME_IMAGE 参数选择。本机 Docker Hub 鉴权端点连接超时，因此没有下载新的 Java 21 镜像。

用户明确要求先停止继续实施、清理磁盘；持续目标已设为 paused。不得自动恢复构建、启动服务或继续消耗存储。

已中断镜像构建及后续部署/验收流水线。Docker 构建缓存已清空（最终 0 B）；只对 cinema/* 的非 local 标签执行无 force 的 image rm，移除 95 个旧标签，1 个受引用保护而跳过；另清理无容器引用的 dangling images，Docker 报告回收 3.603 GB。未删除任何 volume、数据库或源码、配置。Docker Desktop 已正常停止，保持停止以避免继续占用。

C 盘可用空间初始 7.68 GiB，一度降至 6.77 GiB，Docker 停止后为 15.92 GiB；最终数值需按实际主机读数看待，不能把 Docker 缓存的共享计数当作宿主释放量。Docker 数据盘 `C:/Users/32730/AppData/Local/Docker/wsl/disk/docker_data.vhdx` 长度仍约 80.74 GiB，尚未管理员离线压缩。宿主当前进程无管理员权限。保留此 VHDX，禁止删除或 reset/purge Docker 数据。

两种直接删除临时 app.jar 副本的 PowerShell 命令均被自动审批以“blocked by policy”拒绝，未执行；未换工具绕过。`.work` 下各 `*-images` 和 `local-image-context` 仍含约 6 GiB 构建副本；`.work` 同时含私有凭据、Agent 与验收证据，不能整目录删除。临时 JAR 删除与可选管理员压缩需要后续明确处理。

恢复实施前必须先修正镜像更新方式：历史辅助工具反复 `FROM cinema/<role>:before-*` 再 COPY 新 app.jar，保留了多版旧 JAR 的下层，加上长期保存的回滚标签及重复暂存目录，导致空间持续增长。后续应复用不含应用 JAR 的统一 runtime base / 正规 Dockerfile runtime stage，控制历史版本数量与构建缓存、暂存副本；增加磁盘余量门槛。不得再次直接使用已被清理基础标签的 `.work/logging_images.py` 等历史辅助工具。

功能状态：Benefit 预占的独立 8 秒 Feign 读取预算源码已修正。真实 HTTP 延迟测试先失败、修正后通过；最终全量 Maven verify 为 428 项，失败/错误 0，跳过 5，见 `.work/benefit-deadline-verify-20261025.log`。这次新镜像构建未完成、未部署，完整券事务/AI/业务回归仍待执行；不能写为已通过。前几轮失败的独立验收用户/场次/优惠券已在确认 AT 回滚结束后清理。

日志与 HTTP 追踪的已完成证据见 `local-logging-progress-2026-10-25.md`。mq-observe 仅静态检查通过、从未启动，`.work/mq_trace_probe.py` 从未执行。阶段 6 与后续阶段均未标记完成。
