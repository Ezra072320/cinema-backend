package edu.czu.cinema.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "cinema-identity", contextId = "AccountClient", url = "${CINEMA_IDENTITY_URL:}")
public interface AccountClient {

    @GetMapping("/internal/accounts/active")
    boolean isActive(
            @RequestParam("role") String role,
            @RequestParam("id") Long id,
            @RequestParam("pv") String pv);

    @GetMapping("/internal/accounts/{id}")
    edu.czu.cinema.domain.user.entity.User getById(@PathVariable("id") Long id);

    @GetMapping("/internal/accounts/count")
    long count();

    @PutMapping("/internal/accounts/{id}")
    void updateCurrent(
            @PathVariable("id") Long id,
            @RequestBody edu.czu.cinema.domain.user.dto.UserUpdateDTO dto);

    @GetMapping("/internal/operations/{key}")
    String operationResult(@PathVariable("key") String key, @RequestParam("userId") Long uid);
}
