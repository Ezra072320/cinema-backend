package edu.czu.cinema.domain.movie.sync;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.config.MovieSyncProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 只读取公开片单。非 2xx、验证码页、错误 JSON 都视为失败，绝不当影片使用。 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(MovieSyncProperties.class)
public class MaoyanClient {
    private final ObjectMapper json;
    private final MovieSyncProperties properties;
    private long lastRequest;

    public record Candidate(int id, String title, String releaseDate) {}
    public record FeedCache(long savedAt, int cityId, List<Candidate> movies) {}
    public record Picture(byte[] bytes, String contentType, String extension) {}

    public Picture downloadPicture(String url) {
        java.net.URI uri = java.net.URI.create(url);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null
                || !(host.endsWith(".pipi.cn") || host.endsWith(".meituan.net") || host.endsWith(".maoyan.com") || host.equals("image.tmdb.org"))) {
            throw new IllegalArgumentException("不是已知影片图床地址，未下载");
        }
        // P2：下载同样受并发限速器约束（与 get 共用 lastRequest 间隔），避免图片批量下载绕过限速。
        throttle();
        try (HttpResponse response = HttpRequest.get(url).header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://m.maoyan.com/").timeout(properties.getRequestTimeoutMs()).execute()) {
            if (!response.isOk()) throw new IllegalStateException("海报下载失败：HTTP " + response.getStatus());
            return picture(response.bodyBytes());
        }
    }

    /** 请求间隔限速：与上次请求至少相隔 requestIntervalMs。线程内串行，父方法已在同步块内。 */
    private void throttle() {
        long interval = Math.max(0, properties.getRequestIntervalMs());
        long wait = interval - (System.currentTimeMillis() - lastRequest);
        if (wait > 0) {
            try { Thread.sleep(wait); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("同步已中止", ex); }
        }
        lastRequest = System.currentTimeMillis();
    }

    static Picture picture(byte[] bytes) {
        try {
            var type = edu.czu.cinema.file.ImageContent.requireSupported(bytes);
            return new Picture(bytes, type.contentType(), type.extension().substring(1));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("图床没有返回有效图片，未上传 OSS", e);
        }
    }

    /** 大陆待映片单（只拉 comingList/moreComingList；热映榜全是已上映影片，insert-only 同步用不到）。 */
    public List<Candidate> currentMovies() {
        List<Candidate> cached = readCache();
        if (cached != null) return cached;
        JsonNode coming = get("/ajax/comingList?ci=" + properties.getCityId() + "&token=&limit=10");
        requireList(coming, "coming");
        if (!coming.path("movieIds").isArray()) {
            throw new IllegalStateException("猫眼片单缺少 movieIds，本次不更新影片");
        }
        Set<Integer> allIds = ids(coming.path("movieIds"));
        if (allIds.isEmpty()) throw new IllegalStateException("猫眼待映片单为空，保留现有影片");
        Map<Integer, JsonNode> rows = new LinkedHashMap<>();
        collect(rows, coming.path("coming"));
        List<Integer> remaining = allIds.stream().filter(id -> !rows.containsKey(id)).toList();
        for (int offset = 0; offset < remaining.size(); offset += 10) {
            List<Integer> batch = remaining.subList(offset, Math.min(offset + 10, remaining.size()));
            JsonNode more = get("/ajax/moreComingList?ci=" + properties.getCityId()
                    + "&token=&movieIds=" + batch.stream().map(String::valueOf).collect(Collectors.joining(",")));
            requireList(more, "coming");
            collect(rows, more.path("coming"));
        }
        // 不完整的分页不能当作完整待映片单使用。
        if (!rows.keySet().containsAll(allIds)) {
            throw new IllegalStateException("猫眼片单分页不完整，请稍后重试；现有影片未改变");
        }
        List<Candidate> result = allIds.stream().map(id -> {
            JsonNode row = rows.get(id);
            return new Candidate(id, row.path("nm").asText(""), row.path("rt").asText(""));
        }).toList();
        saveCache(result);
        return result;
    }

    private List<Candidate> readCache() {
        if (properties.getFreshMinutes() <= 0) return null;
        try {
            FeedCache cache = json.readValue(java.nio.file.Path.of(properties.getMainlandFeedPath()).toFile(), FeedCache.class);
            if (cache.cityId() == properties.getCityId() && !cache.movies().isEmpty()
                    && System.currentTimeMillis() - cache.savedAt() < properties.getFreshMinutes() * 60_000L) return cache.movies();
        } catch (Exception ex) { log.debug("猫眼片单缓存读取失败，重新拉取公开片单", ex); }
        return null;
    }
    private void saveCache(List<Candidate> movies) {
        if (properties.getFreshMinutes() <= 0) return;
        try {
            java.nio.file.Path path = java.nio.file.Path.of(properties.getMainlandFeedPath()).toAbsolutePath();
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            json.writeValue(temp.toFile(), new FeedCache(System.currentTimeMillis(), properties.getCityId(), movies));
            java.nio.file.Files.move(temp,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) { log.debug("猫眼片单缓存写失败，不影响本次已获取的真实片单", ex); }
    }

    public JsonNode detail(int movieId) {
        JsonNode movie = get("/ajax/detailmovie?movieId=" + movieId).path("detailMovie");
        if (!movie.isObject() || movie.path("id").asInt() != movieId || movie.path("nm").asText("").isBlank()) {
            throw new IllegalStateException("猫眼详情缺失或影片 ID 不匹配（" + movieId + "）");
        }
        return movie;
    }

    public JsonNode crew(int movieId) {
        JsonNode root = get("/mmdb/v7/movie/" + movieId + "/celebrities.json");
        requireList(root, "data");
        return root.path("data");
    }

    /** 幂等 GET 重试次数（首次请求不算；仅对瞬时失败重试，见 {@link #get})。 */
    private static final int MAX_RETRIES = 2;

    synchronized JsonNode get(String path) {
        // P1-M4：幂等 GET 对瞬时失败（连接异常/超时/5xx）退避重试；429/403/401 等限流鉴权仍保持
        // 「来源不可用→中止/切源」语义，绝不重复打被限流的端点。
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(backoff(attempt)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("同步已中止", ex); }
            }
            throttle();
            try {
                return getOnce(path);
            } catch (SourceUnavailableException ex) {
                throw ex; // 429/403/401：来源限制，中止不重试。
            } catch (RetryableFailure ex) {
                last = ex;
            }
        }
        throw last == null ? new IllegalStateException("读取猫眼公开资料失败") : last;
    }

    /** 带随机抖动的退避毫秒：第1次 500±250、第2次 1000±250。 */
    private static long backoff(int attempt) {
        long base = 500L << (attempt - 1);
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        return base / 2 + r.nextLong(base / 2 + 1);
    }

    /** 单次请求（不含重试）；测试可覆写以注入瞬时失败。 */
    JsonNode getOnce(String path) {
        String base = properties.getMaoyanBaseUrl().replaceAll("/+$", "");
        try (HttpResponse response = HttpRequest.get(base + path)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", base + "/")
                .timeout(properties.getRequestTimeoutMs()).execute()) {
            if (response.getStatus() == 429 || response.getStatus() == 403) {
                // 与调用方已有的「来源不可用→中止/切源」语义保持一致：限流不重试。
                throw new SourceUnavailableException("猫眼暂时限制访问，任务已停止，请稍后重试");
            }
            if (response.getStatus() >= 500) throw new RetryableFailure("猫眼服务端错误 HTTP " + response.getStatus());
            if (!response.isOk()) throw new IllegalStateException("猫眼 HTTP " + response.getStatus());
            JsonNode value = json.readTree(response.body());
            if (value == null || !value.isObject()) {
                throw new IllegalStateException("猫眼返回无效资料：" + path);
            }
            if (value.has("code") && value.path("code").asInt(-1) != 0) {
                String reason = value.path("msg").asText(value.path("message").asText("未提供该资源"));
                String lower = reason.toLowerCase(java.util.Locale.ROOT);
                if (value.path("code").asInt() == 429 || value.path("code").asInt() == 406 || reason.matches(".*(验证|频繁|限制|封禁|请求过多|太快|稍后再试).*" )
                        || lower.matches(".*(captcha|rate.limit|blocked|verify).*")) {
                    throw new SourceUnavailableException("猫眼暂时限制访问：" + reason);
                }
                throw new IllegalStateException("猫眼资源暂不可用（" + value.path("code").asText() + "）：" + reason + "；" + path);
            }
            return value;
        } catch (SourceUnavailableException ex) {
            throw ex;
        } catch (RetryableFailure ex) {
            throw ex;
        } catch (java.io.IOException ex) {
            // 连接/超时/IO 异常是瞬时故障，可重试；其 message 一般不含凭据。
            throw new RetryableFailure("猫眼请求失败（连接异常），可重试", ex);
        } catch (Exception ex) {
            // 其余（含 JSON 解析失败）不可重试，直接失败并携带根因。
            throw new IllegalStateException("读取猫眼公开资料失败：" + ex.getMessage(), ex);
        }
    }

    /** 瞬时失败标记（连接/超时/5xx），可安全重试；限流鉴权异常不得重试。 */
    static final class RetryableFailure extends IllegalStateException {
        RetryableFailure(String message) { super(message); }
        RetryableFailure(String message, Throwable cause) { super(message, cause); }
    }

    private static void requireList(JsonNode root, String name) {
        if (!root.path(name).isArray()) throw new SourceUnavailableException("猫眼响应缺少 " + name + "，未写入资料");
    }
    private static Set<Integer> ids(JsonNode array) {
        Set<Integer> result = new LinkedHashSet<>();
        for (JsonNode node : array) if (node.asInt() > 0) result.add(node.asInt());
        return result;
    }
    private static void collect(Map<Integer, JsonNode> rows, JsonNode array) {
        for (JsonNode row : array) if (row.path("id").asInt() > 0) rows.put(row.path("id").asInt(), row);
    }
    public static class SourceUnavailableException extends IllegalStateException {
        public SourceUnavailableException(String message) { super(message); }
    }
}
