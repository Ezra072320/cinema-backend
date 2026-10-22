package edu.czu.cinema.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The blocking LoadBalancer health-check configuration requires a concrete RestClient bean. */
@Configuration(proxyBeanMethods = false)
public class DiscoveryHealthConfig {
    @Bean
    public RestClient discoveryHealthClient(RestClient.Builder builder) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(1000);
        requests.setReadTimeout(1000);
        return builder.requestFactory(requests).build();
    }
}
