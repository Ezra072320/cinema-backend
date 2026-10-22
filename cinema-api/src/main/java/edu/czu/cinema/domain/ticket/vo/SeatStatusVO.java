package edu.czu.cinema.domain.ticket.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 排片下每个座位的状态快照，用于前端"已售/锁定/可售"的高亮渲染.
 */
@Schema(description = "座位状态")
public record SeatStatusVO(
        @JsonProperty("seatId") Long seatId,
        @JsonProperty("status") Integer status
) {}