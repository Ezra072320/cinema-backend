package edu.czu.cinema.domain.movie.sync;

import edu.czu.cinema.config.MovieSyncProperties;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.mapper.MovieMapper;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovieSyncServiceTest {
    @Test void rejectsConcurrentClicksAndKeepsFatalDiscoveryFailureVisible() throws Exception {
        var source = mock(MovieReleaseSource.class);
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        when(source.discover()).thenAnswer(call -> {
            entered.countDown(); resume.await();
            throw new MaoyanClient.SourceUnavailableException("来源暂时不可用");
        });
        var writer = mock(MainlandMovieWriter.class);
        var mapper = mock(MovieMapper.class);
        var service = new MovieSyncService(source, writer, mock(MovieMediaStore.class), mapper,
                new MovieSyncProperties());
        try {
            assertThat(service.start(null)).isTrue();
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(service.start(null)).isFalse();
            resume.countDown();
            waitFor(service);
            assertThat(service.status().state()).isEqualTo("FAILED");
            assertThat(service.status().message()).contains("来源暂时不可用");
            verifyNoInteractions(writer, mapper);
        } finally { resume.countDown(); service.stop(); }
    }

    @Test void insertsOnlyFutureNewMoviesAndRefreshesKnownUpcomingOnes() throws Exception {
        var source = mock(MovieReleaseSource.class);
        var writer = mock(MainlandMovieWriter.class);
        var mapper = mock(MovieMapper.class);
        var media = mock(MovieMediaStore.class);
        when(media.prepare(any())).thenAnswer(call -> call.getArgument(0));
        String future = LocalDate.now().plusDays(3).toString();
        when(source.discover()).thenReturn(new MovieReleaseSource.Feed(List.of(
                new MovieSyncCandidate("MAOYAN",1,"A",future),
                new MovieSyncCandidate("MAOYAN",2,"B",future),
                new MovieSyncCandidate("MAOYAN",3,"C",future)),""));
        Movie knownUpcoming = new Movie();
        knownUpcoming.setId(99L);
        knownUpcoming.setMaoyanId(3);
        knownUpcoming.setReleaseDate(LocalDate.now().plusDays(3)); // 待映 → 走状态刷新
        Movie knownReleased = new Movie();
        knownReleased.setId(98L);
        knownReleased.setMaoyanId(4);
        knownReleased.setReleaseDate(LocalDate.now().minusDays(30)); // 已上映 → 跳过
        knownReleased.setMarketStatus(0);
        when(mapper.selectList(any())).thenReturn(List.of(knownUpcoming, knownReleased));
        var fetched = new MainlandMovieFetcher.Fetched(null,List.of(),List.of());
        when(source.fetch(any(),any())).thenReturn(fetched);
        when(writer.write(fetched)).thenReturn(new MainlandMovieWriter.Written(10L,true,List.of()),
                new MainlandMovieWriter.Written(11L,true,List.of("时长")));
        when(writer.refreshUpcoming(eq(99L), any())).thenReturn(new MainlandMovieWriter.Written(99L,false,List.of()));
        var service = new MovieSyncService(source,writer,media,mapper,new MovieSyncProperties());
        try {
            service.start(null); waitFor(service);
            assertThat(service.status().created()).isEqualTo(2);
            assertThat(service.status().updated()).isEqualTo(1); // 已存待映 → 状态同步
            assertThat(service.status().skipped()).isZero();
            assertThat(service.status().partial()).isEqualTo(1);
            assertThat(service.status().done()).isEqualTo(3);
            assertThat(service.status().state()).isEqualTo("COMPLETED");
            assertThat(service.status().message()).contains("新增 2").contains("待映状态同步 1");
            // 已上映影片绝不 fetch/write；已存待映影片只走 refreshUpcoming
            verify(writer, times(2)).write(any());
            verify(writer).refreshUpcoming(eq(99L), any());
            verify(source, times(3)).fetch(any(),any());
        } finally { service.stop(); }
    }

    @Test void knownReleasedMovieInFeedIsSkippedWithoutAnyFetch() throws Exception {
        var source = mock(MovieReleaseSource.class);
        var writer = mock(MainlandMovieWriter.class);
        var mapper = mock(MovieMapper.class);
        var media = mock(MovieMediaStore.class);
        String future = LocalDate.now().plusDays(3).toString();
        when(source.discover()).thenReturn(new MovieReleaseSource.Feed(List.of(
                new MovieSyncCandidate("MAOYAN",5,"已上映片",future)),""));
        Movie released = new Movie();
        released.setId(77L);
        released.setMaoyanId(5);
        released.setReleaseDate(LocalDate.now().minusDays(10));
        released.setMarketStatus(0);
        when(mapper.selectList(any())).thenReturn(List.of(released));
        var service = new MovieSyncService(source,writer,media,mapper,new MovieSyncProperties());
        try {
            service.start(null); waitFor(service);
            assertThat(service.status().state()).isEqualTo("COMPLETED");
            assertThat(service.status().skipped()).isEqualTo(1);
            assertThat(service.status().updated()).isZero();
            verify(source, never()).fetch(any(),any());
            verifyNoInteractions(writer);
        } finally { service.stop(); }
    }

    @Test void passedReleaseDateLocallyExitsUpcomingStateWithoutNetwork() throws Exception {
        var source = mock(MovieReleaseSource.class);
        var writer = mock(MainlandMovieWriter.class);
        var mapper = mock(MovieMapper.class);
        var media = mock(MovieMediaStore.class);
        when(media.prepare(any())).thenAnswer(call -> call.getArgument(0));
        String future = LocalDate.now().plusDays(3).toString();
        when(source.discover()).thenReturn(new MovieReleaseSource.Feed(List.of(
                new MovieSyncCandidate("MAOYAN",1,"新片",future)),""));
        // 市场状态还是 2，但上映日已过 → 本地推导退出待映
        Movie stale = new Movie();
        stale.setId(66L);
        stale.setTitle("过期待映");
        stale.setMaoyanId(9);
        stale.setReleaseDate(LocalDate.now().minusDays(5));
        stale.setMarketStatus(2);
        when(mapper.selectList(any())).thenReturn(List.of(stale));
        var fetched = new MainlandMovieFetcher.Fetched(null,List.of(),List.of());
        when(source.fetch(any(),any())).thenReturn(fetched);
        when(writer.write(fetched)).thenReturn(new MainlandMovieWriter.Written(10L,true,List.of()));
        var service = new MovieSyncService(source,writer,media,mapper,new MovieSyncProperties());
        try {
            service.start(null); waitFor(service);
            assertThat(service.status().state()).isEqualTo("COMPLETED");
            assertThat(service.status().updated()).isEqualTo(1);
            verify(mapper).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        } finally { service.stop(); }
    }

    @Test void doesNotCollectMoviesReleasingTodayOrEarlier() throws Exception {
        var source = mock(MovieReleaseSource.class);
        var writer = mock(MainlandMovieWriter.class);
        var mapper = mock(MovieMapper.class);
        var media = mock(MovieMediaStore.class);
        when(media.prepare(any())).thenAnswer(call -> call.getArgument(0));
        String today = LocalDate.now().toString();
        String yesterday = LocalDate.now().minusDays(1).toString();
        String future = LocalDate.now().plusDays(5).toString();
        when(source.discover()).thenReturn(new MovieReleaseSource.Feed(List.of(
                new MovieSyncCandidate("MAOYAN",1,"今日上映",today),
                new MovieSyncCandidate("MAOYAN",2,"已上映",yesterday),
                new MovieSyncCandidate("MAOYAN",3,"待映",future)),""));
        when(mapper.selectList(any())).thenReturn(List.of());
        var fetched = new MainlandMovieFetcher.Fetched(null,List.of(),List.of());
        when(source.fetch(any(),any())).thenReturn(fetched);
        when(writer.write(fetched)).thenReturn(new MainlandMovieWriter.Written(10L,true,List.of()));
        var service = new MovieSyncService(source,writer,media,mapper,new MovieSyncProperties());
        try {
            service.start(null); waitFor(service);
            assertThat(service.status().state()).isEqualTo("COMPLETED");
            assertThat(service.status().total()).isEqualTo(1);
            var requested = org.mockito.ArgumentCaptor.forClass(MovieSyncCandidate.class);
            verify(source).fetch(requested.capture(),any());
            assertThat(requested.getValue().title()).isEqualTo("待映");
        } finally { service.stop(); }
    }

    @Test void futureWindowIsBoundedByUpcomingDays() throws Exception {
        var source = mock(MovieReleaseSource.class);
        var writer = mock(MainlandMovieWriter.class);
        var mapper = mock(MovieMapper.class);
        var media = mock(MovieMediaStore.class);
        when(media.prepare(any())).thenAnswer(call -> call.getArgument(0));
        String beyond = LocalDate.now().plusDays(90).toString();
        String inside = LocalDate.now().plusDays(30).toString();
        when(source.discover()).thenReturn(new MovieReleaseSource.Feed(List.of(
                new MovieSyncCandidate("MAOYAN",1,"太远",beyond),
                new MovieSyncCandidate("MAOYAN",2,"窗内",inside)),""));
        when(mapper.selectList(any())).thenReturn(List.of());
        var fetched = new MainlandMovieFetcher.Fetched(null,List.of(),List.of());
        when(source.fetch(any(),any())).thenReturn(fetched);
        when(writer.write(fetched)).thenReturn(new MainlandMovieWriter.Written(10L,true,List.of()));
        var service = new MovieSyncService(source,writer,media,mapper,new MovieSyncProperties());
        try {
            service.start(null); waitFor(service);
            assertThat(service.status().total()).isEqualTo(1);
            verify(source).fetch(argThat(c -> c.title().equals("窗内")),any());
        } finally { service.stop(); }
    }

    @Test void completeTmdbBackupFeedDoesNotTouchExistingMovies() throws Exception {
        var source = mock(MovieReleaseSource.class);
        var candidate = new MovieSyncCandidate("TMDB",12,"影片",LocalDate.now().plusDays(2).toString());
        when(source.discover()).thenReturn(new MovieReleaseSource.Feed(List.of(candidate),"备用片单"));
        var fetched = new MainlandMovieFetcher.Fetched(null,List.of(),List.of());
        when(source.fetch(any(),any())).thenReturn(fetched);
        var media = mock(MovieMediaStore.class);
        when(media.prepare(any())).thenReturn(fetched);
        var writer = mock(MainlandMovieWriter.class);
        when(writer.write(fetched)).thenReturn(new MainlandMovieWriter.Written(10L,true,List.of()));
        var mapper = mock(MovieMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        var service = new MovieSyncService(source,writer,media,mapper,new MovieSyncProperties());
        try {
            service.start(null); waitFor(service);
            assertThat(service.status().state()).isEqualTo("COMPLETED");
            assertThat(service.status().message()).contains("备用片单");
            verify(mapper,never()).update(isNull(),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        } finally { service.stop(); }
    }

    private static void waitFor(MovieSyncService service) throws Exception {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (service.status().running()) Thread.sleep(10);
        });
    }

    @Test void repairsHistoricalMoviesEvenWhenFreshWithoutDiscoveringRecentFeed() throws Exception {
        var source = mock(MovieReleaseSource.class);
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        Movie historical = new Movie(); historical.setId(10L); historical.setTitle("历史影片");
        historical.setReleaseDate(LocalDate.of(1990,1,2)); historical.setLastSyncedAt(java.time.LocalDateTime.now());
        var fetched = new MainlandMovieFetcher.Fetched(null,List.of(),List.of());
        when(source.fetchMissing(historical)).thenAnswer(call -> { entered.countDown(); resume.await(); return fetched; });
        var mapper = mock(MovieMapper.class); when(mapper.selectList(any())).thenReturn(List.of(historical));
        var media = mock(MovieMediaStore.class); when(media.prepareRepair(fetched,historical)).thenReturn(fetched);
        var writer = mock(MainlandMovieWriter.class); when(writer.repair(10L,fetched)).thenReturn(new MainlandMovieWriter.Written(10L,false,List.of()));
        var service = new MovieSyncService(source,writer,media,mapper,new MovieSyncProperties());
        try {
            assertThat(service.startRepair(null)).isTrue();
            assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(service.start(null)).isFalse();
            assertThat(service.startRepair(null)).isFalse();
            resume.countDown(); waitFor(service);
            assertThat(service.status().mode()).isEqualTo("REPAIR");
            assertThat(service.status().state()).isEqualTo("COMPLETED");
            assertThat(service.status().created()).isZero();
            assertThat(service.status().updated()).isEqualTo(1);
            verify(source,never()).discover();
            verify(writer,never()).write(any());
        } finally { resume.countDown(); service.stop(); }
    }

    @Test void repairStopsOnProviderLimitAndLeavesRemainingFilmsForRetry() throws Exception {
        var source = mock(MovieReleaseSource.class);
        Movie old = new Movie(); old.setId(10L); old.setTitle("旧片");
        var mapper = mock(MovieMapper.class); when(mapper.selectList(any())).thenReturn(List.of(old));
        when(source.fetchMissing(old)).thenThrow(new MaoyanClient.SourceUnavailableException("TMDB 请求受限"));
        var writer = mock(MainlandMovieWriter.class);
        var service = new MovieSyncService(source,writer,mock(MovieMediaStore.class),mapper,new MovieSyncProperties());
        try {
            service.startRepair(null); waitFor(service);
            assertThat(service.status().state()).isEqualTo("FAILED");
            assertThat(service.status().pending()).isEqualTo(1);
            assertThat(service.status().message()).contains("请求受限");
            verifyNoInteractions(writer);
        } finally { service.stop(); }
    }
}
