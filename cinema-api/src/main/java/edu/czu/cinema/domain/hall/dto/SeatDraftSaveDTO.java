package edu.czu.cinema.domain.hall.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 「保存草稿」请求体. 来自前端座位图编辑器的 PUT.
 *
 * <p>客户端必须回传 {@code expectedLockVersion} (上次拉到的 draft_lock_version),
 * 服务端校验不匹配则 409, 防止两人同时编辑互相覆盖.
 */
@Data
public class SeatDraftSaveDTO {

    @NotNull
    private Integer expectedLockVersion;

    @NotNull
    private List<SeatDraftSeatDTO> seats;

    /**
     * 情侣对. 整表替换 —— 不传 or 传空数组 = 该版本没有情侣对.
     *
     * <p>跟座位放在同一次保存里, 是为了让「座位 + 配对」在同一个事务和同一个
     * 乐观锁版本下落地, 不会出现座位改了但配对还是旧的这种中间态.
     */
    private List<CoupleGroupDTO> coupleGroups;
}
