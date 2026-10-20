package edu.czu.cinema.domain.hall.service;

import edu.czu.cinema.domain.hall.dto.SeatApplyResultVO;
import edu.czu.cinema.domain.hall.dto.SeatDraftSaveDTO;
import edu.czu.cinema.domain.hall.dto.SeatDraftVO;
import edu.czu.cinema.domain.hall.entity.HallSeatVersion;

import java.util.List;

public interface SeatVersionService {

    /**
     * 新建厅时自动建 v1: 1 条 hall_seat_version(is_current=1) + 0 个 seat(空座位图).
     * 后续管理员在 Phase 3 编辑器里画座位 → 存到草稿 → 发布变成 v2, 这次 v1 也保留.
     */
    Long createInitialVersion(Long hallId, Long templateId);

    /**
     * 拉某厅草稿. 不存在时按当前 current version 复制一份, is_current=0, draft_lock_version=0.
     * 注意: 草稿一旦存在, 再次调用直接返回已有草稿, 不会覆盖. 删除草稿走专门的 dropDraft().
     */
    SeatDraftVO getOrCreateDraft(Long hallId);

    /**
     * 存草稿. 全量替换 seats 表里 version_id = draftId 的所有行.
     * 乐观锁: 不匹配 expectedLockVersion 则 409, 提示「已被改, 请刷新」.
     */
    void saveDraft(Long hallId, SeatDraftSaveDTO dto);

    /**
     * 删草稿 (回退到 current). 草稿存在才能删.
     */
    void dropDraft(Long hallId);

    /**
     * 发布草稿: 校验「该厅无未放映场次」 → 老 current is_current=0+is_locked=1,
     * 草稿 is_current=1 + is_locked=0 + version 自增, hall.current_version_id 切到新版本,
     * 同步 hall.capacity(座位数 = 该版本 status=1 的座位数, 由座位图算出来, 不是管理员填的).
     *
     * <p>有未放映场次时拒绝, 返回 SeatApplyResultVO(ok=false, blockingScheduleIds=[...]).
     */
    SeatApplyResultVO applyDraft(Long hallId, Long adminId);

    /** 列某厅所有版本, 降序. */
    List<HallSeatVersion> listVersions(Long hallId);

    /**
     * 某座位图版本下的全部情侣对. 选座页据此把两格合并成一个视觉单元.
     */
    List<edu.czu.cinema.domain.hall.entity.SeatCoupleGroup> coupleGroupsOf(Long versionId);

    /**
     * 与这些座位相关联的情侣对 —— 只要一对里有一个座位命中就会返回.
     *
     * <p>下单校验用: 用户选中的座位如果牵连了情侣对, 必须成对购买.
     */
    List<edu.czu.cinema.domain.hall.entity.SeatCoupleGroup> coupleGroupsCovering(
            java.util.Collection<Long> seatIds);
}
