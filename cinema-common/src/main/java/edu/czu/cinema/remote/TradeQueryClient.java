package edu.czu.cinema.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "cinema-trade", contextId = "TradeQueryClient", url = "${CINEMA_TRADE_URL:}")
public interface TradeQueryClient {

    @GetMapping("/internal/trade/query/schedules")
    java.util.Map<String, Object> schedules(
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "movieId", required = false) Long movieId,
            @RequestParam(value = "hallId", required = false) Long hallId,
            @RequestParam(value = "after", required = false) String after,
            @RequestParam(value = "before", required = false) String before,
            @RequestParam(value = "maxPrice", required = false) java.math.BigDecimal maxPrice,
            @RequestParam(value = "page", required = false) Integer page);

    @GetMapping("/internal/trade/query/orders")
    java.util.Map<String, Object> orders(
            @RequestParam("userId") Long uid,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", required = false) Integer page);
}
