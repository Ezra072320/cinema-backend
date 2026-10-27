package edu.czu.cinema.domain.movie.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class MovieProjectionController {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final MovieProjectionWriter writer;

  public MovieProjectionController(
      JdbcTemplate jdbc, ObjectMapper json, MovieProjectionWriter writer) {
    this.jdbc = jdbc;
    this.json = json;
    this.writer = writer;
  }

  @GetMapping("/internal/catalog/movie-documents")
  public List<MovieDocument> documents(
      @RequestParam(value = "after", defaultValue = "0") long after) {
    if (after < 0) throw new IllegalArgumentException("Invalid cursor");
    // Cursor by monotonic version: concurrent changes can only move forward, including tombstones.
    return jdbc.query(
        "SELECT payload FROM movie_search_document WHERE version>? ORDER BY version LIMIT 100",
        (rs, n) -> decode(rs.getString(1)),
        after);
  }

  private MovieDocument decode(String payload) {
    try {
      return json.readValue(payload, MovieDocument.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @PutMapping("/api/admin/movie/{id}/search-visibility")
  public R<Void> visibility(@PathVariable("id") long id, @RequestParam("hidden") boolean hidden) {
    SecurityUtils.requireAdminId();
    writer.visibility(id, hidden);
    return R.ok();
  }
}
