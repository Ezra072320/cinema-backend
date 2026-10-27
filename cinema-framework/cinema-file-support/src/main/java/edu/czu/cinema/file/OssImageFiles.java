package edu.czu.cinema.file;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.configproperties.AliOssProperties;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 唯一存储实现。保留原 Bucket、日期目录、影片稳定 key、逐对象 public-read 及超时预算。 */
@Component
@EnableConfigurationProperties(AliOssProperties.class)
public class OssImageFiles implements ImageFiles {
  private final AliOssProperties properties;
  private final Supplier<OSS> clients;

  @Autowired
  public OssImageFiles(AliOssProperties properties) {
    this.properties = properties;
    this.clients = this::client;
  }

  // SDK 替身只用于模块内测试，业务调用者无需了解 OSS SDK。
  OssImageFiles(AliOssProperties properties, Supplier<OSS> clients) {
    this.properties = properties;
    this.clients = clients;
  }

  @Override
  public String uploadImage(byte[] bytes) {
    var type = ImageContent.requireSupported(bytes);
    String key =
        "cinema/"
            + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
            + "/"
            + UUID.randomUUID().toString().replace("-", "")
            + type.extension();
    OSS oss = clients.get();
    try {
      put(oss, key, bytes, type);
    } finally {
      oss.shutdown();
    }
    return publicUrl(key);
  }

  @Override
  public String storeMovieImage(MovieImageSource source, Supplier<byte[]> download) {
    // .image 及 20 位来源 hash 沿用存量规则，避免改造后重复存储或覆盖另一来源的海报。
    String key =
        "cinema/movies/"
            + source.kind().directory()
            + "/"
            + source.provider()
            + "-"
            + source.movieId()
            + "-"
            + hash(source.sourceUrl())
            + ".image";
    OSS oss = clients.get();
    try {
      if (!oss.doesObjectExist(properties.getBucketName(), key)) {
        byte[] bytes = download.get();
        put(oss, key, bytes, ImageContent.requireSupported(bytes));
      }
      return publicUrl(key);
    } finally {
      oss.shutdown();
    }
  }

  @Override
  public boolean owns(String url) {
    return url != null && url.startsWith(publicUrl(""));
  }

  private void put(OSS oss, String key, byte[] bytes, ImageContent.Type type) {
    var metadata = new ObjectMetadata();
    metadata.setContentType(type.contentType());
    metadata.setContentLength(bytes.length);
    metadata.setHeader("x-oss-object-acl", "public-read");
    oss.putObject(
        new PutObjectRequest(
            properties.getBucketName(), key, new ByteArrayInputStream(bytes), metadata));
  }

  private String publicUrl(String key) {
    return "https://" + properties.getBucketName() + "." + endpointHost() + "/" + key;
  }

  private String endpointHost() {
    String endpoint = properties.getEndpoint();
    if (endpoint == null || endpoint.isBlank()) throw new BusinessException("图片存储尚未配置");
    URI uri = URI.create(endpoint.contains("://") ? endpoint : "https://" + endpoint);
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getPort() != -1
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
      throw new BusinessException("OSS endpoint 配置不合法");
    return uri.getHost();
  }

  private OSS client() {
    if (properties.getAccessKeyId() == null
        || properties.getAccessKeyId().isBlank()
        || properties.getAccessKeySecret() == null
        || properties.getAccessKeySecret().isBlank()
        || properties.getBucketName() == null
        || properties.getBucketName().isBlank())
      throw new BusinessException("图片存储尚未配置，请联系管理员配置 OSS");
    var config = new ClientBuilderConfiguration();
    config.setConnectionTimeout(3000);
    config.setSocketTimeout(15000);
    config.setRequestTimeout(20000);
    config.setRequestTimeoutEnabled(true);
    config.setMaxErrorRetry(0);
    return new OSSClientBuilder()
        .build(
            "https://" + endpointHost(),
            properties.getAccessKeyId(),
            properties.getAccessKeySecret(),
            config);
  }

  private static String hash(String source) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 20);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
