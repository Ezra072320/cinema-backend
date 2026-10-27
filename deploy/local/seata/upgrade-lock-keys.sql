-- The application uses up to 64 characters for service_operation ids.
-- Seata's sample DDL limits pk to 36; keep web/AI idempotency keys intact.
ALTER TABLE lock_table MODIFY pk VARCHAR(128);
ALTER TABLE lock_table MODIFY row_key VARCHAR(256) NOT NULL;
