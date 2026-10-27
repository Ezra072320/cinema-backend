package edu.czu.cinema.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class ElasticConfiguration {
  @Bean(destroyMethod = "")
  public RestClient searchRestClient(@Value("${cinema.search.endpoint}") String endpoint) {
    return RestClient.builder(HttpHost.create(endpoint))
        .setRequestConfigCallback(c -> c.setConnectTimeout(1000).setSocketTimeout(2500))
        .setHttpClientConfigCallback(c -> c.setMaxConnTotal(8).setMaxConnPerRoute(8))
        .build();
  }

  @Bean(destroyMethod = "close")
  public RestClientTransport searchTransport(ObjectMapper mapper, RestClient rest) {
    return new RestClientTransport(rest, new JacksonJsonpMapper(mapper));
  }

  @Bean
  public ElasticsearchClient elasticsearchClient(RestClientTransport transport) {
    return new ElasticsearchClient(transport);
  }
}
