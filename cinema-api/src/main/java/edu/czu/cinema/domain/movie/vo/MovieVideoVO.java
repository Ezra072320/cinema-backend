package edu.czu.cinema.domain.movie.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "电影视频(预告片)")
public class MovieVideoVO {
    private Long id;
    private Long movieId;
    /** "mp4" / "YouTube". */
    private String site;
    private String videoKey;
    private String name;
    private String videoType;
    private Integer official;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime publishedAt;

    /**
     * 视频可播地址. mp4: 直接返回 videoKey;
     * YouTube: 返回 https://www.youtube.com/embed/{key}.
     * 前端 &lt;TrailerModal&gt; 直接用 embedUrl.
     */
    private String embedUrl;
}
