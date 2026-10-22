package edu.czu.cinema.ai;

import edu.czu.cinema.ai.controller.AiController;
import edu.czu.cinema.ai.service.AiActionService;
import edu.czu.cinema.ai.service.AiChatService;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.ContextHolder;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiControllerTest {
    AiChatService chats = mock(AiChatService.class);
    AiActionService actions = mock(AiActionService.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        ContextHolder.set(new ContextHolder.Principal(7L,"test","USER"));
        mvc = MockMvcBuilders.standaloneSetup(new AiController(chats,actions)).build();
    }
    @AfterEach void clean() { ContextHolder.clear(); }

    @Test void streamOwnershipFailureIsJson403BeforeSseStarts() throws Exception {
        when(chats.streamChat(7L,"foreign","hi")).thenThrow(new BusinessException(ResultCode.FORBIDDEN,"无权访问"));
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"chatId\":\"foreign\",\"question\":\"hi\"}"))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(2002));
    }
    @Test void invalidStreamPayloadIsJson400InsteadOfBrokenSse() throws Exception {
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"chatId\":\"test\",\"question\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        verifyNoInteractions(chats);
    }
    @Test void confirmUsesCurrentIdentity() throws Exception {
        mvc.perform(post("/api/ai/action/action-id/confirm")).andExpect(status().isOk());
        verify(actions).confirm(7L,"action-id");
    }
    @Test void actionListIsNeverCached() throws Exception {
        when(actions.list(7L,"cid")).thenReturn(java.util.List.of());
        mvc.perform(get("/api/ai/actions/cid"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"));
        verify(actions).list(7L,"cid");
    }
}
