package edu.czu.cinema.domain.movie.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.czu.cinema.config.TmdbProperties;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class TmdbClientTest {
    @Test void bearerUsesHeaderAndNeverLeaksCredentialsThroughErrorsOrQueryString() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var auth = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        server.createContext("/movie/1",exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getRawQuery());
            exchange.sendResponseHeaders(401,-1); exchange.close();
        });
        server.start();
        try {
            var properties = new TmdbProperties();
            properties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            properties.setReadAccessToken("unit-test-only-credential");
            var client = new TmdbClient(new ObjectMapper(),properties);
            assertThatThrownBy(() -> client.get("/movie/1",Map.of()))
                    .hasMessageContaining("鉴权失败").hasMessageNotContaining("unit-test-only-credential");
            assertThat(auth.get()).isEqualTo("Bearer unit-test-only-credential");
            assertThat(query.get()).doesNotContain("api_key").doesNotContain("unit-test-only-credential");
        } finally { server.stop(0); }
    }
}
