package edu.czu.cinema.domain.schedule.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import edu.czu.cinema.domain.schedule.entity.Schedule;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 排片 Mapper.
 *
 * <p>2026-10-20 起, 单表条件查询/区间重叠查询全部改写为 MyBatis-Plus LambdaQueryWrapper (见下方默认方法), XML 只保留无法用 MP
 * 表达的聚合 SQL({@code movieShowSummaries}). 原先 XML resultMap 漏映射 batch_id 的问题随改写自然消失.
 */
public interface ScheduleMapper extends BaseMapper<Schedule> {

    List<edu.czu.cinema.domain.movie.vo.MovieShowSummary> movieShowSummaries(
            @Param("movieIds") List<Long> movieIds);

    /**
     * 查某厅在给定时段内与之冲突的场次(锁定读).
     *
     * <p>时段重叠: 两场 [s1,e1) 与 [s2,e2) 冲突 ⇔ {@code s1 < e2 AND e1 > s2}。 {@code excludeId}
     * 用于「改排片」时排除自身, 否则自己跟自己冲突。
     *
     * <p><strong>必须是锁定读({@code FOR UPDATE} = 当前读)</strong>: MySQL 默认 REPEATABLE_READ 下, 普通 SELECT
     * 读的是事务快照 —— 若快照建立在 lockHall 之前, 就看不到窗口内并发改期/排片 刚提交的新场次, 两个管理员会把同一厅排进重叠时段(双卖). FOR UPDATE 走当前读,
     * 读到最新已提交行. 调用方应先 {@link #lockHall} 拿到厅锁再调本方法。
     *
     * @param excludeId 排除掉的场次 id(改排片时排除自身), 可为 null
     */
    default List<Schedule> listConflict(
            Long hallId, LocalDateTime startTime, LocalDateTime endTime, Long excludeId) {
        return selectList(
                new LambdaQueryWrapper<Schedule>()
                        .eq(Schedule::getHallId, hallId)
                        .eq(Schedule::getStatus, 1)
                        .lt(Schedule::getStartTime, endTime)
                        .gt(Schedule::getEndTime, startTime)
                        .ne(excludeId != null, Schedule::getId, excludeId)
                        .last("FOR UPDATE"));
    }

    /**
     * 锁定读取一条排片(当前读). 用于 lockHall 之后重读最新状态 —— RR 快照下的普通 selectById 可能读到旧 status(如刚被并发 cancel 置 2
     * 的场次).
     */
    default Schedule selectByIdForUpdate(Long id) {
        return selectOne(
                new LambdaQueryWrapper<Schedule>().eq(Schedule::getId, id).last("FOR UPDATE"));
    }

    /**
     * 查某厅「未放映」的场次数: start_time > now() AND status != 已取消(2). 座位图发布前用它拦截. 历史场次不影响(它们的座位图已冻结,
     * 与本次发布无关). 取消场次自动释放时段 —— 取消即解锁座位图发布.
     */
    default long countFutureByHall(Long hallId) {
        Long n =
                selectCount(
                        new LambdaQueryWrapper<Schedule>()
                                .eq(Schedule::getHallId, hallId)
                                .gt(Schedule::getStartTime, LocalDateTime.now())
                                .ne(Schedule::getStatus, 2));
        return n == null ? 0L : n;
    }

    /** 给影厅行加排它锁, 串行化同厅的排片新增/改期/取消的冲突检测(仿 MovieSnapshotRepository.lockById). */
    @org.apache.ibatis.annotations.Select("SELECT id FROM hall WHERE id = #{hallId} FOR UPDATE")
    Long lockHall(@Param("hallId") Long hallId);
}
