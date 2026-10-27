package edu.czu.cinema.ai.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Rechecks activity under a row lock; each session and its children are one transaction. */
@Component @RequiredArgsConstructor
public class AiRetentionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public record Candidate(String chatId, long userId) {}

    public List<Candidate> candidates(LocalDateTime threshold) {
        return jdbc.query("SELECT chat_id,user_id FROM ai_session WHERE update_time<? ORDER BY update_time,chat_id LIMIT 200",
                (rs,row) -> new Candidate(rs.getString(1),rs.getLong(2)),threshold);
    }

    public boolean purge(Candidate candidate, LocalDateTime threshold) {
        var deletion = new TransactionTemplate(tx.getTransactionManager());
        // Finish inside the 30s generation lease, including row-lock waits.
        deletion.setTimeout(10);
        return Boolean.TRUE.equals(deletion.execute(status -> {
            var dates = jdbc.query("SELECT update_time FROM ai_session WHERE chat_id=? AND user_id=? FOR UPDATE",
                    (rs,row) -> rs.getTimestamp(1).toLocalDateTime(),candidate.chatId(),candidate.userId());
            if (dates.isEmpty() || !dates.getFirst().isBefore(threshold)) return false;
            jdbc.update("DELETE FROM ai_action WHERE chat_id=? AND user_id=?",candidate.chatId(),candidate.userId());
            jdbc.update("DELETE FROM ai_message WHERE chat_id=? AND user_id=?",candidate.chatId(),candidate.userId());
            int deleted = jdbc.update("DELETE FROM ai_session WHERE chat_id=? AND user_id=? AND update_time<?",
                    candidate.chatId(),candidate.userId(),threshold);
            if (deleted != 1) throw new IllegalStateException("Session changed during retention transaction");
            return true;
        }));
    }
}
