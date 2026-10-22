package edu.czu.cinema.domain.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 影片图片: 海报 / 背景图 / 剧照 / logo.
 */
@Data
@TableName("movie_image")
@Schema(description = "电影图片(海报/剧照/logo)")
public class MovieImage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long movieId;
    /** 1 POSTER / 2 BACKDROP / 3 STILL / 4 LOGO. */
    private Integer kind;
    private String url;
    private Integer width;
    private Integer height;
    private String language;
}
