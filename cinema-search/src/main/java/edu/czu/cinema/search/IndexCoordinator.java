package edu.czu.cinema.search;

import edu.czu.cinema.domain.movie.search.*;
import edu.czu.cinema.remote.MovieSearchSourceClient;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Persistent, resumable rebuild. Consumers and cutover share the same short DB row lock. */
@Service
public class IndexCoordinator {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(IndexCoordinator.class);
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final MovieIndex index;
  private final MovieSearchSourceClient source;

  public IndexCoordinator(
      JdbcTemplate jdbc, TransactionTemplate tx, MovieIndex index, MovieSearchSourceClient source) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.index = index;
    this.source = source;
  }

  public Map<String, Object> state() {
    return jdbc.queryForMap("SELECT * FROM search_index_state WHERE id=1");
  }

  private Map<String, Object> lock() {
    return jdbc.queryForMap("SELECT * FROM search_index_state WHERE id=1 FOR UPDATE");
  }

  public void receive(MovieChanged event) {
    tx.executeWithoutResult(
        status -> {
          var state = lock();
          String active = (String) state.get("active_index"),
              building = (String) state.get("building_index");
          if (active == null && building == null)
            throw new IllegalStateException("Index is initializing");
          try {
            if (active != null) index.put(active, event.document());
            if (building != null) index.put(building, event.document());
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  public boolean rebuild() {
    return beginRebuild() != null;
  }

  /** Identity of the accepted attempt, so a scheduler can follow its durable cursor. */
  public String beginRebuild() {
    return tx.execute(
            status -> {
              var state = lock();
              if (state.get("building_index") != null) return null;
              String name = "cinema-movies-" + UUID.randomUUID().toString().replace("-", "");
              try {
                index.create(name);
              } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
              }
              jdbc.update(
                  "UPDATE search_index_state SET"
                      + " building_index=?,cursor_version=0,status='BUILDING',last_error=NULL WHERE"
                      + " id=1",
                  name);
              return name;
            });
  }

  @Scheduled(fixedDelay = 2000, initialDelay = 3000)
  public void advance() {
    try {
      var initial = state();
      if (initial.get("active_index") == null && initial.get("building_index") == null) {
        rebuild();
        return;
      }
      String target = (String) initial.get("building_index");
      if (target == null) return;
      long cursor = ((Number) initial.get("cursor_version")).longValue();
      List<MovieDocument> documents = source.documents(cursor);
      tx.executeWithoutResult(
          status -> {
            var current = lock();
            if (!target.equals(current.get("building_index"))
                || cursor != ((Number) current.get("cursor_version")).longValue()) return;
            try {
              if (documents.isEmpty()) {
                index.activate(target);
                jdbc.update(
                    "UPDATE search_index_state SET"
                        + " active_index=?,building_index=NULL,status='READY',last_error=NULL WHERE"
                        + " id=1",
                    target);
              } else {
                for (var document : documents) index.put(target, document);
                jdbc.update(
                    "UPDATE search_index_state SET cursor_version=?,last_error=NULL WHERE id=1",
                    documents.getLast().version());
              }
            } catch (java.io.IOException e) {
              throw new IllegalStateException(e);
            }
          });
    } catch (Exception failure) {
      // Retain building index + cursor; next tick or process restart resumes. Never discard the
      // active alias.
      String error =
          failure.getClass().getSimpleName()
              + ":"
              + (failure.getCause() == null
                  ? "none"
                  : failure.getCause().getClass().getSimpleName());
      if (!error.equals(state().get("last_error")))
        log.warn("Search rebuild paused failure={}", error);
      jdbc.update("UPDATE search_index_state SET last_error=? WHERE id=1", error);
    }
  }
}
