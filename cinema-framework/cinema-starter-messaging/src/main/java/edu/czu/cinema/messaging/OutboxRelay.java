package edu.czu.cinema.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cinema.messaging.enabled", havingValue = "true")
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private final OutboxStore store;
  private final RocketMqPublisher publisher;

  public OutboxRelay(OutboxStore store, RocketMqPublisher publisher) {
    this.store = store;
    this.publisher = publisher;
  }

  @Scheduled(
      fixedDelayString = "${cinema.messaging.relay-interval-ms:2000}",
      scheduler = "messagingScheduler")
  public void relay() {
    for (var delivery : store.claim(System.currentTimeMillis())) {
      try {
        publisher.publish(delivery);
        if (!store.sent(delivery)) log.warn("Outbox lease changed eventId={}", delivery.eventId());
      } catch (Exception failure) {
        // Log only identity and failure type, never raw payloads or client configuration.
        store.retry(delivery, failure.getClass().getSimpleName());
        log.warn(
            "Outbox will retry eventId={} failure={}",
            delivery.eventId(),
            failure.getClass().getSimpleName());
      }
    }
  }
}
