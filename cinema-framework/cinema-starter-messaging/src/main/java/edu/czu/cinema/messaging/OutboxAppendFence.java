package edu.czu.cinema.messaging;

/** Runs in the append transaction. A participant may hold publication until its final decision. */
public interface OutboxAppendFence {
  void capture(String eventId);
}
