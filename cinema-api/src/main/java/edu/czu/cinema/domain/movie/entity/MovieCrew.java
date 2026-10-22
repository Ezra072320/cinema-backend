package edu.czu.cinema.domain.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 人物 × 影片 的角色绑定.
 *
 * <p>替代老 {@code movie_role} 表;通过 {@code role_type}
 * (1 ACTOR / 2 DIRECTOR / 3 SCREENWRITER) 区分职务.
 */
@Data
@TableName("movie_crew")
@Schema(description = "电影人物角色绑定")
public class MovieCrew implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long movieId;
    private Long personId;

    /** 1 ACTOR / 2 DIRECTOR / 3 SCREENWRITER. */
    private Integer roleType;

    /** 饰演角色(仅 ACTOR). */
    private String characterName;

    /** 职务原文 (导演/编剧). */
    private String jobName;

    private Integer sortOrder;
}
