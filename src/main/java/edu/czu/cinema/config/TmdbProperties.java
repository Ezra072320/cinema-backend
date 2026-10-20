package edu.czu.cinema.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 凭据只由后端读取，不生成包含凭据的 toString。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "cinema.tmdb")
public class TmdbProperties {
    private String readAccessToken = "";
    private String apiKey = "";
    private String baseUrl = "https://api.themoviedb.org/3";
    private String proxyUrl = "";
    private int timeoutMs = 15000;
    private long requestIntervalMs = 300;

    public boolean configured() {
        return (readAccessToken != null && !readAccessToken.isBlank()) || (apiKey != null && !apiKey.isBlank());
    }
}
