package edu.czu.cinema.domain.movie.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "电影图片(海报/剧照)")
public class MovieImageVO {
    private Long id;
    private Long movieId;
    /** 1 POSTER / 2 BACKDROP / 3 STILL / 4 LOGO. */
    private Integer kind;
    private String url;
    private Integer width;
    private Integer height;
    private String language;
}
