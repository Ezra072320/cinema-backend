package edu.czu.cinema.domain.movie.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.config.MovieSyncProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaoyanClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private MovieSyncProperties properties() { var properties = new MovieSyncProperties(); properties.setFreshMinutes(0); return properties; }

    @Test void fetchesAllComingPagesAndDeduplicatesPresaleFilms() throws Exception {
        var client = spy(new MaoyanClient(json, properties()));
        doReturn(json.readTree("{\"movieIds\":[2,3],\"coming\":[{\"id\":2,\"nm\":\"B\",\"rt\":\"2026-10-20\"}]}"))
                .when(client).get(startsWith("/ajax/comingList"));
        doReturn(json.readTree("{\"coming\":[{\"id\":3,\"nm\":\"C\",\"rt\":\"2026-11-01\"},{\"id\":2,\"nm\":\"B\",\"rt\":\"2026-10-20\"}]}"))
                .when(client).get(startsWith("/ajax/moreComingList"));
        assertThat(client.currentMovies()).extracting(MaoyanClient.Candidate::id).containsExactly(2, 3);
    }

    @Test void incompleteFeedCannotSilentlyDropPendingFilms() throws Exception {
        var client = spy(new MaoyanClient(json, properties()));
        doReturn(json.readTree("{\"movieIds\":[1,2],\"coming\":[{\"id\":1}]}"))
                .when(client).get(startsWith("/ajax/comingList"));
        doReturn(json.readTree("{\"coming\":[]}"))
                .when(client).get(startsWith("/ajax/moreComingList"));
        assertThatThrownBy(client::currentMovies).hasMessageContaining("分页不完整");
    }

    @Test void emptyComingListIsNotTreatedAsCompleteFeed() throws Exception {
        var client = spy(new MaoyanClient(json, properties()));
        doReturn(json.readTree("{\"movieIds\":[],\"coming\":[]}")).when(client).get(startsWith("/ajax/comingList"));
        assertThatThrownBy(client::currentMovies).hasMessageContaining("待映片单为空");
    }

    @Test void transientFailureIsRetriedUntilSuccess() throws Exception {
        var client = spy(new MaoyanClient(json, properties()));
        // 注入瞬时失败（RetryableFailure），第二次成功：get() 内应退避重试。
        // 注意 doThrow().doReturn() 的顺序：第一次抛，第二次返回。
        doThrow(new MaoyanClient.RetryableFailure("connect reset"))
                .doReturn(json.readTree("{\"ok\":true}"))
                .when(client).getOnce("/ajax/detailmovie?movieId=1");
        assertThat(client.get("/ajax/detailmovie?movieId=1").path("ok").asBoolean()).isTrue();
        verify(client, times(2)).getOnce("/ajax/detailmovie?movieId=1");
    }

    @Test void rateLimitUnavailableIsNotRetried() throws Exception {
        var client = spy(new MaoyanClient(json, properties()));
        doThrow(new MaoyanClient.SourceUnavailableException("猫眼限制")).when(client).getOnce("/ajax/coming");
        assertThatThrownBy(() -> client.get("/ajax/coming")).isInstanceOf(MaoyanClient.SourceUnavailableException.class);
        verify(client, times(1)).getOnce("/ajax/coming"); // 限流不重试
    }
}
