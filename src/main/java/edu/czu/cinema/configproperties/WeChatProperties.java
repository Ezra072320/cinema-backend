package edu.czu.cinema.configproperties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "wechat")
public class WeChatProperties {
    private String appid;
    private String secret;
}
