-- =============================================================
-- 影厅座位图改造 Phase 2: 座位图版本快照 + 老数据回填
-- (2026-10-16)
-- =============================================================
-- 目的:
-- 1) 引入「座位图版本」: 影厅的座位图不再只活在一行行 seat 上, 而是一组冻结的快照.
--    每次管理员修改座位并发布, 都会产生一个新的 v2/v3... 老版本 is_locked=1 永不变.
-- 2) hall.current_version_id 指向「当前生效」的版本; schedule.seat_version_id
--    (Phase 5 加) 会锁定排片时刻的版本, 让「改座位图不影响已售场次」.
-- 3) 老数据回填: 给每个 hall 1 条 v1, 把所有老 seat 挂到 v1, hall.current_version_id
--    指向 v1. 没有老数据时 (空库) 回填脚本自动跳过 —— 后续新建厅由 Phase 3 自动建 v1.
--
-- 设计:
--  - hall_seat_version 是「版本控制表」, 每次发布产生新行, 老行 is_current=0,is_locked=1
--  - is_locked=1 的版本: seat 行永不能删 (Phase 3 草稿保存时校验)
--  - uk_hall_version 保证 (hall_id, version) 唯一
--  - idx_current (hall_id, is_current) 加速「找某厅当前版本」查询
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

-- 1. 座位图版本表
CREATE TABLE IF NOT EXISTS hall_seat_version (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    hall_id      BIGINT NOT NULL
                 COMMENT '所属影厅',
    version      INT    NOT NULL
                 COMMENT '版本号, 从 1 起, 同 hall 自增',
    template_id  BIGINT DEFAULT NULL
                 COMMENT '基于哪个模板. NULL = 完全自定义(暂未支持)',
    applied_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                 COMMENT '发布时间',
    applied_by   BIGINT DEFAULT NULL
                 COMMENT '发布人 admin id',
    note         VARCHAR(255) DEFAULT NULL
                 COMMENT '备注, 例「维修调整」「情人节加情侣位」',
    is_current   TINYINT NOT NULL DEFAULT 0
                 COMMENT '1=当前生效, 同 hall 仅 1 条=1',
    is_locked    TINYINT NOT NULL DEFAULT 0
                 COMMENT '1=冻结, 永不能改不能删, 老版本发布后立即变 1',
    UNIQUE KEY uk_hall_version (hall_id, version),
    KEY idx_current (hall_id, is_current),
    KEY idx_locked  (hall_id, is_locked)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='影厅座位图版本快照: 每次发布产生新行, 老行冻结';

-- 2. 给 hall 加 current_version_id
--    不用 NOT NULL, 因为新建厅后还没建 v1 时它是 NULL.
ALTER TABLE hall
    ADD COLUMN current_version_id BIGINT DEFAULT NULL
        COMMENT '当前生效的座位图版本, 指向 hall_seat_version.id';

-- 3. 给 seat 加 version_id
--    不用 NOT NULL 是因为老代码可能还在 insert 无 version_id 的 seat —— 留到 Phase 3
--    才在 insert 路径强制写 version_id. Phase 2 期间允许 NULL 存在.
ALTER TABLE seat
    ADD COLUMN version_id BIGINT DEFAULT NULL
        COMMENT '所属座位图版本, 指向 hall_seat_version.id. NULL = 待回填';

-- 4. 老数据回填: 给每个 hall 1 条 v1, 把所有老 seat 挂到 v1, hall.current_version_id 指向 v1.
--    幂等: 重复执行时只会重复 insert v1, 失败时因为 uk_hall_version 报错就跳过.
--    没有 hall 时啥也不做 —— 不报错, 后续 Phase 3 新建厅时自动建 v1.
--    没有 seat 的 hall 也会建 v1(空座位图, 允许), 后续管理员在 Phase 3 编辑座位图.
SET @ts := NOW();
INSERT INTO hall_seat_version (hall_id, version, applied_at, is_current, is_locked, note)
SELECT h.id, 1, @ts, 1, 0, 'Phase 2 回填: 老数据初始化'
FROM hall h
WHERE NOT EXISTS (
    SELECT 1 FROM hall_seat_version v
    WHERE v.hall_id = h.id AND v.version = 1
);

-- 5. 把老 seat 挂到该 hall 的 v1.
--    用 UPDATE ... WHERE version_id IS NULL 避免覆盖 Phase 3+ 已写入的 version_id.
UPDATE seat s
JOIN hall_seat_version v ON v.hall_id = s.hall_id AND v.version = 1
SET s.version_id = v.id
WHERE s.version_id IS NULL;

-- 6. 把 hall.current_version_id 指向 v1, 仅在尚未设置时.
UPDATE hall h
JOIN hall_seat_version v ON v.hall_id = h.id AND v.version = 1
SET h.current_version_id = v.id
WHERE h.current_version_id IS NULL;

-- 7. 自检: 必须 100% 覆盖. 启动器(SeatVersionBackfillChecker)也会再校一次, 这里是兜底.
--    输出警告, 不阻断 —— 万一有空 hall 没 seat, 也是允许的.
SELECT
    (SELECT COUNT(*) FROM hall) AS hall_count,
    (SELECT COUNT(*) FROM hall WHERE current_version_id IS NULL) AS hall_without_version,
    (SELECT COUNT(*) FROM seat) AS seat_count,
    (SELECT COUNT(*) FROM seat WHERE version_id IS NULL) AS seat_without_version,
    (SELECT COUNT(*) FROM hall_seat_version WHERE is_current = 1) AS current_version_count;

SET sql_notes = 1;
