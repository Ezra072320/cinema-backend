package edu.czu.cinema.domain.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 又名 / 别名(猫眼 aka / 不同地区片名).
 */
@Data
@TableName("movie_alt_title")
@Schema(description = "电影又名")
public class MovieAltTitle implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long movieId;
    private String title;
    private String country;
    private String language;
}
