package edu.czu.cinema.notify;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Pushes invalidations only; the authenticated REST query remains the source of notification data.
 */
@Component
public class NotificationSocket extends TextWebSocketHandler {
  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    Long userId = (Long) session.getAttributes().get("userId");
    if (userId == null
        || sessions.size() >= 200
        || sessions.values().stream()
                .filter(s -> userId.equals(s.getAttributes().get("userId")))
                .count()
            >= 2) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    session.getAttributes().put("expiresAt", System.currentTimeMillis() + 300_000);
    session.getAttributes().put("lastPing", System.currentTimeMillis());
    sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 3000, 4096));
    session.sendMessage(new TextMessage("{\"type\":\"notifications.connected\"}"));
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    sessions.remove(session.getId());
  }

  public void changed(Long userId) {
    for (var session : sessions.values()) {
      if (!userId.equals(session.getAttributes().get("userId"))) continue;
      try {
        session.sendMessage(new TextMessage("{\"type\":\"notifications.changed\"}"));
      } catch (Exception ignored) {
        discard(session);
      }
    }
  }

  @Scheduled(fixedDelay = 10_000)
  public void expire() {
    for (var session : sessions.values()) {
      if (!session.isOpen()
          || (Long) session.getAttributes().get("expiresAt") < System.currentTimeMillis()) {
        discard(session);
      } else if (System.currentTimeMillis() - (Long) session.getAttributes().get("lastPing")
          >= 30_000) {
        try {
          session.sendMessage(new TextMessage("{\"type\":\"notifications.heartbeat\"}"));
          session.getAttributes().put("lastPing", System.currentTimeMillis());
        } catch (Exception ignored) {
          discard(session);
        }
      }
    }
  }

  private void discard(WebSocketSession session) {
    sessions.remove(session.getId());
    try {
      session.close(CloseStatus.NORMAL);
    } catch (IOException ignored) {
    }
  }
}
