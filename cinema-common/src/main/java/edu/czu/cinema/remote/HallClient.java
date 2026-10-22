package edu.czu.cinema.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "cinema-trade", contextId = "HallClient", url = "${CINEMA_TRADE_URL:}")
public interface HallClient {

    @GetMapping("/internal/trade/halls")
    java.util.List<edu.czu.cinema.domain.hall.entity.Hall> listAll();
}
