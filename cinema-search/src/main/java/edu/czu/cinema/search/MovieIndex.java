package edu.czu.cinema.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.domain.movie.search.*;
import java.io.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** All ES mapping, external-version checks, query DSL and alias swaps are contained here. */
@Component
public class MovieIndex {
  public static final String ALIAS = "cinema-movies";
  private final ElasticsearchClient es;
  private final ObjectMapper json;

  public MovieIndex(ElasticsearchClient es, ObjectMapper json) {
    this.es = es;
    this.json = json;
  }

  public void create(String index) throws IOException {
    if (es.indices().exists(e -> e.index(index)).value()) return;
    es.indices()
        .create(
            c ->
                c.index(index)
                    .withJson(
                        new StringReader(
                            """
{"settings":{"number_of_shards":1,"number_of_replicas":0,"refresh_interval":"1s"},
 "mappings":{"dynamic":"strict","properties":{
  "id":{"type":"long"},"version":{"type":"long"},"hidden":{"type":"boolean"},
  "title":{"type":"text","analyzer":"cjk"},"originalTitle":{"type":"text","analyzer":"cjk"},
  "summary":{"type":"text","analyzer":"cjk"},"aliases":{"type":"text","analyzer":"cjk"},
  "people":{"type":"text","analyzer":"cjk"},"dir":{"type":"text","analyzer":"cjk"},
  "area":{"type":"keyword"},"language":{"type":"keyword"},"cat":{"type":"keyword"},
  "genres":{"type":"keyword"},"releaseDate":{"type":"date","format":"yyyy-MM-dd"},
  "runtimeMin":{"type":"integer"},"score":{"type":"double"},"posterUrl":{"type":"keyword","index":false},
  "marketStatus":{"type":"integer"}}}}
""")));
  }

  public void put(String index, MovieDocument document) throws IOException {
    Map<String, Object> fields =
        json.convertValue(document, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    fields.put(
        "genres",
        document.cat() == null
            ? List.of()
            : Arrays.stream(document.cat().split("[,，/]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList());
    try {
      es.index(
          i ->
              i.index(index)
                  .id(Long.toString(document.id()))
                  .version(document.version())
                  .versionType(VersionType.External)
                  .document(fields));
    } catch (ElasticsearchException failure) {
      if (failure.status() != 409
          || !"version_conflict_engine_exception".equals(failure.error().type())) throw failure;
      // Equal or older immutable source versions have already been applied. Tombstones are never
      // deleted.
    } catch (org.elasticsearch.client.ResponseException failure) {
      // This client's low-level transport also surfaces 409 directly as IOException.
      // Only a confirmed external-version conflict is safe to acknowledge, never arbitrary HTTP
      // errors.
      if (failure.getResponse().getStatusLine().getStatusCode() == 409) {
        try {
          var error =
              json.readTree(
                  org.apache.http.util.EntityUtils.toString(failure.getResponse().getEntity()));
          if ("version_conflict_engine_exception".equals(error.path("error").path("type").asText()))
            return;
        } catch (Exception invalidErrorBody) {
          /* Preserve the original delivery failure. */
        }
      }
      throw failure;
    }
  }

  public void activate(String index) throws IOException {
    es.indices().refresh(r -> r.index(index));
    var response =
        es.indices()
            .updateAliases(
                a ->
                    a.actions(
                            x ->
                                x.remove(
                                    r -> r.index("cinema-movies-*").alias(ALIAS).mustExist(false)))
                        .actions(x -> x.add(r -> r.index(index).alias(ALIAS))));
    if (!response.acknowledged()) throw new IOException("Alias swap not acknowledged");
  }

  public record Hits(long total, List<Hit> rows) {}

  public record Hit(MovieDocument movie, Map<String, List<String>> highlights) {}

  public Hits query(MovieSearchQuery query, List<Long> showingIds) throws IOException {
    var filter = new ArrayList<Object>();
    filter.add(Map.of("term", Map.of("hidden", false)));
    if (!query.genre().isBlank()) filter.add(Map.of("term", Map.of("genres", query.genre())));
    if (!query.area().isBlank()) filter.add(Map.of("term", Map.of("area", query.area())));
    if (query.marketStatus() != null)
      filter.add(Map.of("term", Map.of("marketStatus", query.marketStatus())));
    var bool = new LinkedHashMap<String, Object>();
    bool.put("filter", filter);
    if (query.showing() != null) {
      var terms = Map.of("terms", Map.of("id", showingIds));
      if (query.showing()) filter.add(terms);
      else bool.put("must_not", List.of(terms));
    }
    if (!query.q().isBlank())
      bool.put(
          "must",
          List.of(
              Map.of(
                  "multi_match",
                  Map.of(
                      "query",
                      query.q(),
                      "fields",
                      List.of(
                          "title^5",
                          "originalTitle^3",
                          "aliases^3",
                          "people^2",
                          "dir^2",
                          "summary"),
                      "operator",
                      "and"))));
    var body = new LinkedHashMap<String, Object>();
    body.put("query", Map.of("bool", bool));
    body.put("from", (query.page() - 1) * query.size());
    body.put("size", query.size());
    body.put("track_total_hits", true);
    body.put(
        "sort",
        switch (query.sort()) {
          case "score" ->
              List.of(
                  Map.of("score", Map.of("order", "desc")), Map.of("id", Map.of("order", "desc")));
          case "release" ->
              List.of(
                  Map.of("releaseDate", Map.of("order", "desc", "missing", "_last")),
                  Map.of("id", Map.of("order", "desc")));
          default ->
              List.of(
                  Map.of("_score", Map.of("order", "desc")),
                  Map.of("releaseDate", Map.of("order", "desc", "missing", "_last")),
                  Map.of("id", Map.of("order", "desc")));
        });
    body.put(
        "highlight",
        Map.of(
            "encoder",
            "html",
            "pre_tags",
            List.of("<mark>"),
            "post_tags",
            List.of("</mark>"),
            "fields",
            Map.of(
                "title",
                Map.of(),
                "aliases",
                Map.of(),
                "people",
                Map.of(),
                "summary",
                Map.of("fragment_size", 100, "number_of_fragments", 1))));
    var response =
        es.search(
            new SearchRequest.Builder()
                .index(ALIAS)
                .withJson(new StringReader(json.writeValueAsString(body)))
                .build(),
            JsonData.class);
    var hits = new ArrayList<Hit>();
    for (var hit : response.hits().hits()) {
      var node = json.readTree(hit.source().toJson().toString());
      ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("genres");
      hits.add(new Hit(json.treeToValue(node, MovieDocument.class), hit.highlight()));
    }
    return new Hits(response.hits().total() == null ? 0 : response.hits().total().value(), hits);
  }
}
