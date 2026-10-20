package edu.czu.cinema.domain.hall.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.hall.entity.SeatCoupleGroup;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

public interface SeatCoupleGroupMapper extends BaseMapper<SeatCoupleGroup> {

    default List<SeatCoupleGroup> listByVersion(Long versionId) {
        return selectList(new LambdaQueryWrapper<SeatCoupleGroup>()
                .eq(SeatCoupleGroup::getVersionId, versionId)
                .orderByAsc(SeatCoupleGroup::getId));
    }

    default List<SeatCoupleGroup> listBySeatIds(Collection<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapper<SeatCoupleGroup>()
                .and(w -> w.in(SeatCoupleGroup::getSeatId1, seatIds)
                        .or().in(SeatCoupleGroup::getSeatId2, seatIds)));
    }

    default int deleteByVersion(Long versionId) {
        return delete(new LambdaQueryWrapper<SeatCoupleGroup>()
                .eq(SeatCoupleGroup::getVersionId, versionId));
    }

    default int deleteByHall(Long hallId) {
        return delete(new LambdaQueryWrapper<SeatCoupleGroup>()
                .eq(SeatCoupleGroup::getHallId, hallId));
    }

    /**
     * 批量插入。保留手写 SQL 的唯一理由：MP BaseMapper 无批量 insert，
     * 逐条 insert 在整套座位图（数百行）场景会产生 N 次往返；此处是一条多值 INSERT。
     */
    int batchInsert(@Param("list") List<SeatCoupleGroup> groups);
}
