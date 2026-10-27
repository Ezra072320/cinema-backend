package edu.czu.cinema.domain.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.domain.order.enums.OrderStatus;
import edu.czu.cinema.domain.order.event.OrderEvent;
import edu.czu.cinema.domain.order.mapper.OrderMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cinema.messaging.enabled", havingValue = "true")
public class OrderTimeoutConsumer implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(OrderTimeoutConsumer.class);
  private final OrderMapper orders;
  private final OrderService service;
  private final ObjectMapper json;
  private final String endpoints;
  private PushConsumer consumer;

  public OrderTimeoutConsumer(
      OrderMapper orders,
      OrderService service,
      ObjectMapper json,
      @Value("${cinema.messaging.endpoints:rocketmq-broker:8081}") String endpoints) {
    this.orders = orders;
    this.service = service;
    this.json = json;
    this.endpoints = endpoints;
  }

  /** A message is only a reminder: deadline and current state always come from Trade's database. */
  public boolean handle(OrderEvent event) {
    if (event.kind() != OrderEvent.Kind.PAYMENT_TIMEOUT)
      throw new IllegalArgumentException("Wrong timeout event");
    var order = orders.selectById(event.orderId());
    if (order == null || !OrderStatus.isPending(order.getStatus())) return true;
    if (order.getCreateTime() == null
        || LocalDateTime.now()
            .isBefore(order.getCreateTime().plusMinutes(OrderService.PAY_TIMEOUT_MINUTES)))
      return false;
    service.closeIfExpired(order.getId());
    return true;
  }

  @Scheduled(fixedDelay = 10_000, initialDelay = 3000, scheduler = "messagingScheduler")
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
              .setConsumerGroup("cinema-trade-timeouts")
              .setSubscriptionExpressions(
                  Map.of(
                      "cinema-order-timeouts", new FilterExpression("*", FilterExpressionType.TAG)))
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
                      return handle(json.readValue(bytes, OrderEvent.class))
                          ? ConsumeResult.SUCCESS
                          : ConsumeResult.FAILURE;
                    } catch (Exception failure) {
                      log.warn(
                          "Order timeout delivery failed messageId={} attempt={} failure={}",
                          message.getMessageId(),
                          message.getDeliveryAttempt(),
                          failure.getClass().getSimpleName());
                      return ConsumeResult.FAILURE;
                    }
                  })
              .build();
    } catch (Exception failure) {
      log.warn(
          "Order timeout consumer will reconnect failure={}", failure.getClass().getSimpleName());
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
