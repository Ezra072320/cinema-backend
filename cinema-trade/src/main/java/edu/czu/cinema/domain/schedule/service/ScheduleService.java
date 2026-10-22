package edu.czu.cinema.domain.schedule.service;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.domain.schedule.dto.ScheduleAddDTO;
import edu.czu.cinema.domain.schedule.dto.ScheduleUpdateDTO;
import edu.czu.cinema.domain.schedule.entity.Schedule;

import java.time.LocalDate;
import java.util.List;

public interface ScheduleService {

    PageResult<Schedule> page(int page, int size, LocalDate date, Long hallId, Long movieId, Integer status);

    Schedule getById(Long id);

    Long add(ScheduleAddDTO dto);

    /**
     * 批量排片. 行级冲突/时间不合法的时段被跳过并记录原因, 其余入库;
     * 影片/影厅级问题(不存在/无时长/无座位图)整批直接失败.
     */
    edu.czu.cinema.domain.schedule.vo.ScheduleBatchResultVO batchAdd(
            edu.czu.cinema.domain.schedule.dto.ScheduleBatchAddDTO dto);

    /**
     * 批量改期「同时段整体改时刻」: 同批次里开场时刻与 source 相同的正常场次,
     * 全部改到各自日期上的 target 时刻; 不同时刻的场次不动. 冲突/过期的跳过并记录原因.
     */
    edu.czu.cinema.domain.schedule.vo.ScheduleBatchResultVO batchMove(
            edu.czu.cinema.domain.schedule.dto.ScheduleBatchMoveDTO dto);

    /** 批次同时段规模(total/movable), 前端拖拽确认弹窗用. */
    edu.czu.cinema.domain.schedule.vo.ScheduleBatchInfoVO batchInfo(String batchId, java.time.LocalTime time);

    /**
     * 更新排片. 只能改影片/开场时间 —— **不能改影厅、不能改价**,
     * 换厅或调价都要取消后重排(见 {@link ScheduleUpdateDTO} 的说明).
     */
    boolean update(ScheduleUpdateDTO dto);

    /**
     * 取消场次: status 1→2, 停止售票, 挂在该场次上的订单自动全额退款, 全场票作废。
     * 允许取消任意场次(含已开场/已放映)。返回受影响订单数。
     */
    int cancel(Long id);

    boolean delete(Long id);

    List<Schedule> listForMovie(Long movieId);

    /** 公开：按影厅列出排片（前端门户首页用） */
    List<Schedule> publicByHall(Long hallId, Long movieId);

    /**
     * 选座页数据: 场次信息 + 该场次锁定版本的座位图 + 每座的票状态.
     * 场次不存在返回 null.
     */
    edu.czu.cinema.domain.schedule.vo.ScheduleSeatsVO seatsOfSchedule(Long scheduleId);
}
