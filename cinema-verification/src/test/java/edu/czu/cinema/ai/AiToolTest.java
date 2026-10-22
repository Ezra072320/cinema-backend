package edu.czu.cinema.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.czu.cinema.AiTool;
import edu.czu.cinema.ai.service.*;
import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.domain.hall.service.*;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.order.vo.OrderVO;
import edu.czu.cinema.domain.schedule.vo.ScheduleSeatsVO;
import edu.czu.cinema.domain.score.vo.MovieReviewPage;
import edu.czu.cinema.domain.score.vo.ReviewView;
import edu.czu.cinema.domain.user.entity.User;
import edu.czu.cinema.remote.*;
import edu.czu.cinema.remote.AccountClient;
import edu.czu.cinema.remote.CatalogClient;
import edu.czu.cinema.remote.OrderClient;
import edu.czu.cinema.remote.ScheduleClient;
import edu.czu.cinema.remote.ScoreClient;
import edu.czu.cinema.remote.SeatLabelClient;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class AiToolTest {
    final OrderClient orders = mock(OrderClient.class);
    final AccountClient users = mock(AccountClient.class);
    final CatalogClient movies = mock(CatalogClient.class);
    final ScheduleClient schedules = mock(ScheduleClient.class);
    final SeatLabelClient labels = mock(SeatLabelClient.class);
    final ScoreClient scores = mock(ScoreClient.class);
    final AiActionService actions = mock(AiActionService.class);
    final AiSeatSelectionStore seatSelections = new AiSeatSelectionStore();
    final AiTool tools =
            new AiTool(
                    mock(AiCatalogService.class),
                    movies,
                    schedules,
                    mock(HallClient.class),
                    labels,
                    orders,
                    users,
                    scores,
                    actions,
                    seatSelections);

    ToolContext context() {
        return new ToolContext(Map.of(AiIdentity.CONTEXT_KEY, new AiIdentity(7L, "chat")));
    }

    @Test
    void trustedIdentitySurvivesWithoutHttpThreadLocalAndProfileExcludesSecrets() throws Exception {
        var u = new User();
        u.setId(7L);
        u.setName("电影爱好者");
        u.setAge(20);
        u.setPassword("secret-password");
        u.setOpenid("private-openid");
        when(users.getById(7L)).thenReturn(u);
        String result = new ObjectMapper().writeValueAsString(tools.myProfile(context()));
        assertThat(result)
                .contains("电影爱好者")
                .doesNotContain(
                        "secret-password", "private-openid", "\"password\":", "\"openid\":");
        verify(users).getById(7L);
    }

    @Test
    void orderLookupUsesTrustedUserInsteadOfModelSuppliedIdentity() {
        var o = new OrderVO();
        o.setId(1L);
        o.setStatus(2);
        o.setTotalAmount(BigDecimal.TEN);
        o.setStartTime(LocalDateTime.now().plusDays(1));
        when(orders.detail(1L, 7L)).thenReturn(o);
        tools.orderDetails(1L, context());
        verify(orders).detail(1L, 7L);
        tools.orderDetails(2L, new ToolContext(Map.of("userId", 99L)));
        verify(orders, never()).detail(2L, 99L);
    }

    @Test
    void allToolsRegisterAndIdentityIsNotPartOfModelSchema() {
        var callbacks = ToolCallbacks.from(tools);
        assertThat(callbacks).hasSize(22);
        for (var callback : callbacks) {
            String schema = callback.getToolDefinition().inputSchema();
            assertThat(schema).doesNotContain("\"userId\"", "\"chatId\"", "\"context\"");
        }
    }

    // ---------- 选座回归：2排2座明明可售，不能用分页列表猜 ----------

    @Test
    void seatDetailsFindsExactSeatEvenWhenItIsNotOnTheFirstPage() {
        stubSchedule(fixture());
        var result = tools.seatDetails(1L, "2排2座", context());
        assertThat(result.success()).isTrue();
        Map<String, Object> data = data(result);
        assertThat(data.get("found")).isEqualTo(true);
        assertThat(data.get("available")).isEqualTo(true);
        assertThat(rows(data)).hasSize(1);
        assertThat(rows(data).get(0).get("ticketId")).isEqualTo(5L);
        assertThat(rows(data).get(0).get("label")).isEqualTo("2-2");
        assertThat(rows(data).get(0).get("price")).isEqualTo(new BigDecimal("120.00"));
    }

    @Test
    void seatDetailsRemembersTheVerifiedSelectionForRecovery() {
        stubSchedule(fixture());

        tools.seatDetails(1L, "2排2座", context());

        var selection = seatSelections.recent("chat");
        assertThat(selection).isNotNull();
        assertThat(selection.scheduleId()).isEqualTo(1L);
        assertThat(selection.ticketIds()).containsExactly(5L);
        assertThat(selection.seatLabel()).isEqualTo("2-2");
    }

    @Test
    void seatDetailsReportsSoldSeatInsteadOfSilentlySayingNotFound() {
        ScheduleSeatsVO s = fixture();
        s.getSeats().stream()
                .filter(x -> x.getTicketId().equals(6L))
                .forEach(x -> x.setTicketStatus(0));
        stubSchedule(s);

        Map<String, Object> data = data(tools.seatDetails(1L, "2-3", context()));
        assertThat(data.get("found")).isEqualTo(true);
        assertThat(data.get("available")).isEqualTo(false);
        assertThat((String) data.get("reason")).contains("已售");
    }

    @Test
    void coupleSeatIsUnavailableWhenItsPartnerIsSold() {
        ScheduleSeatsVO s = fixture();
        s.getSeats().stream()
                .filter(x -> x.getTicketId().equals(7L))
                .forEach(x -> x.setTicketStatus(0));
        stubSchedule(s);

        Map<String, Object> data = data(tools.seatDetails(1L, "3-1", context()));
        assertThat(data.get("found")).isEqualTo(true);
        assertThat(data.get("available")).isEqualTo(false);
        assertThat(rows(data)).hasSize(2);
        assertThat(
                        rows(data).stream()
                                .map(r -> (String) r.get("status"))
                                .toList())
                .anyMatch(status -> status.contains("情侣"));
    }

    @Test
    void availableSeatsReturnsAllTypesAndPaginationMetadataWithoutTypeFilter() {
        stubSchedule(fixture());

        Map<String, Object> data = data(tools.availableSeats(1L, null, 1, context()));
        assertThat(data.get("totalAvailableTickets")).isEqualTo(9);
        assertThat(data.get("totalPages")).isEqualTo(1);
        assertThat(rows(data)).hasSize(9);
        assertThat((List<?>) data.get("typeSummary")).hasSize(4);
    }

    @Test
    void availableSeatsWithLabelReturnsOnlyThatSeat() {
        stubSchedule(fixture());

        Map<String, Object> data = data(tools.availableSeats(1L, "2排2座", 1, context()));
        assertThat(data.get("found")).isEqualTo(true);
        assertThat(data.get("available")).isEqualTo(true);
        assertThat(rows(data)).hasSize(1);
        assertThat(rows(data).get(0).get("ticketId")).isEqualTo(5L);
    }

    @Test
    void prepareBookingBySeatUsesTheExactTicketAndDoesNotAskAgain() {
        stubSchedule(fixture());

        var result = tools.prepareBookingBySeat(1L, "2排2座", context());
        assertThat(result.success()).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(
                edu.czu.cinema.ai.dto.AiActionCommand.class);
        verify(actions).prepare(any(AiIdentity.class), captor.capture());
        assertThat(captor.getValue().type())
                .isEqualTo(edu.czu.cinema.ai.dto.AiActionCommand.Type.CREATE_ORDER);
        assertThat(captor.getValue().scheduleId()).isEqualTo(1L);
        assertThat(captor.getValue().ticketIds()).containsExactly(5L);
    }

    @Test
    void prepareBookingBySeatBringsBothCoupleTickets() {
        stubSchedule(fixture());

        var result = tools.prepareBookingBySeat(1L, "3-1", context());
        assertThat(result.success()).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(
                edu.czu.cinema.ai.dto.AiActionCommand.class);
        verify(actions).prepare(any(AiIdentity.class), captor.capture());
        assertThat(captor.getValue().ticketIds()).containsExactly(7L, 8L);
    }

    @Test
    void prepareBookingBySeatRefusesSoldSeatWithoutCreatingAnAction() {
        ScheduleSeatsVO s = fixture();
        s.getSeats().stream()
                .filter(x -> x.getTicketId().equals(6L))
                .forEach(x -> x.setTicketStatus(0));
        stubSchedule(s);

        var result = tools.prepareBookingBySeat(1L, "2-3", context());
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("不可售");
        verify(actions, never()).prepare(any(), any());
    }

    @Test
    void afterSaleChoosesRefundForPaidOrderAfterRequeryingStatus() {
        when(actions.prepareAfterSale(any(AiIdentity.class), eq(4L)))
                .thenReturn(
                        new AiActionService.AfterSaleResult(
                                order(4L, 2),
                                view("REFUND_ORDER", "确认申请退票", "/order/4"),
                                "申请退票"));

        var result = tools.prepareOrderAfterSale(4L, context());
        assertThat(result.success()).isTrue();
        assertThat((String) data(result).get("orderStatusText")).isEqualTo("已支付");
        verify(actions).prepareAfterSale(any(AiIdentity.class), eq(4L));
    }

    @Test
    void afterSaleChoosesCancelForPendingOrder() {
        when(actions.prepareAfterSale(any(AiIdentity.class), eq(4L)))
                .thenReturn(
                        new AiActionService.AfterSaleResult(
                                order(4L, 1),
                                view("CANCEL_ORDER", "取消待支付订单", "/order/4"),
                                "取消待支付订单"));

        var result = tools.prepareOrderAfterSale(4L, context());
        assertThat(result.success()).isTrue();
        verify(actions).prepareAfterSale(any(AiIdentity.class), eq(4L));
    }

    @Test
    void afterSaleDoesNotCreateActionForAlreadyRefundedOrder() {
        when(actions.prepareAfterSale(any(AiIdentity.class), eq(4L)))
                .thenThrow(new edu.czu.cinema.common.exception.BusinessException("订单已退款，无需重复办理"));

        var result = tools.prepareOrderAfterSale(4L, context());
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("已退款");
        verify(actions, never()).prepare(any(), any());
    }

    @Test
    void seatLabelNormalizationAcceptsChineseAndFullWidthDigits() {
        assertThat(AiSeatLabels.normalize("2-2")).isEqualTo("2-2");
        assertThat(AiSeatLabels.normalize("2排2座")).isEqualTo("2-2");
        assertThat(AiSeatLabels.normalize("第2排第2号")).isEqualTo("2-2");
        assertThat(AiSeatLabels.normalize("２排２座")).isEqualTo("2-2");
        assertThat(AiSeatLabels.normalize("12 排 10 座")).isEqualTo("12-10");
        assertThat(AiSeatLabels.normalize("靠窗")).isNull();
        assertThat(AiSeatLabels.normalize("0-1")).isNull();
    }

    // ---------- 评价业务覆盖 ----------

    @Test
    void movieReviewsReturnsPublicSummaryAndMasksNothingFromService() {
        ReviewView v = review();
        when(movies.getMovie(3L)).thenReturn(movie("燃烧吧！爸爸"));
        when(scores.movieReviews(3L, 7L, 1, 10, "newest"))
                .thenReturn(
                        new MovieReviewPage(
                                1,
                                List.of(v),
                                new MovieReviewPage.Summary(
                                        1, new BigDecimal("5.0"), Map.of(5, 1L))));

        Map<String, Object> data =
                data(tools.movieReviews("燃烧吧！爸爸", 3L, 1, "newest", context()));
        assertThat(data.get("total")).isEqualTo(1L);
        assertThat(data.get("movieTitle")).isEqualTo("燃烧吧！爸爸");
        assertThat(data.get("path")).isEqualTo("/movie/3");
        assertThat(rows(data)).hasSize(1);
        assertThat(rows(data).get(0).get("content")).isEqualTo("很好看");
        assertThat(rows(data).get(0).get("author")).isEqualTo("观***");
    }

    @Test
    void movieReviewsReResolvesTitleWhenTheModelGuessesTheWrongMovieId() {
        when(movies.getMovie(3L)).thenReturn(movie("无间道"));
        when(movies.movies("燃烧吧！爸爸", null, 1))
                .thenReturn(
                        Map.of(
                                "rows",
                                List.of(
                                        Map.of(
                                                "movieId", 916L,
                                                "title", "燃烧吧！爸爸"))));
        when(scores.movieReviews(916L, 7L, 1, 10, "newest"))
                .thenReturn(new MovieReviewPage(0, List.of(), null));

        Map<String, Object> data =
                data(tools.movieReviews("燃烧吧！爸爸", 3L, 1, "newest", context()));
        assertThat(data.get("movieId")).isEqualTo(916L);
        assertThat(data.get("movieTitle")).isEqualTo("燃烧吧！爸爸");
        assertThat(data.get("path")).isEqualTo("/movie/916");
        verify(scores, never()).movieReviews(3L, 7L, 1, 10, "newest");
    }

    @Test
    void movieReviewsRefusesUnknownTitleInsteadOfInventingOne() {
        when(movies.movies("不存在的电影", null, 1)).thenReturn(Map.of("rows", List.of()));

        var result = tools.movieReviews("不存在的电影", null, 1, "newest", context());
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("未找到");
        verify(scores, never()).movieReviews(anyLong(), anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    void myReviewsLinksToTheRealPortalRoute() {
        ReviewView v = review();
        v.setMine(true);
        v.setOrderId(10L);
        v.setVersion(1L);
        v.setEditable(true);
        when(scores.myReviews(7L, 1, 10)).thenReturn(PageResult.of(1, List.of(v)));

        Map<String, Object> data = data(tools.myReviews(1, context()));
        assertThat(data.get("path")).isEqualTo("/me/reviews");
        assertThat(rows(data).get(0).get("path")).isEqualTo("/score/3/10");
        assertThat(rows(data).get(0).get("editable")).isEqualTo(true);
    }

    // ---------- fixtures ----------

    private void stubSchedule(ScheduleSeatsVO s) {
        when(schedules.seatsOfSchedule(1L)).thenReturn(s);
        Map<Long, String> labels = new HashMap<>();
        labels.put(7L, "1-1");
        labels.put(8L, "1-2");
        labels.put(9L, "1-3");
        labels.put(23L, "2-1");
        labels.put(24L, "2-2");
        labels.put(25L, "2-3");
        labels.put(39L, "3-1");
        labels.put(40L, "3-1");
        labels.put(41L, "3-2");
        when(this.labels.labelsOf(2L)).thenReturn(labels);
    }

    private static ScheduleSeatsVO fixture() {
        ScheduleSeatsVO s = new ScheduleSeatsVO();
        s.setScheduleId(1L);
        s.setSeatVersionId(2L);
        s.setStatus(1);
        s.setMovieName("燃烧吧！爸爸");
        s.setHallName("1号厅");
        s.setStartTime(LocalDateTime.now().plusDays(1));
        s.setSeats(
                List.of(
                        seat(7L, 1L, 1, 1, 1, 7, "40.00"),
                        seat(8L, 2L, 1, 1, 1, 8, "40.00"),
                        seat(9L, 3L, 4, 1, 1, 9, "40.00"),
                        seat(23L, 4L, 1, 1, 2, 7, "40.00"),
                        seat(24L, 5L, 3, 1, 2, 8, "120.00"),
                        seat(25L, 6L, 1, 1, 2, 9, "40.00"),
                        seat(39L, 7L, 2, 1, 3, 7, "100.00"),
                        seat(40L, 8L, 2, 1, 3, 8, "100.00"),
                        seat(41L, 9L, 1, 1, 3, 9, "40.00")));
        ScheduleSeatsVO.CouplePair pair = new ScheduleSeatsVO.CouplePair();
        pair.setId(1L);
        pair.setSeatId1(39L);
        pair.setSeatId2(40L);
        s.setCoupleGroups(List.of(pair));
        return s;
    }

    private static ScheduleSeatsVO.SeatItem seat(
            long seatId, long ticketId, int type, int seatStatus, int y, int x, String price) {
        ScheduleSeatsVO.SeatItem item = new ScheduleSeatsVO.SeatItem();
        item.setSeatId(seatId);
        item.setTicketId(ticketId);
        item.setTicketStatus(1);
        item.setPrice(new BigDecimal(price));
        item.setType(type);
        item.setStatus(seatStatus);
        item.setY(y);
        item.setX(x);
        return item;
    }

    private static AiActionService.View view(String type, String title, String path) {
        return new AiActionService.View(
                "aid", type, title, "summary", "PENDING", null, null, path);
    }

    private static OrderVO order(long id, int status) {
        OrderVO o = new OrderVO();
        o.setId(id);
        o.setNumber("202610211609230001017562");
        o.setStatus(status);
        o.setTotalAmount(new BigDecimal("120.00"));
        o.setStartTime(LocalDateTime.now().plusDays(1));
        return o;
    }

    private static Movie movie(String title) {
        Movie m = new Movie();
        m.setId(3L);
        m.setTitle(title);
        return m;
    }

    private static ReviewView review() {
        ReviewView v = new ReviewView();
        v.setId(1L);
        v.setMovieId(3L);
        v.setMovieTitle("燃烧吧！爸爸");
        v.setAuthor("观***");
        v.setScore(5);
        v.setContent("很好看");
        v.setPublished(true);
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(AiTool.ToolResult result) {
        assertThat(result.success()).isTrue();
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("rows");
    }
}
