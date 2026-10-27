package edu.czu.cinema.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.domain.order.event.OrderEvent;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Inbox remains queryable while the broker is unavailable. Failed deliveries use broker retry/DLQ.
 */
@Component
public class OrderNotificationConsumer implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(OrderNotificationConsumer.class);
  private final NotificationService notifications;
  private final ObjectMapper json;
  private final String endpoints;
  private final NotificationSocket socket;
  private PushConsumer consumer;

  public OrderNotificationConsumer(
      NotificationService notifications,
      ObjectMapper json,
      NotificationSocket socket,
      @Value("${cinema.messaging.endpoints}") String endpoints) {
    this.notifications = notifications;
    this.json = json;
    this.endpoints = endpoints;
    this.socket = socket;
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
              .setConsumerGroup("cinema-notify-orders")
              .setSubscriptionExpressions(
                  Map.of(
                      "cinema-order-events", new FilterExpression("*", FilterExpressionType.TAG)))
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
                      var event = json.readValue(bytes, OrderEvent.class);
                      boolean changed =
                          notifications.receive(
                              event,
                              HexFormat.of()
                                  .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                      if (changed) socket.changed(event.userId());
                      return ConsumeResult.SUCCESS;
                    } catch (Exception failure) {
                      log.warn(
                          "Notification delivery failed messageId={} attempt={} failure={}",
                          message.getMessageId(),
                          message.getDeliveryAttempt(),
                          failure.getClass().getSimpleName());
                      return ConsumeResult.FAILURE;
                    }
                  })
              .build();
    } catch (Exception failure) {
      log.warn(
          "Notification consumer will reconnect failure={}", failure.getClass().getSimpleName());
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
