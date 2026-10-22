package edu.czu.cinema.domain.movie.vo;

import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.entity.Person;
import edu.czu.cinema.domain.movie.entity.Type;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "电影详情")
public class MovieInfoVO {
    /* ===== 老字段(保留) ===== */
    @com.fasterxml.jackson.annotation.JsonUnwrapped
    private Movie base;
    private List<String> typeNames;
    private List<Type> types;
    /**
     * 兼容字段: 从 {@code person} 表投影出的"演职人员"列表 (与 cast 等价).
     * 老前端代码读 movie.actors[i].name / .pic,这里保留同名 setter,但元素类型换成 Person.
     */
    private List<Person> actors;
    private Boolean isWant;
    private Long soldCount;
    private Long wantCount;

    /* ===== 2026-10-16 新增:导演 / 编剧 / 演职(角色名) ===== */
    private Person director;
    private List<Person> writers;
    private List<MovieCastVO> cast;
    private List<MovieCrewVO> crew;

    /* ===== 2026-10-16 新增:媒体资源 ===== */
    private List<MovieImageVO> images;
    private List<MovieVideoVO> videos;
    private List<String> tags;

    /* ===== 2026-10-16 新增:常用展示字段(平铺,前端少一次 .base.xxx) ===== */
    private String originalTitle;
    private String language;
    private Integer runtimeMin;
    private String posterUrl;
    private String backdropUrl;
    private String trailerUrl;
    private String cat;
    private String dir;
    private String source;
    private String fmt;
    private Integer showDays;
    private BigDecimal firstDayBox;
    private BigDecimal totalBox;
    private Integer wishCount;
    private Integer scoreCount;
    private Integer maoyanId;

    @Data
    public static class RoleVO {
        private Long roleId;
        private String roleName;
        private String roleDescription;
        private String actorName;
        private String actorPic;
    }
}
