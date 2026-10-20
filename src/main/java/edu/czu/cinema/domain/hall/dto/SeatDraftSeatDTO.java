package edu.czu.cinema.domain.hall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 草稿里的一个座位. 来自前端座位图编辑器.
 *
 * <p>必传: y, x, status. id 可空(新增的座位还没拿到 id). unitType 默认 1.
 */
@Data
public class SeatDraftSeatDTO {

    /** 已有座位传 id(草稿保存路径优先保留 id, 不让 ticket 失效); 新增留空. */
    private Long id;

    @NotNull @Min(1) @Max(50)
    private Integer y;

    @NotNull @Min(1) @Max(50)
    private Integer x;

    /**
     * 座位自身状态: 0 不可用(过道/留空) / 1 正常.
     *
     * <p>注意别塞座位类型进来 —— 排片建票时按 {@code status == 1} 筛可售座位,
     * 传个 3 进来会被当成「不可用」而漏建票.
     */
    @NotNull
    @Min(0) @Max(1)
    private Integer status;

    /**
     * 座位类型: 1 标准 / 2 情侣 / 3 VIP / 4 无障碍. 不传默认 1.
     *
     * <p>定价倍率按这个走(2.5x / 3x), 详见 {@code TicketServiceImpl.createTicketsForSchedule}.
     */
    @Min(1) @Max(4)
    private Integer unitType;
}
