package edu.czu.cinema.domain.schedule.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.domain.admin.entity.AdminOperationLog;
import edu.czu.cinema.domain.admin.service.AdminAuditService;
import edu.czu.cinema.domain.hall.entity.Hall;
import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.hall.service.HallService;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.service.MovieService;
import edu.czu.cinema.domain.order.entity.Order;
import edu.czu.cinema.domain.order.entity.OrderAction;
import edu.czu.cinema.domain.order.enums.OrderStatus;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.domain.order.service.OrderAuditService;
import edu.czu.cinema.domain.schedule.dto.ScheduleAddDTO;
import edu.czu.cinema.domain.schedule.dto.ScheduleBatchAddDTO;
import edu.czu.cinema.domain.schedule.dto.ScheduleBatchMoveDTO;
import edu.czu.cinema.domain.schedule.dto.ScheduleUpdateDTO;
import edu.czu.cinema.domain.schedule.entity.Schedule;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import edu.czu.cinema.domain.schedule.service.ScheduleService;
import edu.czu.cinema.domain.schedule.vo.ScheduleBatchInfoVO;
import edu.czu.cinema.domain.schedule.vo.ScheduleBatchResultVO;
import edu.czu.cinema.domain.schedule.vo.ScheduleSeatsVO;
import edu.czu.cinema.domain.ticket.entity.Ticket;
import edu.czu.cinema.domain.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScheduleServiceImpl implements ScheduleService {

    /** 票价的兜底值, 与 TicketServiceImpl 的口径保持一致. */
    private static final BigDecimal DEFAULT_PRICE = new BigDecimal("50");
    private static final BigDecimal DEFAULT_VIP_MULTIPLIER = new BigDecimal("3.00");
    private static final BigDecimal DEFAULT_COUPLE_MULTIPLIER = new BigDecimal("2.50");

    private final ScheduleMapper scheduleMapper;
    private final edu.czu.cinema.domain.movie.mapper.MovieMapper movieMapper;
    private final MovieService movieService;
    private final HallService hallService;
    private final TicketService ticketService;
    private final edu.czu.cinema.domain.hall.service.SeatVersionService seatVersionService;
    /** 跨域直写订单(取消场次自动退款), 仿 ScoreServiceImpl 的做法, 避免服务间循环依赖. */
    private final OrderMapper orderMapper;
    /** 资金操作审计（取消场次自动退款逐单留痕）. */
    private final OrderAuditService orderAuditService;
    /** 高危管理操作审计（取消场次记操作者/场次/退款笔数）. */
    private final AdminAuditService adminAuditService;

    /**
     * 排片分页.
     *
     * <p>status 契约: {@code null / 0 = 全部, 1 = 正常, 2 = 已取消}。
     * "已放映"不落库, 由 {@code endTime < now} 推导。
     */
    @Override
    public PageResult<Schedule> page(int page, int size, LocalDate date, Long hallId, Long movieId, Integer status) {
        Page<Schedule> req = Page.of(page, size);
        LambdaQueryWrapper<Schedule> wrap = new LambdaQueryWrapper<Schedule>()
                .eq(hallId != null, Schedule::getHallId, hallId)
                .eq(movieId != null, Schedule::getMovieId, movieId)
                .eq(status != null && status != 0, Schedule::getStatus, status)
                .orderByDesc(Schedule::getStartTime);
        if (date != null) wrap.ge(Schedule::getStartTime, date.atStartOfDay())
                .lt(Schedule::getStartTime, date.plusDays(1).atStartOfDay());
        Page<Schedule> result = scheduleMapper.selectPage(req, wrap);
        List<Schedule> rows = result.getRecords();
        if (CollUtil.isNotEmpty(rows)) {
            Set<Long> movieIds = rows.stream().map(Schedule::getMovieId).collect(Collectors.toSet());
            Set<Long> hallIds = rows.stream().map(Schedule::getHallId).collect(Collectors.toSet());

            Map<Long, String> movieNames = movieMapper.selectBatchIds(movieIds).stream()
                    .collect(Collectors.toMap(Movie::getId, Movie::getTitle));
            Map<Long, String> hallNames = hallService.listAll().stream()
                    .collect(Collectors.toMap(Hall::getId, Hall::getName));

            rows.forEach(r -> {
                r.setMovieName(movieNames.get(r.getMovieId()));
                r.setHallName(hallNames.get(r.getHallId()));
            });
        }
        return PageResult.of(result.getTotal(), rows);
    }

    @Override
    public Schedule getById(Long id) {
        Schedule s = scheduleMapper.selectById(id);
        if (s != null) {
            // 已售座位 = 被订单占用的票(status=0). 取消场次作废的票(status=2)不算已售,
            // 但保留在票表里供订单回看座位 —— 见 TicketServiceImpl.voidBySchedule.
            s.setSoldSeatIds(ticketService.listBySchedule(id).stream()
                    .filter(t -> t.getSeatId() != null && t.getStatus() != null && t.getStatus() == 0)
                    .map(Ticket::getSeatId)
                    .toList());
            // 直查 mapper 取海报: 影片被删时不能抛异常把订单详情/退款一起弄挂
            Movie m = movieMapper.selectById(s.getMovieId());
            if (m != null) {
                s.setPoster(m.getPosterUrl());
            }
        }
        return s;
    }

    @Override
    @Transactional
    public Long add(ScheduleAddDTO dto) {
        // 先锁影厅行: 串行化同厅并发排片, 否则两个请求都能通过冲突检测插进重叠时段
        scheduleMapper.lockHall(dto.hallId());
        Movie movieInfo = requireMovie(dto.movieId());
        Hall hall = requireHall(dto.hallId());
        List<Seat> seats = requireSeats(hall);
        // 单场排片不打批次号(NULL) —— 拖拽改期不弹批量确认
        return doAdd(movieInfo, hall, seats, dto.startTime(), dto.price(),
                dto.vipPriceMultiplier(), dto.couplePriceMultiplier(), null).getId();
    }

    /**
     * 批量排片.
     *
     * <p>影片/影厅/座位图做整批前置校验 —— 这类失败每一行都会失败, 直接抛错不产生部分结果;
     * 行级校验(时间已过去/时段冲突)逐行捕获, 跳过并记录原因, 其余行正常入库。
     * 与库中已有场次的冲突、与本批先插入行的冲突(同事务内可见)都会被判为跳过。
     */
    @Override
    @Transactional
    public ScheduleBatchResultVO batchAdd(ScheduleBatchAddDTO dto) {
        scheduleMapper.lockHall(dto.hallId());
        Movie movieInfo = requireMovie(dto.movieId());
        Hall hall = requireHall(dto.hallId());
        List<Seat> seats = requireSeats(hall);

        // 同一次批量共享一个批次号 —— 时间轴拖拽改期时据此弹「整批/单天」确认
        String batchId = UUID.randomUUID().toString();
        ScheduleBatchResultVO result = new ScheduleBatchResultVO();
        for (ScheduleBatchAddDTO.Session s : dto.sessions()) {
            try {
                Schedule po = doAdd(movieInfo, hall, seats, s.startTime(),
                        s.price() != null ? s.price() : dto.price(),
                        dto.vipPriceMultiplier(), dto.couplePriceMultiplier(), batchId);
                result.getCreated().add(new ScheduleBatchResultVO.Created(s.startTime(), po.getId()));
            } catch (BusinessException e) {
                result.getSkipped().add(new ScheduleBatchResultVO.Skipped(s.startTime(), e.getMessage()));
            } catch (Exception e) {
                // 行级非业务异常(如唯一键撞库的 DuplicateKey)也只跳过该行, 不让整批 500 ——
                // 其余行照常入库并返回 created/skipped 明细, 管理员能对着明细补录.
                result.getSkipped().add(new ScheduleBatchResultVO.Skipped(s.startTime(),
                        "写入失败: " + e.getMessage()));
            }
        }
        return result;
    }

    /**
     * 批量改期: 「同时段整体改时刻」.
     *
     * <p>同一批次({@code batchId})里所有**开场时刻与 sourceStartTime 相同**的正常场次,
     * 改到各自日期上的 targetStartTime 时刻; 批次里不同时刻的场次不动。
     * 与批量排片同口径: 时间已过/已开场/时段冲突的场次跳过并记录原因, 其余改期。
     * 只改 startTime/endTime(endTime 按**该场自己的影片**当前时长+30 重算), 不动票价。
     *
     * <p>并发口径(P0-6): 批次取数用锁定读(当前读)拿最新已提交行, 紧接着 {@code lockHall}
     * 串行化同厅排片/改期, 冲突检查(listConflict)再走锁定读 —— 三步之后才写.
     * 早先先建 RR 快照后才 lockHall, 快照看不到窗口内并发改期/排片刚提交的场次,
     * 两个管理员能把同一厅排进重叠时段(双卖)。
     *
     * @return created = 已改期场次(startTime 为新时刻); skipped.startTime = 该场原时刻(定位用)
     */
    @Override
    @Transactional
    public ScheduleBatchResultVO batchMove(ScheduleBatchMoveDTO dto) {
        // 锁定读取批次: FOR UPDATE 是当前读, 读得到并发事务刚提交的改期/取消,
        // 同时把这批行锁住, 不会被别的改期在脚下动. 拿到 hallId 后立即 lockHall.
        List<Schedule> batch = scheduleMapper.selectList(new LambdaQueryWrapper<Schedule>()
                .eq(Schedule::getBatchId, dto.batchId())
                .eq(Schedule::getStatus, 1)
                .last("FOR UPDATE"));
        if (batch.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "批次不存在");
        }
        LocalTime sourceTime = dto.sourceStartTime().toLocalTime();
        LocalTime targetTime = dto.targetStartTime().toLocalTime();
        List<Schedule> sameSlot = batch.stream()
                .filter(s -> s.getStartTime() != null && s.getStartTime().toLocalTime().equals(sourceTime))
                .toList();

        ScheduleBatchResultVO result = new ScheduleBatchResultVO();
        if (sameSlot.isEmpty()) {
            return result;
        }
        // 先锁厅再做冲突检查: 锁厅之后的读才算数 —— 同厅的排片新增/改期都被串行化.
        // (批次单厅: batchAdd 一次一厅一片; 逐场冲突检测用自己的 hallId/excludeId)
        scheduleMapper.lockHall(sameSlot.get(0).getHallId());

        // 逐场按自己的 movieId 取时长: 批次名义上单片, 但历史数据可能混片,
        // 用批次首场时长会给别的片算错散场时间.
        Map<Long, Movie> movieCache = new HashMap<>();
        for (Schedule s : sameSlot) {
            LocalDateTime newStart = s.getStartTime().toLocalDate().atTime(targetTime);
            try {
                if (!LocalDateTime.now().isBefore(s.getStartTime())) {
                    throw new BusinessException(ResultCode.CONFLICT, "场次已开场");
                }
                if (!newStart.isAfter(LocalDateTime.now())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "开场时间必须晚于当前时间");
                }
                Movie movieInfo = movieCache.computeIfAbsent(s.getMovieId(), this::requireMovie);
                LocalDateTime newEnd = newStart.plusMinutes(movieInfo.getRuntimeMin()).plusMinutes(30);
                List<Schedule> conflicts = scheduleMapper.listConflict(
                        s.getHallId(), newStart, newEnd, s.getId());
                if (!conflicts.isEmpty()) {
                    throw new BusinessException(ResultCode.CONFLICT, "排片时间冲突");
                }
                Schedule po = new Schedule();
                po.setId(s.getId());
                po.setStartTime(newStart);
                po.setEndTime(newEnd);
                scheduleMapper.updateById(po);
                result.getCreated().add(new ScheduleBatchResultVO.Created(newStart, s.getId()));
            } catch (BusinessException e) {
                result.getSkipped().add(new ScheduleBatchResultVO.Skipped(s.getStartTime(), e.getMessage()));
            }
        }
        return result;
    }

    /** 批次同时段规模: total=同批次同时段的正常场次数, movable=其中未开场、可改期的. */
    @Override
    public ScheduleBatchInfoVO batchInfo(String batchId, LocalTime time) {
        List<Schedule> sameSlot = scheduleMapper.selectList(new LambdaQueryWrapper<Schedule>()
                .eq(Schedule::getBatchId, batchId)
                .eq(Schedule::getStatus, 1))
                .stream()
                .filter(s -> s.getStartTime() != null && s.getStartTime().toLocalTime().equals(time))
                .toList();
        LocalDateTime now = LocalDateTime.now();
        ScheduleBatchInfoVO vo = new ScheduleBatchInfoVO();
        vo.setTotal(sameSlot.size());
        vo.setMovable((int) sameSlot.stream()
                .filter(s -> s.getStartTime().isAfter(now))
                .count());
        return vo;
    }

    /** 影片存在性 + 时长校验. 时长约定: runtimeMin=0 是待映未公布时长, 不能排片(无法算冲突). */
    private Movie requireMovie(Long movieId) {
        Movie movieInfo = movieService.info(movieId).getBase();
        if (movieInfo == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "电影不存在");
        }
        if (movieInfo.getRuntimeMin() == null || movieInfo.getRuntimeMin() <= 0) {
            throw new BusinessException(ResultCode.CONFLICT, "影片时长尚未公布，请补全资料后再排片");
        }
        return movieInfo;
    }

    /** 影厅要存在, 而且要有已发布的座位图 —— 否则排完片没有座位可卖. */
    private Hall requireHall(Long hallId) {
        Hall hall = hallService.getById(hallId);
        if (hall == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "影厅不存在");
        }
        if (hall.getCurrentVersionId() == null) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "影厅「" + hall.getName() + "」还没有座位图, 请先在影厅管理里编辑并发布座位图");
        }
        return hall;
    }

    /**
     * 当前座位图版本里的可售座位.
     * 必须按<strong>版本</strong>取 —— 用 seatsOf(hallId) 会把该厅所有版本的座位混在一起, 建出重复的票.
     */
    private List<Seat> requireSeats(Hall hall) {
        List<Seat> seats = hallService.seatsOfVersion(hall.getCurrentVersionId()).stream()
                .filter(s -> s.getStatus() != null && s.getStatus() == 1)
                .toList();
        if (seats.isEmpty()) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "影厅座位图为空, 无法排片. 请先在影厅管理里画好座位并发布");
        }
        return seats;
    }

    /** 单场次的行级校验 + 落库 + 建票. add 与 batchAdd 共用; batchId 仅批量排片时非空. */
    private Schedule doAdd(Movie movieInfo, Hall hall, List<Seat> seats, LocalDateTime start,
                           BigDecimal price, BigDecimal vipMultiplier, BigDecimal coupleMultiplier,
                           String batchId) {
        if (!start.isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "开场时间必须晚于当前时间");
        }
        LocalDateTime end = start.plusMinutes(movieInfo.getRuntimeMin()).plusMinutes(30);

        // 冲突检测
        List<Schedule> conflicts = scheduleMapper.listConflict(hall.getId(), start, end, null);
        if (!conflicts.isEmpty()) {
            throw new BusinessException("排片时间冲突");
        }

        Schedule po = new Schedule();
        po.setMovieId(movieInfo.getId());
        po.setHallId(hall.getId());
        // 锁死当前座位图版本: 之后影厅改座位图发新版本, 本场次仍用这一版.
        po.setSeatVersionId(hall.getCurrentVersionId());
        po.setStartTime(start);
        po.setEndTime(end);
        // 服务层兜底：AI/内部调用不走 Bean Validation，负价或超大价会穿透成负订单/额度溢出
        BigDecimal basePrice = price == null ? DEFAULT_PRICE : price;
        if (basePrice.compareTo(new BigDecimal("0.01")) < 0
                || basePrice.compareTo(new BigDecimal("9999.99")) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票价需在 0.01~9999.99 之间");
        }
        po.setPrice(basePrice.setScale(2, java.math.RoundingMode.HALF_UP));
        // 倍率: 不传用默认. 建票时按这两个值算 VIP / 情侣座的票价.
        po.setVipPriceMultiplier(vipMultiplier == null
                ? DEFAULT_VIP_MULTIPLIER : vipMultiplier);
        po.setCouplePriceMultiplier(coupleMultiplier == null
                ? DEFAULT_COUPLE_MULTIPLIER : coupleMultiplier);
        po.setStatus(1);
        po.setBatchId(batchId);
        scheduleMapper.insert(po);
        ticketService.createTicketsForSchedule(po, seats);
        return po;
    }

    /**
     * 更新排片.
     *
     * <p>早先这里是 {@code updateById(@RequestBody Schedule)} —— 收实体有两个后果:
     * <ol>
     *   <li>实体上 {@code startTime} 带 {@code @JsonFormat("yyyy-MM-dd HH:mm:ss")},
     *       而新增走的是 ISO 格式, 前端两处发同一个字符串 → 编辑必然 400;</li>
     *   <li>实体带 {@code hallId}, 于是"编辑"能把场次挪到别的厅, 但
     *       {@code seat_version_id} 和已建的票还留在原厅 → 场次显示 B 厅、画的是 A 厅的座位图.</li>
     * </ol>
     * 现在收 {@link ScheduleUpdateDTO}, 两个问题一起解决.
     *
     * <p>并发口径(P0-6 / P1-H4): 首个 selectById 只用来定位 hallId(不该拿它做校验 ——
     * 它建出的 RR 快照可能读到旧 status); 随后立即 {@code lockHall} 串行化同厅写路径,
     * 再用<strong>锁定读</strong>重读该场次并在此基础上校验 —— 并发 cancel 已把场次置 2 时
     * 这里看得到, 已取消场次不会被"复活"成改期写入; 冲突检查同理走锁定读.
     */
    @Override
    @Transactional
    public boolean update(ScheduleUpdateDTO dto) {
        // 仅定位 hallId(hallId 不可被编辑, 老值就是对的), 校验一律用下面锁后的重读结果
        Schedule probe = scheduleMapper.selectById(dto.id());
        if (probe == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "排片不存在");
        }
        // 先锁厅再读: 同厅的 add/batchAdd/batchMove/cancel 都拿这把锁, 改期检查才不漏人
        scheduleMapper.lockHall(probe.getHallId());
        // 锁定读重读(当前读): RR 快照下的 probe 可能已是旧状态
        Schedule exist = scheduleMapper.selectByIdForUpdate(dto.id());
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "排片不存在");
        }
        if (exist.getStatus() != null && exist.getStatus() == 2) {
            throw new BusinessException(ResultCode.CONFLICT, "场次已取消，不能编辑");
        }
        if (!LocalDateTime.now().isBefore(exist.getStartTime())) {
            throw new BusinessException(ResultCode.CONFLICT, "场次已开场，不能编辑");
        }
        Movie m = movieService.info(dto.movieId()).getBase();
        if (m == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "影片不存在");
        }
        if (m.getRuntimeMin() == null || m.getRuntimeMin() <= 0) {
            throw new BusinessException(ResultCode.CONFLICT, "影片时长尚未公布，请补全资料后再排片");
        }
        LocalDateTime start = dto.startTime();
        if (!start.isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "开场时间必须晚于当前时间");
        }
        LocalDateTime end = start.plusMinutes(m.getRuntimeMin()).plusMinutes(30);

        // 时段冲突: 必须排除自己, 否则原时间不动也会跟自己撞
        List<Schedule> conflicts = scheduleMapper.listConflict(
                exist.getHallId(), start, end, dto.id());
        if (!conflicts.isEmpty()) {
            throw new BusinessException(ResultCode.CONFLICT, "该影厅在这个时段已有排片");
        }

        // 只塞允许改的字段. MyBatis-Plus 默认跳过 null, 所以 hallId / seatVersionId /
        // 倍率 / status / price 都不会被这条 update 碰到 —— 票价在建票时定死,
        // 要调价只能取消后重排(见 ScheduleUpdateDTO 的说明).
        Schedule po = new Schedule();
        po.setId(dto.id());
        po.setMovieId(dto.movieId());
        po.setStartTime(start);
        po.setEndTime(end);
        return scheduleMapper.updateById(po) > 0;
    }

    /**
     * 取消场次.
     *
     * <p>语义: status 1→2, 停止售票; 挂在该场次上的未退款订单(待支付/已支付/退款中/已评价)
     * 自动全额退款置 3; 全场票(含未售的)一律作废(status=2)但**保留 order_id** ——
     * 已退款订单还能回看当初的座位(见 {@code TicketServiceImpl.voidBySchedule}).
     * 与用户主动退票的 {@code releaseTickets}(票回可售并解绑)不同, 取消场景整场不再售票,
     * 不留"可售"假象; 作废票不算已售, 取消后的场次可以删除.
     *
     * <p>允许取消任意场次(含已开场/已放映): 影厅故障等场景必须能取消, 已看片的退款风险
     * 靠管理员权限 + 操作日志兜底。
     *
     * @return 受影响订单数(自动退款笔数)
     */
    @Override
    @Transactional
    public int cancel(Long id) {
        Schedule exist = scheduleMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "排片不存在");
        }
        scheduleMapper.lockHall(exist.getHallId());
        // P0-4: 先把场次原子置为取消（条件 UPDATE 走 current read），再退款/作废票。
        // 顺序很关键：pay() 会在事务内对场次行做锁定读复核；场次先置 2 后，
        // 晚到的支付要么被取消流程随后的集合式退款 UPDATE 补退，要么在 pay 复核时被拒——
        // 不再出现「订单已支付、票被作废、无人退款」的窗口。
        int flipped = scheduleMapper.update(null, new LambdaUpdateWrapper<Schedule>()
                .eq(Schedule::getId, id)
                .eq(Schedule::getStatus, 1)
                .set(Schedule::getStatus, 2));
        if (flipped == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "场次已是取消状态");
        }
        int refunded = refundOrdersOfSchedule(id);
        ticketService.voidBySchedule(id);
        // 审计: 取消场次属高风险操作，记录操作者/场次/退款笔数（不阻断业务）
        ContextHolder.Principal admin = ContextHolder.current();
        adminAuditService.record(admin == null ? null : admin.id(), admin == null ? null : admin.name(),
                AdminOperationLog.ACTION_SCHEDULE_CANCEL, "SCHEDULE", id,
                "refunded orders=" + refunded);
        return refunded;
    }

    /**
     * 取消场次时按订单状态分流(2026-10-19 与状态 4 重定义配套):
     * <ul>
     *   <li>status=1 (待支付): 置 4 (已关闭, 不退款)</li>
     *   <li>status in (2, 6): 置 3 (已退款, 全额落 refund_amount / refund_time)</li>
     *   <li>已 3 (已退款): 幂等跳过</li>
     * </ul>
     * 都用条件 UPDATE 走, 并发下不会有"已支付+作废"或"已退款又改"的状态.
     *
     * @return 受影响订单条数
     */
    private int refundOrdersOfSchedule(Long scheduleId) {
        // 待支付单: 关单, 不退款(没付过钱)
        int closed = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getScheduleId, scheduleId)
                .eq(Order::getStatus, OrderStatus.PENDING.getCode())
                .set(Order::getStatus, OrderStatus.CLOSED.getCode()));
        // 已支付/旧已完成单: 集合式条件 UPDATE（DML current read）——一次性看齐最新已提交行，
        // 避免「先 SELECT 挑单再逐条 UPDATE」在 REPEATABLE READ 下漏掉事务期间刚提交的支付。
        // refund_amount 用列到列赋值，金额取自每行当前 total_amount。
        LocalDateTime refundTime = LocalDateTime.now().withNano(0);
        int refunded = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getScheduleId, scheduleId)
                .in(Order::getStatus, OrderStatus.PAID.getCode(), OrderStatus.LEGACY_COMPLETED.getCode())
                .set(Order::getStatus, OrderStatus.REFUNDED.getCode())
                .set(Order::getRefundTime, refundTime)
                .setSql("refund_amount = total_amount"));
        // 逐单审计：本事务内的更新对自身可见，按退款时间精确定位本批订单。
        List<Order> refundedOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getScheduleId, scheduleId)
                .eq(Order::getStatus, OrderStatus.REFUNDED.getCode())
                .eq(Order::getRefundTime, refundTime));
        for (Order o : refundedOrders) {
            orderAuditService.record(o.getId(), o.getNumber(), OrderAction.ACTION_CANCEL_REFUND,
                    OrderStatus.PAID.getCode(), OrderStatus.REFUNDED.getCode(), o.getTotalAmount(),
                    OrderAction.OP_SYSTEM, null, "取消场次 " + scheduleId + " 全额退款");
        }
        return closed + refunded;
    }

    /**
     * 删除排片.
     *
     * <p>顺带清掉该场次的票 —— 早先只删 schedule, 票全成了孤儿:
     * 它们还指着已删除场次和座位, 既没人回收, 又会让 seat 的引用检查误判.
     *
     * <p>但**已售/已锁定的票不允许连坐删除**: 那是用户订单的一部分, 删了就对不上账了.
     * 这种情况要求管理员先处理订单(退票), 再删场次.
     *
     * <p>已开场/已放映的场次也不能删 —— 那是放映历史。已取消但过期的场次同样保留,
     * 管理列表按状态筛选即可。
     */
    @Override
    @Transactional
    public boolean delete(Long id) {
        Schedule exist = scheduleMapper.selectById(id);
        if (exist == null) {
            return false;
        }
        if (!LocalDateTime.now().isBefore(exist.getStartTime())) {
            throw new BusinessException(ResultCode.CONFLICT, "场次已开场或已放映，不能删除");
        }
        long ordered = ticketService.countOrderedBySchedule(id);
        if (ordered > 0) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "该场次已有 " + ordered + " 张票挂在订单上, 不能直接删除. 请先处理相关订单");
        }
        ticketService.deleteBySchedule(id);
        return scheduleMapper.deleteById(id) > 0;
    }

    @Override
    public List<Schedule> listForMovie(Long movieId) {
        List<Schedule> rows = scheduleMapper.selectList(new LambdaQueryWrapper<Schedule>()
                .eq(Schedule::getMovieId, movieId)
                .eq(Schedule::getStatus, 1)
                .ge(Schedule::getStartTime, LocalDateTime.now())
                .orderByAsc(Schedule::getStartTime));
        enrichMovieAndHall(rows);
        return rows;
    }

    @Override
    public List<Schedule> publicByHall(Long hallId, Long movieId) {
        LambdaQueryWrapper<Schedule> wrap = new LambdaQueryWrapper<Schedule>()
                .eq(Schedule::getHallId, hallId)
                .eq(Schedule::getStatus, 1)
                .ge(Schedule::getStartTime, LocalDateTime.now())
                .eq(movieId != null, Schedule::getMovieId, movieId)
                .orderByAsc(Schedule::getStartTime);
        List<Schedule> rows = scheduleMapper.selectList(wrap);
        enrichMovieAndHall(rows);
        return rows;
    }

    /** 批量补片名/海报/厅名。早先这里调了两次 movieService.page(1, 200) —— 重复查询且超 200 部就丢名字. */
    private void enrichMovieAndHall(List<Schedule> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Set<Long> movieIds = rows.stream().map(Schedule::getMovieId).collect(Collectors.toSet());
        Map<Long, Movie> movies = movieMapper.selectBatchIds(movieIds).stream()
                .collect(Collectors.toMap(Movie::getId, m -> m, (a, b) -> a));
        Map<Long, String> hallNames = hallService.listAll().stream()
                .collect(Collectors.toMap(Hall::getId, Hall::getName));
        rows.forEach(r -> {
            Movie m = movies.get(r.getMovieId());
            r.setMovieName(m == null ? null : m.getTitle());
            r.setPoster(m == null ? null : m.getPosterUrl());
            r.setHallName(hallNames.get(r.getHallId()));
        });
    }

    /**
     * 选座页数据.
     *
     * <p>座位取自 {@code schedule.seat_version_id} 锁定的版本 —— 不是影厅的当前版本.
     * 老场次(P5 迁移前)该字段为空, 回退到 {@code hall.current_version_id}.
     *
     * <p>把 seat 和 ticket 按 seatId 对上: ticket 是「本次场次下这个座位卖不卖得出去」,
     * 只有 status=1 的座位在排片时建过票, 所以过道位查不到票, ticketId 为 null.
     */
    @Override
    public ScheduleSeatsVO seatsOfSchedule(Long scheduleId) {
        Schedule s = scheduleMapper.selectById(scheduleId);
        if (s == null) return null;

        Hall hall = hallService.getById(s.getHallId());
        // 场次锁定的版本优先; 没有(迁移前老数据)就退回影厅当前版本
        Long versionId = s.getSeatVersionId();
        if (versionId == null && hall != null) {
            versionId = hall.getCurrentVersionId();
        }

        List<Seat> seats = versionId == null
                ? List.of()
                : hallService.seatsOfVersion(versionId);

        // seatId -> ticket
        Map<Long, Ticket> ticketBySeat = ticketService.listBySchedule(scheduleId).stream()
                .filter(t -> t.getSeatId() != null)
                .collect(Collectors.toMap(Ticket::getSeatId, t -> t, (a, b) -> a));

        List<ScheduleSeatsVO.SeatItem> items = seats.stream().map(seat -> {
            ScheduleSeatsVO.SeatItem item = new ScheduleSeatsVO.SeatItem();
            item.setSeatId(seat.getId());
            item.setY(seat.getY());
            item.setX(seat.getX());
            item.setType(seat.getType());
            item.setStatus(seat.getStatus());
            Ticket t = ticketBySeat.get(seat.getId());
            if (t != null) {
                item.setTicketId(t.getId());
                item.setTicketStatus(t.getStatus());
            }
            return item;
        }).toList();

        ScheduleSeatsVO vo = new ScheduleSeatsVO();
        vo.setScheduleId(s.getId());
        vo.setHallId(s.getHallId());
        vo.setSeatVersionId(versionId);
        vo.setStatus(s.getStatus());
        vo.setHallName(hall == null ? null : hall.getName());
        vo.setStartTime(s.getStartTime());
        vo.setPrice(s.getPrice());
        vo.setVipPriceMultiplier(s.getVipPriceMultiplier() == null
                ? DEFAULT_VIP_MULTIPLIER : s.getVipPriceMultiplier());
        vo.setCouplePriceMultiplier(s.getCouplePriceMultiplier() == null
                ? DEFAULT_COUPLE_MULTIPLIER : s.getCouplePriceMultiplier());
        vo.setSeats(items);
        vo.setCoupleGroups(versionId == null
                ? List.of()
                : seatVersionService.coupleGroupsOf(versionId).stream()
                        .map(g -> {
                            ScheduleSeatsVO.CouplePair p = new ScheduleSeatsVO.CouplePair();
                            p.setId(g.getId());
                            p.setSeatId1(g.getSeatId1());
                            p.setSeatId2(g.getSeatId2());
                            p.setLabel(g.getLabel());
                            return p;
                        }).toList());
        vo.setRows(seats.stream().map(Seat::getY).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0));
        vo.setCols(seats.stream().map(Seat::getX).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0));
        try {
            Movie m = movieService.info(s.getMovieId()).getBase();
            vo.setMovieName(m == null ? null : m.getTitle());
        } catch (Exception ignored) {
            // 影片查不到不影响选座, 名称留空即可
        }
        return vo;
    }
}
