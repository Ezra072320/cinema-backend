package edu.czu.cinema.domain.movie.search;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Existing rows are captured once on startup; no reseeding of business data or source versions. */
@Component
public class MovieProjectionSeed implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  private final MovieProjectionWriter writer;
  private final TransactionTemplate tx;

  public MovieProjectionSeed(
      JdbcTemplate jdbc, MovieProjectionWriter writer, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.writer = writer;
    this.tx = tx;
  }

  @Override
  public void run(ApplicationArguments args) {
    for (long id :
        jdbc.queryForList(
            "SELECT m.id FROM movie m LEFT JOIN movie_search_document d ON d.movie_id=m.id WHERE"
                + " d.movie_id IS NULL ORDER BY m.id",
            Long.class)) tx.executeWithoutResult(status -> writer.changed(id));
  }
}
