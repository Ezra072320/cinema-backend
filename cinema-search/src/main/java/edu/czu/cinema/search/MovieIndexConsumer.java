package edu.czu.cinema.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.domain.movie.search.MovieChanged;
import java.time.Duration;
import java.util.Map;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Failed ES writes are retried by RocketMQ; source versions make deliveries idempotent. */
@Component
public class MovieIndexConsumer implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(MovieIndexConsumer.class);
  private final IndexCoordinator notifications;
  private final ObjectMapper json;
  private final String endpoints;

  private PushConsumer consumer;

  public MovieIndexConsumer(
      IndexCoordinator notifications,
      ObjectMapper json,
      @Value("${cinema.messaging.endpoints}") String endpoints) {
    this.notifications = notifications;
    this.json = json;
    this.endpoints = endpoints;
  }

  @Scheduled(fixedDelay = 10_000, initialDelay = 2000)
  public synchronized void connect() {
    if (consumer != null) return;
    try {
      consumer =
          ClientServiceProvider.loadService()
              .newPushConsumerBuilder()
              .setClientConfiguration(
                  ClientConfiguration.newBuilder()
                      .setEndpoints(endpoints)
                      .enableSsl(false)
                      .setRequestTimeout(Duration.ofSeconds(3))
                      .build())
              .setConsumerGroup("cinema-search-movies")
              .setSubscriptionExpressions(
                  Map.of(
                      "cinema-movie-events", new FilterExpression("*", FilterExpressionType.TAG)))
              .setConsumptionThreadCount(2)
              .setMaxCacheMessageCount(100)
              .setMaxCacheMessageSizeInBytes(4 * 1024 * 1024)
              .setMessageListener(
                  message -> {
                    try {
                      var body = message.getBody();
                      if (body.remaining() > 200_000) return ConsumeResult.FAILURE;
                      byte[] bytes = new byte[body.remaining()];
                      body.get(bytes);
                      var event = json.readValue(bytes, MovieChanged.class);
                      notifications.receive(event);
                      return ConsumeResult.SUCCESS;
                    } catch (Exception failure) {
                      log.warn(
                          "Movie index delivery failed messageId={} attempt={} failure={}",
                          message.getMessageId(),
                          message.getDeliveryAttempt(),
                          failure.getClass().getSimpleName());
                      return ConsumeResult.FAILURE;
                    }
                  })
              .build();
    } catch (Exception failure) {
      log.warn(
          "Movie index consumer will reconnect failure={}", failure.getClass().getSimpleName());
    }
  }

  @Override
  public synchronized void close() throws Exception {
    if (consumer != null) {
      consumer.close();
      consumer = null;
    }
  }
}
