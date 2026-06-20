CREATE TABLE magic_link_tokens (
    id          BIGSERIAL     PRIMARY KEY,
    email       VARCHAR(200)  NOT NULL,
    token_hash  VARCHAR(64)   NOT NULL,
    expires_at  TIMESTAMP     NOT NULL,
    used_at     TIMESTAMP,
    created_at  TIMESTAMP     NOT NULL
);

CREATE UNIQUE INDEX idx_magic_link_token_hash ON magic_link_tokens (token_hash);
CREATE INDEX idx_magic_link_email_unused ON magic_link_tokens (email, expires_at) WHERE used_at IS NULL;
