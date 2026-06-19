CREATE TABLE ai_usage (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT        NOT NULL,
    provider          VARCHAR(20)   NOT NULL,
    model             VARCHAR(100)  NOT NULL,
    feature           VARCHAR(30)   NOT NULL,
    input_tokens      INTEGER,
    output_tokens     INTEGER,
    total_tokens      INTEGER,
    estimated_cost_usd NUMERIC(12, 6),
    status            VARCHAR(10)   NOT NULL,
    error_code        VARCHAR(100),
    created_at        TIMESTAMP     NOT NULL
);

CREATE INDEX idx_ai_usage_user_created ON ai_usage (user_id, created_at);
