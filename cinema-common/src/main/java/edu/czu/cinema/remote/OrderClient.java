package edu.czu.cinema.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "cinema-trade", contextId = "OrderClient", url = "${CINEMA_TRADE_URL:}")
public interface OrderClient {

    @PostMapping("/internal/trade/orders")
    Long createOrder(
            @RequestParam("userId") Long uid,
            @RequestBody edu.czu.cinema.domain.order.dto.OrderAddDTO dto);

    @GetMapping("/internal/trade/orders/{id}")
    edu.czu.cinema.domain.order.vo.OrderVO detail(
            @PathVariable("id") Long id, @RequestParam("userId") Long uid);

    @PostMapping("/internal/trade/orders/{id}/cancel")
    String cancel(@PathVariable("id") Long id, @RequestParam("userId") Long uid);

    @PostMapping("/internal/trade/orders/{id}/refund")
    String refund(
            @PathVariable("id") Long id,
            @RequestParam("userId") Long uid,
            @RequestParam("amount") java.math.BigDecimal amount);

    @GetMapping("/internal/operations/{key}")
    String operationResult(@PathVariable("key") String key, @RequestParam("userId") Long uid);
}
