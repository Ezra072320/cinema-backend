-- Bound the oldest-first retention scan independently of per-user history queries.
CREATE INDEX idx_ai_session_retention ON ai_session(update_time,chat_id);
CREATE INDEX idx_ai_action_retention ON ai_action(status,expires_at,id);
