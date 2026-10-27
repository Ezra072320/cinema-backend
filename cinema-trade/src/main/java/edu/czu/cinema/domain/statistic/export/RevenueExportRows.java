package edu.czu.cinema.domain.statistic.export;

import java.math.BigDecimal;
import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.annotation.format.NumberFormat;
import org.apache.fesod.sheet.annotation.write.style.ColumnWidth;

/** Flat export models belong to Trade, never to shared API contracts. */
public final class RevenueExportRows {
    private RevenueExportRows() {}

    @Data @ColumnWidth(22)
    public static class Period {
        @ExcelProperty(value="期间", index=0) private String label;
        @ExcelProperty(value="开始日期", index=1) private String startDate;
        @ExcelProperty(value="结束日期", index=2) private String endDate;
        @ExcelProperty(value="收款金额(元)", index=3) @NumberFormat("0.00") private BigDecimal grossRevenue;
        @ExcelProperty(value="实退金额(元)", index=4) @NumberFormat("0.00") private BigDecimal refundAmount;
        @ExcelProperty(value="净营收(元)", index=5) @NumberFormat("0.00") private BigDecimal netRevenue;
        @ExcelProperty(value="下单数", index=6) private long totalOrders;
        @ExcelProperty(value="付款订单数(含退款)", index=7) private long paidOrders;
        @ExcelProperty(value="退款订单数", index=8) private long refundOrders;
        @ExcelProperty(value="待支付单数", index=9) private long pendingOrders;
        @ExcelProperty(value="已关闭单数", index=10) private long closedOrders;
        @ExcelProperty(value="旧退款待核实单数", index=11) private long unknownRefundOrders;
        @ExcelProperty(value="旧退款原单金额(元)", index=12) @NumberFormat("0.00") private BigDecimal unknownRefundOrderAmount;
        @ExcelProperty(value="平均每单收款(元)", index=13) @NumberFormat("0.00") private BigDecimal averageOrderValue;
        @ExcelProperty(value="退款订单比例(%)", index=14) @NumberFormat("0.00") private BigDecimal refundRate;
    }

    @Data @ColumnWidth(24)
    public static class Rank {
        @ExcelProperty(value="名称(净营收前10)", index=0) private String name;
        @ExcelProperty(value="付款订单数(含退款)", index=1) private long paidOrders;
        @ExcelProperty(value="收款金额(元)", index=2) @NumberFormat("0.00") private BigDecimal grossRevenue;
        @ExcelProperty(value="实退金额(元)", index=3) @NumberFormat("0.00") private BigDecimal refundAmount;
        @ExcelProperty(value="净营收(元)", index=4) @NumberFormat("0.00") private BigDecimal netRevenue;
        @ExcelProperty(value="占区间净营收(%)", index=5) @NumberFormat("0.00") private BigDecimal share;
    }
}
