package edu.czu.cinema.search;

import static org.assertj.core.api.Assertions.*;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.czu.cinema.domain.movie.search.MovieDocument;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MovieIndexConflictTest {
  @ParameterizedTest
  @CsvSource({
    "409,version_conflict_engine_exception,true",
    "409,unavailable_shards_exception,false",
    "503,unavailable_shards_exception,false"
  })
  void onlyConfirmedExternalVersionConflictsAreAcknowledged(
      int status, String errorType, boolean acknowledge) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] bytes =
              ("{\"error\":{\"type\":\""
                      + errorType
                      + "\",\"reason\":\"test response\"},\"status\":"
                      + status
                      + "}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.getResponseHeaders().set("X-Elastic-Product", "Elasticsearch");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try (var transport =
        new RestClientTransport(
            RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build(),
            new JacksonJsonpMapper(new ObjectMapper()))) {
      var index = new MovieIndex(new ElasticsearchClient(transport), new ObjectMapper());
      var doc =
          new MovieDocument(
              10, 1, false, "星河", null, null, null, null, null, null, null, null, null, null, null,
              List.of(), List.of());
      if (acknowledge)
        assertThatCode(() -> index.put("cinema-movies-test", doc)).doesNotThrowAnyException();
      else
        assertThatThrownBy(() -> index.put("cinema-movies-test", doc))
            .isInstanceOf(java.io.IOException.class);
    } finally {
      server.stop(0);
    }
  }
}
