package edu.czu.cinema.domain.movie.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.config.JsonConfig;
import edu.czu.cinema.config.MybatisPlusConfig;
import edu.czu.cinema.domain.movie.seed.MaoyanImportServiceImpl;
import edu.czu.cinema.domain.movie.service.MovieService;
import edu.czu.cinema.domain.movie.service.impl.MovieServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** 必须显式启用。访问真实公开片源，并对已备份的本地 cinema 库执行资料更新。 */
@EnabledIfSystemProperty(named="cinema.live-sync", matches="true")
@ActiveProfiles("dev")
@SpringBootTest(classes=MainlandMovieLiveTest.LiveApp.class, webEnvironment=SpringBootTest.WebEnvironment.NONE,
        properties={"spring.ai.openai.chat.enabled=false", "spring.ai.openai.embedding.enabled=false",
                "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
                "logging.level.edu.czu.cinema=INFO", "logging.level.root=WARN"})
class MainlandMovieLiveTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan({"edu.czu.cinema.domain.movie.mapper", "edu.czu.cinema.domain.schedule.mapper", "edu.czu.cinema.domain.score.mapper"})
    @Import({JsonConfig.class, MybatisPlusConfig.class, MaoyanClient.class, MainlandMovieFetcher.class,
            MainlandMovieWriter.class, MaoyanImportServiceImpl.class, MovieSyncService.class,
            MovieServiceImpl.class, MovieMediaStore.class,
            edu.czu.cinema.file.OssImageFiles.class,MovieReleaseSource.class,TmdbClient.class,TmdbMovieService.class})
    static class LiveApp {}

    @Autowired MovieSyncService sync;
    @Autowired MovieService movies;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MovieMediaStore media;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    @DisabledIfSystemProperty(named="cinema.live-repair-only", matches="true")
    void refreshActualMainlandReleasesPreservingHistoryAndRepeatWithoutDuplicates() throws Exception {
        assertThat(Files.exists(Path.of("target/movie-sync-audit/before-identities.tsv"))).isTrue();
        var beforeIds = new HashSet<>(jdbc.queryForList("SELECT id FROM movie", Long.class));
        long scheduleCount = jdbc.queryForObject("SELECT COUNT(*) FROM schedule", Long.class);
        assertThat(sync.start(Integer.getInteger("cinema.live-limit"))).isTrue();
        await();
        MovieSyncStatus first = sync.status();
        Files.writeString(Path.of("target/movie-sync-audit/live-sync-result.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(first));
        assertThat(first.state()).describedAs(first.message()).isEqualTo("COMPLETED");
        assertThat(first.done()).isEqualTo(first.total());
        assertThat(first.ok()+first.fail()).isEqualTo(first.done());
        assertThat(first.ok()).isPositive();
        assertThat(new HashSet<>(jdbc.queryForList("SELECT id FROM movie", Long.class))).containsAll(beforeIds);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule", Long.class)).isEqualTo(scheduleCount);
        long expectedShowing = jdbc.queryForObject("SELECT COUNT(DISTINCT movie_id) FROM schedule WHERE status=1 AND end_time>NOW()", Long.class);
        assertThat(movies.hotMovies()).hasSize((int) expectedShowing);
        Long movieId = first.results().stream().filter(MovieSyncStatus.Item::ok).findFirst().orElseThrow().movieId();
        var detail = json.readTree(json.writeValueAsString(movies.info(movieId)));
        assertThat(detail.path("id").asLong()).isEqualTo(movieId);
        assertThat(detail.path("title").asText()).isNotBlank();
        assertThat(detail.path("source").asText()).isIn("MAOYAN","TMDB");
        assertThat(detail.has("hasSchedules")).isTrue();
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM movie", Long.class);
        // 重复写入幂等性由 TmdbMovieLiveTest 的同片两次写入验证。
        var summary = Map.of("before",beforeIds.size(),"after",count,"firstRun",first,"localShowing",expectedShowing);
        Files.writeString(Path.of("target/movie-sync-audit/live-validation.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        System.out.println("LIVE_VALIDATION_OK before="+beforeIds.size()+" after="+count+" added="+first.created()+" refreshed="+first.updated()+" partial="+first.partial()+" failed="+first.fail()+" localShowing="+expectedShowing);
    }

    @Test
    @EnabledIfSystemProperty(named="cinema.live-repair-only", matches="true")
    void transferPreviouslyFetchedPostersIntoOss() throws Exception {
        var rows = jdbc.queryForList("SELECT id,maoyan_id,poster_url FROM movie WHERE source='MAOYAN'");
        int repaired = 0;
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        for (var row : rows) {
            String oldUrl = (String) row.get("poster_url");
            if (media.isStored(oldUrl)) continue;
            String stored = media.transferPoster(oldUrl, ((Number)row.get("maoyan_id")).intValue());
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE movie SET pic=?,poster_url=? WHERE id=?", stored,stored,row.get("id"));
                jdbc.update("UPDATE movie_image SET url=? WHERE movie_id=? AND kind=1",stored,row.get("id"));
            });
            repaired++;
            System.out.println("OSS_POSTER_REPAIRED "+repaired+"/"+rows.size());
        }
        for (String url : jdbc.queryForList("SELECT poster_url FROM movie WHERE source='MAOYAN'", String.class)) {
            assertThat(media.isStored(url)).isTrue();
        }
        Long sample = jdbc.queryForObject("SELECT id FROM movie WHERE source='MAOYAN' ORDER BY id DESC LIMIT 1",Long.class);
        var detail = json.readTree(json.writeValueAsString(movies.info(sample)));
        assertThat(detail.path("id").asLong()).isEqualTo(sample);
        assertThat(detail.path("title").asText()).isNotBlank();
        assertThat(media.isStored(detail.path("posterUrl").asText())).isTrue();
        try (var response = cn.hutool.http.HttpRequest.head(detail.path("posterUrl").asText()).timeout(10000).execute()) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.header("Content-Type")).startsWith("image/");
        }
        assertThat(movies.hotMovies()).isEmpty();
        Files.writeString(Path.of("target/movie-sync-audit/oss-validation.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("repaired",repaired,"checked",rows.size(),"detail",detail)));
        System.out.println("OSS_VALIDATION_OK repaired="+repaired+" checked="+rows.size()+" localShowing=0");
    }

    private void await() throws Exception {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofMinutes(20), () -> {
            int last = -1;
            while (sync.status().running()) {
                MovieSyncStatus status = sync.status();
                if (status.done() != last && (status.done()%5==0 || status.done()==status.total())) {
                    System.out.println("LIVE_PROGRESS "+status.state()+" "+status.done()+"/"+status.total()+" added="+status.created()+" updated="+status.updated()+" failed="+status.fail());
                    last=status.done();
                }
                Thread.sleep(1000);
            }
        });
    }
}
