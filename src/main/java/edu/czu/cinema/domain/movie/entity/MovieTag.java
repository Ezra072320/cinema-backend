package edu.czu.cinema.domain.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 影片标签(猫眼 filmTags). 同一 (movie_id, tag) 唯一.
 */
@Data
@TableName("movie_tag")
@Schema(description = "电影标签")
public class MovieTag implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long movieId;
    private String tag;
}
