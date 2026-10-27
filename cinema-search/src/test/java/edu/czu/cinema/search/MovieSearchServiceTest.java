package edu.czu.cinema.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.domain.movie.search.*;
import edu.czu.cinema.remote.*;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MovieSearchServiceTest {
  @Test
  void failedShowingsLookupDoesNotSilentlyDropTheRequestedFilter() {
    var index = mock(MovieIndex.class);
    var source = mock(MovieSearchSourceClient.class);
    var trade = mock(TradeCatalogClient.class);
    when(trade.activeMovieIds(false)).thenThrow(new IllegalStateException());
    assertThatThrownBy(
            () ->
                new MovieSearchService(index, source, trade)
                    .query(new MovieSearchQuery(null, null, null, null, true, 1, 12, null)))
        .isInstanceOf(edu.czu.cinema.common.exception.BusinessException.class)
        .hasMessageContaining("排片信息");
    verifyNoInteractions(index, source);
  }

  @Test
  void elasticFailureUsesCatalogFallbackWithTheSameFilters() throws Exception {
    var index = mock(MovieIndex.class);
    var source = mock(MovieSearchSourceClient.class);
    var trade = mock(TradeCatalogClient.class);
    var q = new MovieSearchQuery("星河", "科幻", "中国大陆", 1, true, 2, 12, "score");
    when(trade.activeMovieIds(false)).thenReturn(List.of(8L));
    when(index.query(q, List.of(8L))).thenThrow(new IOException());
    when(source.fallback(new MovieSearchFallback(q, List.of(8L)))).thenReturn(PageResult.empty());
    var result = new MovieSearchService(index, source, trade).query(q);
    assertThat(result.mode()).isEqualTo("BASIC");
    assertThat(result.total()).isZero();
    verify(source).fallback(new MovieSearchFallback(q, List.of(8L)));
  }

  @Test
  void queryBoundsAndSortAreValidatedBeforeCallingAnyDependency() {
    assertThatThrownBy(
            () -> new MovieSearchQuery("a".repeat(101), null, null, null, null, 1, 12, null))
        .isInstanceOf(edu.czu.cinema.common.exception.BusinessException.class);
    assertThatThrownBy(() -> new MovieSearchQuery(null, null, null, null, null, 201, 50, null))
        .isInstanceOf(edu.czu.cinema.common.exception.BusinessException.class);
    assertThatThrownBy(
            () -> new MovieSearchQuery(null, null, null, null, null, 1, 12, "score;drop"))
        .isInstanceOf(edu.czu.cinema.common.exception.BusinessException.class);
    assertThat(new MovieSearchQuery(" 星河 ", null, null, null, null, 1, 12, null).q())
        .isEqualTo("星河");
  }
}
