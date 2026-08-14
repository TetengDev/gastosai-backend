-- Backfill step of the integer-centavos migration (ROADMAP E6 step 2, KNOWN-GAPS §1).
--
-- V23 added a nullable BIGINT centavos column beside every money-bearing NUMERIC(19,4) column
-- and deliberately rewrote no rows. This migration fills those columns, keeps them filled, and
-- proves the two representations agree. Nothing is renamed, dropped or retyped here either:
-- the decimal columns stay authoritative and every deployed code path keeps reading them. The
-- contract step that drops them comes only after both clients have migrated to /api/v2.
--
-- ROUNDING RULE
-- -------------
-- centavos = round(decimal * 100), where PostgreSQL's round(numeric) rounds a tie AWAY FROM
-- ZERO. That is exactly BigDecimal's RoundingMode.HALF_UP, which is the rule the application
-- already applies everywhere it reduces money to two decimal places (ChatActionService,
-- AlertService, AiInsightService, AppDataLoader). One rule, stated once, applied to every
-- column below and to every future row through the triggers.
--
-- A residue is possible at all only because the decimal columns are scale 4: a row holding
-- 10.1250 has no exact centavo value and becomes 1013. Real rows come from parsed user input
-- at two decimal places, so the expected residue count is zero; the rule exists so that the
-- odd imported or computed row resolves the same way every time rather than erroring.
--
-- WHY TRIGGERS
-- ------------
-- A one-shot UPDATE is true only until the next INSERT. Deployed v1 code does not know the
-- centavos columns exist, so without a bridge every row written between this migration and
-- the /api/v2 cutover would land NULL and the reconciliation this issue demands would be
-- stale the moment it passed. A BEFORE INSERT OR UPDATE trigger per table derives the
-- centavos value from the decimal one on every write, which makes "decimal is authoritative"
-- a property of the database rather than a convention. The triggers and the CHECK constraints
-- are dropped together with the decimal columns in the contract step.
--
-- EXPAND-CONTRACT
-- ---------------
-- Step: BACKFILL. No column is renamed, dropped or retyped. NOT NULL and the reconciliation
-- CHECKs are only added after the same statement batch has filled every row, and the triggers
-- are created before them so that concurrent and subsequent writes cannot violate either.
-- Pre-migration backup path: scripts/backup-before-migrate.sh (fails closed).
--
-- Exchange rates remain untouched: a rate is not money and does not round to centavos.

-- ---------------------------------------------------------------------------------------------
-- 1. Triggers first, so that any row written while this migration runs is already consistent.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION sync_expenses_centavos() RETURNS trigger AS $$
BEGIN
    NEW.amount_centavos := round(NEW.amount * 100);
    NEW.amount_in_base_currency_centavos := round(NEW.amount_in_base_currency * 100);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_expenses_centavos
    BEFORE INSERT OR UPDATE ON expenses
    FOR EACH ROW EXECUTE FUNCTION sync_expenses_centavos();

CREATE OR REPLACE FUNCTION sync_recurring_expenses_centavos() RETURNS trigger AS $$
BEGIN
    NEW.amount_centavos := round(NEW.amount * 100);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_recurring_expenses_centavos
    BEFORE INSERT OR UPDATE ON recurring_expenses
    FOR EACH ROW EXECUTE FUNCTION sync_recurring_expenses_centavos();

CREATE OR REPLACE FUNCTION sync_budgets_centavos() RETURNS trigger AS $$
BEGIN
    NEW.amount_limit_centavos := round(NEW.amount_limit * 100);
    NEW.amount_limit_in_base_currency_centavos := round(NEW.amount_limit_in_base_currency * 100);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_budgets_centavos
    BEFORE INSERT OR UPDATE ON budgets
    FOR EACH ROW EXECUTE FUNCTION sync_budgets_centavos();

CREATE OR REPLACE FUNCTION sync_budget_rules_centavos() RETURNS trigger AS $$
BEGIN
    NEW.monthly_income_centavos := round(NEW.monthly_income * 100);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_budget_rules_centavos
    BEFORE INSERT OR UPDATE ON budget_rules
    FOR EACH ROW EXECUTE FUNCTION sync_budget_rules_centavos();

CREATE OR REPLACE FUNCTION sync_savings_goals_centavos() RETURNS trigger AS $$
BEGIN
    NEW.target_amount_centavos := round(NEW.target_amount * 100);
    NEW.saved_amount_centavos := round(NEW.saved_amount * 100);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_savings_goals_centavos
    BEFORE INSERT OR UPDATE ON savings_goals
    FOR EACH ROW EXECUTE FUNCTION sync_savings_goals_centavos();

-- ---------------------------------------------------------------------------------------------
-- 2. Backfill every existing row. Every source column is NOT NULL, so no row can be skipped.
-- ---------------------------------------------------------------------------------------------

UPDATE expenses
   SET amount_centavos                  = round(amount * 100),
       amount_in_base_currency_centavos = round(amount_in_base_currency * 100);

UPDATE recurring_expenses
   SET amount_centavos = round(amount * 100);

UPDATE budgets
   SET amount_limit_centavos                  = round(amount_limit * 100),
       amount_limit_in_base_currency_centavos = round(amount_limit_in_base_currency * 100);

UPDATE budget_rules
   SET monthly_income_centavos = round(monthly_income * 100);

UPDATE savings_goals
   SET target_amount_centavos = round(target_amount * 100),
       saved_amount_centavos  = round(saved_amount * 100);

-- ---------------------------------------------------------------------------------------------
-- 3. Make the invariant structural. ADD CONSTRAINT validates every existing row, so these
--    statements are themselves the first reconciliation: a single mismatched row fails the
--    migration and the transaction rolls back.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE expenses
    ALTER COLUMN amount_centavos SET NOT NULL,
    ALTER COLUMN amount_in_base_currency_centavos SET NOT NULL,
    ADD CONSTRAINT ck_expenses_amount_centavos
        CHECK (amount_centavos = round(amount * 100)),
    ADD CONSTRAINT ck_expenses_amount_base_centavos
        CHECK (amount_in_base_currency_centavos = round(amount_in_base_currency * 100));

ALTER TABLE recurring_expenses
    ALTER COLUMN amount_centavos SET NOT NULL,
    ADD CONSTRAINT ck_recurring_expenses_amount_centavos
        CHECK (amount_centavos = round(amount * 100));

ALTER TABLE budgets
    ALTER COLUMN amount_limit_centavos SET NOT NULL,
    ALTER COLUMN amount_limit_in_base_currency_centavos SET NOT NULL,
    ADD CONSTRAINT ck_budgets_amount_limit_centavos
        CHECK (amount_limit_centavos = round(amount_limit * 100)),
    ADD CONSTRAINT ck_budgets_amount_limit_base_centavos
        CHECK (amount_limit_in_base_currency_centavos = round(amount_limit_in_base_currency * 100));

ALTER TABLE budget_rules
    ALTER COLUMN monthly_income_centavos SET NOT NULL,
    ADD CONSTRAINT ck_budget_rules_monthly_income_centavos
        CHECK (monthly_income_centavos = round(monthly_income * 100));

ALTER TABLE savings_goals
    ALTER COLUMN target_amount_centavos SET NOT NULL,
    ALTER COLUMN saved_amount_centavos SET NOT NULL,
    ADD CONSTRAINT ck_savings_goals_target_amount_centavos
        CHECK (target_amount_centavos = round(target_amount * 100)),
    ADD CONSTRAINT ck_savings_goals_saved_amount_centavos
        CHECK (saved_amount_centavos = round(saved_amount * 100));

-- ---------------------------------------------------------------------------------------------
-- 4. The reconciliation query, run as part of the migration so the proof is recorded rather
--    than remembered. It is the same query to run by hand against any database before the next
--    step starts -- lift the UNION ALL body out of this block; a zero-row result is the proof:
--
--      SELECT 'expenses.amount' AS source_column, id FROM expenses
--       WHERE amount_centavos IS DISTINCT FROM round(amount * 100)
--      UNION ALL ... (one arm per column below)
--
-- ---------------------------------------------------------------------------------------------

DO $$
DECLARE
    mismatches bigint;
BEGIN
    SELECT count(*) INTO mismatches FROM (
        SELECT id FROM expenses
         WHERE amount_centavos IS DISTINCT FROM round(amount * 100)
        UNION ALL
        SELECT id FROM expenses
         WHERE amount_in_base_currency_centavos
               IS DISTINCT FROM round(amount_in_base_currency * 100)
        UNION ALL
        SELECT id FROM recurring_expenses
         WHERE amount_centavos IS DISTINCT FROM round(amount * 100)
        UNION ALL
        SELECT id FROM budgets
         WHERE amount_limit_centavos IS DISTINCT FROM round(amount_limit * 100)
        UNION ALL
        SELECT id FROM budgets
         WHERE amount_limit_in_base_currency_centavos
               IS DISTINCT FROM round(amount_limit_in_base_currency * 100)
        UNION ALL
        SELECT id FROM budget_rules
         WHERE monthly_income_centavos IS DISTINCT FROM round(monthly_income * 100)
        UNION ALL
        SELECT id FROM savings_goals
         WHERE target_amount_centavos IS DISTINCT FROM round(target_amount * 100)
        UNION ALL
        SELECT id FROM savings_goals
         WHERE saved_amount_centavos IS DISTINCT FROM round(saved_amount * 100)
    ) AS reconciliation;

    IF mismatches <> 0 THEN
        RAISE EXCEPTION
            'centavos backfill reconciliation failed: % row(s) disagree with their decimal column',
            mismatches;
    END IF;
END;
$$;
