package edu.czu.cinema.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.AiTool;
import edu.czu.cinema.ai.service.*;
import edu.czu.cinema.domain.hall.service.*;
import edu.czu.cinema.domain.movie.service.MovieService;
import edu.czu.cinema.domain.order.service.OrderService;
import edu.czu.cinema.domain.order.vo.OrderVO;
import edu.czu.cinema.domain.schedule.service.ScheduleService;
import edu.czu.cinema.domain.score.service.ScoreService;
import edu.czu.cinema.domain.ticket.service.TicketService;
import edu.czu.cinema.domain.user.entity.User;
import edu.czu.cinema.domain.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiToolTest {
    final OrderService orders = mock(OrderService.class);
    final UserService users = mock(UserService.class);
    final AiTool tools = new AiTool(mock(AiCatalogService.class),mock(MovieService.class),mock(ScheduleService.class),
            mock(HallService.class),mock(TicketService.class),mock(SeatLabelService.class),orders,users,
            mock(ScoreService.class),mock(AiActionService.class));
    ToolContext context() { return new ToolContext(Map.of(AiIdentity.CONTEXT_KEY,new AiIdentity(7L,"chat"))); }

    @Test void trustedIdentitySurvivesWithoutHttpThreadLocalAndProfileExcludesSecrets() throws Exception {
        var u = new User(); u.setId(7L); u.setName("电影爱好者"); u.setAge(20); u.setPassword("secret-password"); u.setOpenid("private-openid");
        when(users.getById(7L)).thenReturn(u);
        String result = new ObjectMapper().writeValueAsString(tools.myProfile(context()));
        assertThat(result).contains("电影爱好者").doesNotContain("secret-password","private-openid","\"password\":","\"openid\":");
        verify(users).getById(7L);
    }
    @Test void orderLookupUsesTrustedUserInsteadOfModelSuppliedIdentity() {
        var o = new OrderVO(); o.setId(1L); o.setStatus(2); o.setTotalAmount(BigDecimal.TEN);
        o.setStartTime(LocalDateTime.now().plusDays(1));
        when(orders.detail(1L,7L)).thenReturn(o);
        tools.orderDetails(1L,context());
        verify(orders).detail(1L,7L);
        tools.orderDetails(2L,new ToolContext(Map.of("userId",99L)));
        verify(orders,never()).detail(2L,99L);
    }
    @Test void allToolsRegisterAndIdentityIsNotPartOfModelSchema() {
        var callbacks = ToolCallbacks.from(tools);
        assertThat(callbacks).hasSize(17);
        for (var callback : callbacks) {
            String schema = callback.getToolDefinition().inputSchema();
            assertThat(schema).doesNotContain("\"userId\"","\"chatId\"","\"context\"");
        }
    }
}
