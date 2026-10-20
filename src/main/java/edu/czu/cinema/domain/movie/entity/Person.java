package edu.czu.cinema.domain.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 真实人物. 取代老的 {@code actor} 表的"演员"角色,
 * 后续 movie_crew.role_type 区分 ACTOR / DIRECTOR / SCREENWRITER 复用本表.
 */
@Data
@TableName("person")
@Schema(description = "人物(演员/导演/编剧)")
public class Person implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String originalName;
    /** 0 未知 / 1 男 / 2 女. */
    private Integer gender;

    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDate birth;

    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDate death;

    private String biography;
    private String profileUrl;
    private String imdbId;
    private Integer maoyanId;
    private Integer tmdbId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
}
