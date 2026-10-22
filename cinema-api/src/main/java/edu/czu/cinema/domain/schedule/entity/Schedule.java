package edu.czu.cinema.domain.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("schedule")
@Schema(description = "排片")
public class Schedule implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "movie_id")
    private Long movieId;

    @TableField(value = "hall_id")
    private Long hallId;

    /**
     * 排片时锁定的座位图版本, 指向 {@code hall_seat_version.id}.
     *
     * <p>场次一旦排出, 这个值就不再变. 之后管理员改影厅座位图并发布会生成 v2/v3,
     * 但本场次仍指向它排出时的版本 —— 已售出的票(seat_id 指向该版本下的座位行)
     * 永远能对上座位. 这是「改座位图不影响已售场次」的关键.
     *
     * <p>为空时读取方回退到 {@code hall.current_version_id}(Phase 5 迁移前的老场次).
     */
    @TableField(value = "seat_version_id")
    private Long seatVersionId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime endTime;

    private BigDecimal price;

    /**
     * VIP 座票价倍率, 乘 {@link #price}. 默认 3.00.
     *
     * <p>倍率属于**场次**而不是座位类型: 座位是不是 VIP 是物理属性, 但"VIP 收几倍"
     * 是排片时的经营决策 —— 同一个厅的午场和午夜场可以不一样.
     */
    @TableField("vip_price_multiplier")
    private BigDecimal vipPriceMultiplier;

    /** 情侣座票价倍率, 乘 {@link #price}. 默认 2.50. */
    @TableField("couple_price_multiplier")
    private BigDecimal couplePriceMultiplier;

    private Integer status;

    /**
     * 批量排片批次号(UUID). 同一次批量排片的场次共享; 单场排片与历史数据为 NULL.
     * 时间轴拖拽改期时据此弹「整批同时段改期 / 只改这一天」确认.
     */
    @TableField("batch_id")
    private String batchId;

    @TableField(exist = false)
    private String movieName;

    @TableField(exist = false)
    private String hallName;

    @TableField(exist = false)
    private Integer soldSeats;

    @TableField(exist = false)
    private java.util.List<Long> soldSeatIds;

    @TableField(exist = false)
    private String poster;
}
