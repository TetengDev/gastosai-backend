-- Indexes for the hot, user-scoped read paths (reporting, alerts, dashboard). All queries filter by
-- user_id and most also by date or month; these avoid full table scans as data grows.

CREATE INDEX IF NOT EXISTS idx_expenses_user            ON expenses(user_id);
CREATE INDEX IF NOT EXISTS idx_expenses_user_date       ON expenses(user_id, date);
CREATE INDEX IF NOT EXISTS idx_expenses_category        ON expenses(category_id);
CREATE INDEX IF NOT EXISTS idx_budgets_user_month       ON budgets(user_id, budget_month);
CREATE INDEX IF NOT EXISTS idx_recurring_user           ON recurring_expenses(user_id);
CREATE INDEX IF NOT EXISTS idx_alerts_user_read         ON alerts(user_id, is_read);
CREATE INDEX IF NOT EXISTS idx_savings_goals_user       ON savings_goals(user_id);
