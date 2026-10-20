package edu.czu.cinema.domain.order.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单视图.
 *
 * <p><b>2026-10-19 修复</b>：早先关键字段（id/number/status/totalAmount 等）只挂在
 * {@code base} 嵌套对象里，前端 OrderInfo 期望的是**扁平字段** ——
 * {@code data.status} 取到 undefined，PayOrder 的状态分支全不命中，落到防御分支
 * 把用户踢到"我的订单"页；Orders.vue 的 {@code o.totalAmount.toFixed(2)} 直接抛
 * TypeError 渲染崩坏。现在**平铺全部常用字段**（与 MovieInfoVO 同做法），
 * BeanUtil.copyProperties 自动带出同名字段，前端不再读 base。
 */
@Data
@Schema(description = "订单视图")
public class OrderVO {
    private Long id;
    private String number;
    private Long userId;
    private Long scheduleId;
    private java.math.BigDecimal totalAmount;
    private Integer status;
    private boolean reviewed;
    private boolean canReview;
    private String reviewReason;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime reviewAvailableAt;

    private String movieName;
    private Long movieId;
    private String hallName;
    private String poster;

    /**
     * 座位可读标签（如「3排5座」）—— 与选座页用同一套编号规则（hall 域 SeatLabelService），
     * 取代早先的物理 seatId（前端会看到「1024 / 1027」之类的内部数字）。
     */
    private List<String> seatLabels;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    private java.math.BigDecimal refundAmount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime refundTime;
}
