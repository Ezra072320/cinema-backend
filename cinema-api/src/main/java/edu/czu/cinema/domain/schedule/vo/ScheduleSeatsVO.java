package edu.czu.cinema.domain.schedule.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 选座页需要的全部数据: 场次信息 + 该场次锁定版本的座位图 + 每座的票状态.
 *
 * <p>设计要点:
 * <ul>
 *   <li>座位来自 {@code schedule.seat_version_id} 锁定的那一版, 不是"影厅当前版本".
 *       这样管理员改完座位图, 老场次的选座页画出来的还是它当初卖票时那张图.</li>
 *   <li>每个座位带上 {@code ticketId}. 前端下单传的是 <b>ticketIds</b>, 不是 seatIds ——
 *       之前前端拿 seat.id 当 ticket.id 传给创建订单接口, 是错的; 这里把票号直接给出来.</li>
 *   <li>过道/不可用座位({@code status=0})也返回, 方便前端按网格对齐渲染, 但它没有票,
 *       {@code ticketId} 为 null, 前端应画成空白不可点.</li>
 * </ul>
 */
@Data
@Schema(description = "选座页数据")
public class ScheduleSeatsVO {

    private Long scheduleId;
    private Long hallId;
    /** 本场次锁定的座位图版本. */
    private Long seatVersionId;

    /** 场次状态: 1 正常 / 2 已取消。已取消场次前端整页停止售票。 */
    private Integer status;

    private String hallName;
    private String movieName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime startTime;
    /** 基础票价. */
    private BigDecimal price;

    /**
     * 本场次的 VIP 座倍率.
     *
     * <p>下发它是为了让前端算出的总价跟后端建票时的价**完全一致** ——
     * 前端如果自己硬编码 3.0, 遇到调过价的场次就会显示一个价、下单扣另一个价.
     */
    private BigDecimal vipPriceMultiplier;

    /** 本场次的情侣座倍率. 同上, 前端不要自己写死. */
    private BigDecimal couplePriceMultiplier;

    /** 座位图行数/列数(按该版本座位坐标的最大值). 前端拿来画网格. */
    private Integer rows;
    private Integer cols;

    private List<SeatItem> seats;

    /**
     * 本场次的情侣对. 前端把每对里的两个座位渲染成一个视觉单元.
     *
     * <p>它同时也是一条**业务约束**: 下单时一对必须一起买, 不能拆
     * (后端在 {@code OrderServiceImpl.createOrder} 强制校验).
     */
    private List<CouplePair> coupleGroups;

    /**
     * 一对情侣座. 两个都是真实座位, 各有独立票.
     */
    @Data
    @Schema(description = "情侣对")
    public static class CouplePair {
        private Long id;
        private Long seatId1;
        private Long seatId2;
        private String label;
    }

    /**
     * 一个座位 + 它在本次场次下的票.
     */
    @Data
    @Schema(description = "座位项")
    public static class SeatItem {
        private Long seatId;
        /** 该座位的票 id. 过道/不可用座位没有票, 为 null. 下单传这个. */
        private Long ticketId;
        /** 票状态: 0 已锁/已售, 1 可售, 2 已退. 无票时为 null. */
        private Integer ticketStatus;

        /**
         * 本场次该座位的实际票价. 与 ticketId/ticketStatus 来自同一次座位图查询，
         * 调用方不应再单独查一次票，否则两个快照可能不一致（余座明明可售却被判为不可售）。
         */
        private java.math.BigDecimal price;

        private Integer y;
        private Integer x;
        /** 1 标准 / 2 情侣 / 3 VIP. */
        private Integer type;
        /** 座位自身: 0 不可用(过道) / 1 正常. */
        private Integer status;
    }
}
