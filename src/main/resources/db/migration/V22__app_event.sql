CREATE TABLE app_event (
    id           BIGSERIAL PRIMARY KEY,
    event_type   VARCHAR(40)  NOT NULL,
    severity     VARCHAR(10)  NOT NULL,
    request_id   VARCHAR(64),
    user_id      BIGINT,
    path         VARCHAR(200),
    http_status  INTEGER,
    message      VARCHAR(500),
    detail       TEXT,
    created_at   TIMESTAMP    NOT NULL
);

CREATE INDEX idx_app_event_created ON app_event (created_at);
CREATE INDEX idx_app_event_type_created ON app_event (event_type, created_at);
