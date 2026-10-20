package edu.czu.cinema.domain.movie.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.config.TmdbProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 官方 TMDB v3 API。异常信息不携带请求 URL、请求头或凭据。 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(TmdbProperties.class)
public class TmdbClient {
    private final ObjectMapper json;
    private final TmdbProperties properties;
    private java.net.http.HttpClient http;
    private long lastRequest;

    public boolean configured() { return properties.configured(); }

    /** 幂等 GET 重试次数（不含首次）。 */
    private static final int MAX_RETRIES = 2;

    synchronized JsonNode get(String path, Map<String,String> parameters) {
        if (!configured()) throw new IllegalStateException("未配置 TMDB 后端凭据");
        // P1-M4：幂等 GET 对瞬时失败（连接异常/超时/5xx）退避重试；401/403/429 鉴权限流不重试，保持「来源不可用」语义。
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(backoff(attempt)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("同步已中止"); }
            }
            throttle();
            try {
                return getOnce(path, parameters);
            } catch (MaoyanClient.SourceUnavailableException ex) {
                throw ex;
            } catch (RetryableFailure ex) {
                last = ex;
            }
        }
        throw last == null ? new IllegalStateException("TMDB 请求失败") : last;
    }

    /** 带随机抖动的退避毫秒：第1次 500±250、第2次 1000±250。 */
    private static long backoff(int attempt) {
        long base = 500L << (attempt - 1);
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        return base / 2 + r.nextLong(base / 2 + 1);
    }

    private void throttle() {
        long pause = properties.getRequestIntervalMs() - (System.currentTimeMillis() - lastRequest);
        if (pause > 0) {
            try { Thread.sleep(pause); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("同步已中止"); }
        }
        lastRequest = System.currentTimeMillis();
    }

    private JsonNode getOnce(String path, Map<String,String> parameters) {
        Map<String,String> query = new LinkedHashMap<>(parameters);
        query.putIfAbsent("language", "zh-CN");
        boolean bearer = properties.getReadAccessToken() != null && !properties.getReadAccessToken().isBlank();
        if (!bearer) query.put("api_key", properties.getApiKey());
        String encoded = query.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        var requestBuilder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        properties.getBaseUrl().replaceAll("/+$", "") + path + "?" + encoded))
                .header("accept", "application/json").header("User-Agent","CinemaServer/0.1")
                .timeout(java.time.Duration.ofMillis(properties.getTimeoutMs())).GET();
        if (bearer) requestBuilder.header("Authorization", "Bearer " + properties.getReadAccessToken().trim());
        try {
            var response = http().send(requestBuilder.build(),java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new MaoyanClient.SourceUnavailableException("TMDB 鉴权失败，请检查后端凭据");
            if (response.statusCode() == 429)
                throw new MaoyanClient.SourceUnavailableException("TMDB 请求受限，请稍后重试");
            if (response.statusCode() >= 500) throw new RetryableFailure("TMDB 服务端错误 HTTP " + response.statusCode());
            if (response.statusCode()<200 || response.statusCode()>=300) throw new IllegalStateException("TMDB 返回 HTTP " + response.statusCode());
            JsonNode result = json.readTree(response.body());
            if (result == null || !result.isObject() || (result.has("success") && !result.path("success").asBoolean()))
                throw new IllegalStateException("TMDB 返回无效资料");
            return result;
        } catch (MaoyanClient.SourceUnavailableException ex) {
            throw ex;
        } catch (RetryableFailure ex) {
            throw ex;
        } catch (java.io.IOException ex) {
            // 连接/超时/HttpTimeoutException(IOException 子类) 皆是瞬时故障，可重试；message 不含 api_key。
            throw new RetryableFailure("TMDB 网络请求失败（可重试）", ex);
        } catch (Exception ex) {
            // 不拼接原始异常，部分 HTTP 客户端异常可能包含带 api_key 的 URL。
            Throwable reason = ex;
            while (reason.getCause()!=null && reason.getCause()!=reason) reason=reason.getCause();
            throw new IllegalStateException("TMDB 请求失败（"+reason.getClass().getSimpleName()+"），请检查网络及服务状态");
        }
    }

    /** 瞬时失败标记（网络/超时/5xx），可安全重试；鉴权限流异常不得重试。 */
    private static final class RetryableFailure extends IllegalStateException {
        RetryableFailure(String message) { super(message); }
        RetryableFailure(String message, Throwable cause) { super(message, cause); }
    }

    /** 图片请求不携带 API 凭据；API 与图片共用用户配置的代理。 */
    public MaoyanClient.Picture downloadPicture(String url) {
        var uri = java.net.URI.create(url);
        if (!"https".equals(uri.getScheme()) || !"image.tmdb.org".equals(uri.getHost()))
            throw new IllegalArgumentException("不是 TMDB 官方图片地址");
        try {
            var request = java.net.http.HttpRequest.newBuilder(uri).GET()
                    .timeout(java.time.Duration.ofMillis(properties.getTimeoutMs())).build();
            var response = http().send(request,java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode()!=200) throw new IllegalStateException("图片 HTTP "+response.statusCode());
            return MaoyanClient.picture(response.body());
        } catch (Exception ex) {
            throw new IllegalStateException("TMDB 图片下载失败（"+ex.getClass().getSimpleName()+"）");
        }
    }

    private synchronized java.net.http.HttpClient http() {
        if (http == null) {
            var builder = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15));
            if (properties.getProxyUrl()!=null && !properties.getProxyUrl().isBlank()) {
                var proxy = java.net.URI.create(properties.getProxyUrl());
                if (!"http".equals(proxy.getScheme()) || proxy.getHost()==null || proxy.getPort()<1)
                    throw new IllegalStateException("TMDB 代理需配置为 http://主机:端口");
                builder.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(proxy.getHost(),proxy.getPort())));
            }
            http=builder.build();
        }
        return http;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
