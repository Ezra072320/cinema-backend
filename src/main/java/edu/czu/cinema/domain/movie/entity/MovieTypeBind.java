package edu.czu.cinema.domain.movie.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 电影 ↔ 类型 中间表.
 */
@Data
@TableName("movie_to_type")
@Schema(description = "电影与类型多对多")
public class MovieTypeBind {
    @TableField(value = "movie_id")
    private Long movieId;
    @TableField(value = "type_id")
    private Long typeId;
}
