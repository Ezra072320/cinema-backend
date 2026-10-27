package edu.czu.cinema.notify;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.server.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocket
public class NotificationSocketConfig implements WebSocketConfigurer {
  private final NotificationSocket socket;
  private final StringRedisTemplate redis;
  private final String[] origins;

  public NotificationSocketConfig(
      NotificationSocket socket,
      StringRedisTemplate redis,
      @Value("${cinema.cors.allowed-origin-patterns}") String[] origins) {
    this.socket = socket;
    this.redis = redis;
    this.origins = origins;
  }

  @Bean
  @Order(1)
  SecurityFilterChain socketHandshakeSecurity(HttpSecurity http) throws Exception {
    // Browsers cannot set Authorization during a native WebSocket handshake.
    // Only this exact path uses the one-time ticket interceptor; REST retains JWT authentication.
    return http.securityMatcher("/api/notify/socket")
        .csrf(c -> c.disable())
        .authorizeHttpRequests(r -> r.anyRequest().permitAll())
        .build();
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(socket, "/api/notify/socket")
        .setAllowedOriginPatterns(origins)
        .addInterceptors(
            new HandshakeInterceptor() {
              public boolean beforeHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler handler,
                  Map<String, Object> attributes) {
                String query = request.getURI().getRawQuery();
                if (query == null || !query.matches("ticket=[A-Za-z0-9_-]{43}")) {
                  response.setStatusCode(HttpStatus.FORBIDDEN);
                  return false;
                }
                String userId =
                    redis.opsForValue().getAndDelete("notify:socket:" + query.substring(7));
                if (userId == null || !userId.matches("[1-9][0-9]{0,18}")) {
                  response.setStatusCode(HttpStatus.FORBIDDEN);
                  return false;
                }
                attributes.put("userId", Long.valueOf(userId));
                return true;
              }

              public void afterHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler handler,
                  Exception exception) {}
            });
  }

  @RestController
  public static class TicketController {
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public TicketController(StringRedisTemplate redis) {
      this.redis = redis;
    }

    @PostMapping("/api/notify/socket-ticket")
    public R<String> ticket() {
      Long userId = SecurityUtils.requireUserId();
      byte[] bytes = new byte[32];
      random.nextBytes(bytes);
      String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      redis.opsForValue().set("notify:socket:" + ticket, userId.toString(), Duration.ofSeconds(30));
      return R.ok(ticket);
    }
  }
}
