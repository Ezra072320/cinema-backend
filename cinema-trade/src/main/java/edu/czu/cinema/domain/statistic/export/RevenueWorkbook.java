package edu.czu.cinema.domain.statistic.export;

import edu.czu.cinema.domain.statistic.dto.RevenueReport;
import java.io.ByteArrayOutputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fesod.sheet.FesodSheet;
import org.springframework.stereotype.Component;

/** Bounded report (at most 366 days and top-ten ranks), fully rendered before HTTP headers. */
@Component @RequiredArgsConstructor
public class RevenueWorkbook {
    private final RevenueExportMapper mapper;

    public byte[] render(RevenueReport report) {
        var bytes = new ByteArrayOutputStream();
        try (var writer = FesodSheet.write(bytes).autoCloseStream(false).build()) {
            List<List<Object>> notes = List.of(
                    List.of("统计区间", report.startDate()+" 至 "+report.endDate()),
                    List.of("生成时间(北京时间)", report.generatedAt().toString()),
                    List.of("统计口径", "按原订单日期统计，退款回计原单日期，不代表支付日现金流"),
                    List.of("旧退款", "缺失实退金额的旧退款单列待核实，不推算实际收款和退款"),
                    List.of("排行范围", "影片及影厅仅展示区间净营收前10"),
                    List.of("趋势粒度", report.granularity()));
            writer.write(notes, FesodSheet.writerSheet(0,"说明").head(List.of(List.of("项目"),List.of("内容"))).build());
            writer.write(List.of(
                    mapper.period(new RevenueReport.PeriodRow("本期",report.startDate(),report.endDate(),report.summary())),
                    mapper.period(new RevenueReport.PeriodRow("上期",report.previousStartDate(),report.previousEndDate(),report.previous()))),
                    FesodSheet.writerSheet(1,"汇总对比").head(RevenueExportRows.Period.class).build());
            writer.write(report.daily().stream().map(mapper::period).toList(),
                    FesodSheet.writerSheet(2,"每日明细").head(RevenueExportRows.Period.class).build());
            writer.write(report.trend().stream().map(mapper::period).toList(),
                    FesodSheet.writerSheet(3,"趋势汇总").head(RevenueExportRows.Period.class).build());
            writer.write(report.movies().stream().map(mapper::rank).toList(),
                    FesodSheet.writerSheet(4,"影片排行").head(RevenueExportRows.Rank.class).build());
            writer.write(report.halls().stream().map(mapper::rank).toList(),
                    FesodSheet.writerSheet(5,"影厅排行").head(RevenueExportRows.Rank.class).build());
        }
        return bytes.toByteArray();
    }
}
