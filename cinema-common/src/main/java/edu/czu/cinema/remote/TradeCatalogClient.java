package edu.czu.cinema.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "cinema-trade", contextId = "TradeCatalogClient", url = "${CINEMA_TRADE_URL:}")
public interface TradeCatalogClient {

    @GetMapping("/internal/trade/showing")
    java.util.List<Long> activeMovieIds(
            @RequestParam(value = "bookable", defaultValue = "false") boolean bookable);

    @PostMapping("/internal/trade/summaries")
    java.util.List<edu.czu.cinema.domain.movie.vo.MovieShowSummary> summaries(
            @RequestBody java.util.List<Long> ids);

    @GetMapping("/internal/trade/sales")
    java.util.List<edu.czu.cinema.domain.movie.vo.MovieSoldVO> sales();
}
