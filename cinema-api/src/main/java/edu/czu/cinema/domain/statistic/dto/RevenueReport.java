package edu.czu.cinema.domain.statistic.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 订单日期口径的经营报表；退款回计原订单，不代表支付日现金流。 */
public record RevenueReport(
        LocalDate startDate, LocalDate endDate, String granularity,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime generatedAt,
        Metrics summary, Metrics previous, LocalDate previousStartDate, LocalDate previousEndDate,
        List<PeriodRow> trend, List<PeriodRow> daily, List<RankRow> movies, List<RankRow> halls,
        List<Distribution> statusDistribution) {
    public record Metrics(BigDecimal grossRevenue, BigDecimal refundAmount, BigDecimal netRevenue,
            long totalOrders, long paidOrders, long refundOrders, long pendingOrders, long closedOrders,
            long unknownRefundOrders, BigDecimal unknownRefundOrderAmount,
            BigDecimal averageOrderValue, BigDecimal refundRate) {}
    public record PeriodRow(String label, LocalDate startDate, LocalDate endDate, Metrics metrics) {}
    public record RankRow(Long id, String name, long paidOrders, BigDecimal grossRevenue,
                          BigDecimal refundAmount, BigDecimal netRevenue, BigDecimal share) {}
    public record Distribution(String name, long value) {}
}
