package edu.czu.cinema.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.czu.cinema.ai.dto.AiActionCommand;
import edu.czu.cinema.ai.entity.AiAction;
import edu.czu.cinema.ai.entity.AiSession;
import edu.czu.cinema.ai.mapper.AiActionMapper;
import edu.czu.cinema.ai.mapper.AiSessionMapper;
import edu.czu.cinema.ai.service.*;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.vo.MovieInfoVO;
import edu.czu.cinema.domain.order.vo.OrderVO;
import edu.czu.cinema.remote.AccountClient;
import edu.czu.cinema.remote.CatalogClient;
import edu.czu.cinema.remote.OrderClient;
import edu.czu.cinema.remote.ScoreClient;

import jakarta.validation.Validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

class AiActionServiceTest {
    final AiActionMapper mapper = mock(AiActionMapper.class);
    final AiSessionMapper sessions = mock(AiSessionMapper.class);
    final CatalogClient movies = mock(CatalogClient.class);
    final OrderClient orders = mock(OrderClient.class);
    final ScoreClient scores = mock(ScoreClient.class);
    final AccountClient users = mock(AccountClient.class);
    final AiBookingService booking = mock(AiBookingService.class);
    final ObjectMapper json = new ObjectMapper();
    final AiActionService service =
            new AiActionService(
                    mapper,
                    sessions,
                    json,
                    Validation.buildDefaultValidatorFactory().getValidator(),
                    booking,
                    orders,
                    movies,
                    scores,
                    users);

    @BeforeEach
    void setup() {
        AiSession session = new AiSession();
        session.setUserId(7L);
        session.setChatId("chat");
        when(sessions.selectById("chat")).thenReturn(session);
        when(mapper.selectList(any())).thenReturn(List.of());
        Movie movie = new Movie();
        movie.setId(3L);
        movie.setTitle("测试影片");
        MovieInfoVO info = new MovieInfoVO();
        info.setBase(movie);
        when(movies.info(3L)).thenReturn(info);
    }

    AiAction prepare(AiActionCommand command) {
        service.prepare(new AiIdentity(7L, "chat"), command);
        ArgumentCaptor<AiAction> cap = ArgumentCaptor.forClass(AiAction.class);
        verify(mapper).insert(cap.capture());
        AiAction a = cap.getValue();
        when(mapper.lockById(a.getId())).thenReturn(a);
        return a;
    }

    AiActionCommand want() {
        return AiActionCommand.builder()
                .type(AiActionCommand.Type.SET_WANT)
                .movieId(3L)
                .wanted(true)
                .build();
    }

    @Test
    void toolsOnlyPrepareWithoutChangingBusiness() {
        AiAction a = prepare(want());
        assertThat(a.getStatus()).isEqualTo("PENDING");
        assertThat(a.getSummary()).contains("测试影片");
        verify(movies, never()).setWant(any(), any(), anyBoolean());
        verifyNoInteractions(orders, scores, users);
    }

    @Test
    void couponPreviewShowsPayableAndConfirmRechecksBeforeSendingTheSelectedCoupon() {
        var benefits=mock(edu.czu.cinema.remote.BenefitClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"benefits",benefits);
        when(booking.quote(any())).thenReturn(new AiBookingService.BookingQuote("测试影片","一号厅",LocalDateTime.now().plusDays(1),List.of("1排1座"),new BigDecimal("50.00"),"/seats/1"));
        when(benefits.quote(any())).thenReturn(new edu.czu.cinema.domain.benefit.CouponContracts.Discount(3L,new BigDecimal("10.00"),new BigDecimal("40.00")));
        var command=AiActionCommand.builder().type(AiActionCommand.Type.CREATE_ORDER).scheduleId(1L).ticketIds(List.of(2L)).couponId(3L).build();
        var action=prepare(command);
        assertThat(action.getSummary()).contains("实付¥40.00","退票不返券");
        verifyNoInteractions(orders);
        when(orders.createOrder(eq(7L),any())).thenReturn(100L);
        assertThat(service.confirm(7L,action.getId()).status()).isEqualTo("DONE");
        verify(orders).createOrder(7L,new edu.czu.cinema.domain.order.dto.OrderAddDTO(1L,List.of(2L),3L));
        verify(benefits,atLeast(2)).quote(new edu.czu.cinema.domain.benefit.CouponContracts.Reserve(7L,3L,1L,new BigDecimal("50.00")));
    }

    @Test
    void confirmedActionExecutesOnceEvenWhenRetried() {
        AiAction a = prepare(want());
        when(movies.isWant(3L, 7L)).thenReturn(false);
        when(movies.setWant(7L, 3L, true)).thenReturn(true);
        assertThat(service.confirm(7L, a.getId()).status()).isEqualTo("DONE");
        assertThat(service.confirm(7L, a.getId()).status()).isEqualTo("DONE");
        verify(movies, times(1)).setWant(7L, 3L, true);
    }

    @Test
    void alreadyWantedRemainsWanted() {
        AiAction a = prepare(want());
        when(movies.isWant(3L, 7L)).thenReturn(true);
        service.confirm(7L, a.getId());
        verify(movies, never()).setWant(any(), any(), anyBoolean());
    }

    @Test
    void cannotConfirmOrDismissAnotherUsersAction() {
        AiAction a = prepare(want());
        assertThatThrownBy(() -> service.confirm(9L, a.getId()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.dismiss(9L, a.getId()))
                .isInstanceOf(BusinessException.class);
        verify(movies, never()).setWant(any(), any(), anyBoolean());
    }

    @Test
    void cannotPrepareOrListInForeignSession() {
        assertThatThrownBy(() -> service.prepare(new AiIdentity(8L, "chat"), want()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(8L, "chat")).isInstanceOf(BusinessException.class);
        verify(mapper, never()).insert(any(AiAction.class));
    }

    @Test
    void expiredAndDismissedActionsCannotExecute() {
        AiAction a = prepare(want());
        a.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThatThrownBy(() -> service.confirm(7L, a.getId())).hasMessageContaining("过期");
        a.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        assertThat(service.dismiss(7L, a.getId()).status()).isEqualTo("DISMISSED");
        assertThatThrownBy(() -> service.confirm(7L, a.getId()))
                .isInstanceOf(BusinessException.class);
        verify(movies, never()).setWant(any(), any(), anyBoolean());
    }

    @Test
    void invalidScoreAndNameAreRejectedBeforeWriting() {
        assertThatThrownBy(
                        () ->
                                service.prepare(
                                        new AiIdentity(7L, "chat"),
                                        AiActionCommand.builder()
                                                .type(AiActionCommand.Type.SUBMIT_SCORE)
                                                .orderId(1L)
                                                .score(6)
                                                .build()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                service.prepare(
                                        new AiIdentity(7L, "chat"),
                                        AiActionCommand.builder()
                                                .type(AiActionCommand.Type.UPDATE_PROFILE)
                                                .name("空 格")
                                                .build()))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).insert(any(AiAction.class));
    }

    OrderVO paid() {
        var o = new OrderVO();
        o.setId(4L);
        o.setNumber("NO4");
        o.setStatus(2);
        o.setMovieName("测试影片");
        o.setHallName("1号厅");
        o.setTotalAmount(new BigDecimal("99.99"));
        o.setStartTime(LocalDateTime.now().plusHours(8));
        o.setSeatLabels(List.of("3-5"));
        o.setCanReview(true);
        o.setReviewed(false);
        return o;
    }

    // ---- 评价预览资格（语义对齐 ReviewPolicy：status 2/6 + 场次有效 + 散场后；已评价引导去编辑） ----

    @Test
    void reviewPreviewAcceptsLegacyCompletedStatus6() {
        var o = paid();
        o.setStatus(6);
        o.setCanReview(true);
        when(orders.detail(4L, 7L)).thenReturn(o);
        AiAction a =
                prepare(
                        AiActionCommand.builder()
                                .type(AiActionCommand.Type.SUBMIT_SCORE)
                                .orderId(4L)
                                .score(5)
                                .content("好看")
                                .build());
        assertThat(a.getSummary()).contains("提交后订单状态不变");
        verify(scores, never()).submit(any(), any());
    }

    @Test
    void reviewPreviewRejectsWhenNotYetShowable() {
        var o = paid();
        o.setCanReview(false);
        o.setReviewReason("电影放映结束后可评价");
        when(orders.detail(4L, 7L)).thenReturn(o);
        assertThatThrownBy(
                        () ->
                                service.prepare(
                                        new AiIdentity(7L, "chat"),
                                        AiActionCommand.builder()
                                                .type(AiActionCommand.Type.SUBMIT_SCORE)
                                                .orderId(4L)
                                                .score(5)
                                                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("放映结束后");
        verify(mapper, never()).insert(any(AiAction.class));
    }

    @Test
    void reviewPreviewGuidesToEditWhenAlreadyReviewedInsteadOfNewAction() {
        var o = paid();
        o.setCanReview(true);
        o.setReviewed(true);
        when(orders.detail(4L, 7L)).thenReturn(o);
        assertThatThrownBy(
                        () ->
                                service.prepare(
                                        new AiIdentity(7L, "chat"),
                                        AiActionCommand.builder()
                                                .type(AiActionCommand.Type.SUBMIT_SCORE)
                                                .orderId(4L)
                                                .score(5)
                                                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评价页编辑原评价");
        verify(scores, never()).submit(any(), any());
    }

    @Test
    void reviewConfirmResultDoesNotClaimOrderCompletion() {
        var o = paid();
        o.setCanReview(true);
        when(orders.detail(4L, 7L)).thenReturn(o);
        when(scores.submit(eq(7L), any())).thenReturn(true);
        AiAction a =
                prepare(
                        AiActionCommand.builder()
                                .type(AiActionCommand.Type.SUBMIT_SCORE)
                                .orderId(4L)
                                .score(5)
                                .content("好")
                                .build());
        assertThat(service.confirm(7L, a.getId()).result())
                .contains("评价已提交")
                .doesNotContain("订单已完成");
    }

    @Test
    void refundTierChangeRequiresAnotherConfirmation() {
        var o = paid();
        when(orders.detail(4L, 7L)).thenReturn(o);
        AiAction a =
                prepare(
                        AiActionCommand.builder()
                                .type(AiActionCommand.Type.REFUND_ORDER)
                                .orderId(4L)
                                .build());
        o.setStartTime(LocalDateTime.now().plusHours(4));
        assertThatThrownBy(() -> service.confirm(7L, a.getId())).hasMessageContaining("变化");
        verify(orders, never()).refund(any(), any(), any());
    }

    @Test
    void refundUsesDisplayedAmountGuard() {
        var o = paid();
        when(orders.detail(4L, 7L)).thenReturn(o);
        AiAction a =
                prepare(
                        AiActionCommand.builder()
                                .type(AiActionCommand.Type.REFUND_ORDER)
                                .orderId(4L)
                                .build());
        when(orders.refund(4L, 7L, new BigDecimal("99.99"))).thenReturn("已退款");
        assertThat(service.confirm(7L, a.getId()).result()).contains("模拟");
        verify(orders).refund(4L, 7L, new BigDecimal("99.99"));
    }

    @Test
    void bookingDoesNotLockUntilConfirm() {
        when(booking.quote(any()))
                .thenReturn(
                        new AiBookingService.BookingQuote(
                                "测试影片",
                                "厅",
                                LocalDateTime.now().plusDays(1),
                                List.of("3-5（¥50.00）"),
                                new BigDecimal("50.00"),
                                "/seats/8"));
        AiAction a =
                prepare(
                        AiActionCommand.builder()
                                .type(AiActionCommand.Type.CREATE_ORDER)
                                .scheduleId(8L)
                                .ticketIds(List.of(101L))
                                .build());
        verify(orders, never()).createOrder(eq(7L), any());
        when(orders.createOrder(eq(7L), any())).thenReturn(999L);
        assertThat(service.confirm(7L, a.getId()).path()).isEqualTo("/pay/999");
        service.confirm(7L, a.getId());
        verify(orders, times(1)).createOrder(eq(7L), any());
    }

    @Test
    void priceChangeDoesNotCreateOrder() {
        var time = LocalDateTime.now().plusDays(1);
        when(booking.quote(any()))
                .thenReturn(
                        new AiBookingService.BookingQuote(
                                "片", "厅", time, List.of("3-5"), new BigDecimal("50"), "/seats/8"),
                        new AiBookingService.BookingQuote(
                                "片", "厅", time, List.of("3-5"), new BigDecimal("60"), "/seats/8"));
        AiAction a =
                prepare(
                        AiActionCommand.builder()
                                .type(AiActionCommand.Type.CREATE_ORDER)
                                .scheduleId(8L)
                                .ticketIds(List.of(101L))
                                .build());
        assertThatThrownBy(() -> service.confirm(7L, a.getId())).hasMessageContaining("变化");
        verify(orders, never()).createOrder(eq(7L), any());
    }

    @Test
    void lostRemoteResponseIsRecoveredEvenAfterPreviewExpires() {
        AiAction a = prepare(want());
        a.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(movies.operationResult(a.getId(), 7L)).thenReturn("true");
        assertThat(service.confirm(7L, a.getId()).status()).isEqualTo("DONE");
        verify(movies, never()).setWant(any(), any(), anyBoolean());
    }

    @Test
    void uncertainOrderResponseReturnsRetryableStatusAndRecoversOriginalOrderBeforeExpiredPreview() {
        when(booking.quote(any())).thenReturn(new AiBookingService.BookingQuote(
                "片","厅",LocalDateTime.now().plusDays(1),List.of("3-5"),new BigDecimal("40"),"/seats/8"));
        AiAction a=prepare(AiActionCommand.builder().type(AiActionCommand.Type.CREATE_ORDER).scheduleId(8L).ticketIds(List.of(101L)).build());
        when(orders.createOrder(eq(7L),any())).thenThrow(mock(feign.RetryableException.class));
        assertThatThrownBy(() -> service.confirm(7L,a.getId()))
                .isInstanceOf(edu.czu.cinema.common.exception.ServiceUnavailableException.class).hasMessageContaining("先核对原办理结果");
        assertThat(a.getStatus()).isEqualTo("PENDING");
        assertThat(edu.czu.cinema.remote.OperationContext.get()).isNull();
        a.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(orders.operationResult(a.getId(),7L)).thenReturn("99");
        var recovered=service.confirm(7L,a.getId());
        assertThat(recovered.status()).isEqualTo("DONE");
        assertThat(recovered.path()).isEqualTo("/pay/99");
        verify(orders,times(1)).createOrder(eq(7L),any());
    }
}
