package edu.czu.cinema.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.api.ResultCode;

import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spring Security 6 配置.
 *
 * <p>保留原项目"按 URL 前缀 + 角色判定"的鉴权思路，配置声明更简洁： /api/user/** 与 /api/admin/** 走 JWT，/api/public/** 完全开放。
 * 接口文档路径仅在 {@code cinema.docs.enabled=true} 时放行（prod 关闭）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] DOCUMENT_PATHS = {"/doc.html", "/swagger-ui/**", "/swagger-ui.html", "/webjars/**", "/v3/api-docs/**"};

    private final JwtAuthenticationFilter jwtFilter;
    private final ObjectMapper objectMapper;

    @Value("${cinema.docs.enabled:false}")
    private boolean docsEnabled;
    @Value("${cinema.metrics.enabled:false}")
    private boolean metricsEnabled;
    @Value("${cinema.metrics.key:}")
    private String metricsKey;

    @Value(
            "${cinema.cors.allowed-origin-patterns:http://localhost:5173,http://127.0.0.1:5173,http://localhost:8088,http://127.0.0.1:8088}")
    private String[] corsAllowedOriginPatterns;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(c -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        reg -> {
                            // ASYNC dispatch 不是新请求, 是初始请求的延续 —— 初始请求已鉴权过.
                            // 不放行会出大事: SSE/流式响应(Flux + SseEmitter)完成时 Tomcat 会做一次
                            // ASYNC dispatch 重新走过滤器链, 而 JwtAuthenticationFilter 继承
                            // OncePerRequestFilter **默认跳过 async dispatch**, 那一跳里没有
                            // Authentication → authenticated() 抛 AuthorizationDeniedException,
                            // 响应早已提交, Tomcat 只能掐断连接 —— 客户端表现为"内容全到了但流收尾
                            // 必炸(IncompleteRead/网络错误)". AI 客服每条回复都因此报"对话出错".
                            reg.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC)
                                    .permitAll()
                                    // 公共接口
                                    .requestMatchers(edu.czu.cinema.config.JwtOptionalPaths.patterns())
                                    .permitAll();
                            // 接口文档仅在显式开启时放行（prod 默认关闭，避免未授权测绘 API 面）
                            if (docsEnabled) {
                                reg.requestMatchers(DOCUMENT_PATHS).permitAll();
                            } else {
                                reg.requestMatchers(DOCUMENT_PATHS).denyAll();
                            }
                            // A separate filter requires the machine key even for administrators.
                            reg.requestMatchers("/actuator/prometheus", "/actuator/prometheus/**").permitAll();
                            // 角色隔离
                            reg.requestMatchers("/api/admin/**", "/api/statistic/**")
                                    .access(new AdminRequestAuthorization())
                                    .requestMatchers("/api/user/**")
                                    .hasRole("USER")
                                    .requestMatchers("/api/ai/**")
                                    .hasRole("USER")
                                    .anyRequest()
                                    .authenticated();
                        })
                .exceptionHandling(
                        eh ->
                                eh.authenticationEntryPoint(
                                                (req, resp, ex) ->
                                                        writeError(
                                                                resp,
                                                                HttpServletResponse.SC_UNAUTHORIZED,
                                                                ResultCode.UNAUTHORIZED))
                                        .accessDeniedHandler(
                                                (req, resp, ex) ->
                                                        writeError(
                                                                resp,
                                                                HttpServletResponse.SC_FORBIDDEN,
                                                                ResultCode.FORBIDDEN)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new MetricsEndpointFilter(metricsEnabled,metricsKey),JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration cfg = new CorsConfiguration();
        // 本地 Vite/容器入口使用明确白名单，生产通过环境变量替换为实际域名。
        cfg.setAllowedOriginPatterns(List.of(corsAllowedOriginPatterns));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization", "X-Trace-Id"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return new CorsFilter(source);
    }

    private void writeError(HttpServletResponse resp, int status, ResultCode code)
            throws java.io.IOException {
        resp.setStatus(status);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(objectMapper.writeValueAsString(R.fail(code, code.getMsg())));
    }
}
