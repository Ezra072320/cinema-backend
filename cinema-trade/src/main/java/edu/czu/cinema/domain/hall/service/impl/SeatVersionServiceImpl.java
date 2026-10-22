package edu.czu.cinema.domain.hall.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.hall.dto.CoupleGroupDTO;
import edu.czu.cinema.domain.hall.dto.SeatApplyResultVO;
import edu.czu.cinema.domain.hall.dto.SeatDraftSaveDTO;
import edu.czu.cinema.domain.hall.dto.SeatDraftSeatDTO;
import edu.czu.cinema.domain.hall.dto.SeatDraftVO;
import edu.czu.cinema.domain.hall.entity.Hall;
import edu.czu.cinema.domain.hall.entity.HallSeatVersion;
import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.hall.entity.SeatCoupleGroup;
import edu.czu.cinema.domain.hall.mapper.HallMapper;
import edu.czu.cinema.domain.hall.mapper.HallSeatVersionMapper;
import edu.czu.cinema.domain.hall.mapper.SeatCoupleGroupMapper;
import edu.czu.cinema.domain.hall.mapper.SeatMapper;
import edu.czu.cinema.domain.hall.service.SeatVersionService;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatVersionServiceImpl implements SeatVersionService {

    /** 座位类型: 情侣座. 定价倍率见 TicketServiceImpl. */
    private static final int TYPE_COUPLE = 2;
    /** 座位类型: 标准. */
    private static final int TYPE_STANDARD = 1;

    private final HallMapper hallMapper;
    private final HallSeatVersionMapper versionMapper;
    private final SeatMapper seatMapper;
    private final SeatCoupleGroupMapper coupleGroupMapper;
    private final ScheduleMapper scheduleMapper;

    /* ====================== 创建初始版本 ====================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createInitialVersion(Long hallId, Long templateId) {
        Hall h = hallMapper.selectById(hallId);
        if (h == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "影厅不存在");
        }
        // 如果已有 current version (回填脚本建的), 直接返回 — 不要重复建
        HallSeatVersion current = versionMapper.findCurrentByHall(hallId);
        if (current != null) {
            return current.getId();
        }
        HallSeatVersion v = new HallSeatVersion();
        v.setHallId(hallId);
        v.setVersion(1);
        v.setTemplateId(templateId);
        v.setAppliedAt(LocalDateTime.now());
        v.setIsCurrent(1);
        v.setIsLocked(0);
        v.setDraftLockVersion(0);
        v.setNote("新建厅: 初始版本");
        versionMapper.insert(v);
        // 同步 hall.current_version_id
        h.setCurrentVersionId(v.getId());
        hallMapper.updateById(h);
        log.info("[seat-version] 影厅 hallId={} 新建初始版本 v1 (id={})", hallId, v.getId());
        return v.getId();
    }

    /* ====================== 草稿拉/建 ====================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SeatDraftVO getOrCreateDraft(Long hallId) {
        Hall h = hallMapper.selectById(hallId);
        if (h == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "影厅不存在");
        }
        // 1. 已有草稿? 直接返回
        HallSeatVersion draft = findDraft(hallId);
        if (draft != null) {
            return buildDraftVO(draft);
        }
        // 2. 没草稿, 从 current 复制一份
        HallSeatVersion current = versionMapper.findCurrentByHall(hallId);
        if (current == null) {
            // 没 current: 说明连 v1 都没有, 自动建 v1 + 草稿
            Long v1Id = createInitialVersion(hallId, null);
            current = versionMapper.selectById(v1Id);
        }
        // 3. 复制 current 的 seat 行到草稿. 注意: version_id 要换, 保留原 id (草稿里)
        List<Seat> currentSeats = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getHallId, hallId)
                .eq(Seat::getVersionId, current.getId()));
        HallSeatVersion newDraft = new HallSeatVersion();
        newDraft.setHallId(hallId);
        // 草稿预占 current.version+1 这个号. uk_hall_version 不会撞, 因为当前 current 还是旧号.
        // 发布时无需再 update version; 老 current→is_locked, 草稿 is_current→1 即可.
        newDraft.setVersion(current.getVersion() + 1);
        newDraft.setTemplateId(current.getTemplateId());
        newDraft.setAppliedAt(LocalDateTime.now());
        newDraft.setIsCurrent(0);
        newDraft.setIsLocked(0);
        newDraft.setDraftLockVersion(0);
        newDraft.setNote("草稿: 基于 v" + current.getVersion());
        try {
            versionMapper.insert(newDraft);
        } catch (DuplicateKeyException e) {
            // P1-H2: 两个请求同时「没草稿 → 建草稿」, 都预占了同一个 version 号,
            // 后到的撞 uk_hall_version. 这不是客户端的错, 直接回读对方建好的草稿返回.
            // 回读必须用**锁定读**(当前读): 本事务开头的 selectById 已建 RR 快照,
            // 普通 SELECT 看不到对方刚提交的草稿行, 会白抛 409.
            HallSeatVersion existing = versionMapper.selectOne(new LambdaQueryWrapper<HallSeatVersion>()
                    .eq(HallSeatVersion::getHallId, hallId)
                    .eq(HallSeatVersion::getIsCurrent, 0)
                    .eq(HallSeatVersion::getIsLocked, 0)
                    .last("LIMIT 1 FOR UPDATE"));
            if (existing == null) {
                throw new BusinessException(ResultCode.CONFLICT, "草稿创建冲突, 请刷新重试");
            }
            log.info("[seat-version] 影厅 hallId={} 并发建草稿, 回读已有草稿 (id={})", hallId, existing.getId());
            return buildDraftVO(existing);
        }
        // 复制 seat 行. 草稿是新的一批 seat 行(新 id), 所以要记下 老id → 新id 的映射,
        // 情侣对引用的是 seat id, 不映射的话复制过去就会指向老版本的座位.
        Map<Long, Long> seatIdMap = new HashMap<>();
        for (Seat src : currentSeats) {
            Seat copy = new Seat();
            copy.setHallId(src.getHallId());
            copy.setVersionId(newDraft.getId());
            copy.setY(src.getY());
            copy.setX(src.getX());
            copy.setStatus(src.getStatus());
            copy.setType(src.getType());
            seatMapper.insert(copy);
            seatIdMap.put(src.getId(), copy.getId());
        }
        // 复制情侣对(带上 id 映射)
        List<SeatCoupleGroup> currentGroups = coupleGroupMapper.listByVersion(current.getId());
        List<SeatCoupleGroup> groupsToCopy = new ArrayList<>();
        for (SeatCoupleGroup g : currentGroups) {
            Long n1 = seatIdMap.get(g.getSeatId1());
            Long n2 = seatIdMap.get(g.getSeatId2());
            if (n1 == null || n2 == null) continue; // 座位没复制过来, 这一对就丢掉
            SeatCoupleGroup copy = new SeatCoupleGroup();
            copy.setHallId(hallId);
            copy.setVersionId(newDraft.getId());
            copy.setSeatId1(n1);
            copy.setSeatId2(n2);
            copy.setLabel(g.getLabel());
            groupsToCopy.add(copy);
        }
        if (!groupsToCopy.isEmpty()) {
            coupleGroupMapper.batchInsert(groupsToCopy);
        }
        log.info("[seat-version] 影厅 hallId={} 新建草稿 (id={}), 从 v{} 复制 {} 个座位 / {} 对情侣座",
                hallId, newDraft.getId(), current.getVersion(), currentSeats.size(), groupsToCopy.size());
        return buildDraftVO(newDraft);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dropDraft(Long hallId) {
        HallSeatVersion draft = findDraft(hallId);
        if (draft == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "无草稿可删除");
        }
        // 先删草稿下的情侣对和 seat 行, 再删草稿本身.
        // 情侣对必须先删: 它引用 seat.id, 虽然没建外键, 但留着就是悬空引用.
        coupleGroupMapper.deleteByVersion(draft.getId());
        seatMapper.delete(new LambdaQueryWrapper<Seat>().eq(Seat::getVersionId, draft.getId()));
        versionMapper.deleteById(draft.getId());
        log.info("[seat-version] 影厅 hallId={} 删除草稿 (id={})", hallId, draft.getId());
    }

    /* ====================== 存草稿 ====================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveDraft(Long hallId, SeatDraftSaveDTO dto) {
        HallSeatVersion draft = findDraft(hallId);
        if (draft == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "无草稿, 请先 GET 一次");
        }
        // 1. 乐观锁: 尝试 +1, 不匹配说明别人改过
        int updated = versionMapper.tryLockDraft(draft.getId(), dto.getExpectedLockVersion());
        if (updated == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "草稿已被别人修改, 请刷新后重试");
        }
        // 2. P1-H3: 写 seat 行之前用**锁定读**复查版本状态 —— 冻结不变量的最后防线.
        //
        //    场景: applyDraft 发布时会把草稿的 draft_lock_version 重置回 0. 于是「已发布」
        //    之后, expectedLockVersion=0 的保存仍能通过上面的乐观锁 CAS(撞上发布后的 0),
        //    把已冻结版本的座位改掉. 普通 SELECT 又受 RR 快照限制看不见并发发布, 所以这里
        //    必须 FOR UPDATE(当前读)重读: 只有 is_current=0 且 is_locked=0 才允许写.
        //    这把版本行锁还会把并发 applyDraft 的 tryLockDraft 顶在事务外, 发布等保存落地.
        HallSeatVersion locked = versionMapper.selectOne(new LambdaQueryWrapper<HallSeatVersion>()
                .eq(HallSeatVersion::getId, draft.getId())
                .last("FOR UPDATE"));
        if (locked == null || locked.getIsCurrent() == null || locked.getIsCurrent() != 0
                || locked.getIsLocked() == null || locked.getIsLocked() != 0) {
            throw new BusinessException(ResultCode.CONFLICT, "草稿已发布或已删除，不能继续编辑");
        }
        // 3. 准备数据 + 边界校验
        List<SeatDraftSeatDTO> incoming = dto.getSeats();
        if (incoming == null) incoming = Collections.emptyList();
        // 校验: (y, x) 不重复; y/x 必须在 [1, 50] 范围内. 草稿可扩缩(空座位图起步都行).
        Set<String> seen = new HashSet<>();
        for (SeatDraftSeatDTO d : incoming) {
            String key = d.getY() + "-" + d.getX();
            if (!seen.add(key)) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "同一草稿下 (y=" + d.getY() + ", x=" + d.getX() + ") 重复");
            }
            if (d.getY() == null || d.getY() < 1 || d.getY() > 50) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "y 越界: " + d.getY());
            }
            if (d.getX() == null || d.getX() < 1 || d.getX() > 50) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "x 越界: " + d.getX());
            }
        }
        // 4. 拿到草稿下当前所有 seat, 建两张索引: 按 id / 按坐标
        List<Seat> currentDraftSeats = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getVersionId, draft.getId()));
        Map<Long, Seat> byId = new HashMap<>();
        Map<String, Seat> byCoord = new HashMap<>();
        for (Seat s : currentDraftSeats) {
            byId.put(s.getId(), s);
            byCoord.put(coordKey(s.getY(), s.getX()), s);
        }
        // 5. 遍历 incoming 做 upsert —— 匹配规则是「先按 id, 再按坐标」.
        //
        //    ⚠️ 只按 id 匹配是不够的. 这个端点的语义是**整图覆盖**, 但客户端不一定把
        //    上一轮拿到的 seat id 回传(比如直接照着文档拼一个 body). 那时已有坐标会被
        //    当成"新座位"去 insert, 撞 uk_version_xy —— 加唯一键之前则是**静默写入
        //    重复坐标**, 前端网格上两个座位叠在同一格. 按坐标兜底匹配之后,
        //    这个端点对任何客户端都是真正的"整图覆盖".
        for (SeatDraftSeatDTO d : incoming) {
            String ck = coordKey(d.getY(), d.getX());
            Seat exist = null;
            if (d.getId() != null && byId.containsKey(d.getId())) {
                exist = byId.get(d.getId());
            } else if (byCoord.containsKey(ck)) {
                // 没给 id(或给的 id 不属于这个草稿), 但坐标上有行 -> 就是它, 改而不要新增
                exist = byCoord.get(ck);
            }
            if (exist != null) {
                // P0-5: 必须在改坐标**之前**记下旧坐标、删掉旧坐标键.
                // 早先先 setY/setX 再 remove(新坐标键) —— 删的是别人家的键/删了个寂寞,
                // 旧坐标键悬空留在 byCoord 里. 于是「移动 A 到新格 + 在 A 的旧格新增 B」时,
                // B 按坐标匹配到 A 的残留条目, 变成"把 A 更新回旧格" —— 新座位被静默吞掉,
                // 拖拽结果被回滚, 无任何报错.
                int oldY = exist.getY();
                int oldX = exist.getX();
                byCoord.remove(coordKey(oldY, oldX));
                exist.setY(d.getY());
                exist.setX(d.getX());
                exist.setStatus(d.getStatus());
                exist.setType(d.getUnitType() == null ? 1 : d.getUnitType());
                seatMapper.updateById(exist);
                byId.remove(exist.getId());
                byCoord.put(ck, exist);
            } else {
                Seat ns = new Seat();
                ns.setHallId(hallId);
                ns.setVersionId(draft.getId());
                ns.setY(d.getY());
                ns.setX(d.getX());
                ns.setStatus(d.getStatus());
                ns.setType(d.getUnitType() == null ? 1 : d.getUnitType());
                seatMapper.insert(ns);
                byCoord.put(ck, ns);
            }
        }
        // 6. byId 剩余 = 客户端删了的, 物理删除.
        //
        //    这里**不需要**检查"座位是否被票引用". 早期版本查过 ticket.seat_id, 但那是个
        //    永远不成立的比较: 草稿里的座位是从 current 整份复制出来的**新行**(新 id),
        //    而票引用的是已发布版本里的旧行 —— 两个 id 空间不相交, 检查恒为 false.
        //
        //    真正的保护来自版本机制本身: 删除只作用于草稿, 已发布版本的座位行一个都不动,
        //    所以已售的票永远能对上它当初那一版的座位. 见 README「影厅与座位图模块」。
        for (Seat removed : byId.values()) {
            seatMapper.deleteById(removed.getId());
        }
        // 7. 情侣对整表替换.
        //    必须放在座位 upsert 之后 —— 新增座位此刻才有 id, 按坐标反查才能查到.
        int groupCount = replaceCoupleGroups(hallId, draft.getId(), dto.getCoupleGroups());
        log.info("[seat-version] 草稿保存 hallId={} draftId={} seats={} 情侣对={}",
                hallId, draft.getId(), incoming.size(), groupCount);
    }

    /**
     * 整表替换某草稿版本的情侣对, 并同步 seat.type.
     *
     * <p>顺便把 {@code seat.type} 跟配对关系对齐: 成对的座位 type=2, 解开的回到 1.
     * 这样 type(=座位的"身份") 和 group(=配对关系) 不会各说各话 —— 定价读 type,
     * 下单校验读 group, 两者必须一致.
     *
     * @return 写入的情侣对数量
     */
    private int replaceCoupleGroups(Long hallId, Long versionId, List<CoupleGroupDTO> couples) {
        // 先清旧关系, 再按新关系写. 整表替换而不是增量 diff: 情侣对数量少,
        // 且"这一版里哪些座位是情侣座"本身就是个整体状态, diff 更容易出错.
        coupleGroupMapper.deleteByVersion(versionId);

        // 坐标 → seat, 用来把 (y,x) 解析成 seat id
        List<Seat> seats = seatMapper.listByVersion(versionId);
        Map<String, Seat> byCoord = new HashMap<>();
        for (Seat s : seats) byCoord.put(s.getY() + "-" + s.getX(), s);

        List<SeatCoupleGroup> toInsert = new ArrayList<>();
        Set<Long> usedSeatIds = new HashSet<>();
        if (CollUtil.isNotEmpty(couples)) {
            for (CoupleGroupDTO c : couples) {
                Seat a = byCoord.get(c.getY1() + "-" + c.getX1());
                Seat b = byCoord.get(c.getY2() + "-" + c.getX2());
                if (a == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "情侣座 (第" + c.getY1() + "排第" + c.getX1() + "列) 没有座位");
                }
                if (b == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "情侣座 (第" + c.getY2() + "排第" + c.getX2() + "列) 没有座位");
                }
                if (Objects.equals(a.getId(), b.getId())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "情侣座的两个位置不能是同一格");
                }
                if (!Objects.equals(a.getY(), b.getY())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "情侣座必须同一排");
                }
                if (Math.abs(a.getX() - b.getX()) != 1) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "情侣座必须左右相邻 (第" + a.getY() + "排 x=" + a.getX() + " 与 x=" + b.getX() + ")");
                }
                if (!isPairable(a) || !isPairable(b)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "情侣座只能由标准座配成, VIP / 无障碍座不能配对 (第" + a.getY()
                                    + "排第" + a.getX() + "列/第" + b.getX() + "列)");
                }
                if (!usedSeatIds.add(a.getId()) || !usedSeatIds.add(b.getId())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "一个座位只能属于一对情侣座 (第" + a.getY() + "排)");
                }
                // 存的时候按 x 升序, 让 seatId1 恒为左边那个, 读出来顺序稳定
                Seat left = a.getX() < b.getX() ? a : b;
                Seat right = a.getX() < b.getX() ? b : a;
                SeatCoupleGroup g = new SeatCoupleGroup();
                g.setHallId(hallId);
                g.setVersionId(versionId);
                g.setSeatId1(left.getId());
                g.setSeatId2(right.getId());
                g.setLabel(c.getLabel());
                toInsert.add(g);
            }
        }
        if (!toInsert.isEmpty()) {
            coupleGroupMapper.batchInsert(toInsert);
        }

        // 同步 seat.type: 成对的置 2, 其余曾经是 2 的退回 1
        for (Seat s : seats) {
            int want = usedSeatIds.contains(s.getId()) ? TYPE_COUPLE : TYPE_STANDARD;
            int cur = s.getType() == null ? TYPE_STANDARD : s.getType();
            // 只动「标准 ↔ 情侣」这一对, 不碰 VIP(3)/无障碍(4)
            if (cur == want) continue;
            if (want == TYPE_COUPLE && cur != TYPE_STANDARD) continue; // VIP 座不因为配对降级
            if (want == TYPE_STANDARD && cur != TYPE_COUPLE) continue;
            Seat patch = new Seat();
            patch.setId(s.getId());
            patch.setType(want);
            seatMapper.updateById(patch);
        }
        return toInsert.size();
    }

    private static boolean isSellable(Seat s) {
        return s.getStatus() != null && s.getStatus() == 1;
    }

    /**
     * 这个座位能不能配成情侣座 —— 必须是可售的**标准座**.
     *
     * <p>VIP(3) / 无障碍(4) 不行: 一个座位的 `type` 只能有一个值, 让它既"是 VIP"
     * 又"是情侣"没法表达. 下面的 type 同步逻辑为了避免"把 VIP 降级"会保持 type=3,
     * 于是会留下一个**看着是 VIP、买的时候却要求成对**的座位 —— 显示、定价(读 type)、
     * 下单校验(读 pair)三者互相矛盾. 与其造出这种数据, 不如在配对这一步就拒掉.
     */
    private static boolean isPairable(Seat s) {
        return isSellable(s) && (s.getType() == null || s.getType() == TYPE_STANDARD);
    }

    /** 坐标索引的 key. 同一版本里 (y,x) 唯一(有 uk_version_xy 兜底). */
    private static String coordKey(Integer y, Integer x) {
        return y + "-" + x;
    }

    /* ====================== 发布 ====================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SeatApplyResultVO applyDraft(Long hallId, Long adminId) {
        // P1-H1: 入口先锁厅(与排片 add/batchAdd 同一把厅锁) —— 堵住「发布×排片」竞态:
        // 否则排片建票读了旧 current_version_id 之后发布一切指针, 场次和票就都挂在
        // 过期版本上. 锁厅也让下面的未放映场次校验读在写路径串行化之后, 不漏刚排进来的场次.
        scheduleMapper.lockHall(hallId);
        Hall h = hallMapper.selectById(hallId);
        if (h == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "影厅不存在");
        }
        HallSeatVersion draft = findDraft(hallId);
        if (draft == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "无草稿可发布");
        }
        // P1-H3: 原子发布 —— 用 tryLockDraft(draftId, 当前 lockVersion) 抢这把草稿乐观锁,
        // 与 saveDraft 同一把钥匙: 有人正在存草稿(或刚存过)就抢不到, 发布 409;
        // 反之保存侧也会因为 lockVersion 对不上而失败. 两个方向都堵死.
        int claimed = versionMapper.tryLockDraft(draft.getId(), draft.getDraftLockVersion());
        if (claimed == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "草稿已被修改，请刷新后重试");
        }
        // 1. 关键校验: 该厅是否有未放映场次. 有就拒绝, 给出 schedule id 列表.
        long future = scheduleMapper.countFutureByHall(hallId);
        if (future > 0) {
            // 列一下 schedule id 供管理员去排片管理取消
            List<Long> blockingIds = listFutureScheduleIds(hallId);
            log.warn("[seat-version] 影厅 hallId={} 有 {} 个未放映场次, 拒绝发布: {}",
                    hallId, future, blockingIds);
            SeatApplyResultVO r = new SeatApplyResultVO();
            r.setOk(false);
            r.setMessage("影厅存在 " + future + " 个未放映场次, 请先到「排片管理」取消这些场次（取消会自动全额退款）");
            r.setBlockingScheduleIds(blockingIds);
            return r;
        }
        // 2. 找 current version
        HallSeatVersion current = versionMapper.findCurrentByHall(hallId);
        if (current == null) {
            throw new BusinessException(ResultCode.SERVER_ERROR, "找不到 current version");
        }
        // 3. 老 current: is_current=0, is_locked=1.
        //    P1-H1: 必须看影响行数 —— 0 行说明版本行已被并发删掉/改掉, 这时若继续把
        //    hall.current_version_id 指过去, 会指向一个"已删/异常"的版本, capacity=0
        //    永久挡排片. 宁可 409 让管理员刷新.
        current.setIsCurrent(0);
        current.setIsLocked(1);
        if (versionMapper.updateById(current) == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "版本状态已变化，请刷新后重试");
        }
        // 4. 草稿 → 新 current: draft.version 已是 current+1(预占的), 只需翻 is_current/is_locked
        draft.setIsCurrent(1);
        draft.setIsLocked(0);
        draft.setDraftLockVersion(0);
        draft.setAppliedAt(LocalDateTime.now());
        draft.setAppliedBy(adminId);
        if (versionMapper.updateById(draft) == 0) {
            // 0 行 = 草稿已被并发删除(dropDraft / 另一个发布), 不能继续
            throw new BusinessException(ResultCode.CONFLICT, "草稿已被删除，请刷新");
        }
        // 5. 同步 hall: 当前版本指针 + 座位数
        //
        //    capacity 在这里**重算**, 不是管理员填的 —— 座位数由座位图决定.
        //    (门户影厅列表直接显示这个值, 所以它必须是"真的画了多少座位")
        h.setCurrentVersionId(draft.getId());
        h.setCapacity(countAvailableSeats(draft.getId()));
        hallMapper.updateById(h);
        log.info("[seat-version] 影厅 hallId={} 发布草稿 → v{} (id={}), capacity={}",
                hallId, draft.getVersion(), draft.getId(), h.getCapacity());
        SeatApplyResultVO r = new SeatApplyResultVO();
        r.setOk(true);
        r.setNewVersionId(draft.getId());
        r.setNewVersionNumber(draft.getVersion());
        r.setMessage("已发布 v" + draft.getVersion());
        return r;
    }

    /* ====================== 查询 ====================== */

    @Override
    public List<HallSeatVersion> listVersions(Long hallId) {
        return versionMapper.listByHall(hallId);
    }

    @Override
    public List<SeatCoupleGroup> coupleGroupsOf(Long versionId) {
        if (versionId == null) return List.of();
        return coupleGroupMapper.listByVersion(versionId);
    }

    @Override
    public List<SeatCoupleGroup> coupleGroupsCovering(Collection<Long> seatIds) {
        if (CollUtil.isEmpty(seatIds)) return List.of();
        return coupleGroupMapper.listBySeatIds(seatIds);
    }

    /* ====================== helpers ====================== */

    /**
     * 找某厅的「草稿」(is_current=0 AND is_locked=0).
     * 注意: 已发布但刚解冻的版本不算草稿. 一次只允许 1 个草稿.
     */
    private HallSeatVersion findDraft(Long hallId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<HallSeatVersion>()
                .eq(HallSeatVersion::getHallId, hallId)
                .eq(HallSeatVersion::getIsCurrent, 0)
                .eq(HallSeatVersion::getIsLocked, 0)
                .last("LIMIT 1"));
    }

    private SeatDraftVO buildDraftVO(HallSeatVersion draft) {
        SeatDraftVO vo = new SeatDraftVO();
        vo.setDraft(draft);
        vo.setLockVersion(draft.getDraftLockVersion());
        vo.setSeats(seatMapper.listByVersion(draft.getId()));
        vo.setCoupleGroups(coupleGroupMapper.listByVersion(draft.getId()));
        return vo;
    }

    private int countAvailableSeats(Long versionId) {
        Long n = seatMapper.selectCount(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getVersionId, versionId)
                .eq(Seat::getStatus, 1));
        return n == null ? 0 : n.intValue();
    }

    /** 列举未放映场次 id, 供拒绝发布时附带给前端. 限 20 条免得膨胀. */
    private List<Long> listFutureScheduleIds(Long hallId) {
        // 复用 ScheduleMapper 的 selectList + 过滤: 这里直接 new 一份, 因为 mapper 里没现成方法
        // 不引入新的 xml, 用 BaseMapper 的能力简单 select
        return scheduleMapper.selectList(new LambdaQueryWrapper<edu.czu.cinema.domain.schedule.entity.Schedule>()
                        .eq(edu.czu.cinema.domain.schedule.entity.Schedule::getHallId, hallId)
                        .gt(edu.czu.cinema.domain.schedule.entity.Schedule::getStartTime, LocalDateTime.now())
                        .ne(edu.czu.cinema.domain.schedule.entity.Schedule::getStatus, 2)
                        .orderByAsc(edu.czu.cinema.domain.schedule.entity.Schedule::getStartTime)
                        .last("LIMIT 20"))
                .stream().map(edu.czu.cinema.domain.schedule.entity.Schedule::getId).toList();
    }

}
