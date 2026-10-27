package edu.czu.cinema.domain.movie;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.domain.movie.search.*;
import edu.czu.cinema.messaging.EventOutbox;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class MovieProjectionTransactionTest {
  JdbcTemplate jdbc;
  TransactionTemplate tx;
  MovieProjectionWriter writer;

  @BeforeEach
  void setup() {
    var db =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    jdbc = new JdbcTemplate(db);
    tx = new TransactionTemplate(new DataSourceTransactionManager(db));
    jdbc.execute(
        "CREATE TABLE movie(id BIGINT PRIMARY KEY,title VARCHAR(255),original_title"
            + " VARCHAR(255),summary VARCHAR(10000),area VARCHAR(64),language"
            + " VARCHAR(64),release_date DATE,runtime_min INT,score DECIMAL(3,1),poster_url"
            + " VARCHAR(512),cat VARCHAR(255),dir VARCHAR(255),status INT)");
    jdbc.execute("CREATE TABLE movie_alt_title(id BIGINT,title VARCHAR(255),movie_id BIGINT)");
    jdbc.execute("CREATE TABLE movie_crew(movie_id BIGINT,person_id BIGINT)");
    jdbc.execute("CREATE TABLE person(id BIGINT,name VARCHAR(128))");
    jdbc.execute("CREATE TABLE movie_search_clock(id INT PRIMARY KEY,version BIGINT)");
    jdbc.execute(
        "CREATE TABLE movie_search_document(movie_id BIGINT PRIMARY KEY,version BIGINT,hidden"
            + " BOOLEAN,payload CLOB)");
    jdbc.execute(
        "CREATE TABLE event_outbox(event_id VARCHAR(64) PRIMARY KEY,topic VARCHAR(80),event_tag"
            + " VARCHAR(64),payload CLOB,delivery_timestamp BIGINT,status VARCHAR(16),attempts"
            + " INT,next_attempt_at BIGINT,created_at BIGINT)");
    jdbc.update("INSERT INTO movie_search_clock VALUES(1,0)");
    jdbc.update("INSERT INTO movie(id,title) VALUES(10,'星河')");
    var json = new ObjectMapper();
    writer = new MovieProjectionWriter(jdbc, json, new EventOutbox(jdbc, json));
  }

  @Test
  void businessChangeProjectionAndOutboxRollbackTogether() {
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      jdbc.update("UPDATE movie SET title='新片名' WHERE id=10");
                      writer.changed(10);
                      throw new IllegalStateException("simulate business failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT title FROM movie WHERE id=10", String.class))
        .isEqualTo("星河");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM movie_search_document", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT version FROM movie_search_clock", Long.class)).isZero();
  }

  @Test
  void versionsIncreaseAndHiddenStateSurvivesLaterMetadataUpdates() throws Exception {
    tx.executeWithoutResult(s -> writer.changed(10));
    tx.executeWithoutResult(s -> writer.visibility(10, true));
    tx.executeWithoutResult(
        s -> {
          jdbc.update("UPDATE movie SET title='新片名' WHERE id=10");
          writer.changed(10);
        });
    var document =
        new ObjectMapper()
            .readValue(
                jdbc.queryForObject("SELECT payload FROM movie_search_document", String.class),
                MovieDocument.class);
    assertThat(document.version()).isEqualTo(3);
    assertThat(document.hidden()).isTrue();
    assertThat(document.title()).isEqualTo("新片名");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Integer.class))
        .isEqualTo(3);
  }
}
