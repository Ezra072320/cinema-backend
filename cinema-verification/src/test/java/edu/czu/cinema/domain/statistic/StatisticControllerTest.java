package edu.czu.cinema.domain.statistic;

import edu.czu.cinema.common.exception.GlobalExceptionHandler;
import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.domain.statistic.controller.StatisticController;
import edu.czu.cinema.domain.statistic.service.RevenueService;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDate;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StatisticControllerTest {
    RevenueService service = mock(RevenueService.class);
    edu.czu.cinema.domain.statistic.export.RevenueWorkbook workbook = mock(edu.czu.cinema.domain.statistic.export.RevenueWorkbook.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        ContextHolder.clear();
        mvc = MockMvcBuilders.standaloneSetup(new StatisticController(service, workbook))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void clean() { ContextHolder.clear(); }
    @Test void anonymousAndUsersCannotReadEitherStatisticsEndpoint() throws Exception {
        for (String path : new String[]{"/api/statistic/getAll","/api/statistic/revenue","/api/statistic/revenue/export"}) {
            ContextHolder.clear();
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            ContextHolder.set(new ContextHolder.Principal(1L,"user","USER"));
            mvc.perform(get(path)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service,workbook);
    }
    @Test void adminDatesAndGranularityAreBoundToOrdinaryBusinessEndpoint() throws Exception {
        ContextHolder.set(new ContextHolder.Principal(1L,"admin","ADMIN"));
        mvc.perform(get("/api/statistic/revenue").param("startDate","2024-03-01").param("endDate","2024-03-04").param("granularity","week"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        verify(service).report(LocalDate.of(2024,3,1),LocalDate.of(2024,3,4),"week");
    }
    @Test void malformedDatesAreRejectedBeforeAggregation() throws Exception {
        ContextHolder.set(new ContextHolder.Principal(1L,"admin","ADMIN"));
        mvc.perform(get("/api/statistic/revenue").param("startDate","not-a-date")).andExpect(jsonPath("$.code").value(0));
        verifyNoInteractions(service);
    }
    @Test void workbookResponseUsesValidatedReportRangeAndIsNotCached() throws Exception {
        ContextHolder.set(new ContextHolder.Principal(1L,"admin","ADMIN"));
        var report = mock(edu.czu.cinema.domain.statistic.dto.RevenueReport.class);
        when(report.startDate()).thenReturn(LocalDate.of(2026,10,1));
        when(report.endDate()).thenReturn(LocalDate.of(2026,10,2));
        when(service.report(null,null,"day")).thenReturn(report);
        when(workbook.render(report)).thenReturn(new byte[]{80,75,3,4});
        mvc.perform(get("/api/statistic/revenue/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"revenue_2026-10-01_2026-10-02.xlsx\""))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(new byte[]{80,75,3,4}));
    }
    @Test void invalidRangeReturnsBusinessJsonWithoutRenderingWorkbook() throws Exception {
        ContextHolder.set(new ContextHolder.Principal(1L,"admin","ADMIN"));
        when(service.report(any(),any(),any())).thenThrow(new edu.czu.cinema.common.exception.BusinessException(
                edu.czu.cinema.common.api.ResultCode.BAD_REQUEST,"日期范围无效"));
        mvc.perform(get("/api/statistic/revenue/export"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.msg").value("日期范围无效"));
        verifyNoInteractions(workbook);
    }
}
