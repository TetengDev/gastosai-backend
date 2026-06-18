-- User messages from the public Contact / Feedback pages (anonymous-friendly).
CREATE TABLE submissions (
    id         BIGSERIAL PRIMARY KEY,
    type       VARCHAR(20)   NOT NULL,
    name       VARCHAR(100),
    email      VARCHAR(200),
    message    VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP     NOT NULL,
    handled    BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_submissions_created_at ON submissions (created_at DESC);
