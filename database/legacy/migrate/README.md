# db/migrate（历史归档）

已由 `db/migration/`（Flyway）接管，此目录仅存历史；不要再往这里加脚本。

新迁移一律放在 `src/main/resources/db/migration/`，按 Flyway 版本号命名（`V22__xxx.sql` 起）。本目录的 21 个脚本已按 README「数据库初始化」中的已验证顺序复制为 `db/migration/` 的 V1~V21（内容除剥离 `CREATE DATABASE`／`USE` 外逐字保留），仅供查阅来源。

历史手工执行方式（无 Flyway 时）见 README「数据库初始化」章节的「历史方案」小节；注意部分脚本不可重复执行，勿手工重跑。
