package edu.czu.cinema.domain.movie.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.common.api.PageResult;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/catalog/movie-search")
public class MovieSearchFallbackController {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public MovieSearchFallbackController(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @PostMapping("/fallback")
  public PageResult<MovieDocument> fallback(@RequestBody MovieSearchFallback request) {
    var q = request.query();
    var args = new ArrayList<Object>();
    var where = new StringBuilder(" WHERE hidden=FALSE");
    if (!q.q().isBlank()) {
      // Literal substring search; never interpret user input as a SQL pattern or expression.
      where.append(
          " AND (LOCATE(?,JSON_UNQUOTE(JSON_EXTRACT(payload,'$.title')))>0 OR"
              + " LOCATE(?,JSON_UNQUOTE(JSON_EXTRACT(payload,'$.originalTitle')))>0 OR"
              + " LOCATE(?,JSON_EXTRACT(payload,'$.aliases'))>0 OR"
              + " LOCATE(?,JSON_EXTRACT(payload,'$.people'))>0)");
      for (int i = 0; i < 4; i++) args.add(q.q());
    }
    if (!q.genre().isBlank()) {
      where.append(" AND FIND_IN_SET(?,JSON_UNQUOTE(JSON_EXTRACT(payload,'$.cat')))>0");
      args.add(q.genre());
    }
    if (!q.area().isBlank()) {
      where.append(" AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.area'))=?");
      args.add(q.area());
    }
    if (q.marketStatus() != null) {
      where.append(" AND JSON_EXTRACT(payload,'$.marketStatus')=?");
      args.add(q.marketStatus());
    }
    if (q.showing() != null) {
      List<Long> ids = request.showingIds();
      if (ids == null || ids.size() > 10000)
        throw new IllegalArgumentException("Invalid showing ids");
      if (ids.isEmpty()) {
        if (q.showing()) where.append(" AND 1=0");
      } else {
        where
            .append(q.showing() ? " AND movie_id IN (" : " AND movie_id NOT IN (")
            .append(String.join(",", Collections.nCopies(ids.size(), "?")))
            .append(")");
        args.addAll(ids);
      }
    }
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM movie_search_document" + where, Long.class, args.toArray());
    String sort =
        q.sort().equals("score")
            ? "CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.score')) AS DECIMAL(3,1))"
            : "JSON_UNQUOTE(JSON_EXTRACT(payload,'$.releaseDate'))";
    args.add(q.size());
    args.add((q.page() - 1) * q.size());
    var rows =
        jdbc.query(
            "SELECT payload FROM movie_search_document"
                + where
                + " ORDER BY "
                + sort
                + " DESC,movie_id DESC LIMIT ? OFFSET ?",
            (rs, n) -> {
              try {
                return json.readValue(rs.getString(1), MovieDocument.class);
              } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
              }
            },
            args.toArray());
    return PageResult.of(total, rows);
  }
}
