package edu.czu.cinema.domain.hall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模板里的座位单元: 一行一列 = 一行记录.
 *
 * <p>{@code (templateId, y, x)} 唯一. 物理列号允许跳号(过道/留空),
 * 此类由 {@code status=0} 表达, <b>不在 DB 层</b>额外建"空位"行:
 * <ul>
 *   <li>{@code status=0}: 此格无座位(过道/留空), 仍占一行, 用于前端网格对齐</li>
 *   <li>{@code status=1}: 标准单人座</li>
 *   <li>{@code status=3}: VIP 单人座</li>
 *   <li>{@code status=4}: 无障碍</li>
 * </ul>
 * <p>情侣对不在本表表达 —— Phase 4 引入 {@code seat_couple_group} 表,
 * 把同模板下 2 个相邻 (y, x) 的单元绑成一组, 售出时是 2 条 ticket.
 */
@Data
@TableName("seat_template_unit")
@Schema(description = "模板里的座位单元")
public class SeatTemplateUnit implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("template_id")
    private Long templateId;

    /** 排号, 从 1 起. */
    private Integer y;

    /** 列号 (物理列号), 从 1 起, 允许中间有 status=0. */
    private Integer x;

    /** 0 无座位(过道) / 1 标准 / 3 VIP / 4 无障碍. */
    private Integer status;

    /** 可选显示名. */
    private String label;
}
