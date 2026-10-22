package edu.czu.cinema.domain.statistic.dto;

import java.math.BigDecimal;
import java.util.List;

public record StatisticVO(long totalUsers, long totalOrders, BigDecimal totalRevenue,
                          long totalSchedules, long totalMovies, BigDecimal todayRevenue,
                          long todayOrders, List<Trend> revenueTrend, List<Movie> topMovies,
                          List<RevenueReport.Distribution> typeDistribution) {
    public record Trend(String date, BigDecimal revenue, long orders) {}
    public record Movie(Long movieId, String title, BigDecimal boxOffice) {}
}
