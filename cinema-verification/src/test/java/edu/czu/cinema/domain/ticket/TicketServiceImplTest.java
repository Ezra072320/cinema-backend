package edu.czu.cinema.domain.ticket;

import edu.czu.cinema.domain.hall.entity.Seat;
import edu.czu.cinema.domain.schedule.entity.Schedule;
import edu.czu.cinema.domain.ticket.entity.Ticket;
import edu.czu.cinema.domain.ticket.mapper.TicketMapper;
import edu.czu.cinema.domain.ticket.service.impl.TicketServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 票价精度: P1-T4 —— 倍率乘积必须 setScale(2, HALF_UP) 再落库, 不让 DECIMAL(8,2)
 * 溢出或循环小数被静默截断.
 */
class TicketServiceImplTest {

    private TicketMapper ticketMapper;
    private TicketServiceImpl service;

    @BeforeEach
    void setUp() {
        ticketMapper = mock(TicketMapper.class);
        service = new TicketServiceImpl(ticketMapper);
    }

    @Test
    void createTicketsForScheduleRoundsPriceHalfUp() {
        Schedule s = new Schedule();
        s.setId(1L);
        s.setPrice(new BigDecimal("33.33"));
        s.setCouplePriceMultiplier(new BigDecimal("2.50"));
        s.setVipPriceMultiplier(new BigDecimal("3.00"));
        Seat couple = new Seat();
        couple.setId(7L);
        couple.setType(2); // 情侣座 → 2.50 倍

        service.createTicketsForSchedule(s, List.of(couple));

        ArgumentCaptor<Ticket> t = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).insert(t.capture());
        // 33.33 × 2.50 = 83.325 → HALF_UP → 83.33
        assertThat(t.getValue().getPrice()).isEqualByComparingTo("83.33");
    }
}