package edu.czu.cinema.domain.movie.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.messaging.EventOutbox;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns source versions, durable current snapshots and Outbox in the caller's local transaction. */
@Service
public class MovieProjectionWriter {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final EventOutbox outbox;

  public MovieProjectionWriter(JdbcTemplate jdbc, ObjectMapper json, EventOutbox outbox) {
    this.jdbc = jdbc;
    this.json = json;
    this.outbox = outbox;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void changed(long movieId) {
    // All existing writers first own the movie row; serialize versions only after that lock.
    jdbc.queryForObject("SELECT id FROM movie WHERE id=? FOR UPDATE", Long.class, movieId);
    long version =
        jdbc.queryForObject(
                "SELECT version FROM movie_search_clock WHERE id=1 FOR UPDATE", Long.class)
            + 1;
    jdbc.update("UPDATE movie_search_clock SET version=? WHERE id=1", version);
    boolean hidden =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT COALESCE(MAX(hidden),0) FROM movie_search_document WHERE movie_id=?",
                Boolean.class,
                movieId));
    MovieDocument document =
        jdbc.queryForObject(
            "SELECT * FROM movie WHERE id=?",
            (rs, row) ->
                new MovieDocument(
                    movieId,
                    version,
                    hidden,
                    rs.getString("title"),
                    rs.getString("original_title"),
                    rs.getString("summary"),
                    rs.getString("area"),
                    rs.getString("language"),
                    rs.getString("release_date"),
                    rs.getObject("runtime_min", Integer.class),
                    rs.getBigDecimal("score"),
                    rs.getString("poster_url"),
                    rs.getString("cat"),
                    rs.getString("dir"),
                    rs.getObject("status", Integer.class),
                    jdbc.queryForList(
                        "SELECT title FROM movie_alt_title WHERE movie_id=? ORDER BY id LIMIT 100",
                        String.class,
                        movieId),
                    jdbc.queryForList(
                        "SELECT DISTINCT p.name FROM movie_crew c JOIN person p ON p.id=c.person_id"
                            + " WHERE c.movie_id=? ORDER BY p.name LIMIT 100",
                        String.class,
                        movieId)),
            movieId);
    try {
      String payload = json.writeValueAsString(document);
      jdbc.update(
          "INSERT INTO movie_search_document(movie_id,version,hidden,payload) VALUES(?,?,?,?) ON"
              + " DUPLICATE KEY UPDATE"
              + " version=VALUES(version),hidden=VALUES(hidden),payload=VALUES(payload)",
          movieId,
          version,
          hidden,
          payload);
      String eventId = UUID.randomUUID().toString();
      outbox.append(
          eventId,
          "cinema-movie-events",
          "MOVIE_CHANGED",
          new MovieChanged(eventId, 1, document),
          null);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Search visibility is separate from marketStatus (classic/now/upcoming), and from bookability.
   */
  @Transactional
  public void visibility(long movieId, boolean hidden) {
    if (jdbc.queryForList("SELECT id FROM movie WHERE id=? FOR UPDATE", Long.class, movieId)
        .isEmpty())
      throw new edu.czu.cinema.common.exception.BusinessException(
          edu.czu.cinema.common.api.ResultCode.NOT_FOUND, "电影不存在");
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM movie_search_document WHERE movie_id=?", Integer.class, movieId)
        == 0) changed(movieId);
    jdbc.update("UPDATE movie_search_document SET hidden=? WHERE movie_id=?", hidden, movieId);
    changed(movieId);
  }
}
