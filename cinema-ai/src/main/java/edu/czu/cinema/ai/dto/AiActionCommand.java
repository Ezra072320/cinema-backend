package edu.czu.cinema.ai.dto;

import jakarta.validation.constraints.*;
import lombok.Builder;
import java.util.List;

@Builder
public record AiActionCommand(
        @NotNull Type type,
        @Positive Long movieId,
        @Positive Long orderId,
        @Positive Long scheduleId,
        @Size(min = 1, max = 6) List<@NotNull @Positive Long> ticketIds,
        Boolean wanted,
        @Min(1) @Max(5) Integer score,
        @Size(max = 1024) String content,
        @Pattern(regexp = "^\\S{2,16}$", message = "昵称须为 2~16 个非空字符") String name,
        @Min(1) @Max(120) Integer age
) {
    public enum Type { CREATE_ORDER, CANCEL_ORDER, REFUND_ORDER, SET_WANT, SUBMIT_SCORE, UPDATE_PROFILE }
}
