package edu.czu.cinema.file;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.PutObjectRequest;
import edu.czu.cinema.configproperties.AliOssProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class OssImageFilesTest {
  private final OSS oss = mock(OSS.class);
  private final AliOssProperties properties = new AliOssProperties();
  private ImageFiles images;
  private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0};
  private static final MovieImageSource SOURCE =
      new MovieImageSource(
          "maoyan", 12, MovieImageSource.Kind.POSTER, "https://p0.pipi.cn/poster.jpg");

  @BeforeEach
  void setUp() {
    properties.setBucketName("existing-bucket");
    properties.setEndpoint("https://oss-cn-chengdu.aliyuncs.com/");
    images = new OssImageFiles(properties, () -> oss);
  }

  @Test
  void uploadKeepsBucketUrlAndServerGeneratedNameAndMetadata() throws Exception {
    String url = images.uploadImage(PNG);
    var request = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(oss).putObject(request.capture());
    var stored = request.getValue();
    assertThat(stored.getBucketName()).isEqualTo("existing-bucket");
    assertThat(stored.getKey()).matches("cinema/\\d{4}/\\d{2}/\\d{2}/[a-f0-9]{32}\\.png");
    assertThat(stored.getInputStream().readAllBytes()).isEqualTo(PNG);
    assertThat(stored.getMetadata().getContentType()).isEqualTo("image/png");
    assertThat(stored.getMetadata().getContentLength()).isEqualTo(PNG.length);
    assertThat(stored.getMetadata().getRawMetadata())
        .containsEntry("x-oss-object-acl", "public-read");
    assertThat(url)
        .isEqualTo("https://existing-bucket.oss-cn-chengdu.aliyuncs.com/" + stored.getKey());
    verify(oss).shutdown();
  }

  @Test
  void rejectsUnsupportedOrOversizedContentBeforeOpeningOss() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> images.uploadImage("<html>hello</html>".getBytes()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> images.uploadImage(new byte[ImageContent.MAX_BYTES + 1]));
    assertThatIllegalArgumentException().isThrownBy(() -> images.uploadImage(null));
    verifyNoInteractions(oss);
  }

  @Test
  void repeatMovieTransferReusesTheLegacyKeyAndSkipsDownload() {
    when(oss.doesObjectExist(anyString(), anyString())).thenReturn(false, true);
    String first = images.storeMovieImage(SOURCE, () -> PNG);
    String second =
        images.storeMovieImage(
            SOURCE,
            () -> {
              throw new AssertionError("already exists");
            });
    assertThat(second).isEqualTo(first);
        assertThat(first)
                .isEqualTo("https://existing-bucket.oss-cn-chengdu.aliyuncs.com/cinema/movies/posters/maoyan-12-19c18b0849f5cc147f14.image");
    verify(oss, times(1)).putObject(any(PutObjectRequest.class));
    verify(oss, times(2)).shutdown();
  }

  @Test
  void sourcesAndImageKindsKeepDifferentKeys() {
    String poster = images.storeMovieImage(SOURCE, () -> PNG);
    String tmdb =
        images.storeMovieImage(
            new MovieImageSource("tmdb", 12, MovieImageSource.Kind.POSTER, SOURCE.sourceUrl()),
            () -> PNG);
    String still =
        images.storeMovieImage(
            new MovieImageSource("maoyan", 12, MovieImageSource.Kind.STILL, SOURCE.sourceUrl()),
            () -> PNG);
    assertThat(poster).isNotEqualTo(tmdb).isNotEqualTo(still);
    assertThat(tmdb).contains("/posters/tmdb-12-");
    assertThat(still).contains("/stills/maoyan-12-");
  }

  @Test
  void downloadValidationAndStorageFailuresDoNotReturnAWorkingUrlAndAlwaysCloseClient() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> images.storeMovieImage(SOURCE, () -> new byte[20]));
    when(oss.putObject(any(PutObjectRequest.class)))
        .thenThrow(new IllegalStateException("upload failed"));
    assertThatThrownBy(() -> images.storeMovieImage(SOURCE, () -> PNG)).hasMessage("upload failed");
    verify(oss, times(2)).shutdown();
  }

  @Test
  void existenceFailureClosesClientWithoutDownloading() {
    when(oss.doesObjectExist(anyString(), anyString()))
        .thenThrow(new IllegalStateException("query failed"));
    assertThatThrownBy(
            () ->
                images.storeMovieImage(
                    SOURCE,
                    () -> {
                      throw new AssertionError("must not download");
                    }))
        .hasMessage("query failed");
    verify(oss).shutdown();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "oss-cn-chengdu.aliyuncs.com",
        "https://oss-cn-chengdu.aliyuncs.com/",
        "http://oss-cn-chengdu.aliyuncs.com"
      })
  void oldConfiguredEndpointFormsKeepTheSameHttpsUrl(String endpoint) {
    properties.setEndpoint(endpoint);
    assertThat(images.owns("https://existing-bucket.oss-cn-chengdu.aliyuncs.com/cinema/old.jpg"))
        .isTrue();
    assertThat(
            images.owns(
                "https://existing-bucket.oss-cn-chengdu.aliyuncs.com.attacker.example/cinema/old.jpg"))
        .isFalse();
    assertThat(images.owns(null)).isFalse();
  }
}
