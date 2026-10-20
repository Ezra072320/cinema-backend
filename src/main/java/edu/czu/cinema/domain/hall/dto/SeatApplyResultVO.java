package edu.czu.cinema.domain.hall.dto;

import lombok.Data;

import java.util.List;

/**
 * 「应用草稿」(发布) 接口的返回. 出错时(被未来场次阻止)附带未下架场次列表,
 * 管理员可以照着 id 去 ScheduleManage 下架.
 */
@Data
public class SeatApplyResultVO {

    private boolean ok;
    private Long newVersionId;
    private Integer newVersionNumber;
    /** 未下架的场次 id 列表, 配合错误信息呈现. */
    private List<Long> blockingScheduleIds;
    private String message;
}
