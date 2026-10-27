package edu.czu.cinema.config;

import java.util.Arrays;
import org.springframework.util.AntPathMatcher;

/** JWT-exempt routes; internal routes still require the separate service-key filter. */
public final class JwtOptionalPaths {
  private static final String[] PATTERNS = {
    "/internal/**",
    "/api/public/**",
    "/api/search/movies",
    "/api/captcha/**",
    "/api/user/login",
    "/api/user/register",
    "/api/admin/login",
    "/api/movie/loadMainPage",
    "/api/movie/topSales",
    "/api/movie/topBoxOffice",
    "/api/movie/list",
    "/api/movie/list/**",
    "/api/movie/info/**",
    "/api/movie/*/images",
    "/api/movie/*/videos",
    "/api/movie/*/crew",
    "/api/movie/*/reviews",
    "/api/hall/list",
    "/api/schedule/byHall/**",
    "/api/schedule/*/seats",
    "/actuator/health",
    "/actuator/health/**",
    "/actuator/prometheus",
    "/favicon.ico",
    "/error"
  };

  private JwtOptionalPaths() {}

  public static String[] patterns() {
    return PATTERNS.clone();
  }

  public static boolean publicApi(String path) {
    return path.startsWith("/api/")
        && Arrays.stream(PATTERNS).anyMatch(pattern -> new AntPathMatcher().match(pattern, path));
  }
}
