package edu.czu.cinema.domain.movie.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** 显式启用后访问真实 TMDB / OSS 并更新已备份的本地电影库。 */
@EnabledIfSystemProperty(named="cinema.tmdb-live",matches="true")
@ActiveProfiles("dev")
@SpringBootTest(classes=MainlandMovieLiveTest.LiveApp.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
        properties={"spring.ai.openai.chat.enabled=false","spring.ai.openai.embedding.enabled=false",
                "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
                "logging.level.root=WARN"})
class TmdbMovieLiveTest {
    @Autowired TmdbClient client;
    @Autowired TmdbMovieService tmdb;
    @Autowired MainlandMovieWriter writer;
    @Autowired MovieMediaStore media;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test void actualMainlandFilmUpdatesIdempotentlyWithOssAndPreservedHistory() throws Exception {
        assertThat(Files.exists(Path.of("target/tmdb-sync-audit/before-tmdb-identities.tsv"))).isTrue();
        var before = new HashSet<>(jdbc.queryForList("SELECT id FROM movie",Long.class));
        long schedules = jdbc.queryForObject("SELECT COUNT(*) FROM schedule",Long.class);
        assertThat(client.get("/configuration",Map.of()).path("images").has("secure_base_url")).isTrue();
        System.out.println("TMDB_JAVA_AUTH_OK");
        var candidates = tmdb.discover();
        System.out.println("TMDB_CN_FEED_OK candidates="+candidates.size());
        MainlandMovieFetcher.Fetched fetched = null;
        MovieSyncCandidate chosen = null;
        for (var candidate : candidates.stream().filter(c -> !LocalDate.parse(c.releaseDate()).isAfter(LocalDate.now())
                && c.title()!=null && c.title().matches(".*[\\p{IsHan}].*")).limit(8).toList()) {
            var result = tmdb.fetch(candidate);
            if (result.snapshot().dur()!=null && result.snapshot().dur()>=60 && result.snapshot().story()!=null) {
                fetched=result; chosen=candidate; break;
            }
        }
        assertThat(fetched).describedAs("应存在资料可用的近期大陆院线长片").isNotNull();
        var prepared = media.prepare(fetched);
        var first = writer.write(prepared);
        var repeat = writer.write(prepared);
        assertThat(repeat.movieId()).isEqualTo(first.movieId());
        assertThat(repeat.created()).isFalse();
        assertThat(new HashSet<>(jdbc.queryForList("SELECT id FROM movie",Long.class))).containsAll(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM movie",Long.class)).isEqualTo(before.size()+(first.created()?1L:0L));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule",Long.class)).isEqualTo(schedules);
        var row = jdbc.queryForMap("SELECT id,title,maoyan_id,douban_id,tmdb_id,poster_url FROM movie WHERE id=?",first.movieId());
        assertThat(((Number)row.get("tmdb_id")).intValue()).isEqualTo(chosen.id());
        assertThat(media.isStored((String)row.get("poster_url"))).isTrue();
        try (var response = cn.hutool.http.HttpRequest.head((String)row.get("poster_url")).timeout(10000).execute()) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.header("Content-Type")).startsWith("image/");
        }
        Files.writeString(Path.of("target/tmdb-sync-audit/live-validation.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("candidateCount",candidates.size(),"first",first,"repeat",repeat,"movie",row,"historyPreserved",before.size())));
        System.out.println("TMDB_LIVE_VALIDATION_OK movieId="+first.movieId()+" created="+first.created()+" historyPreserved="+before.size());
    }
}
