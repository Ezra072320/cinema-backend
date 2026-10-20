package edu.czu.cinema.domain.movie;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.mapper.*;
import edu.czu.cinema.domain.movie.seed.*;
import edu.czu.cinema.domain.movie.service.*;
import edu.czu.cinema.domain.movie.sync.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovieMetadataTest {
    final MovieMapper movies = mock(MovieMapper.class);
    final PersonMapper people = mock(PersonMapper.class);
    final MovieCrewMapper crew = mock(MovieCrewMapper.class);
    final MovieImageMapper images = mock(MovieImageMapper.class);
    final TypeMapper types = mock(TypeMapper.class);
    final MovieTypeBindMapper bindings = mock(MovieTypeBindMapper.class);
    final ObjectMapper json = new ObjectMapper();
    final MaoyanImportServiceImpl importer = new MaoyanImportServiceImpl(movies,people,crew,images,
            mock(MovieVideoMapper.class),mock(MovieTagMapper.class),mock(MovieAltTitleMapper.class),types,bindings);
    final MovieMediaStore media = mock(MovieMediaStore.class);
    final MovieMetadataService service = new MovieMetadataService(movies,importer,media,json);

    @BeforeEach void metadata() { TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"metadata-test"),Movie.class); }

    Movie existing() {
        Movie movie = new Movie(); movie.setId(12L); movie.setTitle("历史影片"); movie.setSummary("原简介");
        movie.setRuntimeMin(100); movie.setReleaseDate(LocalDate.of(2000,1,2));
        movie.setManualRevision(0); movie.setMaoyanId(99); movie.setMarketStatus(0); movie.setSource("MAOYAN");
        when(movies.lockById(12L)).thenReturn(movie);
        when(movies.selectOne(any())).thenReturn(movie);
        when(movies.selectById(12L)).thenReturn(movie);
        return movie;
    }

    @Test void writeNeverTouchesExistingMoviesSoManualEditsAlwaysSurvive() throws Exception {
        Movie movie = existing();
        service.edit(12L,new MovieMetadataService.Edit(Map.of("runtimeMin","108"),List.of(),0));
        assertThat(movie.getLockedFields()).containsExactly("runtimeMin");
        assertThat(movie.getRuntimeMin()).isEqualTo(108);
        var fetched = new MainlandMovieFetcher.Fetched(json.readValue("""
                {"id":99,"nm":"历史影片","source":"MAOYAN","dur":120,"story":"更新简介"}
                """,MaoyanMovieSnapshot.class),List.of(),List.of());
        // insert-only：已存在的影片写入路径直接跳过，一个字段都不改
        var written = new MainlandMovieWriter(importer,movies,people,crew).write(fetched);
        assertThat(written.created()).isFalse();
        assertThat(written.movieId()).isEqualTo(12L);
        assertThat(movie.getRuntimeMin()).isEqualTo(108);
        assertThat(movie.getSummary()).isEqualTo("原简介");
    }

    @Test void protectedDirectorAndPosterKeepTheirAssociatedResources() throws Exception {
        Movie movie = existing(); movie.setManualFieldKeys("dir,cat,posterUrl"); movie.setDir("人工导演");
        var snapshot = json.readValue("""
                {"id":99,"nm":"历史影片","source":"MAOYAN","dir":"新导演","cat":"动作","img":"https://source/new.jpg"}
                """,MaoyanMovieSnapshot.class);
        var fetched = new MainlandMovieFetcher.Fetched(snapshot,List.of(new MainlandMovieFetcher.Credit(2,"新导演",null,2,null)),List.of());
        new MainlandMovieWriter(importer,movies,people,crew).write(fetched);
        assertThat(movie.getDir()).isEqualTo("人工导演");
        verifyNoInteractions(crew,images,bindings,types,people);
    }

    @Test void repairRechecksCurrentRowAndOnlyFillsMissingValues() throws Exception {
        Movie movie = existing(); movie.setLanguage(null); movie.setManualFieldKeys("runtimeMin");
        var snapshot = json.readValue("""
                {"id":77,"nm":"其他译名","source":"TMDB","dur":180,"story":"替代简介","language":"汉语","rt":"2026-10-17","sc":9.9,"status":1}
                """,MaoyanMovieSnapshot.class);
        new MainlandMovieWriter(importer,movies,people,crew).repair(12L,new MainlandMovieFetcher.Fetched(snapshot,List.of(),List.of()));
        assertThat(movie.getLanguage()).isEqualTo("汉语");
        assertThat(movie.getSummary()).isEqualTo("原简介");
        assertThat(movie.getRuntimeMin()).isEqualTo(100);
        assertThat(movie.getReleaseDate()).isEqualTo(LocalDate.of(2000,1,2));
        assertThat(movie.getTitle()).isEqualTo("历史影片");
        assertThat(movie.getSource()).isEqualTo("MAOYAN");
        assertThat(movie.getMarketStatus()).isZero();
        assertThat(movie.getScore()).isNull();
        assertThat(movie.getTmdbId()).isEqualTo(77);
        assertThat(movie.getLastRepairAt()).isNotNull();
        verify(movies,never()).insert(any(Movie.class));
    }

    @Test void unlockingKeepsValueAndAllowsFutureSourceUpdates() throws Exception {
        Movie movie = existing(); movie.setManualFieldKeys("runtimeMin,summary");
        service.edit(12L,new MovieMetadataService.Edit(Map.of(),List.of("runtimeMin"),0));
        assertThat(movie.getRuntimeMin()).isEqualTo(100);
        assertThat(movie.getLockedFields()).containsExactly("summary");
        importer.importOne(json.readValue("{\"id\":99,\"nm\":\"历史影片\",\"dur\":120,\"story\":\"新简介\",\"source\":\"MAOYAN\"}",MaoyanMovieSnapshot.class));
        assertThat(movie.getRuntimeMin()).isEqualTo(120);
        assertThat(movie.getSummary()).isEqualTo("原简介");
    }

    @Test void rejectsStaleEditorInvalidRuntimeUnsupportedFieldsAndExternalPoster() {
        Movie movie = existing(); movie.setManualRevision(2);
        assertThatThrownBy(() -> service.edit(12L,new MovieMetadataService.Edit(Map.of("summary","补录"),List.of(),1)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("其他管理员");
        for (String runtime : List.of("0","-1","1.5","1441"))
            assertThatThrownBy(() -> service.edit(12L,new MovieMetadataService.Edit(Map.of("runtimeMin",runtime),List.of(),2)))
                    .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.edit(12L,new MovieMetadataService.Edit(Map.of("score","9.9"),List.of(),2)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.edit(12L,new MovieMetadataService.Edit(Map.of("posterUrl","https://external/poster.jpg"),List.of(),2)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("OSS");
        verify(movies,never()).updateById(any(Movie.class));
        assertThat(movie.getManualRevision()).isEqualTo(2);
    }
}
