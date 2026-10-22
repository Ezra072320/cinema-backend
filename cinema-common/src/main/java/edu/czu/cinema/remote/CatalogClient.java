package edu.czu.cinema.remote;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "cinema-catalog", contextId = "CatalogClient", url = "${CINEMA_CATALOG_URL:}")
public interface CatalogClient {

    @GetMapping("/internal/catalog/base/{id}")
    edu.czu.cinema.domain.movie.entity.Movie getMovie(@PathVariable("id") Long id);

    @GetMapping("/internal/catalog/info/{id}")
    edu.czu.cinema.domain.movie.vo.MovieInfoVO info(@PathVariable("id") Long id);

    @GetMapping("/internal/catalog/sales")
    java.util.List<edu.czu.cinema.domain.movie.vo.MovieSoldVO> topSales();

    @GetMapping("/internal/catalog/box-office")
    java.util.List<edu.czu.cinema.domain.movie.vo.MovieSoldVO> topBoxOffice();

    @GetMapping("/internal/catalog/want")
    boolean isWant(@RequestParam("movieId") Long movieId, @RequestParam("userId") Long userId);

    @PostMapping("/internal/catalog/want")
    boolean handleWant(@RequestParam("userId") Long userId, @RequestParam("movieId") Long movieId);

    @GetMapping("/internal/catalog/wants")
    java.util.List<Long> userWantList(@RequestParam("userId") Long userId);

    @GetMapping("/internal/catalog/count")
    long count();

    @GetMapping("/internal/catalog/types")
    java.util.List<edu.czu.cinema.domain.statistic.dto.RevenueReport.Distribution>
            typeDistribution();

    @GetMapping("/internal/catalog/search")
    java.util.Map<String, Object> movies(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "showing", required = false) Boolean showing,
            @RequestParam(value = "page", required = false) Integer page);

    @GetMapping("/internal/operations/{key}")
    String operationResult(@PathVariable("key") String key, @RequestParam("userId") Long uid);

    @PostMapping("/internal/catalog/want/target")
    boolean setWant(
            @RequestParam("userId") Long uid,
            @RequestParam("movieId") Long movieId,
            @RequestParam("wanted") boolean wanted);
}
