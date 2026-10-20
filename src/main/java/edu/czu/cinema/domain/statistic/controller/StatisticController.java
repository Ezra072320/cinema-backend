package edu.czu.cinema.domain.statistic.controller;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.statistic.service.RevenueService;
import edu.czu.cinema.domain.statistic.dto.RevenueReport;
import edu.czu.cinema.domain.statistic.dto.StatisticVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@Tag(name = "10. 统计")
@RestController
@RequestMapping("/api/statistic")
@RequiredArgsConstructor
public class StatisticController {

    private final RevenueService revenue;

    @Operation(summary = "管理后台首页统计数据")
    @GetMapping("/getAll")
    public R<StatisticVO> getAll() {
        SecurityUtils.requireAdminId();
        return R.ok(revenue.overview());
    }

    @Operation(summary = "按下单日期汇总营收，退款回计原单日期")
    @GetMapping("/revenue")
    public R<RevenueReport> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "day") String granularity) {
        SecurityUtils.requireAdminId();
        return R.ok(revenue.report(startDate,endDate,granularity));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> businessError(BusinessException e) {
        int status = switch (e.getCode()) { case 2001 -> 401; case 2002 -> 403; default -> 400; };
        return ResponseEntity.status(status).body(R.fail(e.getCode(),e.getMessage()));
    }
}
