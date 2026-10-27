package edu.czu.cinema.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "cinema-trade", contextId = "OrderClient", url = "${CINEMA_TRADE_URL:}")
public interface OrderClient {

    default Long createOrder(Long uid, edu.czu.cinema.domain.order.dto.OrderAddDTO dto) {
        return createOrderCommand(uid,dto,commandOptions());
    }

    @PostMapping("/internal/trade/orders")
    Long createOrderCommand(
            @RequestParam("userId") Long uid,
            @RequestBody edu.czu.cinema.domain.order.dto.OrderAddDTO dto,
            feign.Request.Options options);

    @GetMapping("/internal/trade/orders/{id}")
    edu.czu.cinema.domain.order.vo.OrderVO detail(
            @PathVariable("id") Long id, @RequestParam("userId") Long uid);

    default String cancel(Long id, Long uid) { return cancelCommand(id,uid,commandOptions()); }

    @PostMapping("/internal/trade/orders/{id}/cancel")
    String cancelCommand(@PathVariable("id") Long id, @RequestParam("userId") Long uid, feign.Request.Options options);

    default String refund(Long id, Long uid, java.math.BigDecimal amount) {
        return refundCommand(id,uid,amount,commandOptions());
    }

    @PostMapping("/internal/trade/orders/{id}/refund")
    String refundCommand(
            @PathVariable("id") Long id,
            @RequestParam("userId") Long uid,
            @RequestParam("amount") java.math.BigDecimal amount,
            feign.Request.Options options);

    @GetMapping("/internal/operations/{key}")
    String operationResult(@PathVariable("key") String key, @RequestParam("userId") Long uid);

    private static feign.Request.Options commandOptions() {
        // The write may include a 15s Seata transaction. Query methods keep their short defaults.
        return new feign.Request.Options(java.time.Duration.ofSeconds(1),java.time.Duration.ofSeconds(18),true);
    }
}
