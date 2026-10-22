package edu.czu.cinema.domain.movie.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.mapper.MovieMapper;
import edu.czu.cinema.domain.movie.service.MovieMetadataService;
import edu.czu.cinema.domain.movie.service.MovieMetadataPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** 显式启用：真实历史影片补缺小批提交；人工确认的数据库验证在事务中回滚。 */
@EnabledIfSystemProperty(named="cinema.metadata-live",matches="true")
@ActiveProfiles("dev")
@Import(MovieMetadataService.class)
@SpringBootTest(classes=MainlandMovieLiveTest.LiveApp.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
        properties={"spring.ai.openai.chat.enabled=false","spring.ai.openai.embedding.enabled=false",
                "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl","logging.level.root=WARN"})
class MovieMetadataLiveTest {
    @Autowired MovieMapper movies;
    @Autowired MovieSyncService sync;
    @Autowired MovieMetadataService metadata;
    @Autowired MovieReleaseSource source;
    @Autowired MainlandMovieWriter writer;
    @Autowired MovieMediaStore media;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test void actualWholeLibraryRepairPathKeepsAllExistingValuesAndHistory() throws Exception {
        Path audit = Path.of("target/metadata-repair-audit");
        assertThat(Files.exists(audit.resolve("before-identities.tsv"))).isTrue();
        var before = movies.selectList(null);
        var beforeRows = jdbc.queryForList("SELECT * FROM movie ORDER BY id");
        long schedules = jdbc.queryForObject("SELECT COUNT(*) FROM schedule",Long.class);
        long orders = jdbc.queryForObject("SELECT COUNT(*) FROM `order`",Long.class);
        assertThat(sync.startRepair(3)).isTrue();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofMinutes(4),() -> {
            while (sync.status().running()) Thread.sleep(200);
        });
        var result = sync.status();
        assertThat(result.state()).isEqualTo("COMPLETED");
        assertThat(result.mode()).isEqualTo("REPAIR");
        assertThat(result.done()).isEqualTo(3);
        assertThat(result.created()).isZero();
        assertThat(result.ok()+result.fail()).isEqualTo(3);
        var after = movies.selectList(null);
        assertThat(after).extracting(Movie::getId).containsExactlyElementsOf(before.stream().map(Movie::getId).toList());
        var afterRows = jdbc.queryForList("SELECT * FROM movie ORDER BY id");
        List<String> fields = List.of("title","summary","time","runtime_min","release_date","cat","dir","area","language","pic","poster_url","score","score_source","source","status","maoyan_id","douban_id","tmdb_id");
        for (int i=0;i<beforeRows.size();i++) for (String field : fields) {
            Object value = beforeRows.get(i).get(field);
            if (value == null || value instanceof String s && s.isBlank() || value instanceof Number n && n.intValue()<=0 && List.of("time","runtime_min").contains(field)) continue;
            assertThat(afterRows.get(i).get(field)).describedAs("existing movie %s field %s",beforeRows.get(i).get("id"),field).isEqualTo(value);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule",Long.class)).isEqualTo(schedules);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order`",Long.class)).isEqualTo(orders);
        assertThat(after).allMatch(m -> media.isStored(m.getPosterUrl()));
        Files.writeString(audit.resolve("live-repair.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("METADATA_REPAIR_LIVE_OK processed="+result.done()+" improved="+result.updated()+" partial="+result.partial()+" unavailable="+result.fail()+" history="+before.size());
    }

    @Test @Transactional
    void manualConfirmationPersistsThroughRealMapperAndSyncThenRollsBack() throws Exception {
        Movie original = movies.selectList(null).stream().filter(m -> m.getTmdbId()!=null && m.getRuntimeMin()!=null && m.getRuntimeMin()>0).findFirst().orElseThrow();
        Movie edited = metadata.edit(original.getId(),new MovieMetadataService.Edit(Map.of("runtimeMin",original.getRuntimeMin().toString()),List.of(),original.getManualRevision()));
        Movie locked = movies.lockById(original.getId());
        assertThat(locked.getLockedFields()).contains("runtimeMin");
        assertThat(locked.getMarketStatus()).isEqualTo(original.getMarketStatus());
        assertThat(locked.getManualRevision()).isEqualTo(original.getManualRevision()+1);
        var fetched = source.fetchMissing(locked);
        var written = writer.write(media.prepare(fetched));
        assertThat(written.movieId()).isEqualTo(original.getId());
        Movie refreshed = movies.selectById(original.getId());
        assertThat(refreshed.getRuntimeMin()).isEqualTo(original.getRuntimeMin());
        assertThat(refreshed.getRuntimeMin()).isEqualTo(original.getRuntimeMin());
        assertThat(refreshed.getLockedFields()).contains("runtimeMin");
        var wire = json.readTree(json.writeValueAsString(refreshed));
        assertThat(wire.path("lockedFields").toString()).contains("runtimeMin");
        assertThat(wire.has("manualFieldKeys")).isFalse();
        metadata.edit(original.getId(),new MovieMetadataService.Edit(Map.of(),List.of("runtimeMin"),edited.getManualRevision()));
        assertThat(movies.selectById(original.getId()).getLockedFields()).doesNotContain("runtimeMin");
        // 在回滚事务内制造一个缺项，验证真实来源的语言能经过完整补缺写入路径恢复。
        assertThat(fetched.snapshot().language()).isNotBlank();
        movies.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Movie>()
                .eq(Movie::getId,original.getId()).set(Movie::getLanguage,null));
        Movie missing = movies.selectById(original.getId());
        assertThat(missing.getMissingFields()).contains("语言");
        writer.repair(original.getId(),media.prepareRepair(fetched,missing));
        Movie repaired = movies.selectById(original.getId());
        assertThat(repaired.getLanguage()).isEqualTo(fetched.snapshot().language());
        assertThat(repaired.getMissingFields()).doesNotContain("语言");
        assertThat(repaired.getRuntimeMin()).isEqualTo(original.getRuntimeMin());
        Files.writeString(Path.of("target/metadata-repair-audit/manual-validation.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("movieId",original.getId(),"manualProtectionVerified",true,"unlockVerified",true,
                        "realSourceFillVerified","language","transaction","ROLLBACK")));
        System.out.println("METADATA_MANUAL_LIVE_OK movieId="+original.getId()+" changes=ROLLBACK");
    }
}
