package edu.czu.cinema.query;

import edu.czu.cinema.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CatalogQueryService {
    private final JdbcTemplate jdbc;
    private final edu.czu.cinema.remote.TradeCatalogClient trade;
    private static final int SIZE = 12;

    public Map<String, Object> movies(String keyword, Boolean showing, Integer page) {
        List<Object> args = new ArrayList<>();
        String where = " WHERE 1=1";
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.strip() + "%";
            where +=
                    """
 AND (m.title LIKE ? OR m.original_title LIKE ? OR m.cat LIKE ? OR m.dir LIKE ?
   OR EXISTS (SELECT 1 FROM movie_crew c JOIN person p ON p.id=c.person_id
              WHERE c.movie_id=m.id AND p.name LIKE ?)
   OR EXISTS (SELECT 1 FROM movie_alt_title a WHERE a.movie_id=m.id AND a.title LIKE ?))
""";
            args.addAll(Collections.nCopies(6, like));
        }
        if (showing != null) {
            var ids = trade.activeMovieIds(true);
            if (ids.isEmpty()) {
                if (showing) where += " AND 1=0";
            } else {
                where +=
                        " AND m.id "
                                + (showing ? "IN" : "NOT IN")
                                + " ("
                                + String.join(",", Collections.nCopies(ids.size(), "?"))
                                + ")";
                args.addAll(ids);
            }
        }
        return page(
                """
SELECT m.id AS movieId,m.title,m.cat AS genres,m.dir AS directors,m.area,m.language,
       m.runtime_min AS runtimeMinutes,m.score,m.score_source AS scoreSource,m.release_date AS releaseDate,
       CONCAT('/movie/',m.id) AS detailPath,CONCAT('/hall/',m.id) AS schedulePath
""",
                " FROM movie m",
                where,
                args,
                " ORDER BY m.score DESC,m.release_date DESC,m.id DESC",
                page);
    }

    private Map<String, Object> page(
            String select,
            String from,
            String where,
            List<Object> args,
            String sort,
            Integer page) {
        int p = page == null ? 1 : page;
        if (p < 1 || p > 10000) throw new BusinessException("页码须在1至10000之间");
        Long total =
                jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(SIZE);
        rowArgs.add((p - 1) * SIZE);
        var result = new LinkedHashMap<String, Object>();
        result.put("page", p);
        result.put("pageSize", SIZE);
        result.put("total", total);
        result.put("hasMore", total != null && (long) p * SIZE < total);
        var rows =
                jdbc.queryForList(
                        select + from + where + sort + " LIMIT ? OFFSET ?", rowArgs.toArray());
        rows.forEach(
                row ->
                        row.replaceAll(
                                (key, value) ->
                                        value instanceof java.util.Date
                                                ? value.toString()
                                                : value));
        result.put("rows", rows);
        return result;
    }
}
