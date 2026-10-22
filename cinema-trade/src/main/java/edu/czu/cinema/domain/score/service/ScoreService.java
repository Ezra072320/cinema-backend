package edu.czu.cinema.domain.score.service;

import edu.czu.cinema.domain.score.dto.ScoreSubmitDTO;
import edu.czu.cinema.domain.score.entity.Score;

public interface ScoreService {

    Score getByOrder(Long orderId);
    Score ownedReview(Long userId, Long orderId);
    edu.czu.cinema.domain.score.vo.ReviewContext context(Long userId, Long orderId);
    void remove(Long userId, Long orderId, Long expectedVersion);
    edu.czu.cinema.common.api.PageResult<edu.czu.cinema.domain.score.vo.ReviewView> mine(Long userId, int page, int size);

    /**
     * 提交评分.
     * <p>
     * 服务层内校验 {@code dto.orderId} 对应订单的 owner == {@code userId},
     * 防止任意用户对他人订单写评价.
     * </p>
     */
    boolean submit(Long userId, ScoreSubmitDTO dto);
}
