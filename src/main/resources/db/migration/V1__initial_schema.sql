CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT          NOT NULL,
    email         VARCHAR(255)  NOT NULL UNIQUE,
    password      TEXT          NOT NULL,
    role          VARCHAR(20)   NOT NULL DEFAULT 'USER',
    nickname      VARCHAR(50),
    avatar_color  VARCHAR(20),
    created_at    TIMESTAMP     NOT NULL
);

CREATE TABLE categories (
    id    BIGSERIAL PRIMARY KEY,
    name  VARCHAR(50) NOT NULL UNIQUE,
    icon  VARCHAR(50)
);

CREATE TABLE expenses (
    id                       BIGSERIAL PRIMARY KEY,
    amount                   NUMERIC(19, 4)  NOT NULL,
    user_id                  BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id              BIGINT          REFERENCES categories(id),
    date                     TIMESTAMP,
    description              TEXT            NOT NULL,
    expense_type             VARCHAR(20)     NOT NULL DEFAULT 'PERSONAL',
    reimbursable             BOOLEAN         NOT NULL DEFAULT FALSE,
    currency                 VARCHAR(3)      NOT NULL DEFAULT 'PHP',
    exchange_rate            NUMERIC(19, 6)  NOT NULL DEFAULT 1,
    amount_in_base_currency  NUMERIC(19, 4)  NOT NULL
);

CREATE TABLE recurring_expenses (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT          REFERENCES users(id) ON DELETE CASCADE,
    name           VARCHAR(100)    NOT NULL,
    amount         NUMERIC(19, 4)  NOT NULL,
    currency       VARCHAR(3)      NOT NULL DEFAULT 'PHP',
    exchange_rate  NUMERIC(19, 4)  NOT NULL DEFAULT 1,
    category_id    BIGINT          REFERENCES categories(id),
    frequency      VARCHAR(20)     NOT NULL,
    day_of_month   INT,
    day_of_week    INT,
    month_of_year  INT,
    active         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP,
    updated_at     TIMESTAMP
);

CREATE TABLE budgets (
    id                              BIGSERIAL PRIMARY KEY,
    user_id                         BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id                     BIGINT          NOT NULL REFERENCES categories(id),
    budget_month                    VARCHAR(7)      NOT NULL,
    amount_limit                    NUMERIC(19, 4)  NOT NULL,
    currency                        VARCHAR(3)      NOT NULL DEFAULT 'PHP',
    exchange_rate                   NUMERIC(19, 4)  NOT NULL DEFAULT 1,
    amount_limit_in_base_currency   NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    CONSTRAINT uq_budget UNIQUE (user_id, category_id, budget_month)
);

CREATE TABLE savings_goals (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name           TEXT            NOT NULL,
    target_amount  NUMERIC(19, 4)  NOT NULL,
    saved_amount   NUMERIC(19, 4)  NOT NULL,
    target_date    DATE,
    paused         BOOLEAN         NOT NULL DEFAULT FALSE,
    currency       VARCHAR(3)      NOT NULL DEFAULT 'PHP',
    created_at     TIMESTAMP       NOT NULL
);

CREATE TABLE alerts (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type           VARCHAR(30) NOT NULL,
    alert_month    VARCHAR(7)  NOT NULL,
    category_name  TEXT        NOT NULL,
    message        TEXT        NOT NULL,
    severity       VARCHAR(20) NOT NULL,
    is_read        BOOLEAN     NOT NULL DEFAULT FALSE,
    dismissed      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP   NOT NULL,
    updated_at     TIMESTAMP
);
