package edu.czu.cinema.domain.statistic;

import static org.assertj.core.api.Assertions.assertThat;
import edu.czu.cinema.domain.statistic.dto.RevenueReport;
import edu.czu.cinema.domain.statistic.export.RevenueExportMapper;
import edu.czu.cinema.domain.statistic.export.RevenueWorkbook;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class RevenueWorkbookTest {
    private final RevenueWorkbook workbook = new RevenueWorkbook(Mappers.getMapper(RevenueExportMapper.class));

    @Test void roundTripPreservesMoneyRefundsDatesAndUntrustedNamesAsText() throws Exception {
        var m = metrics("123.45", "23.45");
        var day = LocalDate.of(2026,10,1);
        var row = new RevenueReport.PeriodRow("2026-10-01",day,day,m);
        var rank = new RevenueReport.RankRow(1L,"=HYPERLINK(\"https://example.invalid\")",2,
                m.grossRevenue(),m.refundAmount(),m.netRevenue(),new BigDecimal("100.00"));
        var report = report(m,List.of(row),List.of(rank));
        try (var file = WorkbookFactory.create(new ByteArrayInputStream(workbook.render(report)))) {
            assertThat(file.getNumberOfSheets()).isEqualTo(6);
            var daily = file.getSheet("每日明细");
            assertThat(daily.getRow(1).getCell(1).getStringCellValue()).isEqualTo("2026-10-01");
            assertThat(daily.getRow(1).getCell(3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(daily.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(123.45);
            assertThat(daily.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(23.45);
            assertThat(daily.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(100.00);
            assertThat(daily.getRow(1).getCell(3).getCellStyle().getDataFormatString()).isEqualTo("0.00");
            assertThat(daily.getRow(1).getCell(11).getNumericCellValue()).isEqualTo(1);
            var name = file.getSheet("影片排行").getRow(1).getCell(0);
            assertThat(name.getCellType()).isEqualTo(CellType.STRING);
            assertThat(name.getStringCellValue()).isEqualTo(rank.name());
            assertThat(file.getSheet("说明").getRow(3).getCell(1).getStringCellValue()).contains("退款回计原单日期");
        }
    }

    @Test void emptyReportStillHasNumericSummaryAndAllSheetHeaders() throws Exception {
        try (var file = WorkbookFactory.create(new ByteArrayInputStream(workbook.render(report(metrics("0.00","0.00"),List.of(),List.of()))))) {
            assertThat(file.getSheet("每日明细").getPhysicalNumberOfRows()).isEqualTo(1);
            assertThat(file.getSheet("影片排行").getRow(0).getCell(0).getStringCellValue()).contains("前10");
            assertThat(file.getSheet("汇总对比").getRow(1).getCell(5).getNumericCellValue()).isZero();
        }
    }

    private RevenueReport.Metrics metrics(String gross, String refund) {
        var g = new BigDecimal(gross); var r = new BigDecimal(refund);
        return new RevenueReport.Metrics(g,r,g.subtract(r),4,2,1,0,1,1,new BigDecimal("9.99"),g.divide(new BigDecimal("2")),new BigDecimal("50.00"));
    }
    private RevenueReport report(RevenueReport.Metrics m, List<RevenueReport.PeriodRow> daily, List<RevenueReport.RankRow> ranks) {
        var day = LocalDate.of(2026,10,1);
        return new RevenueReport(day,day,"day",LocalDateTime.of(2026,10,2,9,0),m,m,day.minusDays(1),day.minusDays(1),daily,daily,ranks,ranks,List.of());
    }
}
