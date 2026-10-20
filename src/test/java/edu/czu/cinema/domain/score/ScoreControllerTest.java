package edu.czu.cinema.domain.score;

import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.exception.GlobalExceptionHandler;
import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.domain.score.controller.ScoreController;
import edu.czu.cinema.domain.score.service.ScoreService;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;

class ScoreControllerTest {
    ScoreService scores = mock(ScoreService.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        ContextHolder.set(new ContextHolder.Principal(7L,"test","USER"));
        mvc = MockMvcBuilders.standaloneSetup(new ScoreController(scores)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void clean() { ContextHolder.clear(); }
    @Test void missingZeroAndOutOfRangeStarsAreRejectedByHttpValidation() throws Exception {
        for (String value : new String[]{"null","0","6"}) {
            mvc.perform(post("/api/user/score").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"orderId\":1,\"score\":"+value+",\"content\":\"\",\"expectedVersion\":0}"))
                    .andExpect(jsonPath("$.code",not(1)));
        }
        verifyNoInteractions(scores);
    }
    @Test void privateReadCannotBypassOwnershipAndReturns403() throws Exception {
        when(scores.ownedReview(7L,2L)).thenThrow(new BusinessException(ResultCode.FORBIDDEN,"订单不属于当前用户"));
        mvc.perform(get("/api/user/score/info/2")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(2002));
    }
    @Test void unreviewedOrderReturnsNullWithoutErrorToast() throws Exception {
        mvc.perform(get("/api/user/score/info/1")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        verify(scores).ownedReview(7L,1L);
    }
    @Test void staleDeleteConflictIsSurfaced() throws Exception {
        doThrow(new BusinessException(ResultCode.CONFLICT,"评价已变化")).when(scores).remove(7L,1L,1L);
        mvc.perform(delete("/api/user/score/1").param("version","1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.msg").value("评价已变化"));
    }
}
