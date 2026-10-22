package edu.czu.cinema.domain.movie.service;

import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.seed.MaoyanMovieSnapshot;
import java.util.List;

/** 同步、补缺、人工维护共用字段规则，避免只保护主表却覆盖关联资源。 */
public final class MovieMetadataPolicy {
    private MovieMetadataPolicy() {}
    public static final List<String> FIELDS = List.of("summary", "runtimeMin", "releaseDate", "cat", "dir", "area", "language", "posterUrl");
    public static final String INCOMPLETE_SQL = "(summary IS NULL OR TRIM(summary)='' OR "
            + "(COALESCE(runtime_min,0)<=0 AND (release_date IS NULL OR release_date<=CURDATE())) OR release_date IS NULL OR "
            + "cat IS NULL OR TRIM(cat)='' OR dir IS NULL OR TRIM(dir)='' OR area IS NULL OR TRIM(area)='' OR "
            + "language IS NULL OR TRIM(language)='' OR poster_url IS NULL OR TRIM(poster_url)='')";

    public static boolean missing(Movie m, String field) {
        return switch (field) {
            case "summary" -> blank(m.getSummary());
            case "runtimeMin" -> (m.getRuntimeMin() == null || m.getRuntimeMin() <= 0) && !m.isUpcoming();
            case "releaseDate" -> m.getReleaseDate() == null;
            case "cat" -> blank(m.getCat());
            case "dir" -> blank(m.getDir());
            case "area" -> blank(m.getArea());
            case "language" -> blank(m.getLanguage());
            case "posterUrl" -> blank(m.getPosterUrl());
            default -> throw new IllegalArgumentException("不支持的影片字段");
        };
    }

    public static boolean accepts(Movie m, String field, boolean onlyMissing) {
        return !m.getLockedFields().contains(field) && (!onlyMissing || missing(m, field));
    }

    public static boolean coversMissing(Movie movie, MaoyanMovieSnapshot s) {
        return FIELDS.stream().filter(f -> accepts(movie,f,true)).allMatch(field -> switch (field) {
            case "summary" -> !blank(s.story()) || !blank(s.intro());
            case "runtimeMin" -> movie.isUpcoming() || (s.dur() != null && s.dur() > 0);
            case "releaseDate" -> !blank(s.rt());
            case "cat" -> !blank(s.cat());
            case "dir" -> !blank(s.dir());
            case "area" -> !blank(s.src());
            case "language" -> !blank(s.language());
            case "posterUrl" -> !blank(s.img());
            default -> false;
        });
    }

    public static MaoyanMovieSnapshot filter(Movie m, MaoyanMovieSnapshot s, boolean onlyMissing) {
        return new MaoyanMovieSnapshot(s.id(), s.nm(), onlyMissing ? null : s.enm(),
                accepts(m,"cat",onlyMissing) ? s.cat() : null,
                accepts(m,"dir",onlyMissing) ? s.dir() : null,
                onlyMissing ? null : s.star(),
                accepts(m,"summary",onlyMissing) ? s.story() : null,
                accepts(m,"summary",onlyMissing) ? s.intro() : null,
                onlyMissing ? null : s.sc(), onlyMissing ? null : s.snum(), onlyMissing ? null : s.wishCnt(),
                accepts(m,"runtimeMin",onlyMissing) ? s.dur() : null,
                accepts(m,"posterUrl",onlyMissing) ? s.img() : null,
                onlyMissing ? null : s.bgImg(), onlyMissing ? null : s.photos(), onlyMissing ? null : s.videoUrl(),
                accepts(m,"releaseDate",onlyMissing) ? s.rt() : null,
                accepts(m,"area",onlyMissing) ? s.src() : null,
                onlyMissing ? null : s.fmt(), onlyMissing ? null : s.filmTags(),
                onlyMissing ? null : s.boxInfo(), onlyMissing ? null : s.aka(), onlyMissing ? null : s.status(),
                s.source(), accepts(m,"language",onlyMissing) ? s.language() : null);
    }

    public static boolean blank(String value) { return value == null || value.isBlank(); }
}
