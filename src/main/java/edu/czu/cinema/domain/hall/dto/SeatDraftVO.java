package edu.czu.cinema.domain.hall.dto;

import edu.czu.cinema.domain.hall.entity.HallSeatVersion;
import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.hall.entity.SeatCoupleGroup;
import lombok.Data;

import java.util.List;

/**
 * 「拉/建草稿」返回.
 *
 * <p>包含草稿版本本身 + 当前所有座位行 + 情侣对. 前端用 seats 渲染网格,
 * 用 coupleGroups 把成对的格子合并显示, 用 lockVersion 在保存时回传.
 */
@Data
public class SeatDraftVO {

    private HallSeatVersion draft;

    private List<Seat> seats;

    /** 该草稿版本下的情侣对. */
    private List<SeatCoupleGroup> coupleGroups;

    /** 草稿当前 lockVersion, 等价于 {@code draft.draftLockVersion}. 单独拎出来给前端看. */
    private Integer lockVersion;
}
