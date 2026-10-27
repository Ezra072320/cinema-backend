package edu.czu.cinema.remote;

import edu.czu.cinema.domain.movie.search.MovieDocument;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(
    name = "cinema-catalog",
    contextId = "MovieSearchSourceClient",
    url = "${CINEMA_CATALOG_URL:}")
public interface MovieSearchSourceClient {
  @PostMapping("/internal/catalog/movie-search/fallback")
  edu.czu.cinema.common.api.PageResult<MovieDocument> fallback(
      @RequestBody edu.czu.cinema.domain.movie.search.MovieSearchFallback request);

  @GetMapping("/internal/catalog/movie-documents")
  List<MovieDocument> documents(@RequestParam("after") long after);
}
