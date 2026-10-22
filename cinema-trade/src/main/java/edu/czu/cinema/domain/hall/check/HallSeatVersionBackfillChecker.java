package edu.czu.cinema.domain.hall.check;

import edu.czu.cinema.domain.hall.entity.Hall;
import edu.czu.cinema.domain.hall.entity.HallSeatVersion;
import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.hall.mapper.HallMapper;
import edu.czu.cinema.domain.hall.mapper.HallSeatVersionMapper;
import edu.czu.cinema.domain.hall.mapper.SeatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 启动时自检: hall / seat 的 version_id 字段必须 100% 覆盖.
 *
 * <p>Phase 2 的回填脚本已经写好这条不变量, 这里在每次服务启动时再校一次, 防止
 * <ul>
 *   <li>手工 DML 漏掉某行</li>
 *   <li>Phase 3 草稿保存路径绕过 version_id 直接 insert</li>
 *   <li>未来 phase 在事务中部分失败留下的孤儿 seat</li>
 * </ul>
 *
 * <p><b>检查项</b>:
 * <ol>
 *   <li>每个 hall 必须有 {@code current_version_id}, 且该 id 真的存在于 {@code hall_seat_version}</li>
 *   <li>每个 seat 必须有 {@code version_id}, 且该 id 真的存在</li>
 *   <li>hall_seat_version.is_current=1 的版本数 == hall 数</li>
 * </ol>
 *
 * <p>不通过时只记 ERROR 日志, 不阻断启动 —— 让运营能进后台修数据.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HallSeatVersionBackfillChecker implements ApplicationRunner {

    private final HallMapper hallMapper;
    private final SeatMapper seatMapper;
    private final HallSeatVersionMapper versionMapper;

    @Override
    public void run(ApplicationArguments args) {
        // 1. hall.current_version_id 完整性
        List<Hall> halls = hallMapper.selectList(null);
        if (halls.isEmpty()) {
            log.info("[hall-version-check] 无影厅, 跳过自检");
            return;
        }
        // 先把所有版本 id 一次捞进内存再比对.
        // 早先是对每个 hall / 每个 seat 各来一次 selectById —— 一个几千座位的影院
        // 每次启动就是上万次查询. 版本表本身很小, 一次全量读完全够.
        Set<Long> versionIds = versionMapper.selectList(null).stream()
                .map(HallSeatVersion::getId)
                .collect(Collectors.toSet());

        int hallMissing = 0;
        int hallBadRef = 0;
        for (Hall h : halls) {
            if (h.getCurrentVersionId() == null) {
                hallMissing++;
                log.error("[hall-version-check] hall.id={} name={} 缺 current_version_id",
                        h.getId(), h.getName());
                continue;
            }
            if (!versionIds.contains(h.getCurrentVersionId())) {
                hallBadRef++;
                log.error("[hall-version-check] hall.id={} current_version_id={} 找不到对应版本",
                        h.getId(), h.getCurrentVersionId());
            }
        }

        // 2. seat.version_id 完整性
        List<Seat> seats = seatMapper.selectList(null);
        int seatMissing = 0;
        int seatBadRef = 0;
        for (Seat s : seats) {
            if (s.getVersionId() == null) {
                seatMissing++;
                log.error("[seat-version-check] seat.id={} hall_id={} 缺 version_id",
                        s.getId(), s.getHallId());
                continue;
            }
            if (!versionIds.contains(s.getVersionId())) {
                seatBadRef++;
                log.error("[seat-version-check] seat.id={} version_id={} 找不到对应版本",
                        s.getId(), s.getVersionId());
            }
        }

        // 3. is_current=1 数量 == hall 数量
        long currentCount = versionMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HallSeatVersion>()
                        .eq(HallSeatVersion::getIsCurrent, 1));
        if (currentCount != halls.size()) {
            log.error("[hall-version-check] hall_seat_version.is_current=1 的条数={} 与 hall 数={} 不一致",
                    currentCount, halls.size());
        }

        // 汇总
        boolean ok = hallMissing == 0 && hallBadRef == 0
                && seatMissing == 0 && seatBadRef == 0
                && currentCount == halls.size();
        if (ok) {
            log.info("[hall-version-check] 全部通过: hall={} seat={} currentVersion={}",
                    halls.size(), seats.size(), currentCount);
        } else {
            log.error("[hall-version-check] 不通过: hallMissing={} hallBadRef={} seatMissing={} seatBadRef={} currentCount={}/{}",
                    hallMissing, hallBadRef, seatMissing, seatBadRef, currentCount, halls.size());
        }
    }
}
