package edu.czu.cinema.domain.order.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.OrderNumberGenerator;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.hall.entity.Hall;
import edu.czu.cinema.domain.hall.mapper.HallMapper;
import edu.czu.cinema.domain.hall.service.SeatLabelService;
import edu.czu.cinema.domain.hall.service.SeatVersionService;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.mapper.MovieMapper;
import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import edu.czu.cinema.domain.order.entity.Order;
import edu.czu.cinema.domain.order.entity.OrderAction;
import edu.czu.cinema.domain.order.enums.OrderStatus;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.domain.order.service.OrderAuditService;
import edu.czu.cinema.domain.order.service.OrderCloseHelper;
import edu.czu.cinema.domain.order.service.OrderService;
import edu.czu.cinema.domain.order.service.OrderRefundPolicy;
import edu.czu.cinema.domain.score.mapper.ScoreMapper;
import edu.czu.cinema.domain.score.entity.Score;
import edu.czu.cinema.domain.score.service.ReviewPolicy;
import edu.czu.cinema.domain.order.vo.OrderVO;
import edu.czu.cinema.domain.schedule.entity.Schedule;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import edu.czu.cinema.domain.schedule.service.ScheduleService;
import edu.czu.cinema.domain.ticket.entity.Ticket;
import edu.czu.cinema.domain.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单服务 —— 2026-10-20 订单域修复:
 * <ul>
 *   <li>createOrder: 票数 1~6 断言、票 id 去重、金额 &gt;0 断言、订单号碰撞重试(P1-T5)、审计 CREATE(P1-T1)</li>
 *   <li>closeIfExpired → {@link OrderCloseHelper} REQUIRES_NEW 原子关单(P0-3), pay 内惰性关单不再被外层回滚(P1-T3)</li>
 *   <li>pay: 支付落 pay_time/pay_channel + 审计 PAY; CAS 后复核场次状态(P0-4 配合点)</li>
 *   <li>refund: 审计 REFUND(金额=实退额, reason 注明档位); cancel: 审计 CLOSE</li>
 *   <li>状态机统一走 {@link OrderStatus} 枚举(P1-T6); toVOs 批量预取消灭 N+1(P1-D7)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    /** 订单号唯一键碰撞时的换随机段重试次数(P1-T5). */
    private static final int ORDER_NUMBER_MAX_RETRY = 3;

    /** 单次下单票数上限 —— 与 OrderAddDTO @Size 保持一致, 服务层兜底. */
    private static final int MAX_TICKETS_PER_ORDER = 6;

    private final OrderMapper orderMapper;
    private final ScheduleService scheduleService;
    /** 直注 mapper 做场次存在性/状态校验, 不经 service, 也避免与排片域的服务耦合. */
    private final ScheduleMapper scheduleMapper;
    private final TicketService ticketService;
    private final SeatVersionService seatVersionService;
    private final SeatLabelService seatLabelService;
    private final ScoreMapper scoreMapper;
    /** 批量预取影片/影厅用 mapper 一次 IN 查询, 不走 service 的单条 info(P1-D7). */
    private final MovieMapper movieMapper;
    private final HallMapper hallMapper;
    private final OrderAuditService orderAuditService;
    /** 关单独立事务, 不能自调用 —— 详见 {@link OrderCloseHelper}. */
    private final OrderCloseHelper closeHelper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createOrder(OrderAddDTO dto) {
        Long userId = SecurityUtils.requireUserId();
        // 0. 惰性: 先把本人所有超期待支付单关掉(防囤票). 每单独立事务提交, 这里失败不影响下单.
        try { closeExpiredOf(userId); } catch (Exception ignored) { /* 让下单继续 */ }

        // 0a. 场次校验
        Schedule schedule = scheduleMapper.selectById(dto.scheduleId());
        if (schedule == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "场次不存在");
        }
        if (schedule.getStatus() == null || schedule.getStatus() != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "场次已取消，无法下单");
        }
        if (!LocalDateTime.now().isBefore(schedule.getStartTime())) {
            throw new BusinessException(ResultCode.CONFLICT, "场次已开场，无法下单");
        }
        // 0b. 票数断言 + 显式去重 —— 防止 [a, a, b] 凭巧合通过 size 比对又锁两次同一张
        if (dto.ticketIds() == null || dto.ticketIds().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请至少选择一个座位");
        }
        if (dto.ticketIds().size() > MAX_TICKETS_PER_ORDER) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "一次最多购买 " + MAX_TICKETS_PER_ORDER + " 张票");
        }
        List<Long> distinctIds = dto.ticketIds().stream().distinct().toList();
        if (distinctIds.size() != dto.ticketIds().size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请勿重复选择同一座位");
        }
        // 1. 票存在 + 归属 + 可售
        List<Ticket> tickets = ticketService.listByIds(distinctIds);
        if (tickets.size() != distinctIds.size()) {
            throw new BusinessException(ResultCode.ORDER_LOCK_FAILED);
        }
        for (Ticket t : tickets) {
            if (!Objects.equals(t.getScheduleId(), dto.scheduleId())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "所选票与场次不匹配");
            }
            if (t.getStatus() == null || t.getStatus() != 1) {
                throw new BusinessException(ResultCode.ORDER_LOCK_FAILED);
            }
        }
        // 1b. 情侣座必须成对买 —— 前端辅助, 后端兜底
        assertCouplePairsIntact(tickets);

        // 2. 写订单. 金额定长两位小数(DECIMAL(10,2)), 且必须为正 —— 挡住负价/异常票价穿透
        BigDecimal total = tickets.stream().map(Ticket::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        if (total.signum() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单金额非法，请联系影院工作人员");
        }
        Order order = new Order();
        order.setUserId(userId);
        order.setScheduleId(dto.scheduleId());
        order.setTotalAmount(total);
        order.setStatus(OrderStatus.PENDING.getCode());
        order.setCreateTime(LocalDateTime.now());
        insertWithUniqueNumber(order, userId);

        // 3. 锁票: 全部锁住才算成功. 部分锁(并发抢走几张)整单回滚, 退钱退票退订单.
        int affected = ticketService.lockTickets(distinctIds, order.getId());
        if (affected != distinctIds.size()) {
            throw new BusinessException(ResultCode.ORDER_LOCK_FAILED, "座位被抢了一部分，请刷新重选");
        }
        // 审计: 下单锁座落一条 CREATE(与业务同事务, 随部分锁回滚一起消失)
        orderAuditService.record(order.getId(), order.getNumber(), OrderAction.ACTION_CREATE,
                null, OrderStatus.PENDING.getCode(), total,
                OrderAction.OP_USER, userId, "下单锁定座位");
        return order.getId();
    }

    /**
     * 订单号落库 + 唯一键碰撞兜底(P1-T5).
     * V22 给 order.number 加了唯一键, 同秒同用户 6 位随机段撞上会抛 DuplicateKey ——
     * 重新调生成器换随机段重试, 而不是把冲突当 500 漏给用户.
     */
    private void insertWithUniqueNumber(Order order, Long userId) {
        for (int attempt = 0; attempt <= ORDER_NUMBER_MAX_RETRY; attempt++) {
            order.setNumber(OrderNumberGenerator.generate(userId));
            try {
                orderMapper.insert(order);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt == ORDER_NUMBER_MAX_RETRY) {
                    throw new BusinessException(ResultCode.CONFLICT, "订单号生成冲突，请稍后重试");
                }
                // generate() 每次重掷 6 位随机段, 下一轮即换随机段
            }
        }
    }

    /** 关闭当前用户所有超期待支付单 —— createOrder 入口的惰性清理. */
    public void closeExpiredOf(Long userId) {
        List<Order> expired = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getStatus, OrderStatus.PENDING.getCode())
                .lt(Order::getCreateTime, LocalDateTime.now().minusMinutes(PAY_TIMEOUT_MINUTES)));
        for (Order o : expired) {
            closeHelper.closePending(o.getId(), "超时未支付，系统自动关闭", OrderAction.OP_SYSTEM, null);
        }
    }

    @Override
    public boolean closeIfExpired(Long orderId) {
        // 独立事务原子提交「置 4 + 放票」; 被并发抢先(非 status=1)时返回 false 静默放过
        return closeHelper.closePending(orderId, "超时未支付，系统自动关闭", OrderAction.OP_SYSTEM, null);
    }

    @Override
    public OrderVO detail(Long orderId, Long currentUserId) {
        Order o = orderMapper.selectById(orderId);
        if (o == null) throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        // C2 IDOR 修复: 必须本人. 以前没有所有权校验, 任意登录用户可枚举 id 读他人订单.
        if (!Objects.equals(o.getUserId(), currentUserId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "订单不属于当前用户");
        }
        // 惰性: 详情页访问时如果单子已超期, 先关掉再重读
        if (OrderStatus.isPending(o.getStatus()) && isExpired(o)) {
            closeIfExpired(orderId);
            o = orderMapper.selectById(orderId);
        }
        return toVOs(List.of(o)).get(0);
    }

    /** 订单创建时间早于 now-PAY_TIMEOUT_MINUTES 即视为超时. */
    private boolean isExpired(Order o) {
        if (o.getCreateTime() == null) return false;
        return o.getCreateTime().isBefore(LocalDateTime.now().minusMinutes(PAY_TIMEOUT_MINUTES));
    }

    /**
     * 校验情侣座成对 —— 必须成对买, 否则半张永远卖不出去.
     * seatId(全局唯一)查组, 不区分厅/版本.
     */
    private void assertCouplePairsIntact(List<Ticket> tickets) {
        Set<Long> seatIds = tickets.stream()
                .map(Ticket::getSeatId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (seatIds.isEmpty()) return;
        for (var g : seatVersionService.coupleGroupsCovering(seatIds)) {
            boolean hasLeft = seatIds.contains(g.getSeatId1());
            boolean hasRight = seatIds.contains(g.getSeatId2());
            if (hasLeft != hasRight) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "情侣座需要成对购买, 请把相邻的另一半也选上");
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String refund(Long orderId, Long currentUserId) {
        return refund(orderId, currentUserId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String refund(Long orderId, Long currentUserId, BigDecimal expectedAmount) {
        Order o = orderMapper.selectById(orderId);
        if (o == null) throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        if (!Objects.equals(o.getUserId(), currentUserId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "订单不属于当前用户");
        }
        // M2 白名单: 仅已支付可退; 待支付引导去取消
        if (!Objects.equals(o.getStatus(), OrderStatus.PAID.getCode())) {
            if (OrderStatus.isPending(o.getStatus())) {
                throw new BusinessException(ResultCode.CONFLICT, "订单未支付，请使用「取消订单」");
            }
            throw new BusinessException(ResultCode.CONFLICT, "订单当前状态不可退款");
        }
        Schedule schedule = scheduleService.getById(o.getScheduleId());
        if (schedule == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "场次不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(schedule.getStartTime())) {
            throw new BusinessException(ResultCode.CONFLICT, "场次已开场，不可退款");
        }
        var quote = OrderRefundPolicy.quote(o.getStatus(), o.getTotalAmount(), schedule.getStartTime(), now);
        if (!quote.eligible()) throw new BusinessException(ResultCode.ORDER_REFUND_DENIED, quote.reason());
        BigDecimal amount = quote.amount();
        String reason = quote.reason();
        if (expectedAmount != null && expectedAmount.compareTo(amount) != 0)
            throw new BusinessException(ResultCode.CONFLICT, "退款金额已变化，请重新查询并确认");
        // M5 条件 UPDATE: WHERE status=2 防与 cancel/并发 pay 竞态
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, OrderStatus.PAID.getCode())
                .set(Order::getStatus, OrderStatus.REFUNDED.getCode())
                .set(Order::getRefundAmount, amount)
                .set(Order::getRefundTime, now));
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "订单状态已变化，请刷新重试");
        }
        ticketService.releaseTickets(orderId);
        // 审计: 金额=实退额, reason 里已注明档位(全额退款 / 3-6h 按 70%)
        orderAuditService.record(orderId, o.getNumber(), OrderAction.ACTION_REFUND,
                OrderStatus.PAID.getCode(), OrderStatus.REFUNDED.getCode(),
                amount, OrderAction.OP_USER, currentUserId, reason);
        return "已" + reason + "，退款 ¥" + amount.toPlainString();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String cancel(Long orderId, Long currentUserId) {
        Order o = orderMapper.selectById(orderId);
        if (o == null) throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        if (!Objects.equals(o.getUserId(), currentUserId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "订单不属于当前用户");
        }
        if (OrderStatus.isPending(o.getStatus())) {
            // 与超时关单同一条件关闭语义(置 4 + 放票原子提交), 仅原因/操作者不同
            boolean closed = closeHelper.closePending(orderId, "用户取消订单", OrderAction.OP_USER, currentUserId);
            return closed ? "订单已取消，座位已释放" : "订单状态已变化，请刷新重试";
        }
        throw new BusinessException(ResultCode.CONFLICT, "订单当前状态不可取消");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO pay(Long orderId, String payChannel, Long currentUserId) {
        if (payChannel == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "支付渠道不能为空");
        }
        String ch = payChannel.toUpperCase();
        if (!"WECHAT".equals(ch) && !"ALIPAY".equals(ch) && !"CARD".equals(ch)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "非法支付渠道");
        }
        Order o = orderMapper.selectById(orderId);
        if (o == null) throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        if (!Objects.equals(o.getUserId(), currentUserId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "订单不属于当前用户");
        }
        if (!OrderStatus.isPending(o.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "订单状态不可支付（可能已支付、超时关闭或已退款）");
        }
        // 惰性: 超期单先关再拒付, 避免误付给一个早该释放的座位.
        // 关单走 REQUIRES_NEW 独立提交 —— 否则本方法随后抛异常, 关单会被一起回滚(P1-T3).
        if (isExpired(o)) {
            closeIfExpired(orderId);
            throw new BusinessException(ResultCode.CONFLICT, "订单已超时关闭，请重新下单");
        }
        // 场次已取消/已开场 → 拒付.
        // P0-4: 用锁定读（FOR UPDATE）拿场次行，与 cancel 的锁顺序一致（场次→订单）：
        // ① 普通快照读在 RR 下可能看不到并发 cancel 刚提交的 status=2；
        // ② 先锁场次再锁订单，避免与 cancel（先锁场次再改订单）形成 ABBA 死锁。
        Schedule sc = scheduleMapper.selectByIdForUpdate(o.getScheduleId());
        if (sc == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "场次不存在");
        }
        if (sc.getStatus() == null || sc.getStatus() != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "场次已取消，无法支付");
        }
        if (!LocalDateTime.now().isBefore(sc.getStartTime())) {
            throw new BusinessException(ResultCode.CONFLICT, "场次已开场，无法支付");
        }
        // C5 / M5 条件 UPDATE: WHERE status=1, 0 行 → 竞态下别人先付/取消了.
        // 支付落账: pay_time/pay_channel 与状态同条 UPDATE 提交(P1-T1).
        LocalDateTime payTime = LocalDateTime.now();
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, OrderStatus.PENDING.getCode())
                .set(Order::getStatus, OrderStatus.PAID.getCode())
                .set(Order::getPayTime, payTime)
                .set(Order::getPayChannel, ch));
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "订单状态已变化，请刷新重试");
        }
        // P0-4 配合点: CAS 成功后复核场次. 取消场次的事务可能刚把场次置 2,
        // 快照读看不到 —— 这里重读一次, 发现已取消/已开场就抛异常让整个 pay 事务回滚
        // (钱没真正收、状态回到待支付), 由取消场次退款流程统一关单退款.
        Schedule recheck = scheduleMapper.selectById(o.getScheduleId());
        if (recheck == null || recheck.getStatus() == null || recheck.getStatus() != 1
                || !LocalDateTime.now().isBefore(recheck.getStartTime())) {
            throw new BusinessException(ResultCode.CONFLICT, "场次已取消或已开场，支付未生效");
        }
        orderAuditService.record(orderId, o.getNumber(), OrderAction.ACTION_PAY,
                OrderStatus.PENDING.getCode(), OrderStatus.PAID.getCode(),
                o.getTotalAmount(), OrderAction.OP_USER, currentUserId, "支付渠道 " + ch);
        // 轻量 VO: 支付接口不再全量组装(原 detail() 在行锁事务内 ~10 条 SQL, 放大锁窗口)
        return lightVO(orderId, o);
    }

    /** 支付结果用轻量 VO —— 只带前端支付页要的四个字段. */
    private OrderVO lightVO(Long orderId, Order o) {
        OrderVO vo = new OrderVO();
        vo.setId(orderId);
        vo.setNumber(o.getNumber());
        vo.setStatus(OrderStatus.PAID.getCode());
        vo.setTotalAmount(o.getTotalAmount());
        return vo;
    }

    @Override
    public List<OrderVO> listByUser(Long userId, Integer status) {
        return listByUser(userId, status, null, null);
    }

    @Override
    public List<OrderVO> listByUser(Long userId, Integer status, Integer page, Integer limit) {
        if (userId == null) return List.of();
        LambdaQueryWrapper<Order> wrap = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                // QUERY_ALL(5) 是查询哨兵不落库, 传 5 即不加状态条件
                .eq(status != null && status != OrderStatus.QUERY_ALL, Order::getStatus, status)
                .orderByDesc(Order::getCreateTime);
        List<Order> list;
        if (page != null && limit != null && limit > 0) {
            // 可选分页: 旧前端不传即全量, 接口行为不变
            list = orderMapper.selectPage(Page.of(Math.max(page, 1), limit), wrap).getRecords();
        } else {
            list = orderMapper.selectList(wrap);
        }
        return toVOs(list);
    }

    @Override
    public Map<Integer, Long> countByStatus(Long userId) {
        if (userId == null) return Map.of();
        // 一条 GROUP BY 出全部状态计数, 不再按状态逐个 selectCount(P1-D7).
        // 含 4(已关闭) —— 之前只有 1/2/3/6, 「已关闭」tab 角标会一直为 0.
        List<Map<String, Object>> rows = orderMapper.selectMaps(new QueryWrapper<Order>()
                .select("status", "COUNT(*) AS cnt")
                .eq("user_id", userId)
                .groupBy("status"));
        Map<Integer, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object s = row.get("status");
            Object c = row.get("cnt");
            if (s instanceof Number sn && c instanceof Number cn) {
                long cnt = cn.longValue();
                if (cnt > 0) {
                    result.put(sn.intValue(), cnt);
                }
            }
        }
        return result;
    }

    @Override
    public PageResult<OrderVO> adminPage(int page, int size, Integer status, String keyword) {
        Page<Order> req = Page.of(page, size);
        LambdaQueryWrapper<Order> wrap = new LambdaQueryWrapper<Order>()
                .eq(status != null && status != OrderStatus.QUERY_ALL, Order::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), Order::getNumber, keyword)
                .orderByDesc(Order::getCreateTime);
        Page<Order> result = orderMapper.selectPage(req, wrap);
        List<OrderVO> vos = toVOs(result.getRecords());
        return PageResult.of(result.getTotal(), vos);
    }

    /**
     * Order → OrderVO 批量组装(P1-D7).
     *
     * <p>按 id 分组预取: 场次/影片/影厅各一次 IN 查询, 座位标签按 versionId 去重后各解析一次,
     * 不再每行重查 schedule/movie/hall. 票仍逐单查 —— TicketService 没有按订单批量的接口,
     * 且 ticket(order_id,status) 有索引兜底, 单行索引查代价可接受.
     */
    private List<OrderVO> toVOs(List<Order> rows) {
        if (rows.isEmpty()) return List.of();
        Set<Long> orderIds = rows.stream().map(Order::getId).collect(Collectors.toSet());
        // 评价标记: 一条 IN 查询批量取, 不再逐单 selectCount
        Set<Long> reviewed = scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                        .in(Score::getOrderId, orderIds))
                .stream().map(Score::getOrderId).collect(Collectors.toSet());

        Map<Long, Schedule> schedules = batchByIds(
                rows.stream().map(Order::getScheduleId).filter(Objects::nonNull).collect(Collectors.toSet()),
                scheduleMapper::selectBatchIds, Schedule::getId);
        Set<Long> movieIds = schedules.values().stream().map(Schedule::getMovieId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Movie> movies = batchByIds(movieIds, movieMapper::selectBatchIds, Movie::getId);
        Set<Long> hallIds = schedules.values().stream().map(Schedule::getHallId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Hall> halls = batchByIds(hallIds, hallMapper::selectBatchIds, Hall::getId);

        // 座位标签: labelsOf 按版本实时解析(无跨版本缓存), 这里按 versionId 去重后每版本只算一次
        Map<Long, Map<Long, String>> labelsByVersion = new HashMap<>();
        for (Schedule s : schedules.values()) {
            labelsByVersion.computeIfAbsent(s.getSeatVersionId(), seatLabelService::labelsOf);
        }
        return rows.stream()
                .map(o -> toVO(o, reviewed.contains(o.getId()), schedules, movies, halls, labelsByVersion))
                .toList();
    }

    /** selectBatchIds 空集合会生成非法 IN (), 先挡掉. */
    private <T> Map<Long, T> batchByIds(Set<Long> ids,
                                        java.util.function.Function<Set<Long>, List<T>> loader,
                                        java.util.function.Function<T, Long> idGetter) {
        if (ids.isEmpty()) return Map.of();
        return loader.apply(ids).stream()
                .collect(Collectors.toMap(idGetter, t -> t, (a, b) -> a));
    }

    private OrderVO toVO(Order o, boolean reviewed,
                         Map<Long, Schedule> schedules, Map<Long, Movie> movies, Map<Long, Hall> halls,
                         Map<Long, Map<Long, String>> labelsByVersion) {
        OrderVO vo = new OrderVO();
        // 平铺字段(id/number/status/totalAmount/...)由 copyProperties 同名带出;
        // 2026-10-19 起不再挂 base 嵌套对象, 前端 OrderInfo 期望扁平结构.
        BeanUtil.copyProperties(o, vo);
        vo.setCreateTime(o.getCreateTime());
        vo.setReviewed(reviewed);
        Schedule schedule = schedules.get(o.getScheduleId());
        Integer runtime = null;
        if (schedule != null) {
            vo.setStartTime(schedule.getStartTime());
            vo.setMovieId(schedule.getMovieId());
            try {
                Movie m = movies.get(schedule.getMovieId());
                if (m != null) {
                    runtime = m.getRuntimeMin();
                    vo.setMovieName(m.getTitle());
                    vo.setPoster(m.getPosterUrl());
                }
            } catch (Exception ignored) {
                // 影片已删: 名称/海报留空, 订单列表照常出
            }
            Hall h = halls.get(schedule.getHallId());
            vo.setHallName(h == null ? null : h.getName());
            // 座位可读标签: 用该场次锁定的版本号; 老场次 seatVersionId 为空时
            // labelsOf(null) 返回空 map(见 SeatLabelService), 座位标签留空.
            Map<Long, String> labels = labelsByVersion.getOrDefault(schedule.getSeatVersionId(), Map.of());
            List<Ticket> tickets = ticketService.listByOrder(o.getId());
            vo.setSeatLabels(tickets.stream().map(t -> labels.getOrDefault(t.getSeatId(), "")).toList());
        }
        var eligibility = ReviewPolicy.check(o.getStatus(), schedule, runtime, LocalDateTime.now());
        vo.setCanReview(eligibility.allowed());
        vo.setReviewAvailableAt(eligibility.availableAt());
        vo.setReviewReason(eligibility.reason());
        return vo;
    }
}
