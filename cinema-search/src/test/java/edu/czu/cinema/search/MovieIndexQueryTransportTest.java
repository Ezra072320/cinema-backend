package edu.czu.cinema.search;

import static org.assertj.core.api.Assertions.*;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.czu.cinema.domain.movie.search.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MovieIndexQueryTransportTest {
  @ParameterizedTest
  @ValueSource(strings = {"relevance", "score", "release"})
  void fullTextQueryCanSerializeDslAndReadRealHitDocuments(String sort) throws Exception {
    var json = new ObjectMapper();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] response =
              """
{"took":1,"timed_out":false,"_shards":{"total":1,"successful":1,"skipped":0,"failed":0},
 "hits":{"total":{"value":1,"relation":"eq"},"max_score":1.0,"hits":[
 {"_index":"cinema-movies-test","_id":"10","_score":1.0,
  "_source":{"id":10,"version":2,"hidden":false,"title":"星河","aliases":[],"people":[],"genres":["科幻"]},
  "highlight":{"title":["<mark>星河</mark>"]}}]}}
"""
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.getResponseHeaders().set("X-Elastic-Product", "Elasticsearch");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try (var transport =
        new RestClientTransport(
            RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build(),
            new JacksonJsonpMapper(json))) {
      var hits =
          new MovieIndex(new ElasticsearchClient(transport), json)
              .query(new MovieSearchQuery("星河", "科幻", "中国大陆", 1, true, 1, 12, sort), List.of(10L));
      assertThat(hits.total()).isEqualTo(1);
      assertThat(hits.rows().getFirst().movie().title()).isEqualTo("星河");
      assertThat(hits.rows().getFirst().highlights().get("title"))
          .containsExactly("<mark>星河</mark>");
    } finally {
      server.stop(0);
    }
  }
}
