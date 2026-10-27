package edu.czu.cinema.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.czu.cinema.domain.movie.search.*;
import edu.czu.cinema.remote.MovieSearchSourceClient;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class IndexCoordinatorTest {
  JdbcTemplate jdbc;
  TransactionTemplate tx;
  MovieIndex index;
  MovieSearchSourceClient source;
  IndexCoordinator coordinator;

  MovieDocument document(long version, boolean hidden) {
    return new MovieDocument(
        10, version, hidden, "星河", null, null, null, null, null, null, null, null, null, null, null,
        List.of(), List.of());
  }

  @BeforeEach
  void setup() {
    var db =
        new DriverManagerDataSource(
            "jdbc:h2:mem:"
                + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    jdbc = new JdbcTemplate(db);
    tx = new TransactionTemplate(new DataSourceTransactionManager(db));
    jdbc.execute(
        "CREATE TABLE search_index_state(id INT PRIMARY KEY,active_index"
            + " VARCHAR(100),building_index VARCHAR(100),cursor_version BIGINT DEFAULT 0,status"
            + " VARCHAR(16) DEFAULT 'EMPTY',last_error VARCHAR(128))");
    jdbc.update("INSERT INTO search_index_state(id) VALUES(1)");
    index = mock(MovieIndex.class);
    source = mock(MovieSearchSourceClient.class);
    coordinator = new IndexCoordinator(jdbc, tx, index, source);
  }

  @Test
  void bothGenerationsReceiveIncrementalEventsWhileRebuilding() throws Exception {
    jdbc.update(
        "UPDATE search_index_state SET active_index='old',building_index='new',status='BUILDING'");
    var event = new MovieChanged("event", 1, document(9, true));
    coordinator.receive(event);
    verify(index).put("old", event.document());
    verify(index).put("new", event.document());
    doThrow(new IOException()).when(index).put("new", event.document());
    assertThatThrownBy(() -> coordinator.receive(event)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void failureKeepsTheActiveIndexAndCursorUntilRetrySucceeds() throws Exception {
    jdbc.update(
        "UPDATE search_index_state SET active_index='old',building_index='new',status='BUILDING'");
    when(source.documents(0)).thenReturn(List.of(document(7, false)));
    doThrow(new IOException()).doNothing().when(index).put(eq("new"), any());
    coordinator.advance();
    assertThat(((Number) coordinator.state().get("cursor_version")).longValue()).isZero();
    assertThat(coordinator.state().get("active_index")).isEqualTo("old");
    coordinator.advance();
    assertThat(((Number) coordinator.state().get("cursor_version")).longValue()).isEqualTo(7);
    when(source.documents(7)).thenReturn(List.of());
    doThrow(new IOException()).doNothing().when(index).activate("new");
    coordinator.advance();
    assertThat(coordinator.state().get("active_index")).isEqualTo("old");
    // A fresh process resumes the persisted cursor, including a failed alias switch.
    new IndexCoordinator(jdbc, tx, index, source).advance();
    assertThat(coordinator.state().get("active_index")).isEqualTo("new");
    assertThat(coordinator.state().get("building_index")).isNull();
  }

  @Test
  void duplicateRebuildRequestsDoNotCreateExtraIndices() throws Exception {
    assertThat(coordinator.rebuild()).isTrue();
    assertThat(coordinator.rebuild()).isFalse();
    verify(index, times(1)).create(anyString());
    assertThatThrownBy(
            () -> {
              jdbc.update("UPDATE search_index_state SET building_index=NULL");
              doThrow(new IOException()).when(index).create(anyString());
              coordinator.rebuild();
            })
        .isInstanceOf(IllegalStateException.class);
    assertThat(coordinator.state().get("building_index")).isNull();
  }

  @Test
  void uninitializedConsumerDoesNotAcknowledgeAndLoseEvents() {
    assertThatThrownBy(() -> coordinator.receive(new MovieChanged("event", 1, document(1, false))))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(index);
  }
}
