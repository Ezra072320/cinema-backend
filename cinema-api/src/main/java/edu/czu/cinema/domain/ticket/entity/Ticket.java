package edu.czu.cinema.domain.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
@TableName("ticket")
@Schema(description = "票")
public class Ticket implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "schedule_id")
    private Long scheduleId;

    @TableField(value = "seat_id")
    private Long seatId;

    @TableField(value = "order_id")
    private Long orderId = -1L;

    /** 0 已占用/锁定 1 可售 2 已退 */
    private Integer status;
    private BigDecimal price;
}
