package edu.czu.cinema.domain.hall.service;

import edu.czu.cinema.domain.hall.entity.Hall;
import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.hall.entity.SeatCoupleGroup;

import java.util.List;

public interface HallService {

    List<Hall> listAll();

    Hall getById(Long id);

    Long add(Hall hall);

    boolean delete(Long id);

    Hall updateWithSeats(Hall hall);

    /**
     * 某厅**当前生效版本**的座位. 影厅详情 / 用户侧展示用.
     *
     * <p>不是「该厅所有座位」—— Phase 2 起座位按版本存, 一个厅有多份座位行,
     * 直接按 hall_id 查会返回跨版本重复数据. 这里走 hall.current_version_id.
     */
    List<Seat> seatsOf(Long hallId);

    /**
     * 指定座位图版本的全部座位行(含过道 status=0), 按 (y, x) 升序.
     * 排片建票按场次锁定的版本取, 不要用 seatsOf(hallId).
     */
    List<Seat> seatsOfVersion(Long versionId);

    /**
     * 某厅**当前生效版本**里的情侣对. 影厅详情页据此把一对的两个格子合并成一个视觉单元.
     *
     * <p>必须由后端给, 不能让前端靠"相邻两个 type=2"去猜 —— 一排里连着三个情侣座
     * (两对挨着)时猜不出边界.
     */
    List<SeatCoupleGroup> coupleGroupsOf(Long hallId);
}
