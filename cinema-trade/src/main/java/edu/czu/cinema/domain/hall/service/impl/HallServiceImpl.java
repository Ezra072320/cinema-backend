package edu.czu.cinema.domain.hall.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.hall.dto.SeatDraftSaveDTO;
import edu.czu.cinema.domain.hall.dto.SeatDraftSeatDTO;
import edu.czu.cinema.domain.hall.entity.Hall;
import edu.czu.cinema.domain.hall.entity.HallSeatVersion;
import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.hall.entity.SeatCoupleGroup;
import edu.czu.cinema.domain.hall.mapper.HallMapper;
import edu.czu.cinema.domain.hall.mapper.HallSeatVersionMapper;
import edu.czu.cinema.domain.hall.mapper.SeatCoupleGroupMapper;
import edu.czu.cinema.domain.hall.mapper.SeatMapper;
import edu.czu.cinema.domain.hall.service.HallService;
import edu.czu.cinema.domain.hall.service.SeatVersionService;
import edu.czu.cinema.domain.schedule.entity.Schedule;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HallServiceImpl implements HallService {

    private final HallMapper hallMapper;
    private final SeatMapper seatMapper;
    private final HallSeatVersionMapper versionMapper;
    private final SeatVersionService seatVersionService;
    private final SeatCoupleGroupMapper coupleGroupMapper;
    private final ScheduleMapper scheduleMapper;

    @Override
    public List<Hall> listAll() {
        return hallMapper.selectList(null);
    }

    @Override
    public Hall getById(Long id) {
        return hallMapper.selectById(id);
    }

    /**
     * 新建影厅. Phase 3 改造: 写完 hall 后自动建 v1, 同时:
     * <ul>
     *   <li>如果 hall.seats 非空(老接口调用方传了 seats), 把这些 seat 行挂到 v1 上</li>
     *   <li>如果 hall.seats 为空(新前端调用方不传), v1 是空座位图, 管理员后续用编辑器画</li>
     * </ul>
     */
    /**
     * 新建影厅.
     *
     * <p><b>建厅时不填座位数</b> —— 座位数由座位图决定, 画完并发布之后由
     * {@code applyDraft} 写进 {@code capacity}. 所以这里建出来的厅 capacity 是 0,
     * 前端显示"未画座位图".
     *
     * <p>老调用方(把座位随厅一起传进来的那条兼容路径)才顺手按座位反推一次,
     * 但**不走发布流程**的话它也不是权威值.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(Hall hall) {
        // capacity 不接受客户端传值: 它是算出来的, 不是填出来的.
        // 传了也只是被这里清掉, 免得建厅那一刻就出现一个"看着像真的"的数字.
        hall.setCapacity(0);
        hallMapper.insert(hall);
        // 自动建 v1(空座位图)
        Long v1Id = seatVersionService.createInitialVersion(hall.getId(), null);
        if (CollUtil.isNotEmpty(hall.getSeats())) {
            for (Seat s : hall.getSeats()) {
                s.setHallId(hall.getId());
                s.setVersionId(v1Id);
                seatMapper.insert(s);
            }
            int available = (int) hall.getSeats().stream()
                    .filter(s -> s.getStatus() != null && s.getStatus() == 1).count();
            hall.setCapacity(available);
            hallMapper.updateById(hall);
        }
        return hall.getId();
    }

    /**
     * 删除影厅 —— 连带它的座位图(所有版本)、座位行、情侣对.
     *
     * <p>顺序: 先拦排片 → 再删情侣对 → 座位行 → 版本 → 影厅.
     * 情侣对必须显式删: 这张表没有外键, 早先删厅只删了 seat 和 hall_seat_version,
     * 情侣对全留成了永久孤儿.
     *
     * <p>**有排片就整个拒绝**, 不做级联. 因为排片底下还挂着票(可能已售), a 直接删厅会让
     * 那些票指向一个不存在的厅和不存在的座位, 用户订单页面直接开天窗.
     * 正确姿势是先删排片(删排片自己会拦"有订单占用"的情况), 再删厅.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id) {
        Hall hall = hallMapper.selectById(id);
        if (hall == null) {
            return false;
        }
        long schedules = scheduleMapper.selectCount(
                new LambdaQueryWrapper<Schedule>().eq(Schedule::getHallId, id));
        if (schedules > 0) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "影厅「" + hall.getName() + "」还有 " + schedules
                            + " 个排片, 不能删除. 请先到「排片管理」删掉这些排片");
        }
        coupleGroupMapper.deleteByHall(id);
        seatMapper.delete(new LambdaQueryWrapper<Seat>().eq(Seat::getHallId, id));
        versionMapper.delete(new LambdaQueryWrapper<HallSeatVersion>().eq(HallSeatVersion::getHallId, id));
        return hallMapper.deleteById(id) > 0;
    }

    /**
     * 老的「update hall + 替换全部 seats」接口. Phase 3 起改成走新流程:
     * <ol>
     *   <li>不允许直接改 seats 字段(否则 ticket.seat_id 失效), 改成建草稿 + 调 SeatVersionService.saveDraft</li>
     *   <li>本次只做最小兼容: 拿到 seats 后, 如果当前没有草稿就先建草稿, 然后用 saveDraft 走乐观锁路径</li>
     *   <li>不触发发布 —— 发布必须走 POST /seats/draft/apply, 这是管理员显式操作</li>
     * </ol>
     *
     * <p>前端如果还在用, 需要尽快迁移到 HallSeatAdminController 的新接口.
     * 这个方法暂时保留是为了不让老的前端 HallManage.vue 整体炸掉, 等前端迁移完即可删.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Hall updateWithSeats(Hall hall) {
        if (hall.getId() == null) return hall;
        // 1. 先 update hall 自身(name/type 等).
        //    capacity 清空再更新: 它是座位图算出来的, 不该由客户端指定
        //    (MyBatis-Plus 跳过 null, 所以置 null = 不动这一列).
        hall.setCapacity(null);
        hallMapper.updateById(hall);
        // 2. 如果没传 seats, 啥都不做
        if (CollUtil.isEmpty(hall.getSeats())) {
            return hall;
        }
        // 3. 走草稿路径
        log.warn("[hall] updateWithSeats 走兼容路径: 写草稿不发布, 建议前端迁移到 /seats/draft 系列接口");
        seatVersionService.getOrCreateDraft(hall.getId());
        // 拿到草稿的 lockVersion
        Integer lockVersion = versionMapper.selectOne(new LambdaQueryWrapper<HallSeatVersion>()
                        .eq(HallSeatVersion::getHallId, hall.getId())
                        .eq(HallSeatVersion::getIsCurrent, 0)
                        .eq(HallSeatVersion::getIsLocked, 0)
                        .last("LIMIT 1"))
                .getDraftLockVersion();
        // 准备 DTO
        SeatDraftSaveDTO dto = new SeatDraftSaveDTO();
        dto.setExpectedLockVersion(lockVersion);
        List<SeatDraftSeatDTO> seatDTOs = new ArrayList<>();
        for (Seat s : hall.getSeats()) {
            SeatDraftSeatDTO d = new SeatDraftSeatDTO();
            d.setId(s.getId());
            d.setY(s.getY());
            d.setX(s.getX());
            d.setStatus(s.getStatus());
            d.setUnitType(s.getType());
            seatDTOs.add(d);
        }
        dto.setSeats(seatDTOs);
        seatVersionService.saveDraft(hall.getId(), dto);
        return hall;
    }

    /**
     * 某厅当前生效版本的座位.
     *
     * <p>不能按 hall_id 直接查座位表 —— Phase 2 起一个厅有多份版本的座位行,
     * 按 hall_id 查会返回跨版本重复数据(详情页画重影, 排片建票建双份).
     * 真相在 hall.current_version_id 指向的那个版本里.
     *
     * <p>厅还没发布过任何版本时 current_version_id 可能为空, 返回空列表而不是报错:
     * 新建厅后立刻调详情页是正常操作, 不该 500.
     */
    @Override
    public List<Seat> seatsOf(Long hallId) {
        Hall h = hallMapper.selectById(hallId);
        if (h == null || h.getCurrentVersionId() == null) {
            return List.of();
        }
        return seatMapper.listByVersion(h.getCurrentVersionId());
    }

    @Override
    public List<Seat> seatsOfVersion(Long versionId) {
        if (versionId == null) return List.of();
        return seatMapper.listByVersion(versionId);
    }

    @Override
    public List<SeatCoupleGroup> coupleGroupsOf(Long hallId) {
        Hall h = hallMapper.selectById(hallId);
        if (h == null || h.getCurrentVersionId() == null) {
            return List.of();
        }
        return seatVersionService.coupleGroupsOf(h.getCurrentVersionId());
    }
}
