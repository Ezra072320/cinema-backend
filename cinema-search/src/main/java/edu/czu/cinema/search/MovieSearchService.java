package edu.czu.cinema.search;

import edu.czu.cinema.domain.movie.search.*;
import edu.czu.cinema.domain.movie.vo.MovieShowSummary;
import edu.czu.cinema.remote.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MovieSearchService {
  private final MovieIndex index;
  private final MovieSearchSourceClient source;
  private final TradeCatalogClient trade;

  public MovieSearchService(
      MovieIndex index, MovieSearchSourceClient source, TradeCatalogClient trade) {
    this.index = index;
    this.source = source;
    this.trade = trade;
  }

  public record Item(
      MovieDocument movie,
      Map<String, List<String>> highlights,
      Boolean hasSchedules,
      Integer scheduleCount,
      String nextShowTime) {}

  public record Result(long total, List<Item> rows, String mode) {}

  public Result query(MovieSearchQuery q) {
    List<Long> showingIds;
    try {
      showingIds = q.showing() == null ? List.of() : trade.activeMovieIds(false);
    } catch (Exception unavailable) {
      throw new edu.czu.cinema.common.exception.BusinessException(
          edu.czu.cinema.common.api.ResultCode.REMOTE_ERROR, "排片信息暂不可用，请稍后重试热映筛选");
    }
    MovieIndex.Hits hits;
    String mode = "FULLTEXT";
    try {
      hits = index.query(q, showingIds);
    } catch (Exception unavailable) {
      var fallback = source.fallback(new MovieSearchFallback(q, showingIds));
      hits =
          new MovieIndex.Hits(
              fallback.getTotal(),
              fallback.getRows().stream().map(d -> new MovieIndex.Hit(d, Map.of())).toList());
      mode = "BASIC";
    }
    Map<Long, MovieShowSummary> summaries = new HashMap<>();
    boolean known = false;
    if (!hits.rows().isEmpty())
      try {
        for (var s : trade.summaries(hits.rows().stream().map(h -> h.movie().id()).toList()))
          summaries.put(s.getMovieId(), s);
        known = true;
      } catch (Exception unavailable) {
        /* Showings remain owned by Trade and can be unknown while it is paused. */
      }
    var items = new ArrayList<Item>();
    for (var hit : hits.rows()) {
      var summary = summaries.get(hit.movie().id());
      items.add(
          new Item(
              hit.movie(),
              hit.highlights(),
              known ? summary != null : null,
              known ? (summary == null ? 0 : summary.getScheduleCount()) : null,
              summary == null || summary.getNextShowTime() == null
                  ? null
                  : summary.getNextShowTime().toString()));
    }
    return new Result(hits.total(), items, mode);
  }
}
