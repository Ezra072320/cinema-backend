package edu.czu.cinema.ai.service;

import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import edu.czu.cinema.domain.ticket.entity.Ticket;
import edu.czu.cinema.remote.ScheduleClient;
import edu.czu.cinema.remote.SeatLabelClient;
import edu.czu.cinema.remote.TicketClient;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiBookingService {
    private final ScheduleClient scheduleService;
    private final TicketClient ticketService;
    private final SeatLabelClient labelService;

    public record BookingQuote(
            String movie,
            String hall,
            LocalDateTime startTime,
            List<String> seats,
            BigDecimal total,
            String path) {
        public String summary() {
            return "《"
                    + movie
                    + "》 · "
                    + hall
                    + " · "
                    + startTime.toString().replace('T', ' ')
                    + "\n"
                    + String.join("、", seats)
                    + "\n共"
                    + seats.size()
                    + "张实体票，合计¥"
                    + total.toPlainString()
                    + "。确认后锁座，下单后15分钟内到支付页完成模拟支付。";
        }
    }

    public BookingQuote quote(OrderAddDTO dto) {
        if (dto.scheduleId() == null
                || dto.ticketIds() == null
                || dto.ticketIds().isEmpty()
                || dto.ticketIds().size() > 6
                || new HashSet<>(dto.ticketIds()).size() != dto.ticketIds().size())
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择同场次1至6张不重复的票");
        var schedule = scheduleService.seatsOfSchedule(dto.scheduleId());
        if (schedule == null) throw new BusinessException(ResultCode.NOT_FOUND, "场次不存在");
        if (!Integer.valueOf(1).equals(schedule.getStatus())
                || schedule.getStartTime() == null
                || !schedule.getStartTime().isAfter(LocalDateTime.now()))
            throw new BusinessException(ResultCode.CONFLICT, "场次已取消或已开场，请重新选择");
        List<Ticket> tickets = ticketService.listByIds(dto.ticketIds());
        if (tickets.size() != dto.ticketIds().size()
                || tickets.stream()
                        .anyMatch(
                                t ->
                                        !Objects.equals(t.getScheduleId(), dto.scheduleId())
                                                || !Integer.valueOf(1).equals(t.getStatus())
                                                || t.getPrice() == null))
            throw new BusinessException(ResultCode.CONFLICT, "所选座位不可售，请重新查询余座");
        var seatIds = tickets.stream().map(Ticket::getSeatId).collect(Collectors.toSet());
        var validSeatIds =
                schedule.getSeats().stream()
                        .filter(s -> Integer.valueOf(1).equals(s.getStatus()))
                        .map(s -> s.getSeatId())
                        .collect(Collectors.toSet());
        if (!validSeatIds.containsAll(seatIds))
            throw new BusinessException(ResultCode.CONFLICT, "座位与场次座位图不匹配");
        for (var pair : schedule.getCoupleGroups()) {
            if (seatIds.contains(pair.getSeatId1()) != seatIds.contains(pair.getSeatId2()))
                throw new BusinessException(ResultCode.BAD_REQUEST, "情侣座须整对选择，两张票均计价");
        }
        Map<Long, String> labels = labelService.labelsOf(schedule.getSeatVersionId());
        List<String> seats =
                tickets.stream()
                        .sorted(Comparator.comparing(Ticket::getId))
                        .map(
                                t ->
                                        labels.getOrDefault(t.getSeatId(), "座位资料缺失")
                                                + "（¥"
                                                + t.getPrice().toPlainString()
                                                + "）")
                        .toList();
        return new BookingQuote(
                schedule.getMovieName(),
                schedule.getHallName(),
                schedule.getStartTime(),
                seats,
                tickets.stream().map(Ticket::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add),
                "/seats/" + dto.scheduleId());
    }

    /** Resolve the displayed seat label, then let quote() recheck current sale state and prices. */
    public OrderAddDTO resolveBySeat(Long scheduleId, String seatLabel) {
        String normalized = AiSeatLabels.normalize(seatLabel);
        if (scheduleId == null || normalized == null) throw new BusinessException("请明确场次ID和选座页座号");
        var schedule = scheduleService.seatsOfSchedule(scheduleId);
        if (schedule == null) throw new BusinessException(ResultCode.NOT_FOUND, "场次不存在");
        var labels = labelService.labelsOf(schedule.getSeatVersionId());
        if (labels == null || schedule.getSeats() == null) throw new BusinessException("座位资料暂不可用，请重新查询");
        var selected = schedule.getSeats().stream().filter(seat -> normalized.equals(labels.get(seat.getSeatId()))).toList();
        if (selected.isEmpty()) throw new BusinessException("本场次没有座号 " + normalized);
        Set<Long> seatIds = selected.stream().map(seat -> seat.getSeatId()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (schedule.getCoupleGroups() != null) for (var pair : schedule.getCoupleGroups()) {
            if (seatIds.contains(pair.getSeatId1()) || seatIds.contains(pair.getSeatId2())) {
                seatIds.add(pair.getSeatId1());
                seatIds.add(pair.getSeatId2());
            }
        }
        List<Long> ticketIds = schedule.getSeats().stream().filter(seat -> seatIds.contains(seat.getSeatId()))
                .map(seat -> seat.getTicketId()).filter(Objects::nonNull).distinct().sorted().toList();
        if (ticketIds.size() != seatIds.size()) throw new BusinessException("所选座位尚未开票或情侣座资料不完整");
        return new OrderAddDTO(scheduleId, ticketIds);
    }
}
