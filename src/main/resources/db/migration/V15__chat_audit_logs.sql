CREATE TABLE chat_audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    conversation_id BIGINT,
    tool_name       VARCHAR(64)  NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    detail          VARCHAR(200),
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT fk_chat_audit_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_chat_audit_user_created ON chat_audit_logs (user_id, created_at);
