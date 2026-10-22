package edu.czu.cinema.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "cinema-trade", contextId = "ScheduleClient", url = "${CINEMA_TRADE_URL:}")
public interface ScheduleClient {

    @GetMapping("/internal/trade/seats/{id}")
    edu.czu.cinema.domain.schedule.vo.ScheduleSeatsVO seatsOfSchedule(@PathVariable("id") Long id);
}
