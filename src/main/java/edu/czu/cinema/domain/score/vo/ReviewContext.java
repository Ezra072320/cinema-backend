package edu.czu.cinema.domain.score.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import edu.czu.cinema.domain.score.entity.Score;
import java.time.LocalDateTime;

public record ReviewContext(Long orderId, Long movieId, String movieTitle, String hallName, String poster,
                            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8") LocalDateTime startTime,
                            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8") LocalDateTime availableAt,
                            boolean canReview, String reason, Score review) {}
