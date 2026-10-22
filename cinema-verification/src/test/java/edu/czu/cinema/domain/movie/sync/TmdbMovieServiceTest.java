package edu.czu.cinema.domain.movie.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.config.MovieSyncProperties;
import edu.czu.cinema.config.TmdbProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TmdbMovieServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final TmdbClient client = mock(TmdbClient.class);
    final TmdbMovieService service = new TmdbMovieService(client,json,new TmdbProperties(),new MovieSyncProperties());

    @Test void requiresMainlandTheatricalDateAndDoesNotMistakeChineseLanguageForMainlandRelease() throws Exception {
        var candidate = new MovieSyncCandidate("TMDB",12,"测试","2026-10-01");
        var detail = json.readTree("""
            {"id":12,"title":"测试","release_date":"2026-10-01","poster_path":"/poster.jpg",
             "release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"type":3,"release_date":"2026-10-01"}]},
               {"iso_3166_1":"CN","release_dates":[{"type":4,"release_date":"2026-10-01"}]}]}}
            """);
        assertThatThrownBy(() -> service.parse(candidate,detail)).hasMessageContaining("中国大陆院线上映记录");
    }

    @Test void mapsIndependentMovieAndPersonIdsAndNeverCreatesArtificialScoresOrDurations() throws Exception {
        var result = service.parse(new MovieSyncCandidate("TMDB",12,"进口片","2026-10-01"),json.readTree("""
            {"id":12,"title":"进口片","original_title":"Original","poster_path":"/poster.jpg","runtime":0,
             "vote_average":0,"vote_count":0,"production_countries":[{"name":"United States"}],
             "credits":{"crew":[{"id":15,"name":"导演","job":"Director"}],"cast":[{"id":16,"name":"演员","character":"角色"}]},
             "release_dates":{"results":[{"iso_3166_1":"CN","release_dates":[{"type":3,"release_date":"2026-10-01T00:00:00.000Z"}]}]}}
            """));
        assertThat(result.snapshot().source()).isEqualTo("TMDB");
        assertThat(result.tmdbId()).isEqualTo(12);
        assertThat(result.maoyanId()).isNull();
        assertThat(result.snapshot().dur()).isNull();
        assertThat(result.snapshot().sc()).isNull();
        assertThat(result.snapshot().status()).isZero();
        assertThat(result.credits()).allMatch(c -> c.externalId()==null && c.tmdbId()!=null);
        assertThat(result.snapshot().src()).isEqualTo("United States");
    }

    @Test void incompletePrimaryGetsMissingMetadataWithoutLosingSourceIdsOrReplacingKnownValues() throws Exception {
        var primary = new MainlandMovieFetcher.Fetched(json.readValue("""
            {"id":99,"nm":"原片名","source":"MAOYAN","story":"已有简介","sc":8.5,"rt":"2026-10-01"}
            """,edu.czu.cinema.domain.movie.seed.MaoyanMovieSnapshot.class),List.of(),List.of());
        var extra = new MainlandMovieFetcher.Fetched(json.readValue("""
            {"id":12,"nm":"辅助片名","source":"TMDB","story":"辅助简介","dur":120,"sc":7.1}
            """,edu.czu.cinema.domain.movie.seed.MaoyanMovieSnapshot.class),List.of(),List.of());
        var merged = service.supplement(primary,extra);
        assertThat(merged.snapshot().story()).isEqualTo("已有简介");
        assertThat(merged.snapshot().dur()).isEqualTo(120);
        assertThat(merged.snapshot().sc()).isEqualByComparingTo("8.5");
        assertThat(merged.snapshot().id()).isEqualTo(99);
        assertThat(merged.snapshot().source()).isEqualTo("MAOYAN");
        assertThat(merged.tmdbId()).isEqualTo(12);
        assertThat(merged.maoyanId()).isEqualTo(99);
    }

    @Test void paginationUsesMainlandReleaseFiltersAndReadsEveryPage() throws Exception {
        when(client.get(eq("/discover/movie"),anyMap())).thenAnswer(call -> {
            Map<String,String> params = call.getArgument(1);
            assertThat(params).containsEntry("region","CN").containsEntry("with_release_type","3|2");
            int page = Integer.parseInt(params.get("page"));
            return json.readTree("{\"page\":"+page+",\"total_pages\":2,\"results\":[{\"id\":"+page+",\"title\":\"Sample\",\"release_date\":\""+java.time.LocalDate.now()+"\"}]}");
        });
        assertThat(service.discover()).extracting(MovieSyncCandidate::id).containsExactly(1,2);
        verify(client,times(2)).get(eq("/discover/movie"),anyMap());
    }

    @Test void oldWorldwidePremiereDoesNotHideFutureMainlandReissue() throws Exception {
        // insert-only 窗口为 (今天, 今天+upcomingDays]：老片未来重映仍可入库，重映日必须在窗内。
        String tomorrow = java.time.LocalDate.now().plusDays(1).toString();
        when(client.get(eq("/movie/12"),anyMap())).thenReturn(json.readTree("""
            {"id":12,"title":"重映影片","poster_path":"/poster.jpg","release_date":"1995-01-01",
             "release_dates":{"results":[{"iso_3166_1":"CN","release_dates":[{"type":3,"release_date":"%s"}]}]}}
            """.formatted(tomorrow)));
        var result = service.fetch(new MovieSyncCandidate("TMDB",12,"重映影片","1995-01-01"));
        assertThat(result.snapshot().rt()).isEqualTo(tomorrow);
    }

    @Test void sameTitleWithDifferentMainlandDatesIsNotAutomaticallyMatched() throws Exception {
        when(client.configured()).thenReturn(true);
        when(client.get(eq("/search/movie"),anyMap())).thenReturn(json.readTree("{\"results\":[{\"id\":12,\"title\":\"同名\"}]}"));
        when(client.get(eq("/movie/12"),anyMap())).thenReturn(json.readTree("""
            {"id":12,"title":"同名","poster_path":"/poster.jpg",
             "release_dates":{"results":[{"iso_3166_1":"CN","release_dates":[{"type":3,"release_date":"2000-01-01"}]}]}}
            """));
        assertThat(service.match(new MaoyanClient.Candidate(99,"同名","2026-10-01"),null,null)).isEmpty();
    }

    @Test void knownHistoricalIdCanSupplyMissingTextWithoutRecentReleaseOrSourcePoster() throws Exception {
        var movie = new edu.czu.cinema.domain.movie.entity.Movie(); movie.setTmdbId(12); movie.setTitle("历史影片");
        movie.setReleaseDate(java.time.LocalDate.of(1990,1,2));
        when(client.get(eq("/movie/12"),anyMap())).thenReturn(json.readTree("""
                {"id":12,"title":"历史影片","runtime":98,"overview":"核实简介","release_dates":{"results":[]}}
                """));
        var fetched = service.fetchExisting(movie);
        assertThat(fetched.snapshot().story()).isEqualTo("核实简介");
        assertThat(fetched.snapshot().dur()).isEqualTo(98);
        assertThat(fetched.snapshot().rt()).isNull();
        assertThat(fetched.snapshot().img()).isNull();
        assertThat(fetched.tmdbId()).isEqualTo(12);
    }
}
