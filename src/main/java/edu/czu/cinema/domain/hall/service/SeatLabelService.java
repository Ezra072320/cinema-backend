package edu.czu.cinema.domain.hall.service;

import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.hall.entity.SeatCoupleGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 座位可读标签服务 —— 一比一移植前端的 useSeatGrid 编号规则,
 * 产出"3-5"形式的座号, 与选座页 grid 显示一致.
 *
 * <p>三条规则:
 * <ol>
 *   <li>过道(status≠1)不占号</li>
 *   <li>一对情侣座只占一个号(靠左那半递增, 另一半复用同号)</li>
 *   <li>整排都是过道的行, 该排不占排号</li>
 * </ol>
 *
 * <p>座位排序按 x 升序, 排号重排仅用于显示, 与物理 (y, x) 坐标无关.
 * 改编号规则时前端 useSeatGrid 与此处要同步改.
 */
@Service
@RequiredArgsConstructor
public class SeatLabelService {

    private final HallService hallService;
    private final SeatVersionService seatVersionService;

    /**
     * 解析某版本的"座位 id → 显示标签", 如 {@code 3-5} 表示"第 3 排第 5 号".
     *
     * @param versionId 座位图版本 id(为 null 时返回空 map)
     * @return seatId → 标签字符串; 不可售/不在场次的座位不会出现
     */
    @org.springframework.cache.annotation.Cacheable(
            cacheNames = "seatLabels", key = "#versionId", condition = "#versionId != null")
    public Map<Long, String> labelsOf(Long versionId) {
        if (versionId == null) return Collections.emptyMap();
        // 该版本的可售座位(走 HallService 的版本专用查询, 不混入其他版本的座位)
        List<Seat> seats = hallService.seatsOfVersion(versionId);
        // 情侣对 group → 双方 seatId
        List<SeatCoupleGroup> groups = seatVersionService.coupleGroupsOf(versionId);

        // seatId → 情侣组 id(null 表示非情侣座)
        Map<Long, Long> groupOfSeat = new HashMap<>();
        for (SeatCoupleGroup g : groups) {
            if (g.getSeatId1() != null) groupOfSeat.put(g.getSeatId1(), g.getId());
            if (g.getSeatId2() != null) groupOfSeat.put(g.getSeatId2(), g.getId());
        }

        // 按 y 分组, 内部按 x 排序
        Map<Integer, List<Seat>> byRow = new TreeMap<>();
        for (Seat s : seats) {
            if (s.getStatus() == null || s.getStatus() != 1) continue; // 1=可售
            byRow.computeIfAbsent(s.getY(), k -> new java.util.ArrayList<>()).add(s);
        }

        // 第一遍: 收集有座位(按 x 升序)的排, 用于计算排号重排
        // 仅包含至少有一个 status=1 座位的 y, 才能算有排号
        Map<Long, String> result = new HashMap<>();
        int rowNo = 0;
        for (Map.Entry<Integer, List<Seat>> entry : byRow.entrySet()) {
            int y = entry.getKey();
            List<Seat> sortedSeats = entry.getValue();
            sortedSeats.sort((a, b) -> Integer.compare(a.getX() == null ? 0 : a.getX(),
                    b.getX() == null ? 0 : b.getX()));
            // 该排全为 status=1 才进入(ensure byRow 本身已过滤)
            rowNo += 1;
            int seatNo = 0;
            Set<Long> countedGroups = new HashSet<>();
            for (Seat s : sortedSeats) {
                Long gid = groupOfSeat.get(s.getId());
                if (gid != null) {
                    if (!countedGroups.add(gid)) {
                        // 情侣对右半: 复用左半已占的号, 不递增
                        result.put(s.getId(), rowNo + "-" + seatNo);
                        continue;
                    }
                }
                seatNo += 1;
                result.put(s.getId(), rowNo + "-" + seatNo);
            }
        }
        return result;
    }
}
