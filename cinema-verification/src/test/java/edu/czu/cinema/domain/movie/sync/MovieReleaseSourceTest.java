package edu.czu.cinema.domain.movie.sync;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovieReleaseSourceTest {
    @Test void maoyanBlockedUsesTmdbFeedWithExplicitNote() {
        var maoyan = mock(MaoyanClient.class);
        var tmdb = mock(TmdbMovieService.class);
        when(maoyan.currentMovies()).thenThrow(new MaoyanClient.SourceUnavailableException("限流"));
        when(tmdb.configured()).thenReturn(true);
        when(tmdb.discover()).thenReturn(List.of(new MovieSyncCandidate("TMDB",1,"影片","2026-10-01")));
        var source = new MovieReleaseSource(maoyan,mock(MainlandMovieFetcher.class),tmdb);
        assertThat(source.discover().note()).isNotBlank();
    }

    @Test void detailRateLimitSwitchesTheRemainingCandidatesWithoutRepeatedlyRequestingBlockedSource() {
        var maoyan = mock(MaoyanClient.class);
        var fetcher = mock(MainlandMovieFetcher.class);
        var tmdb = mock(TmdbMovieService.class);
        var candidate = new MaoyanClient.Candidate(1,"影片","2026-10-01");
        when(maoyan.currentMovies()).thenReturn(List.of(candidate));
        when(tmdb.configured()).thenReturn(true);
        when(fetcher.fetch(any())).thenThrow(new MaoyanClient.SourceUnavailableException("限流"));
        var extra = new MainlandMovieFetcher.Fetched(null,List.of(),List.of());
        when(tmdb.match(any(),isNull(),isNull())).thenReturn(Optional.of(extra));
        when(tmdb.fallback(any(),eq(extra))).thenReturn(extra);
        var source = new MovieReleaseSource(maoyan,fetcher,tmdb);
        source.discover();
        assertThat(source.fetch(MovieSyncCandidate.maoyan(candidate),null)).isSameAs(extra);
        assertThat(source.fetch(MovieSyncCandidate.maoyan(candidate),null)).isSameAs(extra);
        verify(fetcher,times(1)).fetch(any());
    }

    @Test void repairContinuesMaoyanForFieldsStillMissingFromKnownTmdbFilm() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var movie = new edu.czu.cinema.domain.movie.entity.Movie(); movie.setId(10L); movie.setTmdbId(1); movie.setMaoyanId(99); movie.setTitle("影片");
        var primary = new MainlandMovieFetcher.Fetched(json.readValue("{\"id\":99,\"nm\":\"影片\",\"source\":\"MAOYAN\",\"dur\":100}",edu.czu.cinema.domain.movie.seed.MaoyanMovieSnapshot.class),List.of(),List.of());
        var extra = new MainlandMovieFetcher.Fetched(json.readValue("{\"id\":1,\"nm\":\"影片\",\"source\":\"TMDB\"}",edu.czu.cinema.domain.movie.seed.MaoyanMovieSnapshot.class),List.of(),List.of());
        var tmdb = mock(TmdbMovieService.class); when(tmdb.configured()).thenReturn(true); when(tmdb.fetchExisting(movie)).thenReturn(extra);
        var fetcher = mock(MainlandMovieFetcher.class); when(fetcher.fetch(any())).thenReturn(primary);
        when(tmdb.supplement(primary,extra)).thenReturn(primary);
        var source = new MovieReleaseSource(mock(MaoyanClient.class),fetcher,tmdb); source.beginRepair();
        assertThat(source.fetchMissing(movie)).isSameAs(primary);
        verify(fetcher).fetch(any());
        verify(tmdb,never()).matchForRepair(any(),any());
    }
}
