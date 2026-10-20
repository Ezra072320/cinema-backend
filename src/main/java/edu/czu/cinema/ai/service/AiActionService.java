package edu.czu.cinema.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.ai.dto.AiActionCommand;
import edu.czu.cinema.ai.entity.AiAction;
import edu.czu.cinema.ai.mapper.AiActionMapper;
import edu.czu.cinema.ai.mapper.AiSessionMapper;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.movie.service.MovieService;
import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import edu.czu.cinema.domain.order.service.OrderRefundPolicy;
import edu.czu.cinema.domain.order.service.OrderService;
import edu.czu.cinema.domain.order.vo.OrderVO;
import edu.czu.cinema.domain.score.dto.ScoreSubmitDTO;
import edu.czu.cinema.domain.score.service.ScoreService;
import edu.czu.cinema.domain.user.dto.UserUpdateDTO;
import edu.czu.cinema.domain.user.service.UserService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** 模型只能创建服务单；执行入口仅供用户界面调用，不注册为 AI Tool。 */
@Service
@RequiredArgsConstructor
public class AiActionService {
    private final AiActionMapper mapper;
    private final AiSessionMapper sessionMapper;
    private final ObjectMapper json;
    private final Validator validator;
    private final AiBookingService booking;
    private final OrderService orders;
    private final MovieService movies;
    private final ScoreService scores;
    private final UserService users;

    public record View(String id, String type, String title, String summary, String status,
                       @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
                       LocalDateTime expiresAt, String result, String path) {}
    private record Preview(String title, String summary, String path) {}

    public void requireSession(Long uid, String chatId) {
        var session = sessionMapper.selectById(chatId);
        if (session == null || !Objects.equals(session.getUserId(), uid))
            throw new BusinessException(ResultCode.FORBIDDEN, "无权限访问该会话");
    }

    public List<View> list(Long uid, String chatId) {
        requireSession(uid, chatId);
        return mapper.selectList(new LambdaQueryWrapper<AiAction>().eq(AiAction::getUserId, uid)
                .eq(AiAction::getChatId, chatId).orderByDesc(AiAction::getCreateTime)
                .orderByDesc(AiAction::getId).last("LIMIT 30")).stream().map(this::view).toList();
    }

    public View prepare(AiIdentity identity, AiActionCommand command) {
        requireSession(identity.userId(), identity.chatId());
        if (command == null) throw new BusinessException("操作不能为空");
        var violations = validator.validate(command);
        if (!violations.isEmpty()) throw new BusinessException(violations.stream()
                .map(v -> v.getMessage()).sorted().collect(Collectors.joining("；")));
        Preview p = preview(identity.userId(), command);
        String payload = encode(command);
        var duplicate = mapper.selectList(new LambdaQueryWrapper<AiAction>()
                .eq(AiAction::getUserId, identity.userId()).eq(AiAction::getChatId, identity.chatId())
                .eq(AiAction::getStatus, "PENDING").eq(AiAction::getPayload, payload)
                .gt(AiAction::getExpiresAt, LocalDateTime.now()).last("LIMIT 1"));
        if (!duplicate.isEmpty() && Objects.equals(duplicate.get(0).getSummary(), p.summary()))
            return view(duplicate.get(0));
        AiAction a = new AiAction();
        a.setId(UUID.randomUUID().toString());
        a.setUserId(identity.userId());
        a.setChatId(identity.chatId());
        a.setType(command.type().name());
        a.setTitle(p.title());
        a.setSummary(p.summary());
        a.setPath(p.path());
        a.setPayload(payload);
        a.setStatus("PENDING");
        a.setCreateTime(LocalDateTime.now());
        a.setExpiresAt(a.getCreateTime().plusMinutes(5));
        mapper.insert(a);
        return view(a);
    }

    @Transactional(rollbackFor = Exception.class)
    public View confirm(Long uid, String id) {
        AiAction a = ownedLocked(uid, id);
        if ("DONE".equals(a.getStatus())) return view(a); // 重复点击/重试只返回已执行结果
        if (!"PENDING".equals(a.getStatus()) || !a.getExpiresAt().isAfter(LocalDateTime.now()))
            throw new BusinessException(ResultCode.CONFLICT, "服务单已撤销或过期，请让客服重新查询并生成");
        AiActionCommand c = decode(a.getPayload());
        Preview fresh = preview(uid, c);
        if (!Objects.equals(fresh.summary(), a.getSummary()))
            throw new BusinessException(ResultCode.CONFLICT, "业务信息或金额已变化，请让客服重新生成确认单");
        String result;
        switch (c.type()) {
            case CREATE_ORDER -> {
                Long orderId = orders.createOrder(new OrderAddDTO(c.scheduleId(), c.ticketIds()));
                a.setPath("/pay/" + orderId);
                result = "订单已创建，座位已锁定，请在15分钟内到支付页完成模拟支付。";
            }
            case CANCEL_ORDER -> result = orders.cancel(c.orderId(), uid);
            case REFUND_ORDER -> {
                var o = orders.detail(c.orderId(), uid);
                var quote = OrderRefundPolicy.quote(o.getStatus(), o.getTotalAmount(), o.getStartTime(), LocalDateTime.now());
                // fresh.summary已核对用户看到的档位，金额取服务单展示值；再次跨档由订单域拒绝。
                if (!quote.eligible() || !a.getSummary().equals(preview(uid, c).summary()))
                    throw new BusinessException(ResultCode.CONFLICT, "退款信息已变化，请重新生成确认单");
                result = orders.refund(c.orderId(), uid, quote.amount()) + "（模拟退款记录）";
            }
            case SET_WANT -> {
                boolean current = movies.isWant(c.movieId(), uid);
                if (current != c.wanted()) {
                    boolean next = movies.handleWant(uid, c.movieId());
                    if (next != c.wanted()) throw new BusinessException(ResultCode.CONFLICT, "想看状态已变化，请重试");
                }
                result = c.wanted() ? "已加入想看清单" : "已从想看清单移除";
            }
            case SUBMIT_SCORE -> {
                if (!scores.submit(uid, new ScoreSubmitDTO(c.orderId(), c.score(), c.content())))
                    throw new BusinessException("评价提交失败，请重试");
                // 评价独立于订单状态（不再把已支付单改为已完成），结果文案同步修正。
                result = "评价已提交，可在评价页再次编辑。";
                a.setPath("/order/" + c.orderId());
            }
            case UPDATE_PROFILE -> {
                users.updateCurrent(uid, new UserUpdateDTO(c.name(), c.age()));
                result = "个人资料已更新。";
            }
            default -> throw new BusinessException("不支持的操作");
        }
        a.setStatus("DONE");
        a.setResult(result);
        mapper.updateById(a);
        return view(a);
    }

    @Transactional
    public View dismiss(Long uid, String id) {
        AiAction a = ownedLocked(uid, id);
        if ("PENDING".equals(a.getStatus())) {
            a.setStatus("DISMISSED");
            mapper.updateById(a);
        }
        return view(a);
    }

    private AiAction ownedLocked(Long uid, String id) {
        AiAction a = mapper.lockById(id);
        if (a == null) throw new BusinessException(ResultCode.NOT_FOUND, "服务单不存在");
        if (!Objects.equals(a.getUserId(), uid)) throw new BusinessException(ResultCode.FORBIDDEN, "服务单不属于当前用户");
        requireSession(uid, a.getChatId());
        return a;
    }

    private Preview preview(Long uid, AiActionCommand c) {
        return switch (c.type()) {
            case CREATE_ORDER -> {
                var q = booking.quote(new OrderAddDTO(c.scheduleId(), c.ticketIds()));
                yield new Preview("确认购票", q.summary(), q.path());
            }
            case CANCEL_ORDER -> {
                var o = order(uid, c.orderId());
                if (!Integer.valueOf(1).equals(o.getStatus())) throw new BusinessException("仅待支付订单可取消");
                yield new Preview("取消待支付订单", orderSummary(o) + "\n确认后关闭订单并释放座位，不发生退款。",
                        "/order/" + o.getId());
            }
            case REFUND_ORDER -> {
                var o = order(uid, c.orderId());
                var q = OrderRefundPolicy.quote(o.getStatus(), o.getTotalAmount(), o.getStartTime(), LocalDateTime.now());
                if (!q.eligible()) throw new BusinessException(q.reason());
                yield new Preview("确认申请退票", orderSummary(o) + "\n" + q.reason() + "，预计退款¥"
                        + q.amount().toPlainString() + "；确认后释放座位（模拟退款）。", "/order/" + o.getId());
            }
            case SET_WANT -> {
                if (c.movieId() == null || c.wanted() == null) throw new BusinessException("请指定影片和想看目标状态");
                var m = movies.info(c.movieId()).getBase();
                yield new Preview(c.wanted() ? "加入想看" : "取消想看", "《" + m.getTitle() + "》\n"
                        + (c.wanted() ? "加入" : "移出") + "你的想看清单。", "/me/wants");
            }
            case SUBMIT_SCORE -> {
                var o = order(uid, c.orderId());
                // 资格复用评价模块唯一事实源 ReviewPolicy（OrderVO 的 canReview/reviewReason
                // 已由订单域按 ReviewPolicy.check 口径填充）：2/6 已支付或旧已完成 + 场次有效 + 散场后。
                if (o.isReviewed()) throw new BusinessException("该订单已发表过评价，请到评价页编辑原评价，无需重新拟单");
                if (!o.isCanReview()) throw new BusinessException(
                        o.getReviewReason() == null || o.getReviewReason().isBlank()
                                ? "当前订单暂不可评价（场次已取消、未散场或订单状态不符）" : o.getReviewReason());
                if (c.score() == null) throw new BusinessException("请先给出1至5分评分");
                yield new Preview("提交观影评价", orderSummary(o) + "\n评分：" + c.score() + "/5\n评论："
                        + (c.content() == null || c.content().isBlank() ? "（无文字评论）" : c.content())
                        + "\n提交后订单状态不变，可在评价页再次编辑。", "/score/" + o.getMovieId() + "/" + o.getId());
            }
            case UPDATE_PROFILE -> {
                if (c.name() == null && c.age() == null) throw new BusinessException("请指定昵称或年龄");
                var u = users.getById(uid);
                if (u == null) throw new BusinessException("用户不存在");
                yield new Preview("更新个人资料", (c.name() == null ? "" : "昵称：" + u.getName() + " → " + c.name() + "\n")
                        + (c.age() == null ? "" : "年龄：" + u.getAge() + " → " + c.age()), "/me/info");
            }
        };
    }

    private OrderVO order(Long uid, Long id) {
        if (id == null) throw new BusinessException("请先明确要办理的订单");
        return orders.detail(id, uid);
    }

    private String orderSummary(OrderVO o) {
        return "订单" + o.getNumber() + " · 《" + o.getMovieName() + "》\n" + o.getHallName() + " · "
                + o.getStartTime() + " · " + (o.getSeatLabels() == null ? "" : String.join("、", o.getSeatLabels()))
                + "\n订单金额¥" + o.getTotalAmount().toPlainString();
    }

    private View view(AiAction a) {
        String status = "PENDING".equals(a.getStatus()) && !a.getExpiresAt().isAfter(LocalDateTime.now())
                ? "EXPIRED" : a.getStatus();
        return new View(a.getId(), a.getType(), a.getTitle(), a.getSummary(), status, a.getExpiresAt(),
                a.getResult(), a.getPath());
    }

    private String encode(AiActionCommand c) {
        try { return json.writeValueAsString(c); }
        catch (JsonProcessingException e) { throw new IllegalStateException("服务单序列化失败", e); }
    }

    private AiActionCommand decode(String text) {
        try { return json.readValue(text, AiActionCommand.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("服务单数据损坏", e); }
    }
}
