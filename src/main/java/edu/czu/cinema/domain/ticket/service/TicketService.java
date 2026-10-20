package edu.czu.cinema.domain.ticket.service;

import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.schedule.entity.Schedule;
import edu.czu.cinema.domain.ticket.entity.Ticket;
import edu.czu.cinema.domain.ticket.vo.SeatStatusVO;

import java.util.List;

public interface TicketService {

    List<Ticket> listByIds(List<Long> ids);

    /** 某场次的全部票(每个可售座位一条). 选座页用它把 seatId ↔ ticketId 对上. */
    List<Ticket> listBySchedule(Long scheduleId);

    /**
     * 该场次下**已被订单占用**的票数(占用语义: {@code ticket.status=0}).
     *
     * <p>删场次前用它判断能不能删. 只数被订单占着的票: 取消场次作废的票(status=2)
     * 不算已售 —— 否则「取消后场次可删」永远过不去, 与「已售数」口径打架.
     */
    long countOrderedBySchedule(Long scheduleId);

    /** 删除某场次的全部票(删场次时回收). 返回删除行数. */
    int deleteBySchedule(Long scheduleId);

    /**
     * 场次取消: 全场票作废(status=2), 但**保留 order_id** —— 已退款订单还能回看当初的座位.
     * 与 {@link #releaseTickets}(用户退票, 票回可售并解绑)不同 —— 取消场景整场不再售票.
     */
    int voidBySchedule(Long scheduleId);

    void createTicketsForSchedule(Schedule schedule, List<Seat> seats);

    /**
     * 锁定票为某订单占座. 返回**受影响行数** —— 调用方必须与请求数比较:
     * 不足即部分锁(并发抢票), 整单业务要回滚, 不要再用 &gt;0 判断成功.
     */
    int lockTickets(List<Long> ticketIds, Long orderId);

    boolean releaseTickets(Long orderId);

    /** 该场次已售票数(占用语义: status=0). 作废票不算已售. */
    Long countSoldBySchedule(Long scheduleId);

    List<Ticket> listByOrder(Long orderId);

    /** 排片下所有票的座位状态（前端选座渲染） */
    List<SeatStatusVO> listSeatStatusBySchedule(Long scheduleId);
}
