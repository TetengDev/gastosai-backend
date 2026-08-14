-- Expand step of the integer-centavos migration (ROADMAP E6 step 1, KNOWN-GAPS §1).
--
-- Adds an integer-centavos column beside every money-bearing NUMERIC(19,4) column on the
-- expense, budget, goal and recurring tables. Nothing is renamed, dropped or retyped: the
-- decimal columns stay authoritative and every deployed code path keeps reading them.
--
-- The new columns are nullable BIGINT and carry no default on purpose -- an expand step must
-- not rewrite existing rows. The backfill and the NOT NULL / reconciliation constraints are
-- the next step in the sequence; the contract step that drops the decimal columns comes only
-- after both clients have migrated to /api/v2.
--
-- Exchange rates are deliberately untouched: a rate is not money and does not round to
-- centavos.

-- expenses
ALTER TABLE expenses
    ADD COLUMN amount_centavos                  BIGINT,
    ADD COLUMN amount_in_base_currency_centavos BIGINT;

-- recurring_expenses
ALTER TABLE recurring_expenses
    ADD COLUMN amount_centavos BIGINT;

-- budgets
ALTER TABLE budgets
    ADD COLUMN amount_limit_centavos                  BIGINT,
    ADD COLUMN amount_limit_in_base_currency_centavos BIGINT;

-- budget_rules -- the per-user income a rule splits is money on a budget table
ALTER TABLE budget_rules
    ADD COLUMN monthly_income_centavos BIGINT;

-- savings_goals
ALTER TABLE savings_goals
    ADD COLUMN target_amount_centavos BIGINT,
    ADD COLUMN saved_amount_centavos  BIGINT;
