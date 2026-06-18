-- User's preferred default category (name). Preselected when adding an expense.
-- Nullable: falls back to "Uncategorized" when unset.
ALTER TABLE users ADD COLUMN default_category_name VARCHAR(50);
