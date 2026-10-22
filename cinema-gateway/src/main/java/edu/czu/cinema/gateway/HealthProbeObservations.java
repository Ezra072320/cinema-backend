package edu.czu.cinema.gateway;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keep health-probe client meters separate from the gateway's upstream request meters. */
@Configuration(proxyBeanMethods = false)
public class HealthProbeObservations {
    @Bean
    public WebClientCustomizer healthProbeObservationPolicy() {
        return builder -> builder.observationRegistry(ObservationRegistry.NOOP);
    }
}
