ALTER TABLE alerts ADD COLUMN IF NOT EXISTS recurring_expense_id BIGINT REFERENCES recurring_expenses(id) ON DELETE SET NULL;
