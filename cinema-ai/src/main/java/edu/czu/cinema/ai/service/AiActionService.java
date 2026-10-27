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
import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import edu.czu.cinema.domain.order.enums.OrderStatus;
import edu.czu.cinema.domain.order.service.OrderRefundPolicy;
import edu.czu.cinema.domain.order.vo.OrderVO;
import edu.czu.cinema.domain.score.dto.ScoreSubmitDTO;
import edu.czu.cinema.domain.user.dto.UserUpdateDTO;
import edu.czu.cinema.remote.AccountClient;
import edu.czu.cinema.remote.CatalogClient;
import edu.czu.cinema.remote.OrderClient;
import edu.czu.cinema.remote.ScoreClient;

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
    private final OrderClient orders;
    private final CatalogClient movies;
    private final ScoreClient scores;
    private final AccountClient users;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private edu.czu.cinema.remote.BenefitClient benefits;

    public record View(
            String id,
            String type,
            String title,
            String summary,
            String status,
            @com.fasterxml.jackson.annotation.JsonFormat(
                            pattern = "yyyy-MM-dd HH:mm:ss",
                            timezone = "GMT+8")
                    LocalDateTime expiresAt,
            String result,
            String path) {}

    private record Preview(String title, String summary, String path) {}

    /** 取消/退票拟单的统一结果：订单实时状态 + 生成的服务单 + 动作名称。 */
    public record AfterSaleResult(OrderVO order, View action, String actionName) {}

    /**
     * 取消或退票的统一拟单入口。
     *
     * <p>模型不需要自己判断“该取消还是该退票”：这里先 {@code orders.detail} 现查订单状态，
     * 待支付生成取消单，已支付按既有退票规则生成退票单。状态已经变化（已退款/已关闭）时
     * 不重复拟单，直接返回业务原因。
     */
    public AfterSaleResult prepareAfterSale(AiIdentity identity, Long orderId) {
        requireSession(identity.userId(), identity.chatId());
        if (orderId == null) throw new BusinessException("请先明确订单ID");
        var order = orders.detail(orderId, identity.userId());
        int status = order.getStatus() == null ? 0 : order.getStatus();
        AiActionCommand.Type type;
        String actionName;
        if (status == OrderStatus.PENDING.getCode()) {
            type = AiActionCommand.Type.CANCEL_ORDER;
            actionName = "取消待支付订单";
        } else if (status == OrderStatus.PAID.getCode()) {
            type = AiActionCommand.Type.REFUND_ORDER;
            actionName = "申请退票";
        } else if (status == OrderStatus.REFUNDED.getCode()) {
            throw new BusinessException("订单已退款，无需重复办理");
        } else if (status == OrderStatus.CLOSED.getCode()) {
            throw new BusinessException("订单已关闭，无需办理取消或退票");
        } else {
            throw new BusinessException(
                    "订单当前状态为" + statusText(status) + "，暂不支持取消或退票");
        }
        View action =
                prepare(
                        identity,
                        AiActionCommand.builder().type(type).orderId(orderId).build());
        return new AfterSaleResult(order, action, actionName);
    }

    public View prepareBookingBySeat(AiIdentity identity, Long scheduleId, String seatLabel) {
        requireSession(identity.userId(), identity.chatId());
        var selection = booking.resolveBySeat(scheduleId, seatLabel);
        return prepare(identity, AiActionCommand.builder().type(AiActionCommand.Type.CREATE_ORDER)
                .scheduleId(selection.scheduleId()).ticketIds(selection.ticketIds()).build());
    }

    private static String statusText(Integer status) {
        for (OrderStatus value : OrderStatus.values()) {
            if (value.getCode() == status) return value.getDesc();
        }
        return "状态" + status;
    }

    public void requireSession(Long uid, String chatId) {
        var session = sessionMapper.selectById(chatId);
        if (session == null || !Objects.equals(session.getUserId(), uid))
            throw new BusinessException(ResultCode.FORBIDDEN, "无权限访问该会话");
    }

    public List<View> list(Long uid, String chatId) {
        requireSession(uid, chatId);
        return mapper
                .selectList(
                        new LambdaQueryWrapper<AiAction>()
                                .eq(AiAction::getUserId, uid)
                                .eq(AiAction::getChatId, chatId)
                                .orderByDesc(AiAction::getCreateTime)
                                .orderByDesc(AiAction::getId)
                                .last("LIMIT 30"))
                .stream()
                .map(this::view)
                .toList();
    }

    public View prepare(AiIdentity identity, AiActionCommand command) {
        requireSession(identity.userId(), identity.chatId());
        if (command == null) throw new BusinessException("操作不能为空");
        var violations = validator.validate(command);
        if (!violations.isEmpty())
            throw new BusinessException(
                    violations.stream()
                            .map(v -> v.getMessage())
                            .sorted()
                            .collect(Collectors.joining("；")));
        Preview p = preview(identity.userId(), command);
        String payload = encode(command);
        var duplicate =
                mapper.selectList(
                        new LambdaQueryWrapper<AiAction>()
                                .eq(AiAction::getUserId, identity.userId())
                                .eq(AiAction::getChatId, identity.chatId())
                                .eq(AiAction::getStatus, "PENDING")
                                .eq(AiAction::getPayload, payload)
                                .gt(AiAction::getExpiresAt, LocalDateTime.now())
                                .last("LIMIT 1"));
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

    @Transactional(rollbackFor = Exception.class, timeout = 25)
    public View confirm(Long uid, String id) {
        try {
            return confirmOwned(uid,id);
        } catch (feign.RetryableException failure) {
            throw confirmationUnavailable();
        } catch (BusinessException failure) {
            if (failure.getCode() == ResultCode.REMOTE_ERROR.getCode()) throw confirmationUnavailable();
            throw failure;
        }
    }

    private edu.czu.cinema.common.exception.ServiceUnavailableException confirmationUnavailable() {
        return new edu.czu.cinema.common.exception.ServiceUnavailableException(
                "服务暂不可用，办理结果尚未确认。请稍后再次确认本单，系统会先核对原办理结果");
    }

    private View confirmOwned(Long uid, String id) {
        AiAction a = ownedLocked(uid, id);
        if ("DONE".equals(a.getStatus())) return view(a); // 重复点击/重试只返回已执行结果
        if (recover(uid, a)) return view(a);
        if (!"PENDING".equals(a.getStatus()) || !a.getExpiresAt().isAfter(LocalDateTime.now()))
            throw new BusinessException(ResultCode.CONFLICT, "服务单已撤销或过期，请让客服重新查询并生成");
        AiActionCommand c = decode(a.getPayload());
        Preview fresh = preview(uid, c);
        if (!Objects.equals(fresh.summary(), a.getSummary()))
            throw new BusinessException(ResultCode.CONFLICT, "业务信息或金额已变化，请让客服重新生成确认单");
        String result;
        edu.czu.cinema.remote.OperationContext.set(a.getId());
        try {
            switch (c.type()) {
                case CREATE_ORDER -> {
                    Long orderId =
                            orders.createOrder(uid, new OrderAddDTO(c.scheduleId(), c.ticketIds(),c.couponId()));
                    a.setPath("/pay/" + orderId);
                    result = "订单已创建，座位已锁定，请在15分钟内到支付页完成模拟支付。";
                }
                case CANCEL_ORDER -> result = orders.cancel(c.orderId(), uid);
                case REFUND_ORDER -> {
                    var o = orders.detail(c.orderId(), uid);
                    var quote =
                            OrderRefundPolicy.quote(
                                    o.getStatus(),
                                    o.getTotalAmount(),
                                    o.getStartTime(),
                                    LocalDateTime.now());
                    // fresh.summary已核对用户看到的档位，金额取服务单展示值；再次跨档由订单域拒绝。
                    if (!quote.eligible() || !a.getSummary().equals(preview(uid, c).summary()))
                        throw new BusinessException(ResultCode.CONFLICT, "退款信息已变化，请重新生成确认单");
                    result = orders.refund(c.orderId(), uid, quote.amount()) + "（模拟退款记录）";
                }
                case SET_WANT -> {
                    boolean current = movies.isWant(c.movieId(), uid);
                    if (current != c.wanted()) {
                        boolean next = movies.setWant(uid, c.movieId(), c.wanted());
                        if (next != c.wanted())
                            throw new BusinessException(ResultCode.CONFLICT, "想看状态已变化，请重试");
                    }
                    result = c.wanted() ? "已加入想看清单" : "已从想看清单移除";
                }
                case SUBMIT_SCORE -> {
                    if (!scores.submit(
                            uid, new ScoreSubmitDTO(c.orderId(), c.score(), c.content())))
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
        } finally {
            edu.czu.cinema.remote.OperationContext.clear();
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

    private boolean recover(Long uid, AiAction a) {
        // Read the target journal before revalidating a preview. A previous response may have
        // been lost after the remote transaction committed (including after action expiry).
        var type = AiActionCommand.Type.valueOf(a.getType());
        String prior =
                switch (type) {
                    case SET_WANT -> movies.operationResult(a.getId(), uid);
                    case UPDATE_PROFILE -> users.operationResult(a.getId(), uid);
                    default -> orders.operationResult(a.getId(), uid);
                };
        if (prior == null || prior.isBlank()) return false;
        try {
            var value = json.readTree(prior);
            String result =
                    switch (type) {
                        case CREATE_ORDER -> {
                            a.setPath("/pay/" + value.asLong());
                            yield "订单已创建，座位已锁定，请在15分钟内到支付页完成模拟支付。";
                        }
                        case CANCEL_ORDER -> value.asText();
                        case REFUND_ORDER -> value.asText() + "（模拟退款记录）";
                        case SET_WANT -> value.asBoolean() ? "已加入想看清单" : "已从想看清单移除";
                        case SUBMIT_SCORE -> "评价已提交，可在评价页再次编辑。";
                        case UPDATE_PROFILE -> "个人资料已更新。";
                    };
            a.setStatus("DONE");
            a.setResult(result);
            mapper.updateById(a);
            return true;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("操作结果损坏", e);
        }
    }

    private AiAction ownedLocked(Long uid, String id) {
        AiAction a = mapper.lockById(id);
        if (a == null) throw new BusinessException(ResultCode.NOT_FOUND, "服务单不存在");
        if (!Objects.equals(a.getUserId(), uid))
            throw new BusinessException(ResultCode.FORBIDDEN, "服务单不属于当前用户");
        requireSession(uid, a.getChatId());
        return a;
    }

    private Preview preview(Long uid, AiActionCommand c) {
        return switch (c.type()) {
            case CREATE_ORDER -> {
                var q = booking.quote(new OrderAddDTO(c.scheduleId(), c.ticketIds(),c.couponId()));
                String summary=q.summary();
                if(c.couponId()!=null){
                    if(benefits==null)throw new BusinessException("优惠券服务暂不可用");
                    var discount=benefits.quote(new edu.czu.cinema.domain.benefit.CouponContracts.Reserve(uid,c.couponId(),1L,q.total()));
                    summary += "\n使用优惠券 #"+c.couponId()+"，优惠¥"+discount.discount().toPlainString()+"，实付¥"+discount.payable().toPlainString()+"。支付后核销，退票不返券。";
                }
                yield new Preview("确认购票", summary, q.path());
            }
            case CANCEL_ORDER -> {
                var o = order(uid, c.orderId());
                if (!Integer.valueOf(1).equals(o.getStatus()))
                    throw new BusinessException("仅待支付订单可取消");
                yield new Preview(
                        "取消待支付订单",
                        orderSummary(o) + "\n确认后关闭订单并释放座位，不发生退款。",
                        "/order/" + o.getId());
            }
            case REFUND_ORDER -> {
                var o = order(uid, c.orderId());
                var q =
                        OrderRefundPolicy.quote(
                                o.getStatus(),
                                o.getTotalAmount(),
                                o.getStartTime(),
                                LocalDateTime.now());
                if (!q.eligible()) throw new BusinessException(q.reason());
                yield new Preview(
                        "确认申请退票",
                        orderSummary(o)
                                + "\n"
                                + q.reason()
                                + "，预计退款¥"
                                + q.amount().toPlainString()
                                + "；确认后释放座位（模拟退款）。",
                        "/order/" + o.getId());
            }
            case SET_WANT -> {
                if (c.movieId() == null || c.wanted() == null)
                    throw new BusinessException("请指定影片和想看目标状态");
                var m = movies.info(c.movieId()).getBase();
                yield new Preview(
                        c.wanted() ? "加入想看" : "取消想看",
                        "《" + m.getTitle() + "》\n" + (c.wanted() ? "加入" : "移出") + "你的想看清单。",
                        "/me/wants");
            }
            case SUBMIT_SCORE -> {
                var o = order(uid, c.orderId());
                // 资格复用评价模块唯一事实源 ReviewPolicy（OrderVO 的 canReview/reviewReason
                // 已由订单域按 ReviewPolicy.check 口径填充）：2/6 已支付或旧已完成 + 场次有效 + 散场后。
                if (o.isReviewed()) throw new BusinessException("该订单已发表过评价，请到评价页编辑原评价，无需重新拟单");
                if (!o.isCanReview())
                    throw new BusinessException(
                            o.getReviewReason() == null || o.getReviewReason().isBlank()
                                    ? "当前订单暂不可评价（场次已取消、未散场或订单状态不符）"
                                    : o.getReviewReason());
                if (c.score() == null) throw new BusinessException("请先给出1至5分评分");
                yield new Preview(
                        "提交观影评价",
                        orderSummary(o)
                                + "\n评分："
                                + c.score()
                                + "/5\n评论："
                                + (c.content() == null || c.content().isBlank()
                                        ? "（无文字评论）"
                                        : c.content())
                                + "\n提交后订单状态不变，可在评价页再次编辑。",
                        "/score/" + o.getMovieId() + "/" + o.getId());
            }
            case UPDATE_PROFILE -> {
                if (c.name() == null && c.age() == null) throw new BusinessException("请指定昵称或年龄");
                var u = users.getById(uid);
                if (u == null) throw new BusinessException("用户不存在");
                yield new Preview(
                        "更新个人资料",
                        (c.name() == null ? "" : "昵称：" + u.getName() + " → " + c.name() + "\n")
                                + (c.age() == null ? "" : "年龄：" + u.getAge() + " → " + c.age()),
                        "/me/info");
            }
        };
    }

    private OrderVO order(Long uid, Long id) {
        if (id == null) throw new BusinessException("请先明确要办理的订单");
        return orders.detail(id, uid);
    }

    private String orderSummary(OrderVO o) {
        return "订单"
                + o.getNumber()
                + " · 《"
                + o.getMovieName()
                + "》\n"
                + o.getHallName()
                + " · "
                + o.getStartTime()
                + " · "
                + (o.getSeatLabels() == null ? "" : String.join("、", o.getSeatLabels()))
                + "\n订单金额¥"
                + o.getTotalAmount().toPlainString();
    }

    private View view(AiAction a) {
        String status =
                "PENDING".equals(a.getStatus()) && !a.getExpiresAt().isAfter(LocalDateTime.now())
                        ? "EXPIRED"
                        : a.getStatus();
        return new View(
                a.getId(),
                a.getType(),
                a.getTitle(),
                a.getSummary(),
                status,
                a.getExpiresAt(),
                a.getResult(),
                a.getPath());
    }

    private String encode(AiActionCommand c) {
        try {
            return json.writeValueAsString(c);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("服务单序列化失败", e);
        }
    }

    private AiActionCommand decode(String text) {
        try {
            return json.readValue(text, AiActionCommand.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("服务单数据损坏", e);
        }
    }
}
