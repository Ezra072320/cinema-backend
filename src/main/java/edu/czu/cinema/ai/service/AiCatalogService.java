package edu.czu.cinema.ai.service;

import edu.czu.cinema.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * 只读目录：有界分页、全参数绑定，不向模型暴露 SQL 或数据库连接。
 *
 * <p>为何保留 JdbcTemplate 而非 MP LambdaWrapper（MyBatis-Plus 统一原则 §4.2 例外）：
 * 这些是跨 movie/schedule/hall/order 的多表 JOIN + EXISTS 子查询 + 聚合（可用票数/最低价/榜单），
 * 用 MP Wrapper 表达会显著降低可读性，且属只读聚合，集中于此并已由 AiCatalogServiceTest 钉住语义。
 * 若日后抽独立查询组件可再评估，但属于「复杂聚合」保留项，不计入手写 SQL 清零范围。
 */
@Service
@RequiredArgsConstructor
public class AiCatalogService {
    private final JdbcTemplate jdbc;
    private static final int SIZE = 12;
    private static final String BOOKABLE = "EXISTS (SELECT 1 FROM schedule s WHERE s.movie_id=m.id AND s.status=1 AND s.start_time>?)";

    public Map<String, Object> movies(String keyword, Boolean showing, Integer page) {
        List<Object> args = new ArrayList<>();
        String where = " WHERE 1=1";
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.strip() + "%";
            where += """
                 AND (m.title LIKE ? OR m.original_title LIKE ? OR m.cat LIKE ? OR m.dir LIKE ?
                   OR EXISTS (SELECT 1 FROM movie_crew c JOIN person p ON p.id=c.person_id
                              WHERE c.movie_id=m.id AND p.name LIKE ?)
                   OR EXISTS (SELECT 1 FROM movie_alt_title a WHERE a.movie_id=m.id AND a.title LIKE ?))
                """;
            args.addAll(Collections.nCopies(6, like));
        }
        if (showing != null) {
            where += " AND " + (showing ? "" : "NOT ") + BOOKABLE;
            args.add(LocalDateTime.now());
        }
        return page("""
                SELECT m.id AS movieId,m.title,m.cat AS genres,m.dir AS directors,m.area,m.language,
                       m.runtime_min AS runtimeMinutes,m.score,m.score_source AS scoreSource,m.release_date AS releaseDate,
                       CONCAT('/movie/',m.id) AS detailPath,CONCAT('/hall/',m.id) AS schedulePath
                """, " FROM movie m", where, args, " ORDER BY m.score DESC,m.release_date DESC,m.id DESC", page);
    }

    public Map<String, Object> schedules(String date, Long movieId, Long hallId, String after,
                                         String before, BigDecimal maxPrice, Integer page) {
        LocalDate day = resolveDate(date);
        LocalTime from = time(after, LocalTime.MIN);
        LocalTime to = time(before, LocalTime.MAX);
        if (from.isAfter(to)) throw new BusinessException("开始时间不能晚于结束时间；跨午夜请分日期查询");
        if (maxPrice != null && maxPrice.signum() < 0) throw new BusinessException("预算不能为负数");
        List<Object> args = new ArrayList<>(List.of(day.atTime(from), day.atTime(to), LocalDateTime.now()));
        String where = " WHERE s.status=1 AND s.start_time>=? AND s.start_time<=? AND s.start_time>?";
        if (movieId != null) { where += " AND s.movie_id=?"; args.add(movieId); }
        if (hallId != null) { where += " AND s.hall_id=?"; args.add(hallId); }
        if (maxPrice != null) {
            where += " AND EXISTS (SELECT 1 FROM ticket t WHERE t.schedule_id=s.id AND t.status=1 AND t.price<=?)";
            args.add(maxPrice);
        }
        var result = page("""
                SELECT s.id AS scheduleId,s.movie_id AS movieId,m.title AS movie,s.hall_id AS hallId,
                       h.name AS hall,h.type AS hallType,s.start_time AS startTime,m.runtime_min AS runtimeMinutes,
                       CASE WHEN m.runtime_min>0 THEN TIMESTAMPADD(MINUTE,m.runtime_min,s.start_time)
                            ELSE NULL END AS estimatedMovieEndTime,
                       s.price AS basePrice,
                       (SELECT COUNT(*) FROM ticket t WHERE t.schedule_id=s.id AND t.status=1) AS availableTickets,
                       (SELECT MIN(t.price) FROM ticket t WHERE t.schedule_id=s.id AND t.status=1) AS minimumAvailablePrice,
                       CONCAT('/seats/',s.id) AS seatsPath
                """, " FROM schedule s JOIN movie m ON m.id=s.movie_id JOIN hall h ON h.id=s.hall_id",
                where, args, " ORDER BY s.start_time ASC,s.id ASC", page);
        result.put("date", day.toString());
        result.put("note", "预计散场按已公布影片时长计算，未含清场。可售票数为实体座位数，最低价与余座实时变化，情侣座须整对购买。");
        return result;
    }

    public Map<String, Object> orders(Long uid, Integer status, String keyword, Integer page) {
        if (status != null && !List.of(1, 2, 3, 4, 5, 6).contains(status)) throw new BusinessException("未知订单状态");
        List<Object> args = new ArrayList<>(List.of(uid));
        String where = " WHERE o.user_id=?";
        if (status != null && status != 5) { where += " AND o.status=?"; args.add(status); }
        if (keyword != null && !keyword.isBlank()) {
            where += " AND (o.number LIKE ? OR m.title LIKE ?)";
            args.add("%" + keyword.strip() + "%"); args.add("%" + keyword.strip() + "%");
        }
        return page("SELECT o.id AS orderId", " FROM " + (char)96 + "order" + (char)96
                        + " o LEFT JOIN schedule s ON s.id=o.schedule_id LEFT JOIN movie m ON m.id=s.movie_id",
                where, args, " ORDER BY o.create_time DESC,o.id DESC", page);
    }

    private Map<String, Object> page(String select, String from, String where, List<Object> args, String sort, Integer page) {
        int p = page == null ? 1 : page;
        if (p < 1 || p > 10000) throw new BusinessException("页码须在1至10000之间");
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(SIZE); rowArgs.add((p - 1) * SIZE);
        var result = new LinkedHashMap<String, Object>();
        result.put("page", p); result.put("pageSize", SIZE); result.put("total", total);
        result.put("hasMore", total != null && (long) p * SIZE < total);
        var rows = jdbc.queryForList(select + from + where + sort + " LIMIT ? OFFSET ?", rowArgs.toArray());
        rows.forEach(row -> row.replaceAll((key, value) -> value instanceof java.util.Date ? value.toString() : value));
        result.put("rows", rows);
        return result;
    }

    public static LocalDate resolveDate(String date) {
        String s = date == null ? "" : date.strip();
        return switch (s) {
            case "", "今天", "今日" -> LocalDate.now();
            case "明天", "明日" -> LocalDate.now().plusDays(1);
            case "后天" -> LocalDate.now().plusDays(2);
            default -> {
                try { yield LocalDate.parse(s); }
                catch (Exception e) { throw new BusinessException("日期无法识别，请使用yyyy-MM-dd或今天、明天、后天"); }
            }
        };
    }

    private static LocalTime time(String value, LocalTime fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalTime.parse(value); }
        catch (Exception e) { throw new BusinessException("时间须为HH:mm，例如19:30"); }
    }
}
