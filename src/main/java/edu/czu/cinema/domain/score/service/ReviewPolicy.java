package edu.czu.cinema.domain.score.service;

import edu.czu.cinema.domain.schedule.entity.Schedule;
import java.time.LocalDateTime;

/** 统一评价资格；时间仅按服务端判定，未知时长时保守使用场次结束时间。 */
public final class ReviewPolicy {
    private ReviewPolicy() {}
    public record Eligibility(boolean allowed, String reason, LocalDateTime availableAt) {}
    public static Eligibility check(Integer orderStatus, Schedule schedule, Integer runtime, LocalDateTime now) {
        if (schedule == null) return new Eligibility(false, "场次资料不存在", null);
        LocalDateTime end = runtime != null && runtime > 0 && schedule.getStartTime() != null
                ? schedule.getStartTime().plusMinutes(runtime) : schedule.getEndTime();
        if (!Integer.valueOf(2).equals(orderStatus) && !Integer.valueOf(6).equals(orderStatus))
            return new Eligibility(false, "仅已支付或已完成且未退款的订单可评价", end);
        if (!Integer.valueOf(1).equals(schedule.getStatus()))
            return new Eligibility(false, "场次已取消，不能发表或修改评价", end);
        if (end == null) return new Eligibility(false, "放映结束时间尚未确认", null);
        if (now.isBefore(end)) return new Eligibility(false, "电影放映结束后可评价", end);
        return new Eligibility(true, "", end);
    }
}
