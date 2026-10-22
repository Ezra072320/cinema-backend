package edu.czu.cinema.domain.order.entity;

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

/**
 * 订单实体.
 * <p>
 * 状态枚举：1 待支付 / 2 已支付 / 3 已退款 / 4 已关闭(超时/手动取消) / 5 全部(查询) / 6 已完成（存量）
 * 评价状态单独存储，新评价不再改变订单状态。
 * </p>
 * 4 原本标"退款中"，是死状态从未写入过库，2026-10-19 重定义为"已关闭"——超时关单与用户主动取消共用。
 */
@Data
@TableName("`order`")
@Schema(description = "订单")
public class Order implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String number;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "schedule_id")
    private Long scheduleId;

    private BigDecimal totalAmount;

    private Integer status;

    /** 实退金额：全额=totalAmount；3-6h 档=totalAmount*0.7；未退款为 NULL. */
    @TableField("refund_amount")
    private BigDecimal refundAmount;

    /** 退款时间. */
    @TableField("refund_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime refundTime;

    /**
     * 支付时间(V23) —— 收款侧对账痕迹.
     * 此前只有退款侧落 refund_time/refund_amount, 支付成功后库里查不到「何时收的钱」,
     * 对账只能靠 create_time 推断.
     */
    @TableField("pay_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime payTime;

    /** 支付渠道(模拟收银): WECHAT / ALIPAY / CARD(V23). */
    @TableField("pay_channel")
    private String payChannel;

    @TableField(exist = false)
    private String movieName;

    @TableField(exist = false)
    private String hallName;

    @TableField(exist = false)
    private String poster;

    /**
     * 开场时间 —— **不是 order 表的列**, 是组装 VO 时从 schedule 带过来的展示字段.
     * 早先漏了 exist=false, selectById 会生成 {@code SELECT ... start_time ...} 直接
     * SQL 报错(支付/订单详情/退款全走这条查询).
     */
    @TableField(exist = false)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
}
