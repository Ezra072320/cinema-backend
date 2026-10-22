package edu.czu.cinema.internal;

import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.domain.hall.entity.Hall;
import edu.czu.cinema.domain.hall.service.*;
import edu.czu.cinema.domain.movie.vo.*;
import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import edu.czu.cinema.domain.order.service.OrderService;
import edu.czu.cinema.domain.order.vo.OrderVO;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import edu.czu.cinema.domain.schedule.service.ScheduleService;
import edu.czu.cinema.domain.schedule.vo.ScheduleSeatsVO;
import edu.czu.cinema.domain.score.dto.ScoreSubmitDTO;
import edu.czu.cinema.domain.score.entity.Score;
import edu.czu.cinema.domain.score.service.ReviewQueryService;
import edu.czu.cinema.domain.score.service.ScoreService;
import edu.czu.cinema.domain.score.vo.MovieReviewPage;
import edu.czu.cinema.domain.score.vo.ReviewView;
import edu.czu.cinema.domain.ticket.entity.Ticket;
import edu.czu.cinema.domain.ticket.service.TicketService;
import edu.czu.cinema.query.TradeQueryService;
import edu.czu.cinema.remote.OperationJournal;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/internal/trade")
@RequiredArgsConstructor
public class TradeController {
    private final ScheduleService schedules;
    private final ScheduleMapper scheduleMapper;
    private final HallService halls;
    private final SeatLabelService labels;
    private final TicketService tickets;
    private final OrderService orders;
    private final ScoreService scores;
    private final ReviewQueryService reviewQueries;
    private final JdbcTemplate jdbc;
    private final TradeQueryService queries;
    private final OperationJournal journal;

    @GetMapping("/showing")
    public List<Long> showing(@RequestParam(defaultValue = "false") boolean bookable) {
        return jdbc.queryForList(
                "SELECT DISTINCT movie_id FROM schedule WHERE status=1 AND "
                        + (bookable ? "start_time" : "end_time")
                        + ">NOW()",
                Long.class);
    }

    @PostMapping("/summaries")
    public List<MovieShowSummary> summaries(@RequestBody List<Long> ids) {
        return ids.isEmpty() ? List.of() : scheduleMapper.movieShowSummaries(ids);
    }

    @GetMapping("/sales")
    public List<MovieSoldVO> sales() {
        return new edu.czu.cinema.query.TradeSalesQuery(jdbc).sales();
    }

    @GetMapping("/seats/{id}")
    public ScheduleSeatsVO seats(@PathVariable Long id) {
        return schedules.seatsOfSchedule(id);
    }

    @GetMapping("/halls")
    public List<Hall> halls() {
        return halls.listAll();
    }

    @PostMapping("/tickets")
    public List<Ticket> tickets(@RequestBody List<Long> ids) {
        return tickets.listByIds(ids);
    }

    @GetMapping("/tickets/{id}")
    public List<Ticket> tickets(@PathVariable Long id) {
        return tickets.listBySchedule(id);
    }

    @GetMapping("/labels/{id}")
    public Map<Long, String> labels(@PathVariable Long id) {
        return labels.labelsOf(id);
    }

    @PostMapping("/orders")
    public Long create(
            @RequestParam Long userId,
            @RequestBody OrderAddDTO dto,
            @RequestHeader("X-Operation-Id") String key) {
        return journal.run(
                key,
                userId,
                "CREATE_ORDER",
                dto,
                Long.class,
                () -> {
                    ContextHolder.set(new ContextHolder.Principal(userId, "AI用户", "USER"));
                    try {
                        return orders.createOrder(dto);
                    } finally {
                        ContextHolder.clear();
                    }
                });
    }

    @GetMapping("/orders/{id}")
    public OrderVO order(@PathVariable Long id, @RequestParam Long userId) {
        return orders.detail(id, userId);
    }

    @PostMapping("/orders/{id}/cancel")
    public String cancel(
            @PathVariable Long id,
            @RequestParam Long userId,
            @RequestHeader("X-Operation-Id") String key) {
        return journal.run(
                key, userId, "CANCEL_ORDER", id, String.class, () -> orders.cancel(id, userId));
    }

    @PostMapping("/orders/{id}/refund")
    public String refund(
            @PathVariable Long id,
            @RequestParam Long userId,
            @RequestParam BigDecimal amount,
            @RequestHeader("X-Operation-Id") String key) {
        return journal.run(
                key,
                userId,
                "REFUND_ORDER",
                List.of(id, amount),
                String.class,
                () -> orders.refund(id, userId, amount));
    }

    @GetMapping("/reviews/{id}")
    public Score review(@PathVariable Long id, @RequestParam Long userId) {
        return scores.ownedReview(userId, id);
    }

    /**
     * 影片公开评价（AI 客服）。

     * <p>viewerId 只用于标记“我的评价/可编辑”，返回体本身已由 ReviewQueryService
     * 做作者脱敏，不包含他人订单号或用户 ID。
     */
    @GetMapping("/reviews/movie/{movieId}")
    public MovieReviewPage movieReviews(
            @PathVariable Long movieId,
            @RequestParam(required = false) Long viewerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "newest") String sort) {
        return reviewQueries.movie(movieId, viewerId, page, limit, sort);
    }

    /** 本人评价分页（AI 客服）。 */
    @GetMapping("/reviews/mine")
    public edu.czu.cinema.common.api.PageResult<ReviewView> myReviews(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        return reviewQueries.mine(userId, page, limit);
    }

    @PostMapping("/reviews")
    public boolean review(
            @RequestParam Long userId,
            @RequestBody ScoreSubmitDTO dto,
            @RequestHeader("X-Operation-Id") String key) {
        return journal.run(
                key, userId, "SUBMIT_SCORE", dto, Boolean.class, () -> scores.submit(userId, dto));
    }

    @GetMapping("/query/schedules")
    public Map<String, Object> schedules(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Long movieId,
            @RequestParam(required = false) Long hallId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Integer page) {
        return queries.schedules(date, movieId, hallId, after, before, maxPrice, page);
    }

    @GetMapping("/query/orders")
    public Map<String, Object> orderQuery(
            @RequestParam Long userId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page) {
        return queries.orders(userId, status, keyword, page);
    }
}
