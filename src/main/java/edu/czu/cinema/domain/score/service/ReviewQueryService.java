package edu.czu.cinema.domain.score.service;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.schedule.entity.Schedule;
import edu.czu.cinema.domain.score.vo.MovieReviewPage;
import edu.czu.cinema.domain.score.vo.ReviewView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReviewQueryService {
    private final JdbcTemplate jdbc;
    private static final String FROM = " FROM score r JOIN " + (char)96 + "order" + (char)96
            + " o ON o.id=r.order_id LEFT JOIN schedule s ON s.id=o.schedule_id"
            + " LEFT JOIN movie m ON m.id=s.movie_id LEFT JOIN " + (char)96 + "user" + (char)96 + " u ON u.id=r.user_id";
    private static final String END = "CASE WHEN m.runtime_min>0 THEN TIMESTAMPADD(MINUTE,m.runtime_min,s.start_time) ELSE s.end_time END";
    private static final String VISIBLE = " r.score BETWEEN 1 AND 5 AND r.user_id=o.user_id AND r.movie_id=s.movie_id"
            + " AND o.status IN(2,6) AND s.status=1 AND " + END + "<=?";
    private static final String SELECT = "SELECT r.id,r.order_id,r.movie_id,r.score,r.content,r.create_time,r.update_time,r.version,"
            + " r.user_id,m.title,m.poster_url,u.name AS author,o.status AS order_status,s.status AS schedule_status,"
            + " s.start_time,s.end_time,m.runtime_min";

    @Transactional(readOnly = true)
    public MovieReviewPage movie(Long movieId, Long viewerId, int page, int size, String sort) {
        validatePage(page, size);
        if (!Set.of("newest", "highest", "lowest").contains(sort)) throw new BusinessException("未知评价排序");
        LocalDateTime now = LocalDateTime.now();
        String where = " WHERE s.movie_id=? AND " + VISIBLE;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + FROM + where, Long.class, movieId, now);
        BigDecimal average = jdbc.queryForObject("SELECT AVG(r.score)" + FROM + where, BigDecimal.class, movieId, now);
        Map<Integer, Long> distribution = new LinkedHashMap<>();
        for (int i = 5; i >= 1; i--) distribution.put(i, 0L);
        jdbc.query("SELECT r.score,COUNT(*) AS n" + FROM + where + " GROUP BY r.score",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> distribution.put(rs.getInt("score"), rs.getLong("n")), movieId, now);
        String order = switch (sort) {
            case "highest" -> "r.score DESC,";
            case "lowest" -> "r.score ASC,";
            default -> "";
        };
        List<ReviewView> rows = jdbc.query(SELECT + FROM + where + " ORDER BY " + order
                        + "COALESCE(r.update_time,r.create_time) DESC,r.id DESC LIMIT ? OFFSET ?",
                mapper(viewerId, false, now), movieId, now, size, (page - 1) * size);
        return new MovieReviewPage(total == null ? 0 : total, rows,
                new MovieReviewPage.Summary(total == null ? 0 : total,
                        average == null ? null : average.setScale(1, RoundingMode.HALF_UP), distribution));
    }

    @Transactional(readOnly = true)
    public PageResult<ReviewView> mine(Long uid, int page, int size) {
        validatePage(page, size);
        String where = " WHERE r.user_id=? AND o.user_id=?";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + FROM + where, Long.class, uid, uid);
        var rows = jdbc.query(SELECT + FROM + where + " ORDER BY COALESCE(r.update_time,r.create_time) DESC,r.id DESC LIMIT ? OFFSET ?",
                mapper(uid, true, LocalDateTime.now()), uid, uid, size, (page - 1) * size);
        return PageResult.of(total == null ? 0 : total, rows);
    }

    private RowMapper<ReviewView> mapper(Long viewer, boolean personal, LocalDateTime now) {
        return (rs, row) -> {
            var v = new ReviewView();
            v.setId(rs.getLong("id")); v.setMovieId(rs.getLong("movie_id"));
            v.setMovieTitle(rs.getString("title")); v.setPoster(rs.getString("poster_url"));
            v.setScore(number(rs, "score")); v.setContent(rs.getString("content"));
            v.setCreateTime(time(rs, "create_time")); v.setUpdateTime(time(rs, "update_time"));
            boolean mine = viewer != null && viewer == rs.getLong("user_id");
            v.setMine(mine);
            String author = rs.getString("author");
            v.setAuthor(author == null || author.isBlank() ? "观影用户" : author.substring(0, author.offsetByCodePoints(0,1)) + "***");
            Schedule s = new Schedule(); s.setStatus(number(rs, "schedule_status"));
            s.setStartTime(time(rs, "start_time")); s.setEndTime(time(rs, "end_time"));
            var eligibility = ReviewPolicy.check(number(rs, "order_status"), s,
                    number(rs, "runtime_min"), now);
            boolean validScore = v.getScore() != null && v.getScore() >= 1 && v.getScore() <= 5;
            v.setPublished(eligibility.allowed() && validScore);
            v.setEditable(mine && eligibility.allowed()); v.setReason(eligibility.reason());
            if (personal || mine) {
                v.setOrderId(rs.getLong("order_id"));
                v.setVersion(rs.getLong("version"));
            }
            return v;
        };
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column); return ts == null ? null : ts.toLocalDateTime();
    }
    private static Integer number(ResultSet rs, String column) throws SQLException {
        int n = rs.getInt(column); return rs.wasNull() ? null : n;
    }
    private static void validatePage(int page, int size) {
        if (page < 1 || page > 10000 || size < 1 || size > 50) throw new BusinessException("页码或每页数量不合法");
    }
}
