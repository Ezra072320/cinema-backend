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
 * 情侣对: 两个物理座位 + 一条绑定记录.
 *
 * <p>座位仍然是 seat 表里的两行, 各有独立 id 和独立票, 检票时两张都验.
 * 本表只回答「哪两个座位是一对」, 用来做两件事:
 * <ol>
 *   <li><b>下单约束</b>: 选中其中一个, 必须把另一个也选上, 不能拆开卖
 *       (见 {@code OrderServiceImpl.createOrder})</li>
 *   <li><b>展示</b>: 选座页把两格渲染成一个视觉单元</li>
 * </ol>
 *
 * <p>不变量: 同一排、x 相邻、一个座位最多属于一对(DB 的 UNIQUE KEY 兜底).
 */
@Data
@TableName("seat_couple_group")
@Schema(description = "情侣对")
public class SeatCoupleGroup implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("hall_id")
    private Long hallId;

    @TableField("version_id")
    private Long versionId;

    /** 一对里的第一个座位. */
    @TableField("seat_id_1")
    private Long seatId1;

    /** 一对里的第二个座位, 与 seatId1 同排且 x 相邻. */
    @TableField("seat_id_2")
    private Long seatId2;

    private String label;
}
