package edu.czu.cinema.benefit;

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
 * Coupon queries stay available while the broker is unavailable; lifecycle facts retry durably.
 */
@Component
public class CouponOrderConsumer implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(CouponOrderConsumer.class);
  private final CouponService coupons;
  private final ObjectMapper json;
  private final String endpoints;
  private PushConsumer consumer;

  public CouponOrderConsumer(
      CouponService coupons,
      ObjectMapper json,
      @Value("${cinema.messaging.endpoints}") String endpoints) {
    this.coupons = coupons;
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
              .setConsumerGroup("cinema-benefit-orders")
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
                      coupons.receive(
                          event,
                          HexFormat.of()
                              .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                      return ConsumeResult.SUCCESS;
                    } catch (Exception failure) {
                      log.warn(
                          "Coupon fact delivery failed messageId={} attempt={} failure={}",
                          message.getMessageId(),
                          message.getDeliveryAttempt(),
                          failure.getClass().getSimpleName());
                      return ConsumeResult.FAILURE;
                    }
                  })
              .build();
    } catch (Exception failure) {
      log.warn(
          "Coupon consumer will reconnect failure={}", failure.getClass().getSimpleName());
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
