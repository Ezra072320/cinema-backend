package edu.czu.cinema.search;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.movie.search.MovieSearchQuery;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class MovieSearchController {
  private final MovieSearchService search;
  private final IndexCoordinator coordinator;

  public MovieSearchController(MovieSearchService search, IndexCoordinator coordinator) {
    this.search = search;
    this.coordinator = coordinator;
  }

  @GetMapping("/api/search/movies")
  public R<MovieSearchService.Result> movies(
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "genre", required = false) String genre,
      @RequestParam(value = "area", required = false) String area,
      @RequestParam(value = "marketStatus", required = false) Integer marketStatus,
      @RequestParam(value = "showing", required = false) Boolean showing,
      @RequestParam(value = "page", defaultValue = "1") int page,
      @RequestParam(value = "size", defaultValue = "12") int size,
      @RequestParam(value = "sort", defaultValue = "relevance") String sort) {
    return R.ok(
        search.query(
            new MovieSearchQuery(q, genre, area, marketStatus, showing, page, size, sort)));
  }

  @GetMapping("/api/admin/search/status")
  public R<Map<String, Object>> status() {
    SecurityUtils.requireAdminId();
    return R.ok(coordinator.state());
  }

  @PostMapping("/api/admin/search/rebuild")
  public R<Map<String, Boolean>> rebuild() {
    SecurityUtils.requireAdminId();
    return R.ok(Map.of("started", coordinator.rebuild()));
  }
}
