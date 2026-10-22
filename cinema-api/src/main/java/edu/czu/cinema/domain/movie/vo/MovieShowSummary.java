package edu.czu.cinema.domain.movie.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MovieShowSummary {
    private Long movieId;
    private Integer scheduleCount;
    private LocalDateTime nextShowTime;
}
