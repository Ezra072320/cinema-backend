package edu.czu.cinema.domain.score.vo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record MovieReviewPage(long total, List<ReviewView> rows, Summary summary) {
    public record Summary(long count, BigDecimal average, Map<Integer, Long> distribution) {}
}
