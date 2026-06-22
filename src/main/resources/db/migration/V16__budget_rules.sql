-- Rule-based budgeting (e.g. 50-30-20): a per-user income split across NEEDS/WANTS/SAVINGS buckets,
-- plus an optional bucket tag on each category so spend can be aggregated per bucket.

ALTER TABLE categories ADD COLUMN bucket VARCHAR(16);

CREATE TABLE budget_rules (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    rule_type      VARCHAR(32) NOT NULL,
    monthly_income NUMERIC(19, 4) NOT NULL DEFAULT 0,
    needs_pct      INTEGER NOT NULL,
    wants_pct      INTEGER NOT NULL,
    savings_pct    INTEGER NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP NOT NULL DEFAULT now()
);
