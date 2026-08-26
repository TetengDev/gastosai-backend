-- Expand step (TEN-178): attribute an expense to a project or client.
--
-- Expand-only: one new table and one new nullable column. Nothing is renamed, dropped or retyped,
-- and deployed code that has never heard of a tag keeps inserting valid rows — an untagged expense
-- is the normal case, not a migration gap, so there is nothing to backfill.
--
-- One table for both words in "project or client": a freelancer tags with whatever they bill
-- against, and the two behave identically here. See the Project entity.
CREATE TABLE projects (
    id      BIGSERIAL PRIMARY KEY,
    name    VARCHAR(60) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE
);

-- Per user, and case-insensitive, so "Acme" and "acme" cannot become two tags that split one
-- engagement's total in half. Mirrors uq_categories_user_lower_name from V11.
CREATE UNIQUE INDEX uq_projects_user_lower_name ON projects (user_id, lower(name));

-- ON DELETE SET NULL, not CASCADE: deleting a tag detaches its expenses, it does not delete them.
-- Losing spending history because a tag was tidied away is far worse than an untagged expense.
ALTER TABLE expenses ADD COLUMN project_id BIGINT REFERENCES projects(id) ON DELETE SET NULL;

-- The filter and the per-tag totals are always scoped to one user, so user_id leads — the same
-- shape as idx_expenses_user_source in V27.
CREATE INDEX idx_expenses_user_project ON expenses (user_id, project_id);
