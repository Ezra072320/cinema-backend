package edu.czu.cinema.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 本地缓存配置（单实例部署假设）.
 * <p>
 * 只缓存三类热点读数据：
 * <ul>
 *   <li>{@code movieInfo}：影片详情（一次组装约 10 条查询）</li>
 *   <li>{@code seatLabels}：座位版本标签（版本冻结后基本不变）</li>
 *   <li>{@code statOverview}：管理概览聚合（多条重量级 SQL）</li>
 * </ul>
 * 订单、票、余座等强一致数据**不缓存**。60 秒 TTL 自愈，避免为每次写入
 * 维护复杂的 evict 依赖；管理端修改后最迟 1 分钟内可见。
 * 多实例部署时需换成 Redis 缓存并统一失效策略。
 * </p>
 */
@Configuration
public class CacheConfig {

    public static final String MOVIE_INFO = "movieInfo";
    public static final String SEAT_LABELS = "seatLabels";
    public static final String STAT_OVERVIEW = "statOverview";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(MOVIE_INFO, SEAT_LABELS, STAT_OVERVIEW);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(10_000));
        return manager;
    }
}
