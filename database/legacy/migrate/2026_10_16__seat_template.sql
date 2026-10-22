-- =============================================================
-- 影厅座位图改造 Phase 1: 座位模板
-- (2026-10-16 跟电影模块翻新脚本同日, 序号按 db 实际执行顺序追加)
-- =============================================================
-- 目的:
-- 1) 引入「座位模板」概念: 一个模板可被多个影厅复用, 改模板不影响已发布的影厅座位图。
-- 2) 模板由「座位单元」构成, 每行每列 1 行记录, 情侣对不在此表达(留到 Phase 4 的
--    seat_couple_group 表, 情侣对 = 2 个物理座位 + 后端绑定)。
-- 3) 本次脚本**只新建** seat_template / seat_template_unit 两张表, 不动老 hall / seat
--    任何数据 —— 零风险, 完全向前兼容。
--
-- 后续 Phase:
--   Phase 2: 建 hall_seat_version (版本快照) + 老数据回填
--   Phase 3: 草稿/发布接口
--   Phase 4: 情侣对 (seat_couple_group)
--   Phase 5: schedule 锁死版本
--   Phase 6: 票价由 schedule 控制
-- =============================================================

SET NAMES utf8mb4;
SET sql_notes = 0;

-- 模板: 画一次, 多厅复用
CREATE TABLE IF NOT EXISTS seat_template (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64) NOT NULL UNIQUE
                COMMENT '模板名称, 全局唯一, 例「1号厅标准模板」/「IMAX 巨幕」',
    description VARCHAR(255)
                COMMENT '备注, 例「8 排 10 列, 7 排起为 VIP」',
    rows_count  INT NOT NULL
                COMMENT '总排数, 与 seat_template_unit.y 的最大值一致',
    cols_count  INT NOT NULL
                COMMENT '总列数, 物理列号 1..cols_count, 允许中间有 status=0 (过道/留空)',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='座位模板: 草图, 可复用, 不直接绑定到任何影厅';

-- 模板里的座位单元: 每行每列 1 行, 物理坐标
-- status: 0 此格无座位(过道/留空) / 1 标准 / 3 VIP / 4 无障碍
-- label 可空, 前端默认显示 "y排x座"
CREATE TABLE IF NOT EXISTS seat_template_unit (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL
                COMMENT '所属模板, 模板删除时级联删单元(暂时手工级联,见 Service.delete)',
    y           INT NOT NULL
                COMMENT '排号, 从 1 起',
    x           INT NOT NULL
                COMMENT '列号, 从 1 起, 物理列号',
    status      TINYINT NOT NULL DEFAULT 1
                COMMENT '0 无座位(过道) / 1 标准 / 3 VIP / 4 无障碍',
    label       VARCHAR(16) DEFAULT NULL
                COMMENT '可选显示名, 例 "5排12座" / "VIP-A1". 不填前端按 y/x 自动生成',
    UNIQUE KEY uk_template_xy (template_id, y, x)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='模板里的座位单元: 一行一列 = 一行记录';

SET sql_notes = 1;
