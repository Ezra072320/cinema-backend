package edu.czu.cinema.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "cinema-trade", contextId = "TicketClient", url = "${CINEMA_TRADE_URL:}")
public interface TicketClient {

    @PostMapping("/internal/trade/tickets")
    java.util.List<edu.czu.cinema.domain.ticket.entity.Ticket> listByIds(
            @RequestBody java.util.List<Long> ids);

    @GetMapping("/internal/trade/tickets/{id}")
    java.util.List<edu.czu.cinema.domain.ticket.entity.Ticket> listBySchedule(
            @PathVariable("id") Long id);
}
