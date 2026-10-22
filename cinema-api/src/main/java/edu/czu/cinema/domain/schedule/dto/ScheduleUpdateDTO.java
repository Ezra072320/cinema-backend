package edu.czu.cinema.domain.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 更新排片的请求体.
 *
 * <p><b>为什么不用 {@link edu.czu.cinema.domain.schedule.entity.Schedule} 实体收参</b>:
 * 实体上的 {@code startTime} 带 {@code @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")},
 * 而新增用的是 {@link ScheduleAddDTO}(record, 无该注解, Jackson 按 ISO-8601 解析)。
 * 前端两处发的都是 ISO 的 {@code 2026-11-02T10:00:00}, 于是「编辑排片」必然报
 * {@code could not be parsed at index 10}(index 10 就是那个 'T')。
 * 用 record 就自动跟新增走同一套解析规则。
 *
 * <p><b>刻意不收的字段</b>:
 * <ul>
 *   <li>{@code hallId} —— 改影厅会让场次、座位图版本、已建的票三者对不上:
 *       场次挂到了 B 厅, {@code seat_version_id} 还指着 A 厅的版本, 票也还是 A 厅的座位。
 *       要换厅就删掉重排。</li>
 *   <li>{@code vipPriceMultiplier / couplePriceMultiplier} —— 票在建票那一刻就把价算好落库了,
 *       改倍率不会回头改已售的票, 收它只会造成"改了没反应"的困惑。</li>
 *   <li>{@code price} —— 同理: 票价在建票时就按基础价×倍率算好落到每张票上, 改价对已建的票
 *       完全无效。要调价只能取消场次后按新价重排。</li>
 *   <li>{@code seatVersionId / status} —— 由系统维护, 不由客户端指定。</li>
 * </ul>
 */
public record ScheduleUpdateDTO(
        @Schema(description = "排片 ID") @NotNull Long id,
        @Schema(description = "电影 ID") @NotNull Long movieId,
        @Schema(description = "开场时间") @NotNull LocalDateTime startTime
) {}
