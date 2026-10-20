package edu.czu.cinema.domain.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 预告片 / 视频. 猫眼 videoUrl 是单条 mp4, 也可扩展为多条 (官方/花絮/片段).
 */
@Data
@TableName("movie_video")
@Schema(description = "电影视频(预告片/花絮)")
public class MovieVideo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
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
}
