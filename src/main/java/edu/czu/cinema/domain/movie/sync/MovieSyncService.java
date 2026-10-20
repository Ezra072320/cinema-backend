package edu.czu.cinema.domain.movie.sync;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.czu.cinema.config.MovieSyncProperties;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.mapper.MovieMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 只新增大陆待映影片：上映日 &gt; 今天且库中没有的才入库，已有影片一概不修改（补缺走 repair）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieSyncService {
    private final MovieReleaseSource source;
    private final MainlandMovieWriter writer;
    private final MovieMediaStore media;
    private final MovieMapper movies;
    private final MovieSyncProperties properties;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mainland-movie-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final Object lock = new Object();
    private boolean running;
    private int total, done, ok, fail, created, updated, skipped, partial;
    private String current, message;
    private String state = "IDLE";
    private String mode = "SYNC";
    private long startedAt, finishedAt;
    /** P2：results 限长，仅保留最近 {@code MAX_RESULTS} 条（总数由 done 计数反映），防进度接口无限膨胀。 */
    private static final int MAX_RESULTS = 200;
    private final List<MovieSyncStatus.Item> results = new ArrayList<>();

    private void addResult(MovieSyncStatus.Item item) {
        synchronized (lock) {
            results.add(item);
            if (results.size() > MAX_RESULTS) results.remove(0);
        }
    }

    /** 默认处理完整当前片单；小批核验不会调整未处理影片的市场状态。 */
    public boolean start(Integer limit) {
        return startTask(limit,false);
    }

    public boolean startRepair(Integer limit) { return startTask(limit,true); }

    private boolean startTask(Integer limit, boolean repair) {
        if (limit != null && (limit < 1 || limit > 500)) throw new IllegalArgumentException("batchSize 需为 1~500");
        synchronized (lock) {
            if (running) return false;
            running = true;
            total = done = ok = fail = created = updated = skipped = partial = 0;
            current = null;
            mode = repair ? "REPAIR" : "SYNC";
            message = repair ? "正在扫描全片库缺失资料（包括历史影片）" : "正在获取未来 " + properties.getUpcomingDays() + " 天的大陆待映片单，仅新增库中没有的影片";
            state = repair ? "REPAIRING" : "DISCOVERING";
            startedAt = System.currentTimeMillis();
            finishedAt = 0;
            results.clear();
        }
        try { executor.execute(() -> { if (repair) runRepair(limit); else run(limit); }); }
        catch (RuntimeException ex) {
            synchronized (lock) { running = false; state = "FAILED"; message = "任务启动失败"; }
            throw ex;
        }
        return true;
    }

    public MovieSyncStatus status() {
        synchronized (lock) {
            return new MovieSyncStatus(running, total, done, ok, fail, current, startedAt, finishedAt,
                    List.copyOf(results), created, updated, skipped, partial, Math.max(0, total - done), message, state, mode);
        }
    }

    private void runRepair(Integer limit) {
        try {
            source.beginRepair();
            var targets = movies.selectList(null).stream().filter(m -> !m.getMissingFields().isEmpty())
                    .sorted(Comparator.comparing(Movie::getLastRepairAt,Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(Movie::getId)).toList();
            if (limit != null && targets.size() > limit) targets = targets.subList(0,limit);
            synchronized (lock) { total = targets.size(); message = "正在逐部补缺；已有资料和人工确认字段保持原值"; }
            for (Movie movie : targets) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("任务已中止");
                synchronized (lock) { current = movie.getTitle(); }
                try {
                    // 包括未匹配、失败的尝试都记录时间，下一次优先处理尚未尝试的影片。
                    movies.update(null,new LambdaUpdateWrapper<Movie>().eq(Movie::getId,movie.getId())
                            .set(Movie::getLastRepairAt,java.time.LocalDateTime.now()));
                    var result = writer.repair(movie.getId(),media.prepareRepair(source.fetchMissing(movie),movie));
                    boolean improved = result.missingFields().size() < movie.getMissingFields().size();
                    synchronized (lock) {
                        ok++; done++;
                        if (improved) updated++; else skipped++;
                        if (!result.missingFields().isEmpty()) partial++;
                        String detail = result.missingFields().isEmpty() ? "基础资料已补齐" : (improved ? "已补充部分资料；仍待补：" : "来源暂无新增资料；仍待补：") + String.join("、",result.missingFields());
                        addResult(new MovieSyncStatus.Item(movie.getTitle(),true,detail,movie.getId(),result.missingFields()));
                    }
                } catch (MaoyanClient.SourceUnavailableException ex) {
                    throw ex;
                } catch (Exception ex) {
                    synchronized (lock) {
                        fail++; done++;
                        if (!movie.getMissingFields().isEmpty()) partial++;
                        addResult(new MovieSyncStatus.Item(movie.getTitle(),false,brief(ex),movie.getId(),movie.getMissingFields()));
                    }
                }
            }
            synchronized (lock) {
                state = "COMPLETED";
                message = total == 0 ? "全片库基础资料完整，无需补缺" : "补缺完成：已补充 " + updated + " 部，来源暂无新增资料 " + skipped
                        + " 部，仍待补 " + partial + " 部，未能补缺 " + fail + " 部；可在影片列表核实补录";
            }
        } catch (Exception ex) {
            log.warn("[mainland-sync] 补缺任务中止", ex);
            synchronized (lock) { state = "FAILED"; message = "补缺中止：" + brief(ex) + "。已补充资料保留"; }
        } finally {
            synchronized (lock) { running = false; current = null; finishedAt = System.currentTimeMillis(); }
        }
    }

    private void run(Integer limit) {
        try {
            MovieReleaseSource.Feed feed = source.discover();
            List<MovieSyncCandidate> all = feed.movies();
            List<Movie> stored = movies.selectList(null);
            Map<String, Movie> known = new java.util.HashMap<>();
            for (Movie movie : stored) {
                if (movie.getMaoyanId() != null) known.put("MAOYAN:" + movie.getMaoyanId(),movie);
                if (movie.getTmdbId() != null) known.put("TMDB:" + movie.getTmdbId(),movie);
            }
            // 已存待映影片的状态维护（本地推导，无需网络）：上映日已过的退出待映（2→1/0）。
            // 这类影片已不在待映片单里，靠抓取无法覆盖，只能按日期推导。
            for (Movie movie : stored) {
                if (!Integer.valueOf(2).equals(movie.getMarketStatus()) || movie.getReleaseDate() == null || movie.isUpcoming()) continue;
                Integer derived = movie.getReleaseDate().isAfter(LocalDate.now().minusDays(90)) ? 1 : 0;
                if (!derived.equals(movie.getMarketStatus())) {
                    movies.update(null, new LambdaUpdateWrapper<Movie>().eq(Movie::getId, movie.getId())
                            .set(Movie::getMarketStatus, derived)
                            .set(Movie::getUpdateTime, java.time.LocalDateTime.now()));
                    synchronized (lock) {
                        updated++;
                        addResult(new MovieSyncStatus.Item(movie.getTitle(), true,
                                derived == 1 ? "已上映，市场状态更新为上映中" : "已上映超过 90 天，市场状态更新为资料库", movie.getId(), movie.getMissingFields()));
                    }
                }
            }
            LocalDate today = LocalDate.now();
            LocalDate until = today.plusDays(properties.getUpcomingDays());
            // 只收未来待映：上映日严格大于今天（重映老片若猫眼给的是历史首映日会被过滤，属预期）。
            List<MovieSyncCandidate> selected = all.stream().filter(candidate -> {
                LocalDate date = MainlandMovieFetcher.date(candidate.releaseDate());
                return date != null && date.isAfter(today) && !date.isAfter(until);
            }).sorted(Comparator.comparing(c -> MainlandMovieFetcher.date(c.releaseDate()),
                    Comparator.nullsLast(Comparator.naturalOrder()))).toList();
            if (selected.isEmpty()) throw new IllegalStateException("本次未获取有效的待映片单，保留原资料");
            List<MovieSyncCandidate> targets = limit == null || limit >= selected.size() ? selected : selected.subList(0, limit);
            synchronized (lock) { total = targets.size(); state = "UPDATING"; message = "正在新增大陆待映影片，并同步已存待映影片的状态"; }
            for (var candidate : targets) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("任务已中止");
                synchronized (lock) { current = candidate.title(); }
                Movie previous = known.get(candidate.source() + ":" + candidate.id());
                try {
                    if (previous != null) {
                        // 已存在：待映影片只刷新「状态」（时长公布/改档/市场状态），已上映影片完全不动。
                        if (!previous.isUpcoming() && !Integer.valueOf(2).equals(previous.getMarketStatus())) {
                            synchronized (lock) {
                                skipped++; ok++; done++;
                                if (!previous.getMissingFields().isEmpty()) partial++;
                                addResult(new MovieSyncStatus.Item(candidate.title(),true,"已存在且已上映，跳过（补缺请用「补全缺失资料」）",previous.getId(),previous.getMissingFields()));
                            }
                            continue;
                        }
                        var refreshed = writer.refreshUpcoming(previous.getId(), source.fetch(candidate, previous));
                        synchronized (lock) {
                            updated++; ok++; done++;
                            if (!refreshed.missingFields().isEmpty()) partial++;
                            String detail = "待映状态已同步"
                                    + (refreshed.missingFields().isEmpty() ? "，资料完整" : "；待补：" + String.join("、", refreshed.missingFields()));
                            addResult(new MovieSyncStatus.Item(candidate.title(), true, detail, previous.getId(), refreshed.missingFields()));
                        }
                        continue;
                    }
                    var result = writer.write(media.prepare(source.fetch(candidate,null)));
                    synchronized (lock) {
                        ok++;
                        if (result.created()) created++; else skipped++;
                        if (!result.missingFields().isEmpty()) partial++;
                        String detail = (result.created() ? "已新增" : "已存在，跳过")
                                + (result.missingFields().isEmpty() ? "，基础资料完整" : "；待补：" + String.join("、", result.missingFields()));
                        addResult(new MovieSyncStatus.Item(candidate.title(), true, detail, result.movieId(), result.missingFields()));
                        done++;
                    }
                } catch (MaoyanClient.SourceUnavailableException ex) {
                    throw ex; // 来源限制访问时停止，不能继续灌入空资料。
                } catch (Exception ex) {
                    // 带完整堆栈，便于定位抓取失败根因；用户可见 message 仍取 brief(ex) 摘要。
                    log.warn("[mainland-sync] 处理失败: {}", candidate.title(), ex);
                    synchronized (lock) {
                        fail++; done++;
                        addResult(new MovieSyncStatus.Item(candidate.title(), false, brief(ex), null, List.of()));
                    }
                }
            }
            synchronized (lock) {
                state = "COMPLETED";
                message = "更新完成：新增 " + created + " 部，待映状态同步 " + updated + " 部，已存在跳过 " + skipped + " 部，失败 " + fail
                        + " 部，资料待补 " + partial + " 部；已上映影片与历史影片未改动"
                        + (feed.note().isBlank() ? "" : "。" + feed.note());
            }
        } catch (Exception ex) {
            log.warn("[mainland-sync] 同步任务中止", ex);
            synchronized (lock) { state = "FAILED"; message = "更新中止：" + brief(ex) + "。已成功的资料保留，未处理影片可再次点击重试"; }
        } finally {
            synchronized (lock) { running = false; current = null; finishedAt = System.currentTimeMillis(); }
        }
    }

    public Map<String, Object> overview() {
        List<Movie> all = movies.selectList(null);
        return Map.of("total", all.size(), "incomplete", all.stream().filter(m -> !m.getMissingFields().isEmpty()).count(),
                "lastSyncedAt", all.stream().map(Movie::getLastSyncedAt).filter(java.util.Objects::nonNull)
                        .max(Comparator.naturalOrder()).map(Object::toString).orElse(""),
                "upcomingDays", properties.getUpcomingDays(), "source", "猫眼大陆院线片单 / TMDB 辅助资料");
    }

    @PreDestroy public void stop() { executor.shutdownNow(); }
    private static String brief(Exception ex) {
        String value = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return value.substring(0, Math.min(220, value.length()));
    }
}
