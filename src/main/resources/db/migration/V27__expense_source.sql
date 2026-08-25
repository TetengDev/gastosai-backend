-- Expand step (TEN-175): record how each expense was created.
--
-- Expand-only: a new NOT NULL column with a default, so deployed code that has never heard of
-- `source` keeps inserting valid rows. Nothing is renamed, dropped or retyped.
--
-- Existing rows all become 'MANUAL'. That is a stated default, not a measurement — the routes that
-- wrote them were not recorded, so there is nothing to backfill from, and MANUAL is the value the
-- API has effectively been reporting all along by omission. A user reading a source filter over
-- history should know it only distinguishes rows written after this migration.
--
-- VARCHAR, not a PostgreSQL enum type: the values are an application concern (ExpenseSource) and
-- adding one must not need a migration. The length matches the entity's @Column(length = 20).
ALTER TABLE expenses ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';

-- The filter is always scoped to one user (only an admin sees across users), so user_id leads.
CREATE INDEX idx_expenses_user_source ON expenses (user_id, source);
