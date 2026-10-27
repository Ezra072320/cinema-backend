package edu.czu.cinema.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Lazily opens the producer so an unavailable broker does not prevent the business app starting.
 */
@Component
@ConditionalOnProperty(name = "cinema.messaging.enabled", havingValue = "true")
public class RocketMqPublisher implements AutoCloseable {
  private final String endpoints;
  private final String[] topics;
  private final ClientServiceProvider provider = ClientServiceProvider.loadService();
  private Producer producer;

  public RocketMqPublisher(
      @Value("${cinema.messaging.endpoints:rocketmq-broker:8081}") String endpoints,
      @Value("${cinema.messaging.topics:cinema-order-events,cinema-order-timeouts}")
          String[] topics) {
    this.endpoints = endpoints;
    this.topics = topics;
  }

  public synchronized void publish(OutboxStore.Delivery row) throws Exception {
    if (producer == null) {
      producer =
          provider
              .newProducerBuilder()
              .setClientConfiguration(
                  ClientConfiguration.newBuilder()
                      .setEndpoints(endpoints)
                      .enableSsl(false)
                      .setRequestTimeout(Duration.ofSeconds(3))
                      .build())
              .setTopics(topics)
              .setMaxAttempts(1)
              .build();
    }
    var builder =
        provider
            .newMessageBuilder()
            .setTopic(row.topic())
            .setTag(row.tag())
            .setKeys(row.eventId())
            .setBody(row.payload().getBytes(StandardCharsets.UTF_8));
    if (row.deliveryTimestamp() != null)
      builder.setDeliveryTimestamp(
          Math.max(row.deliveryTimestamp(), System.currentTimeMillis() + 1000));
    producer.send(builder.build());
  }

  @Override
  public synchronized void close() throws Exception {
    if (producer != null) {
      producer.close();
      producer = null;
    }
  }
}
