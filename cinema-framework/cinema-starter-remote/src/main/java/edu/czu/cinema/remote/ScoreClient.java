package edu.czu.cinema.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "cinema-trade", contextId = "ScoreClient", url = "${CINEMA_TRADE_URL:}")
public interface ScoreClient {

    @GetMapping("/internal/trade/reviews/{id}")
    edu.czu.cinema.domain.score.entity.Score getByOrder(
            @PathVariable("id") Long id, @RequestParam("userId") Long uid);

    @PostMapping("/internal/trade/reviews")
    boolean submit(
            @RequestParam("userId") Long uid,
            @RequestBody edu.czu.cinema.domain.score.dto.ScoreSubmitDTO dto);

    /** 影片公开评价分页；viewerId 用于标记“我的评价”。 */
    @GetMapping("/internal/trade/reviews/movie/{movieId}")
    edu.czu.cinema.domain.score.vo.MovieReviewPage movieReviews(
            @PathVariable("movieId") Long movieId,
            @RequestParam(value = "viewerId", required = false) Long viewerId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "sort", required = false) String sort);

    /** 本人评价分页。 */
    @GetMapping("/internal/trade/reviews/mine")
    edu.czu.cinema.common.api.PageResult<edu.czu.cinema.domain.score.vo.ReviewView> myReviews(
            @RequestParam("userId") Long uid,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "limit", required = false) Integer limit);
}
