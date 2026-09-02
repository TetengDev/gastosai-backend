-- Expand step (TEN-327): record on the row whether a category was provided by the system or
-- created by the user, so the plan category cap can count user-created rows only.
--
-- Expand-contract: a new nullable-in-practice column with a NOT NULL DEFAULT FALSE. Nothing is
-- renamed, dropped or retyped, and code deployed before this migration never reads the column, so
-- old and new application versions both work against this shape. The UPDATE below is a one-time
-- backfill of the column this same file adds — it cannot change a value any deployed code has
-- read. Run scripts/backup-before-migrate.sh (or .ps1) first regardless: the rule is the rule.
--
-- Why the column exists. CategorySeedService creates 13 starter categories at registration, while
-- the account is still FREE against a cap of 5 (CategoryLimitProperties.free). Since TEN-319 the
-- cap is enforced on every creation path including the incidental ones, so with
-- gastos.monetization.enforce=true seeding fails at the sixth starter and registration fails with
-- it. The decision (TEN-327) is that starters are system-provided and do not consume the cap: a
-- FREE account keeps all 13 and may create 5 of its own. The cap stays 5.
--
-- The distinction has to be persisted rather than inferred from the name at read time. A user may
-- rename a starter, delete one, or legitimately create a category that happens to be named
-- "Vacation"; none of those may change how the row counts.

ALTER TABLE categories ADD COLUMN system_provided BOOLEAN NOT NULL DEFAULT FALSE;

-- One-time backfill. For rows that already exist the name is the only signal there is, so this is
-- the single place where the inference the rest of the change refuses is permitted — it runs once,
-- against history, and never again at read time.
--
-- Two knowingly imprecise edges, both preferred to the alternative:
--   * A user who renamed a starter before this migration keeps that row as user-created. It counts
--     against their 5. Undercounting the cap for a renamed starter would be the worse error.
--   * A user who created a category by hand with exactly a starter's name has it marked
--     system-provided. It stops counting. This favours the user and cannot lock anybody out.
-- Matched case-insensitively because the unique index on (user_id, lower(name)) means a user's
-- "meal plan" and "Meal Plan" are the same row; the seeder wrote the mixed-case spelling.
UPDATE categories
SET system_provided = TRUE
WHERE lower(name) IN (
    'cleaning essentials',
    'date',
    'extras',
    'family contributions',
    'hygiene essentials',
    'meal plan',
    'monthly personal',
    'monthly utilities',
    'training/upskilling',
    'transaction fees',
    'transportation',
    'uncategorized',
    'vacation'
);
