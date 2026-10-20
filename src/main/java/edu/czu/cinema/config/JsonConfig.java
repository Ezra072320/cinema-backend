package edu.czu.cinema.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置.
 * <p>
 * 不自定义 ObjectMapper Bean——Boot 自动配置的 mapper 默认已关闭
 * FAIL_ON_UNKNOWN_PROPERTIES 与 WRITE_DATES_AS_TIMESTAMPS；此前的 @Primary
 * 自定义 mapper 把这两项又打开了（前端多传一个字段即报错、java.time 被序列化成数组）。
 * 日期格式沿用 application.yml 的 spring.jackson 配置 + 字段级 @JsonFormat。
 * </p>
 */
@Configuration
public class JsonConfig {

    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer customizer() {
        return builder -> builder
                .simpleDateFormat(PATTERN)
                .modulesToInstall(new JavaTimeModule())
                .featuresToDisable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
