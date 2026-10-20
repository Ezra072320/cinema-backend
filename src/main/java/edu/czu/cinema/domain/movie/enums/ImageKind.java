package edu.czu.cinema.domain.movie.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * {@code movie_image.kind} 取值.
 */
@Getter
@AllArgsConstructor
public enum ImageKind {
    POSTER(1, "海报"),
    BACKDROP(2, "背景图"),
    STILL(3, "剧照"),
    LOGO(4, "logo");

    private final int code;
    private final String label;

    public static ImageKind of(int code) {
        for (ImageKind k : values()) {
            if (k.code == code) return k;
        }
        return POSTER;
    }
}
