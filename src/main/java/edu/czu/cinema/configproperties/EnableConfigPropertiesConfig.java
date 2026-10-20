package edu.czu.cinema.configproperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 ConfigurationProperties 类的简洁入口.
 */
@Configuration
@EnableConfigurationProperties({
        AliOssProperties.class,
        WeChatProperties.class
})
public class EnableConfigPropertiesConfig {
}
