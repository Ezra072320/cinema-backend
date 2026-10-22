package edu.czu.cinema.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "cinema-trade", contextId = "SeatLabelClient", url = "${CINEMA_TRADE_URL:}")
public interface SeatLabelClient {

    @GetMapping("/internal/trade/labels/{id}")
    java.util.Map<Long, String> labelsOf(@PathVariable("id") Long id);
}
