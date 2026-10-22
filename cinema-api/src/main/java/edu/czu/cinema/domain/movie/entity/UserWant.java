package edu.czu.cinema.domain.movie.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户 ↔ 电影 想看 多对多关系.
 */
@Data
@TableName("user_want_movie")
@Schema(description = "用户想看电影")
public class UserWant {
    @TableField(value = "user_id")
    private Long userId;
    @TableField(value = "movie_id")
    private Long movieId;
}
