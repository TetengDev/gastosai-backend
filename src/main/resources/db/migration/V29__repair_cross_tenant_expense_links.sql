-- Backfill step (TEN-318): repair expense rows whose category or project belongs to a different
-- user than the expense itself.
--
-- Expand-contract: this is a pure backfill. No column is added, renamed, dropped or retyped, and
-- no constraint changes — deployed code before and after reads exactly the same shape. Only the
-- values of expenses.category_id / expenses.project_id move, and only for rows that are already
-- wrong. Run scripts/backup-before-migrate.sh (or .ps1) first: the repair rewrites data, so the
-- pre-migration dump is the only way back to the disagreeing state.
--
-- Where the disagreement came from: category and tag resolution used to run against the *acting*
-- user rather than the expense's owner, so an ADMIN editing someone else's expense could file it
-- under a category of their own. TEN-314 closed that path, so nothing creates new cross-tenant
-- links; the rows it already wrote are still there, and they are a live hazard — deleting such a
-- category walked the other tenant's expense (TEN-318). The service-layer fix scopes that lookup
-- to the category's owner; this script cleans up behind it.
--
-- The repair re-points each mismatched row at the *owner's* row of the same name, cloning it when
-- the owner has none. Same shape as V11, and for the same reason: the user-visible meaning of the
-- expense ("Food", "Acme") is preserved, where dropping the link to NULL would silently lose it.

-- The clone deliberately ignores the plan's category cap (CategoryService#enforceCategoryLimit).
-- The cap governs what a user may create; this is a repair of a row they already have and can
-- already see the spending of, and refusing it would leave the expense pointing at somebody else's
-- category — the bug — to protect a limit. A capped user can end up one or two categories over
-- until they tidy up, which is the lesser of the two surprises.

-- 1. Give every affected owner a category of their own with the same name, where they do not have
--    one already. DISTINCT ON collapses the candidates to one row per (user, lower(name)) so the
--    insert cannot collide with uq_categories_user_lower_name; ordering by c.id makes the winner
--    the oldest spelling, which is deterministic rather than whatever the planner emits first.
INSERT INTO categories (name, icon, bucket, user_id)
SELECT DISTINCT ON (e.user_id, lower(c.name)) c.name, c.icon, c.bucket, e.user_id
FROM expenses e
JOIN categories c ON c.id = e.category_id
WHERE c.user_id <> e.user_id
  AND NOT EXISTS (
      SELECT 1 FROM categories own
      WHERE own.user_id = e.user_id
        AND lower(own.name) = lower(c.name))
ORDER BY e.user_id, lower(c.name), c.id;

-- 2. Re-point the mismatched expenses. Step 1 guarantees the owner's category exists, so this
--    leaves no row behind; the foreign category itself is untouched — it is its owner's, and
--    deleting it is their business.
UPDATE expenses e
SET category_id = own.id
FROM categories mismatched, categories own
WHERE mismatched.id = e.category_id
  AND mismatched.user_id <> e.user_id
  AND own.user_id = e.user_id
  AND lower(own.name) = lower(mismatched.name);

-- 3. The same two steps for the project/client tag. Cloning rather than nulling matters more here
--    than for categories: an untagged expense drops out of the per-project totals entirely (the
--    report inner-joins the tag), so a NULL would quietly change what a freelancer bills.
INSERT INTO projects (name, user_id)
SELECT DISTINCT ON (e.user_id, lower(p.name)) p.name, e.user_id
FROM expenses e
JOIN projects p ON p.id = e.project_id
WHERE p.user_id <> e.user_id
  AND NOT EXISTS (
      SELECT 1 FROM projects own
      WHERE own.user_id = e.user_id
        AND lower(own.name) = lower(p.name))
ORDER BY e.user_id, lower(p.name), p.id;

UPDATE expenses e
SET project_id = own.id
FROM projects mismatched, projects own
WHERE mismatched.id = e.project_id
  AND mismatched.user_id <> e.user_id
  AND own.user_id = e.user_id
  AND lower(own.name) = lower(mismatched.name);

-- 4. Fail closed. The migration's whole purpose is that no expense disagrees with its category or
--    its tag afterwards; if one still does, the transaction must roll back rather than leave a
--    half-repaired database that looks migrated.
DO $$
DECLARE
    remaining BIGINT;
BEGIN
    SELECT count(*) INTO remaining
    FROM expenses e
    LEFT JOIN categories c ON c.id = e.category_id
    LEFT JOIN projects p ON p.id = e.project_id
    WHERE (c.id IS NOT NULL AND c.user_id <> e.user_id)
       OR (p.id IS NOT NULL AND p.user_id <> e.user_id);

    IF remaining > 0 THEN
        RAISE EXCEPTION 'V29 left % expense row(s) still linked across tenants', remaining;
    END IF;
END $$;
