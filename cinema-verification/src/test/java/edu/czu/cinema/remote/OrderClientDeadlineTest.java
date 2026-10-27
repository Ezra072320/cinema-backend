package edu.czu.cinema.remote;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import feign.Feign;
import feign.Request;
import feign.Response;
import feign.Retryer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

class OrderClientDeadlineTest {
    @Test void actualFeignContractUsesLongerWriteBudgetsAcrossThreadsAndKeepsQueryBudget() throws Exception {
        var json = new ObjectMapper();
        var budgets = new ArrayList<Integer>();
        var bodies = new ArrayList<String>();
        var client = Feign.builder().contract(new SpringMvcContract())
                .retryer(Retryer.NEVER_RETRY)
                .options(new Request.Options(Duration.ofSeconds(1),Duration.ofSeconds(3),true))
                .encoder((value,type,template) -> {
                    try { template.body(json.writeValueAsString(value)); }
                    catch (java.io.IOException failure) { throw new feign.codec.EncodeException("Cannot encode fixture",failure); }
                })
                .decoder((response,type) -> type==String.class ? "ok" : json.readValue(response.body().asInputStream(),json.constructType(type)))
                .client((request,options) -> {
                    budgets.add(options.readTimeoutMillis());
                    if (request.body()!=null) bodies.add(new String(request.body(),StandardCharsets.UTF_8));
                    return Response.builder().request(request).status(200).reason("OK").headers(Map.of()).body("99",StandardCharsets.UTF_8).build();
                }).target(OrderClient.class,"http://localhost:1");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(pool.submit(() -> client.createOrder(7L,new OrderAddDTO(8L,List.of(101L)))).get()).isEqualTo(99L);
        }
        client.cancel(99L,7L);
        client.refund(99L,7L,new java.math.BigDecimal("40.00"));
        client.operationResult("fixture",7L);
        assertThat(budgets).containsExactly(18000,18000,18000,3000);
        assertThat(json.readTree(bodies.getFirst()).get("ticketIds").get(0).asLong()).isEqualTo(101L);
        assertThat(bodies.getFirst()).doesNotContain("readTimeout","connectTimeout");
    }
}
