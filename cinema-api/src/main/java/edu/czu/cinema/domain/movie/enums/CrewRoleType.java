package edu.czu.cinema.domain.movie.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 人物在某部电影中的职务.
 *
 * <p>对应 {@code movie_crew.role_type} 列,数据库内只存 1/2/3.
 * 这里保留 enum 便于 service / vo 层做 if/switch, 序列化交给 @JsonValue.
 */
@Getter
@AllArgsConstructor
public enum CrewRoleType {
    ACTOR(1, "演员"),
    DIRECTOR(2, "导演"),
    SCREENWRITER(3, "编剧"),
    PRODUCER(4, "制片人");

    private final int code;
    private final String label;

    public static CrewRoleType of(int code) {
        for (CrewRoleType t : values()) {
            if (t.code == code) return t;
        }
        return ACTOR;
    }
}
