package edu.czu.cinema.configproperties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "aliyun.oss")
public class AliOssProperties {
  private String accessKeyId;
  private String accessKeySecret;
  private String endpoint;
  private String bucketName;
}
