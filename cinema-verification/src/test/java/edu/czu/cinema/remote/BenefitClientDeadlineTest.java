package edu.czu.cinema.remote;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.czu.cinema.domain.benefit.CouponContracts.Reserve;
import feign.Feign;
import feign.Request;
import feign.RetryableException;
import feign.Retryer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

class BenefitClientDeadlineTest {
    @Test void slowReservationCompletesOnceWithinTheGlobalTransactionBudget() throws Exception {
        exercise("reserve");
    }

    @Test void couponReadKeepsTheShortDeadlineAndDoesNotRetry() throws Exception {
        exercise("coupons");
    }

    @Test void quotationKeepsTheShortDeadlineAndDoesNotRetry() throws Exception {
        exercise("quote");
    }

    private void exercise(String operation) throws Exception {
        var json = new ObjectMapper();
        var attempts = new ConcurrentHashMap<String, AtomicInteger>();
        var bodies = new ConcurrentHashMap<String, String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(threads);
            server.createContext("/internal/benefit/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                attempts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
                bodies.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                try {
                    Thread.sleep(4200); // Real HTTP response slower than the shared 3s read deadline.
                    byte[] response = (path.endsWith("/coupons") ? "[]"
                        : "{\"couponId\":99,\"discount\":10,\"payable\":30}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (java.io.IOException clientClosed) {
                    // Expected when the query-side deadline expires before this response.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            var client = Feign.builder().contract(new SpringMvcContract())
                .retryer(Retryer.NEVER_RETRY)
                .options(new Request.Options(Duration.ofSeconds(1), Duration.ofSeconds(3), true))
                .encoder((value, type, template) -> {
                    try { template.body(json.writeValueAsString(value)); }
                    catch (java.io.IOException failure) { throw new feign.codec.EncodeException("Fixture encoding", failure); }
                })
                .decoder((response, type) -> json.readValue(response.body().asInputStream(), json.constructType(type)))
                .target(BenefitClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
            var request = new Reserve(7L, 99L, 101L, new BigDecimal("40"));
            if (operation.equals("reserve")) {
                assertThat(client.reserve(request).payable()).isEqualByComparingTo("30");
                assertThat(json.readTree(bodies.get("/internal/benefit/reservations")).size()).isEqualTo(4);
                assertThat(bodies.get("/internal/benefit/reservations")).doesNotContain("readTimeout", "connectTimeout");
            } else if (operation.equals("coupons")) {
                assertThatThrownBy(() -> client.coupons(7L)).isInstanceOf(RetryableException.class).hasMessageContaining("Read timed out");
            } else {
                assertThatThrownBy(() -> client.quote(request)).isInstanceOf(RetryableException.class).hasMessageContaining("Read timed out");
            }
            assertThat(attempts.values()).hasSize(1).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
        } finally {
            server.stop(0);
        }
    }
}
