package edu.czu.cinema.domain.hall;

import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.hall.dto.CoupleGroupDTO;
import edu.czu.cinema.domain.hall.dto.SeatApplyResultVO;
import edu.czu.cinema.domain.hall.dto.SeatDraftSaveDTO;
import edu.czu.cinema.domain.hall.dto.SeatDraftSeatDTO;
import edu.czu.cinema.domain.hall.entity.Hall;
import edu.czu.cinema.domain.hall.entity.HallSeatVersion;
import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.hall.mapper.HallMapper;
import edu.czu.cinema.domain.hall.mapper.HallSeatVersionMapper;
import edu.czu.cinema.domain.hall.mapper.SeatCoupleGroupMapper;
import edu.czu.cinema.domain.hall.mapper.SeatMapper;
import edu.czu.cinema.domain.hall.service.impl.SeatVersionServiceImpl;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 座位图版本服务单测: 聚焦本次修复的并发/坐标竞态 ——
 * saveDraft「移动+同坐标新增」(P0-5)、applyDraft 原子发布与 409(P1-H1/H3)、
 * getOrCreateDraft 并发撞唯一键回读(P1-H2)、saveDraft 拒改已发布版本(P1-H3)。
 */
class SeatVersionServiceImplTest {

    private HallMapper hallMapper;
    private HallSeatVersionMapper versionMapper;
    private SeatMapper seatMapper;
    private SeatCoupleGroupMapper coupleGroupMapper;
    private ScheduleMapper scheduleMapper;
    private SeatVersionServiceImpl service;

    @BeforeEach
    void setUp() {
        hallMapper = mock(HallMapper.class);
        versionMapper = mock(HallSeatVersionMapper.class);
        seatMapper = mock(SeatMapper.class);
        coupleGroupMapper = mock(SeatCoupleGroupMapper.class);
        scheduleMapper = mock(ScheduleMapper.class);
        service = new SeatVersionServiceImpl(hallMapper, versionMapper, seatMapper,
                coupleGroupMapper, scheduleMapper);
    }

    /** 一个可被 findDraft(selectOne) 返回的草稿版本行. */
    private HallSeatVersion draft(long id, int lockVersion, int isCurrent, int isLocked) {
        HallSeatVersion v = new HallSeatVersion();
        v.setId(id);
        v.setHallId(2L);
        v.setVersion(2);
        v.setTemplateId(null);
        v.setAppliedAt(java.time.LocalDateTime.now());
        v.setNote("草稿");
        v.setIsCurrent(isCurrent);
        v.setIsLocked(isLocked);
        v.setDraftLockVersion(lockVersion);
        return v;
    }

    // ===== saveDraft: P0-5 坐标残留 =====

    /**
     * 一次保存内「把 A(id=11)从 (1,1) 移到 (2,2), 又在 (1,1) 新增 B」:
     * A 必须被 update 且落到 (2,2), B 必须被 insert 到 (1,1).
     * 修复前 byCoord 里 A 的旧坐标键悬空, B 按坐标匹配到 A 的残留, 会把 A 改回旧格、
     * 新座位静默吞掉.
     */
    @Test
    void saveDraftMoveThenInsertAtOldCoord() {
        HallSeatVersion draft = draft(1L, 5, 0, 0);
        when(versionMapper.selectOne(any())).thenReturn(draft); // findDraft + P1-H3 复查
        when(versionMapper.tryLockDraft(1L, 5)).thenReturn(1);

        Seat moving = new Seat();
        moving.setId(11L);
        moving.setHallId(2L);
        moving.setVersionId(1L);
        moving.setY(1);
        moving.setX(1);
        moving.setStatus(1);
        moving.setType(1);
        when(seatMapper.selectList(any())).thenReturn(List.of(moving));
        when(coupleGroupMapper.deleteByVersion(anyLong())).thenReturn(0);
        when(seatMapper.listByVersion(anyLong())).thenReturn(List.of(moving));

        SeatDraftSaveDTO dto = new SeatDraftSaveDTO();
        dto.setExpectedLockVersion(5);
        SeatDraftSeatDTO moved = new SeatDraftSeatDTO();
        moved.setId(11L); moved.setY(2); moved.setX(2); moved.setStatus(1); moved.setUnitType(1);
        SeatDraftSeatDTO added = new SeatDraftSeatDTO();
        added.setY(1); added.setX(1); added.setStatus(1); added.setUnitType(1);
        dto.setSeats(List.of(moved, added));
        dto.setCoupleGroups(List.<CoupleGroupDTO>of());

        service.saveDraft(2L, dto);

        // A 只被 update 一次, 落到 (2,2)
        ArgumentCaptor<Seat> upd = ArgumentCaptor.forClass(Seat.class);
        verify(seatMapper, times(1)).updateById(upd.capture());
        assertThat(upd.getValue().getId()).isEqualTo(11L);
        assertThat(upd.getValue().getY()).isEqualTo(2);
        assertThat(upd.getValue().getX()).isEqualTo(2);
        // B 作为新座位 insert 到 (1,1), 而不是被 A 吞掉
        ArgumentCaptor<Seat> ins = ArgumentCaptor.forClass(Seat.class);
        verify(seatMapper, times(1)).insert(ins.capture());
        assertThat(ins.getValue().getY()).isEqualTo(1);
        assertThat(ins.getValue().getX()).isEqualTo(1);
        assertThat(ins.getValue().getVersionId()).isEqualTo(1L);
    }

    /** P1-H3: 草稿已被发布(is_current=1)后不能继续编辑 —— 409, 且不写任何 seat. */
    @Test
    void saveDraftRejectsAfterVersionPublished() {
        HallSeatVersion draft = draft(1L, 0, 0, 0);
        HallSeatVersion published = draft(1L, 0, 1, 0);
        when(versionMapper.selectOne(any())).thenReturn(draft, published); // findDraft→复查
        when(versionMapper.tryLockDraft(1L, 0)).thenReturn(1);

        SeatDraftSaveDTO dto = new SeatDraftSaveDTO();
        dto.setExpectedLockVersion(0);
        SeatDraftSeatDTO s = new SeatDraftSeatDTO();
        s.setId(11L); s.setY(1); s.setX(1); s.setStatus(1);
        dto.setSeats(List.of(s));

        assertThatThrownBy(() -> service.saveDraft(2L, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能继续编辑");
        verify(seatMapper, never()).insert(any(Seat.class));
        verify(seatMapper, never()).updateById(any(Seat.class));
        verify(seatMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    // ===== applyDraft: P1-H1/H3 =====

    @Test
    void applyDraftLocksHallFirstThenPublishes() {
        when(scheduleMapper.lockHall(2L)).thenReturn(1L);
        Hall hall = new Hall(); hall.setId(2L); hall.setName("1 号厅"); hall.setCurrentVersionId(100L);
        when(hallMapper.selectById(2L)).thenReturn(hall);
        HallSeatVersion draft = draft(55L, 3, 0, 0);
        when(versionMapper.selectOne(any())).thenReturn(draft);
        when(versionMapper.tryLockDraft(55L, 3)).thenReturn(1);
        when(scheduleMapper.countFutureByHall(2L)).thenReturn(0L);
        HallSeatVersion current = draft(100L, 0, 1, 0);
        when(versionMapper.findCurrentByHall(2L)).thenReturn(current);
        when(versionMapper.updateById(any(HallSeatVersion.class))).thenReturn(1); // current 和 draft 都成功
        when(seatMapper.selectCount(any())).thenReturn(6L);

        SeatApplyResultVO r = service.applyDraft(2L, 77L);

        assertThat(r.isOk()).isTrue();
        assertThat(r.getNewVersionId()).isEqualTo(55L);
        // 入口先锁厅 —— 与排片写路径同一把锁
        verify(scheduleMapper).lockHall(2L);
        verify(versionMapper).tryLockDraft(55L, 3);
        // capacity 按 status=1 座位数重算
        ArgumentCaptor<Hall> hCap = ArgumentCaptor.forClass(Hall.class);
        verify(hallMapper).updateById(hCap.capture());
        assertThat(hCap.getValue().getCurrentVersionId()).isEqualTo(55L);
        assertThat(hCap.getValue().getCapacity()).isEqualTo(6);
    }

    /** P1-H1/H3: 草稿已被并发删除时 updateById 返回 0 行 → 409「草稿已被删除，请刷新」. */
    @Test
    void applyDraftThrows409WhenDraftDeletedConcurrently() {
        when(scheduleMapper.lockHall(2L)).thenReturn(1L);
        Hall hall = new Hall(); hall.setId(2L); hall.setName("1 号厅");
        when(hallMapper.selectById(2L)).thenReturn(hall);
        HallSeatVersion draft = draft(55L, 3, 0, 0);
        when(versionMapper.selectOne(any())).thenReturn(draft);
        when(versionMapper.tryLockDraft(55L, 3)).thenReturn(1);
        when(scheduleMapper.countFutureByHall(2L)).thenReturn(0L);
        HallSeatVersion current = draft(100L, 0, 1, 0);
        when(versionMapper.findCurrentByHall(2L)).thenReturn(current);
        // current 更新成功, 但草稿那行 update 撞上并发删除 → 0 行
        when(versionMapper.updateById(any(HallSeatVersion.class))).thenReturn(1, 0);

        assertThatThrownBy(() -> service.applyDraft(2L, 77L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("草稿已被删除");
        // 失败要整体回滚: hall 指针不能被改
        verify(hallMapper, never()).updateById(any(Hall.class));
    }

    /** P1-H1/H3: 有人正在存草稿时发布失败 → 409. */
    @Test
    void applyDraftThrows409WhenSaveInFlight() {
        when(scheduleMapper.lockHall(2L)).thenReturn(1L);
        Hall hall = new Hall(); hall.setId(2L); hall.setName("1 号厅");
        when(hallMapper.selectById(2L)).thenReturn(hall);
        HallSeatVersion draft = draft(55L, 3, 0, 0);
        when(versionMapper.selectOne(any())).thenReturn(draft);
        when(versionMapper.tryLockDraft(55L, 3)).thenReturn(0); // 乐观锁被抢

        assertThatThrownBy(() -> service.applyDraft(2L, 77L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("草稿已被修改");
        verify(scheduleMapper, never()).countFutureByHall(anyLong());
    }

    // ===== getOrCreateDraft: P1-H2 =====

    /** 并发双建草稿: 后到的撞 uk_hall_version, 回读对方草稿返回而非 500. */
    @Test
    void getOrCreateDraftReturnsExistingOnDuplicateKey() {
        Hall hall = new Hall(); hall.setId(2L); hall.setName("1 号厅");
        when(hallMapper.selectById(2L)).thenReturn(hall);
        HallSeatVersion existingDraft = draft(9L, 0, 0, 0);
        // 第一次 findDraft → null(还没草稿); 撞键后回读 → existingDraft
        when(versionMapper.selectOne(any())).thenReturn(null, existingDraft);
        HallSeatVersion current = draft(1L, 0, 1, 0);
        when(versionMapper.findCurrentByHall(2L)).thenReturn(current);
        when(seatMapper.selectList(any())).thenReturn(List.of());
        when(versionMapper.insert(any(HallSeatVersion.class))).thenThrow(new DuplicateKeyException("uk_hall_version"));
        when(seatMapper.listByVersion(anyLong())).thenReturn(List.of());
        when(coupleGroupMapper.listByVersion(anyLong())).thenReturn(List.of());

        var vo = service.getOrCreateDraft(2L);

        assertThat(vo.getDraft().getId()).isEqualTo(9L);
    }
}