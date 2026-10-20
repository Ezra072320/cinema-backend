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
    MockMvc mvc;
    @BeforeEach void setup() {
        ContextHolder.clear();
        mvc = MockMvcBuilders.standaloneSetup(new StatisticController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void clean() { ContextHolder.clear(); }
    @Test void anonymousAndUsersCannotReadEitherStatisticsEndpoint() throws Exception {
        for (String path : new String[]{"/api/statistic/getAll","/api/statistic/revenue"}) {
            ContextHolder.clear();
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            ContextHolder.set(new ContextHolder.Principal(1L,"user","USER"));
            mvc.perform(get(path)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
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
}
