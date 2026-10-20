package edu.czu.cinema.domain.score.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.hall.mapper.HallMapper;
import edu.czu.cinema.domain.movie.mapper.MovieMapper;
import edu.czu.cinema.domain.order.entity.Order;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.domain.schedule.entity.Schedule;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import edu.czu.cinema.domain.score.dto.ScoreSubmitDTO;
import edu.czu.cinema.domain.score.entity.Score;
import edu.czu.cinema.domain.score.mapper.ScoreMapper;
import edu.czu.cinema.domain.score.service.*;
import edu.czu.cinema.domain.score.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ScoreServiceImpl implements ScoreService {
    private final ScoreMapper scoreMapper;
    private final OrderMapper orderMapper;
    private final ScheduleMapper scheduleMapper;
    private final MovieMapper movieMapper;
    private final HallMapper hallMapper;
    private final ReviewQueryService queries;

    /** 仅供已验证订单归属的内部调用；HTTP端点必须走ownedReview/context。 */
    @Override
    public Score getByOrder(Long orderId) {
        return scoreMapper.selectOne(new LambdaQueryWrapper<Score>().eq(Score::getOrderId, orderId));
    }

    @Override
    public Score ownedReview(Long uid, Long orderId) {
        owned(orderMapper.selectById(orderId), uid);
        return getByOrder(orderId);
    }

    @Override
    public ReviewContext context(Long uid, Long orderId) {
        Order order = owned(orderMapper.selectById(orderId), uid);
        Schedule schedule = scheduleMapper.selectById(order.getScheduleId());
        var movie = schedule == null ? null : movieMapper.selectById(schedule.getMovieId());
        var eligibility = ReviewPolicy.check(order.getStatus(), schedule,
                movie == null ? null : movie.getRuntimeMin(), LocalDateTime.now());
        var hall = schedule == null ? null : hallMapper.selectById(schedule.getHallId());
        return new ReviewContext(orderId, movie == null ? null : movie.getId(), movie == null ? null : movie.getTitle(),
                hall == null ? null : hall.getName(), movie == null ? null : movie.getPosterUrl(),
                schedule == null ? null : schedule.getStartTime(), eligibility.availableAt(),
                eligibility.allowed() && movie != null, movie == null ? "影片资料不存在" : eligibility.reason(), getByOrder(orderId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean submit(Long uid, ScoreSubmitDTO dto) {
        if (dto == null || dto.orderId() == null || dto.orderId() <= 0 || dto.score() == null || dto.score() < 1 || dto.score() > 5)
            throw new BusinessException(ResultCode.BAD_REQUEST, "请给出1至5分评分及有效订单");
        if (dto.content() != null && dto.content().length() > 1024)
            throw new BusinessException(ResultCode.BAD_REQUEST, "短评最多1024字");
        if (dto.expectedVersion() != null && dto.expectedVersion() < 0)
            throw new BusinessException(ResultCode.BAD_REQUEST, "评价版本不合法");
        Order order = owned(orderMapper.lockForReview(dto.orderId()), uid);
        Schedule schedule = scheduleMapper.selectById(order.getScheduleId());
        var movie = schedule == null ? null : movieMapper.selectById(schedule.getMovieId());
        if (movie == null) throw new BusinessException(ResultCode.NOT_FOUND, "影片或场次不存在");
        var eligibility = ReviewPolicy.check(order.getStatus(), schedule, movie.getRuntimeMin(), LocalDateTime.now());
        if (!eligibility.allowed()) throw new BusinessException(ResultCode.CONFLICT, eligibility.reason());
        Score previous = getByOrder(order.getId());
        String content = dto.content() == null ? "" : dto.content().strip();
        LocalDateTime now = LocalDateTime.now();
        if (previous == null) {
            if (dto.expectedVersion() != null && dto.expectedVersion() != 0)
                throw new BusinessException(ResultCode.CONFLICT, "评价已被删除，请刷新后重新发表");
            Score s = new Score(); s.setOrderId(order.getId()); s.setUserId(uid); s.setMovieId(schedule.getMovieId());
            s.setScore(dto.score()); s.setContent(content); s.setCreateTime(now); s.setUpdateTime(now); s.setVersion(1L);
            if (scoreMapper.insert(s) != 1) throw new BusinessException("评价保存失败");
        } else {
            if (!Objects.equals(previous.getUserId(), uid)) throw new BusinessException(ResultCode.FORBIDDEN, "评价不属于当前用户");
            long version = previous.getVersion() == null ? 1 : previous.getVersion();
            if (!Objects.equals(dto.expectedVersion(), version))
                throw new BusinessException(ResultCode.CONFLICT, "评价已存在或已被其他窗口修改，请刷新后再提交");
            if (Objects.equals(previous.getScore(), dto.score()) && Objects.equals(previous.getContent(), content)) return true;
            int changed = scoreMapper.update(null, new LambdaUpdateWrapper<Score>()
                    .eq(Score::getId, previous.getId()).eq(Score::getVersion, version)
                    .set(Score::getMovieId, schedule.getMovieId()).set(Score::getScore, dto.score()).set(Score::getContent, content)
                    .set(Score::getUpdateTime, now).set(Score::getVersion, version + 1));
            if (changed != 1) throw new BusinessException(ResultCode.CONFLICT, "评价已变化，请刷新后重试");
        }
        // 评价是独立状态，不再把已支付订单改成6，也不写外部来源的movie.score。
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long uid, Long orderId, Long expectedVersion) {
        owned(orderMapper.lockForReview(orderId), uid);
        Score previous = getByOrder(orderId);
        if (previous == null) return;
        if (!Objects.equals(previous.getUserId(), uid)) throw new BusinessException(ResultCode.FORBIDDEN, "评价不属于当前用户");
        if (expectedVersion == null || !Objects.equals(previous.getVersion(), expectedVersion))
            throw new BusinessException(ResultCode.CONFLICT, "评价已变化，请刷新后再删除");
        if (scoreMapper.delete(new LambdaQueryWrapper<Score>().eq(Score::getId, previous.getId())
                .eq(Score::getVersion, expectedVersion)) != 1)
            throw new BusinessException(ResultCode.CONFLICT, "评价已变化，请刷新后重试");
    }

    @Override
    public PageResult<ReviewView> mine(Long uid, int page, int size) { return queries.mine(uid, page, size); }

    private Order owned(Order order, Long uid) {
        if (order == null) throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        if (uid == null || !Objects.equals(order.getUserId(), uid))
            throw new BusinessException(ResultCode.FORBIDDEN, "订单不属于当前用户");
        return order;
    }
}
