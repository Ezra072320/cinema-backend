package edu.czu.cinema.domain.hall.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.hall.entity.Seat;

import java.util.List;

public interface SeatMapper extends BaseMapper<Seat> {

    /**
     * 按版本取座位（按 y,x 排序）。
     * <p>不要改成按 hall_id —— 一个厅有多份版本的座位行，按 hall_id 会返回跨版本重复数据。</p>
     */
    default List<Seat> listByVersion(Long versionId) {
        return selectList(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getVersionId, versionId)
                .orderByAsc(Seat::getY)
                .orderByAsc(Seat::getX));
    }
}
