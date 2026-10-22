package edu.czu.cinema.gateway;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class CapacityFallback {
    @RequestMapping("/capacity/fallback")
    public ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(503)
                .header("Retry-After", "2")
                .body(Map.of("code", 3001, "msg", "服务繁忙，请稍后重试；订单结果可在我的订单中查询"));
    }
}
