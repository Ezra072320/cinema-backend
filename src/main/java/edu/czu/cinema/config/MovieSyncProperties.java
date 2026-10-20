package edu.czu.cinema.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 影片同步配置.
 *
 * <pre>
 * cinema:
 *   sync:
 *     upcoming-days: 60
 *     fresh-minutes: 15
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "cinema.sync")
public class MovieSyncProperties {

    /** 猫眼大陆待映片单；查询城市默认北京。 */
    private String maoyanBaseUrl = "https://m.maoyan.com";
    private int cityId = 1;
    private int upcomingDays = 60;
    private long requestIntervalMs = 1500;
    private int requestTimeoutMs = 15000;
    private int freshMinutes = 15;
    private String mainlandFeedPath = "data/mainland-movie-feed.json";
}
