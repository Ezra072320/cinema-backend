package edu.czu.cinema.domain.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record OrderAddDTO(
        @Schema(description = "排片 ID") @NotNull Long scheduleId,
        @Schema(description = "要锁定的票 ID (1~6 张)")
        @NotEmpty @Size(max = 6, message = "一次最多购买 6 张票") List<Long> ticketIds,
        @jakarta.validation.constraints.Positive Long couponId
) { public OrderAddDTO(Long scheduleId,List<Long> ticketIds){this(scheduleId,ticketIds,null);} }
