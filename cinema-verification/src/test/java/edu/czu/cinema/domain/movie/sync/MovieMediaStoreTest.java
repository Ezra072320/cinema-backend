package edu.czu.cinema.domain.movie.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.file.ImageFiles;
import edu.czu.cinema.file.MovieImageSource;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class MovieMediaStoreTest {
  private final ObjectMapper json = new ObjectMapper();
  private final MaoyanClient client = mock(MaoyanClient.class);
  private final ImageFiles images = mock(ImageFiles.class);
  private final TmdbClient tmdb = mock(TmdbClient.class);
  private static final String STORED =
      "https://bucket.oss-cn-chengdu.aliyuncs.com/cinema/movies/posters/example.image";

  @Test
  void callerUsesFileBusinessInterfaceAndDoesNotRedownloadAnOssUrl() {
    when(images.owns(STORED)).thenReturn(true);
    when(images.storeMovieImage(any(), any())).thenReturn(STORED);
    var store = new MovieMediaStore(client, images, json, tmdb);
    String first = store.transferPoster("https://p0.pipi.cn/poster.jpg", 12);
    assertThat(first).isEqualTo(STORED);
    assertThat(store.transferPoster(first, 12)).isEqualTo(first);
    verify(images)
        .storeMovieImage(
            eq(
                new MovieImageSource(
                    "maoyan", 12, MovieImageSource.Kind.POSTER, "https://p0.pipi.cn/poster.jpg")),
            any());
    verifyNoInteractions(client, tmdb);
  }

  @Test
  void failedUploadNeverReturnsTheSourceUrlAsAWorkingPoster() {
    when(images.storeMovieImage(any(), any())).thenThrow(new IllegalStateException("OSS 不可用"));
    var store = new MovieMediaStore(client, images, json, tmdb);
    assertThatThrownBy(() -> store.transferPoster("https://p0.pipi.cn/poster.jpg", 12))
        .hasMessageContaining("OSS 不可用");
  }

  @Test
  void tmdbMediaKeepsProviderNamespaceAndIdentityThroughOssTransfer() throws Exception {
    when(images.owns(STORED)).thenReturn(true);
    when(tmdb.downloadPicture(anyString()))
        .thenReturn(new MaoyanClient.Picture(new byte[20], "image/jpeg", "jpg"));
    when(images.storeMovieImage(any(), any()))
        .thenAnswer(
            call -> {
              MovieImageSource source = call.getArgument(0);
              assertThat(source.provider()).isEqualTo("tmdb");
              assertThat(source.movieId()).isEqualTo(12);
              Supplier<byte[]> download = call.getArgument(1);
              assertThat(download.get()).hasSize(20);
              return STORED;
            });
    var snapshot =
        json.readValue(
            "{\"id\":12,\"nm\":\"影片\",\"source\":\"TMDB\",\"img\":\"https://image.tmdb.org/t/p/w500/a.jpg\"}",
            edu.czu.cinema.domain.movie.seed.MaoyanMovieSnapshot.class);
    var result =
        new MovieMediaStore(client, images, json, tmdb)
            .prepare(new MainlandMovieFetcher.Fetched(snapshot, List.of(), List.of()));
    assertThat(result.snapshot().img()).isEqualTo(STORED);
    assertThat(result.tmdbId()).isEqualTo(12);
    assertThat(result.maoyanId()).isNull();
    verify(tmdb).downloadPicture("https://image.tmdb.org/t/p/w500/a.jpg");
    verifyNoInteractions(client);
  }
}
