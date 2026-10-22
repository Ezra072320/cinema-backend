package edu.czu.cinema.ai.service;

import edu.czu.cinema.remote.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiCatalogService {
    private final CatalogClient catalog;
    private final TradeQueryClient trade;

    public Map<String, Object> movies(String keyword, Boolean showing, Integer page) {
        return catalog.movies(keyword, showing, page);
    }

    public Map<String, Object> schedules(
            String date,
            Long movie,
            Long hall,
            String after,
            String before,
            BigDecimal max,
            Integer page) {
        return trade.schedules(date, movie, hall, after, before, max, page);
    }

    public Map<String, Object> orders(Long uid, Integer status, String keyword, Integer page) {
        return trade.orders(uid, status, keyword, page);
    }
}
