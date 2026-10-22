package edu.czu.cinema.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.czu.cinema.ai.service.AiBookingService;
import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import edu.czu.cinema.domain.schedule.vo.ScheduleSeatsVO;
import edu.czu.cinema.domain.ticket.entity.Ticket;
import edu.czu.cinema.remote.ScheduleClient;
import edu.czu.cinema.remote.SeatLabelClient;
import edu.czu.cinema.remote.TicketClient;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

class AiBookingServiceTest {
    final ScheduleClient schedules = mock(ScheduleClient.class);
    final TicketClient tickets = mock(TicketClient.class);
    final SeatLabelClient labels = mock(SeatLabelClient.class);
    final AiBookingService service = new AiBookingService(schedules, tickets, labels);

    void fixture() {
        var s = new ScheduleSeatsVO();
        s.setScheduleId(1L);
        s.setSeatVersionId(9L);
        s.setStatus(1);
        s.setStartTime(LocalDateTime.now().plusDays(1));
        s.setMovieName("测试片");
        s.setHallName("影院");
        var a = new ScheduleSeatsVO.SeatItem();
        a.setSeatId(10L);
        a.setStatus(1);
        var b = new ScheduleSeatsVO.SeatItem();
        b.setSeatId(11L);
        b.setStatus(1);
        s.setSeats(List.of(a, b));
        var pair = new ScheduleSeatsVO.CouplePair();
        pair.setSeatId1(10L);
        pair.setSeatId2(11L);
        s.setCoupleGroups(List.of(pair));
        when(schedules.seatsOfSchedule(1L)).thenReturn(s);
        when(labels.labelsOf(9L)).thenReturn(Map.of(10L, "2-3", 11L, "2-3"));
    }

    Ticket ticket(long id, long seat, int status, String price) {
        var t = new Ticket();
        t.setId(id);
        t.setSeatId(seat);
        t.setScheduleId(1L);
        t.setStatus(status);
        t.setPrice(new BigDecimal(price));
        return t;
    }

    @Test
    void actualStoredPricesAndBothCoupleTicketsAreQuoted() {
        fixture();
        when(tickets.listByIds(List.of(100L, 101L)))
                .thenReturn(List.of(ticket(100, 10, 1, "88.88"), ticket(101, 11, 1, "88.88")));
        var q = service.quote(new OrderAddDTO(1L, List.of(100L, 101L)));
        assertThat(q.total()).isEqualByComparingTo("177.76");
        assertThat(q.seats()).hasSize(2);
        assertThat(q.summary()).contains("2-3", "177.76", "2张实体票");
        assertThat(
                        java.util.Arrays.stream(TicketClient.class.getMethods())
                                .map(java.lang.reflect.Method::getName))
                .doesNotContain("lockTickets");
    }

    @Test
    void exactLabelResolvesBothPhysicalCoupleTicketsWithoutPickingANearbySeat() {
        fixture();
        var schedule = schedules.seatsOfSchedule(1L);
        schedule.getSeats().get(0).setTicketId(100L);
        schedule.getSeats().get(1).setTicketId(101L);
        var resolved = service.resolveBySeat(1L, "2排3座");
        assertThat(resolved.ticketIds()).containsExactly(100L, 101L);
        assertThatThrownBy(() -> service.resolveBySeat(1L, "2排4座")).hasMessageContaining("没有座号");
        schedule.getSeats().get(1).setTicketId(null);
        assertThatThrownBy(() -> service.resolveBySeat(1L, "2排3座")).hasMessageContaining("尚未开票");
    }

    @Test
    void halfCoupleAndUnavailableTicketsAreRejected() {
        fixture();
        when(tickets.listByIds(List.of(100L))).thenReturn(List.of(ticket(100, 10, 1, "88")));
        assertThatThrownBy(() -> service.quote(new OrderAddDTO(1L, List.of(100L))))
                .hasMessageContaining("整对");
        when(tickets.listByIds(List.of(100L))).thenReturn(List.of(ticket(100, 10, 0, "88")));
        assertThatThrownBy(() -> service.quote(new OrderAddDTO(1L, List.of(100L))))
                .hasMessageContaining("不可售");
    }

    @Test
    void duplicatesTooManyAndForeignScheduleAreRejected() {
        assertThatThrownBy(() -> service.quote(new OrderAddDTO(1L, List.of(1L, 1L))))
                .hasMessageContaining("重复");
        assertThatThrownBy(
                        () ->
                                service.quote(
                                        new OrderAddDTO(1L, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L))))
                .isInstanceOf(RuntimeException.class);
        fixture();
        var t = ticket(100, 10, 1, "40");
        t.setScheduleId(2L);
        when(tickets.listByIds(List.of(100L))).thenReturn(List.of(t));
        assertThatThrownBy(() -> service.quote(new OrderAddDTO(1L, List.of(100L))))
                .hasMessageContaining("不可售");
    }
}
