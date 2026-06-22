-- Recurring budgets: when true, a budget is carried forward to later months automatically
-- (materialized on first access of a month that has no row for the category).
ALTER TABLE budgets ADD COLUMN recurring BOOLEAN NOT NULL DEFAULT FALSE;
