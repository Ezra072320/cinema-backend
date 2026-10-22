package edu.czu.cinema.domain.schedule;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.hall.entity.Hall;
import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.hall.service.HallService;
import edu.czu.cinema.domain.hall.service.SeatVersionService;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.vo.MovieInfoVO;
import edu.czu.cinema.domain.order.entity.Order;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import edu.czu.cinema.domain.schedule.dto.ScheduleAddDTO;
import edu.czu.cinema.domain.schedule.dto.ScheduleBatchAddDTO;
import edu.czu.cinema.domain.schedule.dto.ScheduleBatchMoveDTO;
import edu.czu.cinema.domain.schedule.dto.ScheduleUpdateDTO;
import edu.czu.cinema.domain.schedule.entity.Schedule;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import edu.czu.cinema.domain.schedule.service.impl.ScheduleServiceImpl;
import edu.czu.cinema.domain.schedule.vo.ScheduleBatchResultVO;
import edu.czu.cinema.domain.ticket.service.TicketService;
import edu.czu.cinema.remote.CatalogClient;
import edu.czu.cinema.snapshot.MovieSnapshotRepository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 排片服务单测: 时间校验 / 禁改价 / 取消自动退款 / 删除规则。 */
class ScheduleServiceImplTest {

    @BeforeAll
    static void initMybatisLambdaCache() {
        // 2026-10-19 取消场次时 refundOrdersOfSchedule 用了 LambdaUpdateWrapper<Order>,
        // 纯 Mockito 单测里要手动注册 Order 表缓存, 否则 lambda 列名解析失败.
        TableInfoHelper.initTableInfo(
                new com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant(
                        new MybatisConfiguration(), ""),
                Order.class);
    }

    // 注: 3.5.9 的 TableInfoHelper 没有公开的 clear() 静态方法, 缓存只在本测试 JVM 进程内,
    // 对其它测试类无影响, 无需清理.

    private ScheduleMapper scheduleMapper;
    private MovieSnapshotRepository movieMapper;
    private CatalogClient movieService;
    private HallService hallService;
    private TicketService ticketService;
    private SeatVersionService seatVersionService;
    private OrderMapper orderMapper;
    private edu.czu.cinema.domain.order.service.OrderAuditService orderAuditService;
    private edu.czu.cinema.domain.admin.service.AdminAuditService adminAuditService;
    private ScheduleServiceImpl service;

    @BeforeEach
    void setUp() {
        scheduleMapper = mock(ScheduleMapper.class);
        movieMapper = mock(MovieSnapshotRepository.class);
        movieService = mock(CatalogClient.class);
        hallService = mock(HallService.class);
        ticketService = mock(TicketService.class);
        seatVersionService = mock(SeatVersionService.class);
        orderMapper = mock(OrderMapper.class);
        orderAuditService = mock(edu.czu.cinema.domain.order.service.OrderAuditService.class);
        adminAuditService = mock(edu.czu.cinema.domain.admin.service.AdminAuditService.class);
        service =
                new ScheduleServiceImpl(
                        scheduleMapper,
                        movieMapper,
                        movieService,
                        hallService,
                        ticketService,
                        seatVersionService,
                        orderMapper,
                        orderAuditService,
                        adminAuditService);
    }

    /** 常规可排片的影片: 时长 120 分钟。 */
    private void givenMovie(long id, int runtimeMin) {
        Movie m = new Movie();
        m.setId(id);
        m.setTitle("测试影片");
        m.setRuntimeMin(runtimeMin);
        MovieInfoVO vo = new MovieInfoVO();
        vo.setBase(m);
        when(movieService.getMovie(id)).thenReturn(m);
    }

    /** 有座位图的影厅。 */
    private void givenHall(long id) {
        Hall hall = new Hall();
        hall.setId(id);
        hall.setName("1 号厅");
        hall.setCurrentVersionId(9L);
        when(hallService.getById(id)).thenReturn(hall);
    }

    private ScheduleAddDTO addDto(long movieId, long hallId, LocalDateTime start) {
        return new ScheduleAddDTO(movieId, hallId, start, null, null, null);
    }

    /** 让影厅有可售座位 —— 座位图校验现在早于时间/冲突校验, 走到那些分支的用例都要 mock. */
    private void givenSeats(long versionId) {
        Seat seat = new Seat();
        seat.setId(11L);
        seat.setStatus(1);
        when(hallService.seatsOfVersion(versionId)).thenReturn(List.of(seat));
    }

    /** 让 update() 既有"定位 hallId 的首读"又有一份"锁后锁定读重读" —— P1-H4 起校验全部放在锁后重读上, 两个读都要桩. */
    private void givenExistingSchedule(Schedule exist) {
        when(scheduleMapper.selectById(exist.getId())).thenReturn(exist);
        when(scheduleMapper.selectByIdForUpdate(exist.getId())).thenReturn(exist);
    }

    // ===== add =====

    @Test
    void addRejectsPastStartTime() {
        givenMovie(1L, 120);
        givenHall(2L);
        givenSeats(9L);
        assertThatThrownBy(() -> service.add(addDto(1L, 2L, LocalDateTime.now().minusHours(1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("开场时间必须晚于当前时间");
    }

    @Test
    void addRejectsUnpublishedRuntime() {
        // 时长约定: runtimeMin=0 是待映未公布时长, 不能排片
        givenMovie(1L, 0);
        givenHall(2L);
        assertThatThrownBy(() -> service.add(addDto(1L, 2L, LocalDateTime.now().plusDays(1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("时长尚未公布");
    }

    @Test
    void addRejectsHallWithoutSeatMap() {
        givenMovie(1L, 120);
        Hall hall = new Hall();
        hall.setId(2L);
        hall.setName("毛坯厅");
        hall.setCurrentVersionId(null);
        when(hallService.getById(2L)).thenReturn(hall);
        assertThatThrownBy(() -> service.add(addDto(1L, 2L, LocalDateTime.now().plusDays(1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("还没有座位图");
    }

    @Test
    void addRejectsTimeConflict() {
        givenMovie(1L, 120);
        givenHall(2L);
        givenSeats(9L);
        when(scheduleMapper.listConflict(eq(2L), any(), any(), isNull()))
                .thenReturn(List.of(new Schedule()));
        assertThatThrownBy(() -> service.add(addDto(1L, 2L, LocalDateTime.now().plusDays(1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("排片时间冲突");
    }

    @Test
    void addCreatesTicketsAtMultiplierPrice() {
        givenMovie(1L, 120);
        givenHall(2L);
        Seat seat = new Seat();
        seat.setId(11L);
        seat.setStatus(1);
        when(hallService.seatsOfVersion(9L)).thenReturn(List.of(seat));
        when(scheduleMapper.insert(any(Schedule.class)))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(0, Schedule.class).setId(100L);
                            return 1;
                        });

        LocalDateTime start = LocalDateTime.now().plusDays(1).withNano(0);
        ScheduleAddDTO dto =
                new ScheduleAddDTO(
                        1L,
                        2L,
                        start,
                        new BigDecimal("60"),
                        new BigDecimal("2.00"),
                        new BigDecimal("1.50"));
        Long id = service.add(dto);
        assertThat(id).isEqualTo(100L);

        ArgumentCaptor<Schedule> po = ArgumentCaptor.forClass(Schedule.class);
        verify(ticketService).createTicketsForSchedule(po.capture(), eq(List.of(seat)));
        // end = 时长 + 30 分钟清场
        assertThat(po.getValue().getEndTime()).isEqualTo(start.plusMinutes(150));
        assertThat(po.getValue().getPrice()).isEqualByComparingTo("60");
        assertThat(po.getValue().getVipPriceMultiplier()).isEqualByComparingTo("2.00");
        assertThat(po.getValue().getCouplePriceMultiplier()).isEqualByComparingTo("1.50");
        assertThat(po.getValue().getStatus()).isEqualTo(1);
    }

    // ===== update =====

    @Test
    void updateRejectsPastStartTime() {
        Schedule exist = new Schedule();
        exist.setId(5L);
        exist.setHallId(2L);
        exist.setStatus(1);
        exist.setStartTime(LocalDateTime.now().plusDays(1));
        givenExistingSchedule(exist);
        givenMovie(1L, 120);
        assertThatThrownBy(
                        () ->
                                service.update(
                                        new ScheduleUpdateDTO(
                                                5L, 1L, LocalDateTime.now().minusHours(1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("开场时间必须晚于当前时间");
    }

    @Test
    void updateRejectsCanceledSchedule() {
        Schedule exist = new Schedule();
        exist.setId(5L);
        exist.setHallId(2L);
        exist.setStatus(2);
        exist.setStartTime(LocalDateTime.now().plusDays(1));
        givenExistingSchedule(exist);
        assertThatThrownBy(
                        () ->
                                service.update(
                                        new ScheduleUpdateDTO(
                                                5L, 1L, LocalDateTime.now().plusDays(2))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已取消");
    }

    @Test
    void updateRejectsStartedSchedule() {
        Schedule exist = new Schedule();
        exist.setId(5L);
        exist.setHallId(2L);
        exist.setStatus(1);
        exist.setStartTime(LocalDateTime.now().minusMinutes(10));
        givenExistingSchedule(exist);
        assertThatThrownBy(
                        () ->
                                service.update(
                                        new ScheduleUpdateDTO(
                                                5L, 1L, LocalDateTime.now().plusDays(2))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已开场");
    }

    @Test
    void updateNeverTouchesPrice() {
        Schedule exist = new Schedule();
        exist.setId(5L);
        exist.setHallId(2L);
        exist.setStatus(1);
        exist.setStartTime(LocalDateTime.now().plusDays(1));
        givenExistingSchedule(exist);
        givenMovie(1L, 90);
        when(scheduleMapper.updateById(any(Schedule.class))).thenReturn(1);

        assertThat(service.update(new ScheduleUpdateDTO(5L, 1L, LocalDateTime.now().plusDays(2))))
                .isTrue();

        ArgumentCaptor<Schedule> po = ArgumentCaptor.forClass(Schedule.class);
        verify(scheduleMapper).updateById((Schedule) po.capture());
        // 禁改价: update 生成的实体里不许出现 price(建票时已按当时价格落库)
        assertThat(po.getValue().getPrice()).isNull();
        assertThat(po.getValue().getHallId()).isNull();
        assertThat(po.getValue().getStatus()).isNull();
    }

    /**
     * P1-H4 TOCTOU: 定位首读读到 status=1(旧), 但并发 cancel 已把它置 2, 锁后锁定读重读必须看到最新 —— 已取消场次不能被改期写入(不允许"复活").
     */
    @Test
    void updateRejectsBecauseLockedAfterConcurrentCancel() {
        Schedule stale = new Schedule();
        stale.setId(5L);
        stale.setHallId(2L);
        stale.setStatus(1);
        stale.setStartTime(LocalDateTime.now().plusDays(1));
        when(scheduleMapper.selectById(5L)).thenReturn(stale); // 首读: 只看 hallId
        Schedule fresh = new Schedule();
        fresh.setId(5L);
        fresh.setHallId(2L);
        fresh.setStatus(2);
        fresh.setStartTime(LocalDateTime.now().plusDays(1));
        when(scheduleMapper.selectByIdForUpdate(5L)).thenReturn(fresh); // 锁后重读: 已取消

        assertThatThrownBy(
                        () ->
                                service.update(
                                        new ScheduleUpdateDTO(
                                                5L, 1L, LocalDateTime.now().plusDays(2))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已取消");
        verify(scheduleMapper, never()).updateById(any(Schedule.class));
    }

    /** P0-6: update 必须「先 lockHall 再做冲突检查」, 否则 RR 快照漏看并发改期/排片. */
    @Test
    void updateLocksHallBeforeConflictCheck() {
        Schedule exist = new Schedule();
        exist.setId(5L);
        exist.setHallId(2L);
        exist.setStatus(1);
        exist.setStartTime(LocalDateTime.now().plusDays(1));
        givenExistingSchedule(exist);
        givenMovie(1L, 90);
        when(scheduleMapper.listConflict(any(), any(), any(), any())).thenReturn(List.of());
        when(scheduleMapper.updateById(any(Schedule.class))).thenReturn(1);

        service.update(new ScheduleUpdateDTO(5L, 1L, LocalDateTime.now().plusDays(2)));

        InOrder inOrder = inOrder(scheduleMapper);
        inOrder.verify(scheduleMapper).lockHall(2L);
        inOrder.verify(scheduleMapper).listConflict(eq(2L), any(), any(), eq(5L));
    }

    /** P0-6: batchMove 必须先 lockHall 再做冲突检查. */
    @Test
    void batchMoveLocksHallBeforeConflictCheck() {
        givenMovie(1L, 120);
        String bid = "batch-before-conflict";
        Schedule s1 = batchSession(71, 1, 10, bid);
        when(scheduleMapper.selectList(any())).thenReturn(List.of(s1));
        when(scheduleMapper.listConflict(any(), any(), any(), any())).thenReturn(List.of());
        when(scheduleMapper.updateById(any(Schedule.class))).thenReturn(1);

        service.batchMove(
                new ScheduleBatchMoveDTO(
                        bid, s1.getStartTime(), s1.getStartTime().toLocalDate().atTime(14, 0)));

        InOrder inOrder = inOrder(scheduleMapper);
        inOrder.verify(scheduleMapper).lockHall(2L);
        inOrder.verify(scheduleMapper).listConflict(eq(2L), any(), any(), eq(71L));
    }

    /** P2: batchMove 的 endTime 必须按**每场自己的**影片时长重算 —— 批次可能混片, 用首场时长会给别的片算错散场时间. */
    @Test
    void batchMoveComputesEndTimePerMovieRuntime() {
        givenMovie(1L, 120); // 第一场这部
        Movie shortMovie = new Movie();
        shortMovie.setId(2L);
        shortMovie.setTitle("短片");
        shortMovie.setRuntimeMin(60);
        MovieInfoVO shortVo = new MovieInfoVO();
        shortVo.setBase(shortMovie);
        when(movieService.getMovie(2L)).thenReturn(shortVo.getBase());

        String bid = "batch-mixed";
        Schedule longS = new Schedule();
        longS.setId(81L);
        longS.setMovieId(1L);
        longS.setHallId(2L);
        longS.setStatus(1);
        longS.setBatchId(bid);
        longS.setStartTime(
                LocalDateTime.now()
                        .plusDays(1)
                        .withHour(10)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0));
        Schedule shortS = new Schedule();
        shortS.setId(82L);
        shortS.setMovieId(2L);
        shortS.setHallId(2L);
        shortS.setStatus(1);
        shortS.setBatchId(bid);
        shortS.setStartTime(
                LocalDateTime.now()
                        .plusDays(2)
                        .withHour(10)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0));
        when(scheduleMapper.selectList(any())).thenReturn(List.of(longS, shortS));
        when(scheduleMapper.listConflict(any(), any(), any(), any())).thenReturn(List.of());
        when(scheduleMapper.updateById(any(Schedule.class))).thenReturn(1);

        ScheduleBatchResultVO r =
                service.batchMove(
                        new ScheduleBatchMoveDTO(
                                bid,
                                longS.getStartTime(),
                                longS.getStartTime().toLocalDate().atTime(14, 0)));
        assertThat(r.getCreated()).hasSize(2);

        ArgumentCaptor<Schedule> po = ArgumentCaptor.forClass(Schedule.class);
        verify(scheduleMapper, times(2)).updateById((Schedule) po.capture());
        Map<Long, Schedule> byId =
                po.getAllValues().stream()
                        .collect(java.util.stream.Collectors.toMap(Schedule::getId, p -> p));
        // 长片 120 + 清场 30; 短片 60 + 清场 30 —— 各按各的时长
        assertThat(byId.get(81L).getEndTime())
                .isEqualTo(byId.get(81L).getStartTime().plusMinutes(150));
        assertThat(byId.get(82L).getEndTime())
                .isEqualTo(byId.get(82L).getStartTime().plusMinutes(90));
    }

    /** P2/清单11: batchAdd 非业务异常(DuplicateKey 等)只跳过该行, 其余行照常入库. */
    @Test
    void batchAddSkipsRowOnDuplicateKeyAndKeepsOthers() {
        givenMovie(1L, 120);
        givenHall(2L);
        givenSeats(9L);
        LocalDateTime ok1 = LocalDateTime.now().plusDays(1).withNano(0);
        LocalDateTime bad = LocalDateTime.now().plusDays(2).withNano(0);
        LocalDateTime ok2 = LocalDateTime.now().plusDays(3).withNano(0);
        when(scheduleMapper.listConflict(any(), any(), any(), any())).thenReturn(List.of());
        when(scheduleMapper.insert(any(Schedule.class)))
                .thenAnswer(
                        inv -> {
                            Schedule s = inv.getArgument(0, Schedule.class);
                            if (s.getStartTime().equals(bad)) {
                                throw new org.springframework.dao.DuplicateKeyException(
                                        "uk_schedule_seat");
                            }
                            s.setId(900L + Math.abs(s.getStartTime().getDayOfMonth()));
                            return 1;
                        });

        ScheduleBatchResultVO r =
                service.batchAdd(
                        new ScheduleBatchAddDTO(
                                1L,
                                2L,
                                List.of(
                                        new ScheduleBatchAddDTO.Session(ok1, null),
                                        new ScheduleBatchAddDTO.Session(bad, null),
                                        new ScheduleBatchAddDTO.Session(ok2, null)),
                                new BigDecimal("60"),
                                null,
                                null));

        assertThat(r.getCreated()).hasSize(2);
        assertThat(r.getSkipped()).hasSize(1);
        assertThat(r.getSkipped().get(0).getStartTime()).isEqualTo(bad);
        assertThat(r.getSkipped().get(0).getReason()).contains("写入失败");
    }

    // ===== cancel =====

    @Test
    void cancelRefundsActiveOrdersAndVoidsTickets() {
        // 2026-10-19 改造: status=1 → 已关闭(4), status in (2,6) → 已退款(3, 全额落账),
        // 已 3 的不动. 三个单据分两类去走不同路径(关闭用 update, 退款用 selectList+update).
        Schedule exist = new Schedule();
        exist.setId(5L);
        exist.setHallId(2L);
        exist.setStatus(1);
        when(scheduleMapper.selectById(5L)).thenReturn(exist);
        // P0-4: 场次先做条件 UPDATE 置取消（1 行）
        when(scheduleMapper.update(
                        any(),
                        any(
                                com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper
                                        .class)))
                .thenReturn(1);

        Order paid = new Order();
        paid.setId(2L);
        paid.setUserId(11L);
        paid.setScheduleId(5L);
        paid.setStatus(2);
        paid.setTotalAmount(new java.math.BigDecimal("80"));

        // 待支付关闭 + 已支付退款两条集合式 UPDATE 各 1 行
        when(orderMapper.update(
                        any(),
                        any(
                                com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper
                                        .class)))
                .thenReturn(1);
        // 退款后审计查询: 返回本批已退款订单
        when(orderMapper.selectList(
                        any(
                                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
                                        .class)))
                .thenReturn(List.of(paid));
        when(ticketService.voidBySchedule(5L)).thenReturn(10);

        int n = service.cancel(5L);
        // 关 1 单 + 退 1 单 = 2
        assertThat(n).isEqualTo(2);
        verify(ticketService).voidBySchedule(5L);
        // 场次状态由集合式条件 UPDATE 翻转（不再走实体 updateById）
        verify(scheduleMapper)
                .update(
                        any(),
                        any(
                                com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper
                                        .class));
        // 取消场次必须写管理审计
        verify(adminAuditService).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelOnCanceledScheduleThrows() {
        Schedule exist = new Schedule();
        exist.setId(5L);
        exist.setStatus(2);
        when(scheduleMapper.selectById(5L)).thenReturn(exist);
        assertThatThrownBy(() -> service.cancel(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已是取消状态");
    }

    // ===== batchAdd =====

    @Test
    void batchAddSkipsConflictingAndPastSessionsAndCreatesValidOnes() {
        givenMovie(1L, 120);
        givenHall(2L);
        Seat seat = new Seat();
        seat.setId(11L);
        seat.setStatus(1);
        when(hallService.seatsOfVersion(9L)).thenReturn(List.of(seat));
        LocalDateTime valid1 = LocalDateTime.now().plusDays(1).withNano(0);
        LocalDateTime valid2 = LocalDateTime.now().plusDays(2).withNano(0);
        LocalDateTime past = LocalDateTime.now().minusHours(1).withNano(0);
        LocalDateTime existingConflict = LocalDateTime.now().plusDays(3).withNano(0);
        // 库里已有一场占用 existingConflict 时段
        when(scheduleMapper.listConflict(
                        eq(2L),
                        eq(existingConflict),
                        eq(existingConflict.plusMinutes(150)),
                        isNull()))
                .thenReturn(List.of(new Schedule()));
        // 批内互撞: 最后一行与 valid1 完全同时段 —— 需要 listConflict 在第一次插入后
        // 对 valid1 的重复时段也报冲突. 用 answer 模拟"已插入的时段再查就有冲突".
        java.util.Set<LocalDateTime> inserted = new java.util.HashSet<>();
        when(scheduleMapper.listConflict(eq(2L), eq(valid1), eq(valid1.plusMinutes(150)), isNull()))
                .thenAnswer(inv -> inserted.contains(valid1) ? List.of(new Schedule()) : List.of());
        when(scheduleMapper.insert(any(Schedule.class)))
                .thenAnswer(
                        inv -> {
                            Schedule s = inv.getArgument(0, Schedule.class);
                            inserted.add(s.getStartTime());
                            s.setId(100L + inserted.size());
                            return 1;
                        });

        ScheduleBatchAddDTO dto =
                new ScheduleBatchAddDTO(
                        1L,
                        2L,
                        List.of(
                                new ScheduleBatchAddDTO.Session(valid1, null),
                                new ScheduleBatchAddDTO.Session(past, null),
                                new ScheduleBatchAddDTO.Session(existingConflict, null),
                                new ScheduleBatchAddDTO.Session(valid2, new BigDecimal("80")),
                                new ScheduleBatchAddDTO.Session(valid1, null)), // 批内互撞 valid1
                        new BigDecimal("60"),
                        null,
                        null);
        ScheduleBatchResultVO r = service.batchAdd(dto);

        assertThat(r.getCreated())
                .hasSize(2)
                .extracting(ScheduleBatchResultVO.Created::getStartTime)
                .containsExactlyInAnyOrder(valid1, valid2);
        assertThat(r.getSkipped()).hasSize(3);
        assertThat(r.getSkipped())
                .extracting(ScheduleBatchResultVO.Skipped::getReason)
                .anySatisfy(reason -> assertThat(reason).contains("开场时间必须晚于当前时间"))
                .allSatisfy(reason -> assertThat(reason).containsAnyOf("开场时间必须晚于当前时间", "排片时间冲突"));
        // 只建了 2 场票; 行内票价 80 生效
        verify(ticketService, times(2)).createTicketsForSchedule(any(), eq(List.of(seat)));
    }

    @Test
    void batchAddFailsFastOnInvalidMovie() {
        // 影片不存在 → 整批失败, 一行都不插
        when(movieService.getMovie(404L)).thenReturn(null);
        ScheduleBatchAddDTO dto =
                new ScheduleBatchAddDTO(
                        404L,
                        2L,
                        List.of(
                                new ScheduleBatchAddDTO.Session(
                                        LocalDateTime.now().plusDays(1), null)),
                        null,
                        null,
                        null);
        assertThatThrownBy(() -> service.batchAdd(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("电影不存在");
        verify(scheduleMapper, never()).insert(any(Schedule.class));
    }

    @Test
    void batchAddFailsFastOnHallWithoutSeatMap() {
        givenMovie(1L, 120);
        Hall hall = new Hall();
        hall.setId(2L);
        hall.setName("毛坯厅");
        hall.setCurrentVersionId(null);
        when(hallService.getById(2L)).thenReturn(hall);
        ScheduleBatchAddDTO dto =
                new ScheduleBatchAddDTO(
                        1L,
                        2L,
                        List.of(
                                new ScheduleBatchAddDTO.Session(
                                        LocalDateTime.now().plusDays(1), null)),
                        null,
                        null,
                        null);
        assertThatThrownBy(() -> service.batchAdd(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("还没有座位图");
        verify(scheduleMapper, never()).insert(any(Schedule.class));
    }

    // ===== batchMove / batchInfo =====

    /** 造一个批次场次: 同厅同片, 指定日期的指定时刻开场. */
    private Schedule batchSession(long id, int dayOffset, int hour, String batchId) {
        Schedule s = new Schedule();
        s.setId(id);
        s.setMovieId(1L);
        s.setHallId(2L);
        s.setStatus(1);
        s.setBatchId(batchId);
        s.setStartTime(
                LocalDateTime.now()
                        .plusDays(dayOffset)
                        .withHour(hour)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0));
        return s;
    }

    @Test
    void batchMoveMovesOnlySameSlotSessions() {
        givenMovie(1L, 120);
        String bid = "batch-1";
        // 3 场 10:00(不同日期) + 1 场 15:00 —— 只有 10:00 的三场要动
        Schedule s1 = batchSession(21, 1, 10, bid);
        Schedule s2 = batchSession(22, 2, 10, bid);
        Schedule s3 = batchSession(23, 3, 10, bid);
        Schedule other = batchSession(24, 1, 15, bid);
        when(scheduleMapper.selectList(any())).thenReturn(List.of(s1, s2, s3, other));
        when(scheduleMapper.listConflict(any(), any(), any(), any())).thenReturn(List.of());
        when(scheduleMapper.updateById(any(Schedule.class))).thenReturn(1);

        LocalDateTime source = s1.getStartTime();
        LocalDateTime target = source.toLocalDate().atTime(14, 0);
        ScheduleBatchResultVO r = service.batchMove(new ScheduleBatchMoveDTO(bid, source, target));

        assertThat(r.getCreated())
                .hasSize(3)
                .extracting(ScheduleBatchResultVO.Created::getStartTime)
                .allMatch(t -> t.toLocalTime().equals(java.time.LocalTime.of(14, 0)))
                // 各场保留自己的日期
                .extracting(t -> t.toLocalDate())
                .containsExactlyInAnyOrder(
                        s1.getStartTime().toLocalDate(),
                        s2.getStartTime().toLocalDate(),
                        s3.getStartTime().toLocalDate());
        assertThat(r.getSkipped()).isEmpty();
        // 只改了 3 场(15:00 那场不动), 且只动 startTime/endTime 不动票价
        ArgumentCaptor<Schedule> po = ArgumentCaptor.forClass(Schedule.class);
        verify(scheduleMapper, times(3)).updateById((Schedule) po.capture());
        assertThat(po.getAllValues())
                .allSatisfy(
                        p -> {
                            assertThat(p.getPrice()).isNull();
                            assertThat(p.getStartTime().toLocalTime())
                                    .isEqualTo(java.time.LocalTime.of(14, 0));
                            // endTime = 新时刻 + 时长 120 + 清场 30
                            assertThat(p.getEndTime()).isEqualTo(p.getStartTime().plusMinutes(150));
                        });
    }

    @Test
    void batchMoveSkipsConflictsAndStarted() {
        givenMovie(1L, 120);
        String bid = "batch-2";
        Schedule ok = batchSession(31, 1, 10, bid);
        Schedule conflict = batchSession(32, 2, 10, bid);
        Schedule started = new Schedule();
        started.setId(33L);
        started.setMovieId(1L);
        started.setHallId(2L);
        started.setStatus(1);
        started.setBatchId(bid);
        started.setStartTime(
                LocalDateTime.now()
                        .minusDays(1)
                        .withHour(10)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0)); // 昨天的, 已开场
        when(scheduleMapper.selectList(any())).thenReturn(List.of(ok, conflict, started));
        when(scheduleMapper.listConflict(any(), any(), any(), any())).thenReturn(List.of());
        // 冲突场的目标时刻与库里已有场次撞
        when(scheduleMapper.listConflict(
                        eq(2L),
                        eq(conflict.getStartTime().toLocalDate().atTime(14, 0)),
                        any(),
                        eq(32L)))
                .thenReturn(List.of(new Schedule()));
        when(scheduleMapper.updateById(any(Schedule.class))).thenReturn(1);

        LocalDateTime source = ok.getStartTime();
        ScheduleBatchResultVO r =
                service.batchMove(
                        new ScheduleBatchMoveDTO(bid, source, source.toLocalDate().atTime(14, 0)));

        assertThat(r.getCreated())
                .hasSize(1)
                .extracting(ScheduleBatchResultVO.Created::getScheduleId)
                .containsExactly(31L);
        assertThat(r.getSkipped()).hasSize(2);
        assertThat(r.getSkipped())
                .extracting(ScheduleBatchResultVO.Skipped::getReason)
                .anySatisfy(reason -> assertThat(reason).contains("排片时间冲突"))
                .anySatisfy(reason -> assertThat(reason).contains("场次已开场"));
        verify(scheduleMapper, times(1)).updateById(any(Schedule.class));
    }

    @Test
    void batchMoveIgnoresCanceledSessions() {
        givenMovie(1L, 120);
        String bid = "batch-3";
        Schedule only = batchSession(41, 1, 10, bid);
        // 查询层只取 status=1(SQL 层过滤, 由 ScheduleSqlTest 的
        // batchQueryExcludesCanceledSessions 验证) —— 已取消场次不会进入改期范围
        when(scheduleMapper.selectList(any())).thenReturn(List.of(only));
        when(scheduleMapper.listConflict(any(), any(), any(), any())).thenReturn(List.of());
        when(scheduleMapper.updateById(any(Schedule.class))).thenReturn(1);

        ScheduleBatchResultVO r =
                service.batchMove(
                        new ScheduleBatchMoveDTO(
                                bid,
                                only.getStartTime(),
                                only.getStartTime().toLocalDate().atTime(14, 0)));
        assertThat(r.getCreated()).hasSize(1);
    }

    @Test
    void batchInfoCountsMovableSessions() {
        String bid = "batch-4";
        Schedule future1 = batchSession(51, 1, 10, bid);
        Schedule future2 = batchSession(52, 2, 10, bid);
        Schedule started = new Schedule();
        started.setId(53L);
        started.setStatus(1);
        started.setBatchId(bid);
        started.setStartTime(
                LocalDateTime.now()
                        .minusDays(1)
                        .withHour(10)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0));
        // 同批不同时刻的不算
        Schedule otherSlot = batchSession(54, 1, 15, bid);
        when(scheduleMapper.selectList(any()))
                .thenReturn(List.of(future1, future2, started, otherSlot));

        var info = service.batchInfo(bid, java.time.LocalTime.of(10, 0));
        assertThat(info.getTotal()).isEqualTo(3);
        assertThat(info.getMovable()).isEqualTo(2);
    }

    @Test
    void batchAddStampsSharedBatchId() {
        givenMovie(1L, 120);
        givenHall(2L);
        givenSeats(9L);
        when(scheduleMapper.insert(any(Schedule.class)))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(0, Schedule.class).setId(100L);
                            return 1;
                        });
        LocalDateTime t1 =
                LocalDateTime.now()
                        .plusDays(1)
                        .withHour(10)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
        LocalDateTime t2 =
                LocalDateTime.now()
                        .plusDays(2)
                        .withHour(10)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
        service.batchAdd(
                new ScheduleBatchAddDTO(
                        1L,
                        2L,
                        List.of(
                                new ScheduleBatchAddDTO.Session(t1, null),
                                new ScheduleBatchAddDTO.Session(t2, null)),
                        new BigDecimal("60"),
                        null,
                        null));

        ArgumentCaptor<Schedule> po = ArgumentCaptor.forClass(Schedule.class);
        verify(scheduleMapper, times(2)).insert((Schedule) po.capture());
        assertThat(po.getAllValues().get(0).getBatchId()).isNotNull();
        assertThat(po.getAllValues().get(0).getBatchId())
                .isEqualTo(po.getAllValues().get(1).getBatchId());
    }

    // ===== delete =====

    @Test
    void deleteRejectsStartedSchedule() {
        Schedule exist = new Schedule();
        exist.setId(5L);
        exist.setStartTime(LocalDateTime.now().minusHours(1));
        when(scheduleMapper.selectById(5L)).thenReturn(exist);
        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能删除");
    }

    @Test
    void deleteRejectsWithOrderedTickets() {
        Schedule exist = new Schedule();
        exist.setId(5L);
        exist.setStartTime(LocalDateTime.now().plusDays(1));
        when(scheduleMapper.selectById(5L)).thenReturn(exist);
        when(ticketService.countOrderedBySchedule(5L)).thenReturn(2L);
        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("挂在订单上");
    }

    @Test
    void deleteRemovesTicketsAndSchedule() {
        Schedule exist = new Schedule();
        exist.setId(5L);
        exist.setStartTime(LocalDateTime.now().plusDays(1));
        when(scheduleMapper.selectById(5L)).thenReturn(exist);
        when(ticketService.countOrderedBySchedule(5L)).thenReturn(0L);
        when(scheduleMapper.deleteById(5L)).thenReturn(1);

        assertThat(service.delete(5L)).isTrue();
        verify(ticketService).deleteBySchedule(5L);
        verify(scheduleMapper).deleteById(5L);
    }
}
