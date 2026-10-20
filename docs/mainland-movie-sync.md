# 单影院影片资料更新

管理员在「影片管理」点击「同步大陆待映影片」。任务读取猫眼公开大陆待映片单（TMDB 备用），只新增满足以下全部条件的影片：大陆院线上映（含进口片，不按产地过滤）、上映日严格大于今天且在未来 60 天内、库中没有。同时**同步已存待映影片的状态**：时长公布（0→真实值）、改档（来源给出新上映日）、市场状态按上映日重推导（2 待映 / 1 上映中 / 0 资料库）；上映日已过的待映影片不需网络、按日期本地推导退出待映。已上映影片同步一个字段都不改——补缺走「补全缺失资料」（repair），纠错走人工补录。重映老片若来源给的是历史首映日会被过滤，属已知行为。

## 时长约定

`runtime_min = 0` 表示「**待映影片，时长尚未公布**」，是合法状态而非缺失资料：
- 待映影片（上映日 > 今天）的 0 时长不计入缺项、不显示"待补"；
- 已上映影片的 0 时长才是资料待补（repair 会去补）；
- 时长公布后由同步的状态刷新写入真实值；已公布的非零时长不被同步回退或覆盖（人工补录优先）；
- 排片要求时长 > 0：待映未公布时长的影片不能排片（无法计算场次冲突）。

（2026-10-17 起 `movie.time` 冗余列已删除，统一用 `runtime_min`；`pic` 并入 `poster_url`。）

## 两种状态分开

- `marketStatus`：大陆市场状态，0 已上映/资料库、1 大陆上映中、2 大陆待上映。沿用数据库 `movie.status` 列，避免破坏历史数据。新片按上映日推导为 2；**已存待映影片在同步时会按上映日重推导市场状态**（连同时长公布、改档共「状态三件套」），已上映影片不调整。
- `hasSchedules` / `scheduleCount` / `nextShowTime`：本店排片信息，查询时计算，不由同步任务或管理员手动设置。
- 本店热映：存在 `schedule.status=1 AND end_time>NOW()` 的场次。撤销排片、最后一场结束后自动退出；老片重新排片也会进入。
- 新片入库不会自动创建排片，也不会自动在本店售票。未公布有效时长的电影不能排片，避免用猜测时长计算场次冲突。

历史影片永久保留。同名不同年份不会直接合并；外部 ID（`maoyan_id`/`tmdb_id`）命中即视为已存在并跳过，绝不更新其任何字段。

## 数据和图片

猫眼 ID 存 `maoyan_id`，TMDB ID 存 `tmdb_id`；`score_source` 单独记录评分来源（douban_id 已随 2026-10-17 清理迁移删除）。未开分不伪造评分，未公布的资料以“待补”显示。

所有影片海报必须下载后转存现有阿里云 OSS 桶，成功后才更新影片表和海报资源表。剧照同样尝试转存，失败时保留原剧照并记录待补原因。对象以来源 ID 和图片地址摘要命名；重复同步复用相同对象，`.image` 对象由真实图片的 `Content-Type` 指定格式。沿用现有 OSS 配置及公开读策略。

片单默认保持 15 分钟新鲜期（`cinema.sync.fresh-minutes`），减少重复抓取。公开来源出现 403、429、406/访问频繁等响应时停止任务并保留成功结果，管理员稍后重试，不尝试绕过来源限制。

## 部署和配置

已有数据库先备份，再执行：

```text
src/main/resources/db/migrate/2026_10_17__mainland_movie_sync.sql
```

脚本可重复运行。它修复旧代码将豆瓣 ID 放入 `maoyan_id` 的问题；其中 MANUAL 记录只迁移已经核对过的 18 条豆瓣种子，不能按 ID 大小猜来源。

配置位于 `application.yml` 的 `cinema.sync`：

| 配置 | 默认 | 用途 |
|---|---|---|
| `city-id` | 1 | 猫眼待映片单查询城市，北京；不能把单城市片单声称为全国所有影院的实时排片 |
| `upcoming-days` | 60 | 有明确日期的近期待映范围 |
| `request-interval-ms` | 1500 | JSON 请求最小间隔 |
| `request-timeout-ms` | 15000 | 单次来源请求超时 |
| `fresh-minutes` | 15 | 近期片单和已成功更新资料的新鲜期 |
| `mainland-feed-path` | data/mainland-movie-feed.json | 当前片单缓存，不是历史影片资料的权威存储 |

旧豆瓣合集候选池（MovieCatalogService/DoubanClient）及 `/api/admin/movie/catalog*` 端点已于 2026-10-17 整体下线删除，`db/seed/movies.json` 种子灌库链路（MaoyanSeedRunner）一并移除。

## 接口与验证

- `POST /api/admin/movie/sync`：启动异步任务；可选 `batchSize=1..500` 用于小批验证，默认处理完整待映片单。只新增库中没有的影片，并同步已存待映影片的状态。
- `GET /api/admin/movie/sync/status`：包含新增、待映状态同步、已存在跳过、失败、待补、未处理数，以及明确的任务状态。
- `GET /api/admin/movie/sync/overview`：资料库总数、缺项数、最近更新时间。
- `GET /api/movie/list?showing=true` / `showing=false`：按本店排片过滤；`status` 参数仅用于管理员筛选市场状态。
- `/api/movie/loadMainPage` 和 `/api/movie/list/hot`：仅返回本店有有效排片的电影。
- `/api/movie/topBoxOffice`：公开票房榜 Top10（`movie.total_box` 全国累计票房，元）。
- `/api/movie/topSales`：影院销售额榜 Top10——真实已支付票款（`ticket.status=0` 且 `order.status IN(2,6)`，剔除待支付/退款/作废票）。旧 `loadSoldLimit50` 的"全票求和"口径已废弃删除。

离线测试：Java 21 下执行 `mvn -o test`。真实片源验证默认跳过，需要显式设置 `-Dcinema.live-sync=true -Dtest=MainlandMovieLiveTest`，并事先备份本地数据库。该验证会实际更新配置的数据库并上传图片到 OSS，不适合在生产环境当普通单元测试运行。

## 首轮猫眼实测（接入 TMDB 前）

更新前已备份受影响的数据表，文件位于 `target/movie-sync-audit/before-mainland-sync-20261017-112409.sql`。原有 848 部影片和内部 ID 全部保留；已新增 20 部、刷新原有 19 部，当前共 868 部。只读核验结果保存在 `target/movie-sync-audit/final-audit.json`，原有豆瓣 ID 也已逐条核对。

全部 868 部影片的海报地址均属于当前配置的 OSS。此次刷新的 39 部已完成海报转存，主表 `pic`、`poster_url` 与海报资源记录一致。真实 OSS 验证通过，重复执行没有再次上传，抽查海报返回 HTTP 200 且 `Content-Type` 为图片。

此次更新的 39 部中，有 1 部尚无有效时长，界面会标记待补，并禁止使用未知时长进行排片；没有编造缺失资料。当前数据库排片和订单均为 0，因此本店热映返回空列表。原库已存在的 71 条演职员和 12 条图片的无影片关联记录与备份一致，此次未产生新的此类记录。

该阶段的完整在线同步受限：首次发现 91 个符合条件的候选，处理 39 部后公开片源返回 406 访问频率限制，后续正常重试仍受限。任务保留成功结果并显示中止原因，没有将错误响应写成影片资料。随后接入 TMDB 的实测结果见下文。

## TMDB 辅助来源

后端通过 `cinema.tmdb.read-access-token`（优先）或 `cinema.tmdb.api-key` 调用官方 API。可以设置环境变量 `TMDB_READ_ACCESS_TOKEN` / `TMDB_API_KEY`，也可以复制 `config/tmdb-local.example.yml` 为 `config/tmdb-local.yml` 填写。该本地文件已排除版本控制，位于 resources 外，不会作为静态资源或打包资源交给前端。日志和任务错误信息不输出凭据。

连接需要代理时设置 `TMDB_PROXY_URL` 或 `cinema.tmdb.proxy-url`，格式为 `http://主机:端口`。API 和 TMDB 图片下载共用这一设置；其他来源不受影响。未配置凭据时保留原猫眼流程。

- 猫眼片单正常时优先使用猫眼；时长、简介、语言或演职员缺项时尝试 TMDB 补全，已有有效字段优先。首次匹配要求片名或已知原名匹配、CN 院线上映日一致且结果唯一；不确定时保留待补。
- 猫眼详情受限时停止向其继续请求，已知片单上的影片可尝试 TMDB 详情。猫眼片单本身不可用时，TMDB 提供未来 60 天内的备用候选（只收待映），读取全部分页。
- 备用候选按 `region=CN`、`with_release_type=3|2` 和地区上映日期范围筛选，入库前再次核对详情中的 CN 院线记录及日期。中文语言、影片产地、全球首映或数字发行日期均不代表大陆院线上映。相关字段仍属于第三方资料，可能存在未收录或未更新。
- 同步只对**已存待映影片**刷新「时长/改档/市场状态」三件套，已上映影片一个字段都不改；上映日不等于本店热映，仍以本店有效排片为准。
- 影片与人物分别保存独立的 `tmdb_id`，不会占用猫眼或豆瓣 ID。已有环境执行 `src/main/resources/db/migrate/2026_10_17__tmdb_movie_support.sql`；脚本可重复执行。
- 图片优先中文海报，转存现有 OSS 后再入库；对象使用 `tmdb-` 命名空间，与猫眼对象区分。外部评分按来源标注，已有猫眼或豆瓣评分不被 TMDB 评分覆盖。

页面“关于与资料来源”包含 TMDB 官方 Logo 与署名。接口和筛选规则依据 [TMDB 地区说明](https://developer.themoviedb.org/docs/region-support)、[上映日期类型](https://developer.themoviedb.org/reference/movie-release-dates) 与 [署名要求](https://developer.themoviedb.org/docs/faq)。

本影院购票观众评分已记录为后续需求，应与外部平台评分独立汇总和展示；按当前任务范围，本轮只完成影片数据接入，不扩展订单、购票及评价流程。

## TMDB 接入后的整批实测结果

2026-10-17 已通过真实 Java 后端调用、单片重复写入、OSS 图片访问及整批同步验证。这台开发电脑的系统 HTTP 代理为本地服务，Java 与 Python 的连接差异已通过本地 TMDB 代理配置解决；运行时需要该代理保持可用。

整批处理 101 个备用候选：新增 46 部、更新 48 部、复用刚更新的 1 部。当前电影总数为 **914**，最初 848 部和接入 TMDB 前的 868 部均全部保留，原猫眼和豆瓣 ID 逐条核对未变。已保存 95 部影片的独立 TMDB ID；全部 914 部的海报地址均属于现有 OSS。

6 个候选未导入：1 个没有日期范围内的 CN 院线记录，5 个没有海报；本次同步中的 27 部已入库影片仍缺部分简介、时长或演职员等资料，已如实标记待补。这不是全库缺项数量，旧片库也有缺失资料。来源未提供的信息不以猜测值补齐，也不把这些缺项算作完整资料。

重复执行整批任务后，新增和更新均为 0，复用 95 部，总数仍为 914；缺少必要资料的 6 部仍显示具体原因。已有 39 部猫眼影片的已确认市场状态已与同步前备份核对，备用来源没有据缺失片单推断下映。本店排片、订单均未新增。

25 项离线测试通过；3 项真实访问测试默认跳过，按需显式启用。前端类型检查与生产构建通过，已检查前端源文件、构建产物和后端 resources 不包含本地 TMDB 令牌。

本次证据文件位于 `target/tmdb-sync-audit/`：`full-first-sync.json`、`live-validation.json`、`final-audit.json`，以及接入前的 SQL 备份。单片真实验证命令为 `mvn -o '-Dcinema.tmdb-live=true' '-Dtest=TmdbMovieLiveTest' test`；完整按钮任务验证为 `mvn -o '-Dcinema.live-sync=true' '-Dtest=MainlandMovieLiveTest' test`。真实测试会写入配置的数据库并上传图片，应先备份，且只在指定的开发环境运行。

## 全片库补缺与核实补录

本轮开发与剩余验证状态见 [影片数据进度记录](影片数据进度记录.md)。

影片管理新增「补全缺失资料」。它扫描全部已入库影片，包括历史影片，只处理基础资料缺项，不受近期上映窗口或普通同步 15 分钟复用期限制。优先处理从未尝试补缺、其次最久未尝试的影片。更新和补缺共用一个任务队列，不能同时启动。

- 有可信 TMDB ID 时直接读取详情；猫眼资料可继续互补。只有豆瓣 ID 的旧片也会尝试按片名或原名、准确大陆上映日唯一匹配 TMDB。不能唯一核实则保留并说明需人工补录，不按同名猜测关联。
- 已确认 ID 的历史电影读取详情不要求落在近期窗口，来源缺海报不会阻断已有 OSS 海报影片的文字补缺。新的上映日只采用真实 CN 院线记录。
- 补缺仅填简介、时长、上映日、类型、导演、地区、语言、海报的空缺，保留已有值、外部评分和市场状态；不会新增电影、排片或订单。海报仍需先转存本项目 OSS。
- 来源没有新增字段时继续显示具体待补项；匹配不唯一、来源缺失或访问失败均显示原因。来源限流或凭据失效时中止，保留成功结果及剩余数，不持续请求受限来源。

列表勾选「只看资料待补」可以按整个片库分页筛选，点击「补录」打开维护窗口。只填写核实过的值，保存后该字段自动标为人工确认。也可勾选已有值确认保护；取消勾选只解除保护并保留原值，之后可由普通来源更新。海报使用上传入口转存 OSS，不接受外部海报地址。未开分、缺预告片不算资料缺项；时长未知仍禁止排片。

人工保护按字段生效，覆盖普通同步和补缺。导演、类型和海报同步维护关联表，保护导演时也不会被来源演职员列表覆盖。写入事务持有影片行锁，网络抓取完成后会重新检查当前缺项和保护状态，避免抓取期间覆盖人工修改；人工编辑版本冲突时拒绝保存并提示重开窗口。

接口：`POST /api/admin/movie/repair` 启动全片库补缺（可选 `batchSize` 用于小批验证），复用 `GET /api/admin/movie/sync/status` 返回进度，`mode=REPAIR`。`PUT /api/admin/movie/{id}/metadata` 接收 `fields`、`unlockFields`、`expectedRevision`，仅管理员可调用。`GET /api/movie/list?incomplete=true` 筛选全库缺项。保护键为 `summary`、`runtimeMin`、`releaseDate`、`cat`、`dir`、`area`、`language`、`posterUrl`。

已有数据库需先备份并执行 `src/main/resources/db/migrate/2026_10_17__movie_metadata_repair.sql`，增加 `manual_fields`、`manual_revision` 和 `last_repair_at`，可重复执行。当前开发库已备份并执行，保留原 914 部影片及来源标识。证据及备份位于 `target/metadata-repair-audit/`。真实验证命令：`mvn -o '-Dcinema.metadata-live=true' '-Dtest=MovieMetadataLiveTest' test`，小批历史补缺会提交真实来源提供的字段，人工补录验证使用回滚事务。
