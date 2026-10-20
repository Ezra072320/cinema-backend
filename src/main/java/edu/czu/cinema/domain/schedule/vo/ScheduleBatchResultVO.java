package edu.czu.cinema.domain.schedule.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量排片的结果: 成功建了几场 + 被跳过的时段明细.
 *
 * <p>跳过不是失败 —— 冲突的/时间不合法的行被挑出来告诉管理员原因,
 * 其余行已正常入库(整场批次在同一事务里, 但只有"跳过"没有"回滚").
 */
@Data
public class ScheduleBatchResultVO {

    private List<Created> created = new ArrayList<>();
    private List<Skipped> skipped = new ArrayList<>();

    @Data
    public static class Created {
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
        private LocalDateTime startTime;
        private Long scheduleId;

        public Created(LocalDateTime startTime, Long scheduleId) {
            this.startTime = startTime;
            this.scheduleId = scheduleId;
        }
    }

    @Data
    public static class Skipped {
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
        private LocalDateTime startTime;
        private String reason;

        public Skipped(LocalDateTime startTime, String reason) {
            this.startTime = startTime;
            this.reason = reason;
        }
    }
}
