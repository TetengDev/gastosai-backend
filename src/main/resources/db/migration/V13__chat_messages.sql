CREATE TABLE chat_messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT       NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    content         TEXT,
    tool_name       VARCHAR(64),
    response_type   VARCHAR(24),
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT fk_chat_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

CREATE INDEX idx_chat_messages_conversation_created ON chat_messages (conversation_id, created_at);
