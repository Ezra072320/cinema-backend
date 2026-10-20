package edu.czu.cinema.domain.hall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 影厅座位模板.
 *
 * <p>模板是「草图」: 一个模板可被多个影厅复用, 改模板不影响任何已发布的座位图
 * (已发布的座位图由 {@code hall_seat_version} 表冻结, 后续 Phase 2 引入)。
 * <p>本次只暴露 {@code name / description / rowsCount / colsCount} 4 个核心字段。
 * 模板里的实际座位由 {@link SeatTemplateUnit} 表达。
 */
@Data
@TableName("seat_template")
@Schema(description = "座位模板")
public class SeatTemplate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板名称, 全局唯一. */
    private String name;

    /** 备注, 可空. */
    private String description;

    /** 总排数. */
    private Integer rowsCount;

    /** 总列数 (物理列号 1..colsCount). */
    private Integer colsCount;

    private LocalDateTime createdAt;

    /** 模板里的座位单元. {@code @TableField(exist=false)} 表示非 DB 列, 由 Service 装配. */
    @TableField(exist = false)
    private List<SeatTemplateUnit> units;

    /** 模板下单元总数. 仅列表查询时填充, 不映射到 DB. */
    @TableField(exist = false)
    private Integer unitCount;
}
