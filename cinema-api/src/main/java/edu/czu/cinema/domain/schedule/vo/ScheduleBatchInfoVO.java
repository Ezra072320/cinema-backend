package edu.czu.cinema.domain.schedule.vo;

import lombok.Data;

/**
 * 批次同时段的规模信息 —— 前端拖拽批量改期确认弹窗用.
 * {@code total} = 同批次同时段的正常场次数; {@code movable} = 其中未开场、可参与改期的场次数.
 */
@Data
public class ScheduleBatchInfoVO {

    private int total;
    private int movable;
}
