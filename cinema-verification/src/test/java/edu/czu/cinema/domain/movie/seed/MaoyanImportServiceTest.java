package edu.czu.cinema.domain.movie.seed;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.entity.MovieAltTitle;
import edu.czu.cinema.domain.movie.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaoyanImportServiceTest {
    @Mock MovieMapper movies;
    @Mock PersonMapper people;
    @Mock MovieCrewMapper crew;
    @Mock MovieImageMapper images;
    @Mock MovieVideoMapper videos;
    @Mock MovieTagMapper tags;
    @Mock MovieAltTitleMapper aliases;
    @Mock TypeMapper types;
    @Mock MovieTypeBindMapper bindings;
    @Mock edu.czu.cinema.domain.movie.search.MovieProjectionWriter projection;
    @InjectMocks MaoyanImportServiceImpl importer;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Movie.class);
    }

    @Test void partialRefreshPreservesKnownMetadataAndNeverPublishesLocally() throws Exception {
        Movie old = new Movie();
        old.setId(10L); old.setMaoyanId(12); old.setTitle("测试");
        old.setSummary("已有简介"); old.setLanguage("英语"); old.setCat("剧情"); old.setDir("已有导演");
        old.setScore(new BigDecimal("8.4")); old.setScoreSource("DOUBAN"); old.setMarketStatus(0);
        old.setReleaseDate(LocalDate.of(2026, 1, 1));
        when(movies.selectOne(any())).thenReturn(old);
        when(movies.lockById(10L)).thenReturn(old);
        var snapshot = json.readValue("{\"id\":12,\"nm\":\"测试\",\"source\":\"MAOYAN\",\"status\":1,\"rt\":\"2026-10-01\"}", MaoyanMovieSnapshot.class);
        assertThat(importer.importOne(snapshot)).isEqualTo(10L);
        assertThat(old.getSummary()).isEqualTo("已有简介");
        assertThat(old.getLanguage()).isEqualTo("英语");
        assertThat(old.getScore()).isEqualByComparingTo("8.4");
        assertThat(old.getScoreSource()).isEqualTo("DOUBAN");
        assertThat(old.getMaoyanId()).isEqualTo(12);
        assertThat(old.getMarketStatus()).isEqualTo(1);
        assertThat(old.getStatus()).isZero();
        assertThat(old.getLastSyncedAt()).isNotNull();
        verifyNoInteractions(crew, images, videos, tags, aliases, bindings, types, people);
        verify(movies).updateById(old);
        verify(movies, never()).insert(any(Movie.class));
    }

    @Test void sameTitleDifferentYearsAreNotMergedButOriginalTitleCanIdentifyReissues() throws Exception {
        Movie old = new Movie(); old.setTitle("同名"); old.setReleaseDate(LocalDate.of(1990, 1, 1));
        var snapshot = json.readValue("{\"id\":12,\"nm\":\"同名\",\"enm\":\"The Original\",\"source\":\"MAOYAN\",\"rt\":\"2026-10-01\"}", MaoyanMovieSnapshot.class);
        assertThat(MaoyanImportServiceImpl.sameFilm(old, snapshot)).isFalse();
        old.setOriginalTitle("The Original");
        assertThat(MaoyanImportServiceImpl.sameFilm(old, snapshot)).isTrue();
        old.setMaoyanId(999);
        assertThat(MaoyanImportServiceImpl.sameFilm(old, snapshot)).isFalse();
    }

    @Test void normalizeTitleStripsPunctuationWhitespaceAndHalfWidth() {
        assertThat(MaoyanImportServiceImpl.normalizeTitle("  星际: 穿越!  ")).isEqualTo("星际穿越");
        assertThat(MaoyanImportServiceImpl.normalizeTitle("ＡＢＣ  世 界")).isEqualTo("abc世界");
        assertThat(MaoyanImportServiceImpl.normalizeTitle(null)).isEqualTo("");
    }

    @Test void findOrNewMergesByAltTitleWhenNoExactTitleMatch() throws Exception {
        Movie existing = new Movie(); existing.setId(10L); existing.setTitle("沙丘"); existing.setReleaseDate(LocalDate.of(2024, 3, 8));
        // 新快照主标题是英文译名，但库里经别名(akas)知道这部片子，判重应按别名命中并合并。
        MovieAltTitle alt = new MovieAltTitle(); alt.setMovieId(10L); alt.setTitle("Dune 沙丘");
        when(movies.selectList(any())).thenReturn(List.of(existing));
        when(aliases.selectList(any())).thenReturn(List.of(alt));
        var snapshot = json.readValue("{\"id\":99,\"nm\":\"Dune\",\"enm\":\"Dune\",\"source\":\"TMDB\",\"rt\":\"2024-03-08\"}", MaoyanMovieSnapshot.class);
        assertThat(importer.findOrNew(snapshot).getId()).isEqualTo(10L);
    }

    @Test void findOrNewDoesNotMergeSameNameWithoutYearOrOriginalTitle() throws Exception {
        Movie existing = new Movie(); existing.setId(10L); existing.setTitle("无名"); // 无 releaseDate/originalTitle
        when(movies.selectList(any())).thenReturn(List.of(existing));
        when(aliases.selectList(any())).thenReturn(List.of());
        var snapshot = json.readValue("{\"id\":99,\"nm\":\"无名\",\"source\":\"TMDB\"}", MaoyanMovieSnapshot.class);
        assertThat(importer.findOrNew(snapshot).getId()).isNull();
    }

    @Test void tmdbImportKeepsOtherProviderIdsAndKnownRatingAndInternalId() throws Exception {
        Movie old = new Movie(); old.setId(10L); old.setMaoyanId(99);
        old.setMarketStatus(1);
        old.setTitle("测试"); old.setScore(new BigDecimal("8.4")); old.setScoreSource("DOUBAN");
        when(movies.selectOne(any())).thenReturn(old);
        when(movies.lockById(10L)).thenReturn(old);
        var snapshot = json.readValue("{\"id\":12,\"nm\":\"测试\",\"source\":\"TMDB\",\"sc\":7.1,\"status\":0}",MaoyanMovieSnapshot.class);
        assertThat(importer.importOne(snapshot)).isEqualTo(10L);
        assertThat(old.getTmdbId()).isEqualTo(12);
        assertThat(old.getMaoyanId()).isEqualTo(99);
        assertThat(old.getScore()).isEqualByComparingTo("8.4");
        assertThat(old.getScoreSource()).isEqualTo("DOUBAN");
        assertThat(old.getStatus()).isZero();
        assertThat(old.getMarketStatus()).isEqualTo(1);
    }
}
