package edu.czu.cinema.domain.movie.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.common.util.AliOssUtil;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MovieMediaStoreTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MaoyanClient client = mock(MaoyanClient.class);
    private final AliOssUtil oss = mock(AliOssUtil.class);
    private final TmdbClient tmdb = mock(TmdbClient.class);

    @Test void databaseReceivesOssPosterAndRepeatedTransferUsesTheExistingObject() {
        when(oss.publicUrl(anyString())).thenAnswer(call -> "https://bucket.oss-cn-chengdu.aliyuncs.com/" + call.getArgument(0));
        when(oss.objectExists(anyString())).thenReturn(false, true);
        when(client.downloadPicture(anyString())).thenReturn(new MaoyanClient.Picture(new byte[20],"image/jpeg","jpg"));
        when(oss.uploadBytes(any(),anyString(),anyString())).thenAnswer(call -> oss.publicUrl(call.getArgument(1)));
        var store = new MovieMediaStore(client,oss,json,tmdb);
        String url = "https://p0.pipi.cn/poster.jpg";
        String first = store.transferPoster(url,12);
        assertThat(first).startsWith("https://bucket.oss-cn-chengdu.aliyuncs.com/cinema/movies/posters/maoyan-12-");
        assertThat(store.transferPoster(url,12)).isEqualTo(first);
        verify(client,times(1)).downloadPicture(url);
        verify(oss,times(1)).uploadBytes(any(),anyString(),eq("image/jpeg"));
    }

    @Test void failedUploadNeverReturnsTheSourceUrlAsAWorkingPoster() {
        when(oss.publicUrl("")).thenReturn("https://bucket.oss-cn-chengdu.aliyuncs.com/");
        when(client.downloadPicture(anyString())).thenReturn(new MaoyanClient.Picture(new byte[20],"image/jpeg","jpg"));
        when(oss.uploadBytes(any(),anyString(),anyString())).thenThrow(new IllegalStateException("OSS 不可用"));
        var store = new MovieMediaStore(client,oss,json,tmdb);
        assertThatThrownBy(() -> store.transferPoster("https://p0.pipi.cn/poster.jpg",12)).hasMessageContaining("OSS 不可用");
    }

    @Test void tmdbMediaKeepsProviderNamespaceAndIdentityThroughOssTransfer() throws Exception {
        when(oss.publicUrl(anyString())).thenAnswer(call -> "https://bucket.oss-cn-chengdu.aliyuncs.com/"+call.getArgument(0));
        when(tmdb.downloadPicture(anyString())).thenReturn(new MaoyanClient.Picture(new byte[20],"image/jpeg","jpg"));
        when(oss.uploadBytes(any(),anyString(),anyString())).thenAnswer(call -> oss.publicUrl(call.getArgument(1)));
        var snapshot = json.readValue("{\"id\":12,\"nm\":\"影片\",\"source\":\"TMDB\",\"img\":\"https://image.tmdb.org/t/p/w500/a.jpg\"}",
                edu.czu.cinema.domain.movie.seed.MaoyanMovieSnapshot.class);
        var result = new MovieMediaStore(client,oss,json,tmdb).prepare(new MainlandMovieFetcher.Fetched(snapshot,List.of(),List.of()));
        assertThat(result.snapshot().img()).contains("/tmdb-12-");
        assertThat(result.tmdbId()).isEqualTo(12);
        assertThat(result.maoyanId()).isNull();
    }
}
