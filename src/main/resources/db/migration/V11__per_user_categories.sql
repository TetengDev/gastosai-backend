-- Postgres-only; not reversible (global rows deleted). Tests use entity schema (Flyway off on H2).

ALTER TABLE categories ADD COLUMN user_id BIGINT REFERENCES users(id) ON DELETE CASCADE;

-- Drop the global unique-on-name constraint BEFORE cloning categories per user,
-- otherwise the per-user clones (same name, different user) collide on it.
DROP INDEX IF EXISTS uk_categories_name;
ALTER TABLE categories DROP CONSTRAINT IF EXISTS categories_name_key;
ALTER TABLE categories DROP CONSTRAINT IF EXISTS uk_categories_name;

DO $$
DECLARE
    rec RECORD;
    cat RECORD;
    new_cat_id BIGINT;
BEGIN
    CREATE TEMP TABLE cat_id_map (
        user_id    BIGINT,
        old_cat_id BIGINT,
        new_cat_id BIGINT
    ) ON COMMIT DROP;

    FOR rec IN SELECT id FROM users LOOP
        FOR cat IN SELECT id, name, icon FROM categories WHERE user_id IS NULL LOOP
            INSERT INTO categories (name, icon, user_id)
            VALUES (cat.name, cat.icon, rec.id)
            RETURNING id INTO new_cat_id;

            INSERT INTO cat_id_map (user_id, old_cat_id, new_cat_id)
            VALUES (rec.id, cat.id, new_cat_id);
        END LOOP;
    END LOOP;

    UPDATE expenses e
    SET category_id = m.new_cat_id
    FROM cat_id_map m
    WHERE m.user_id = e.user_id
      AND m.old_cat_id = e.category_id;

    UPDATE budgets b
    SET category_id = m.new_cat_id
    FROM cat_id_map m
    WHERE m.user_id = b.user_id
      AND m.old_cat_id = b.category_id;

    UPDATE recurring_expenses r
    SET category_id = m.new_cat_id
    FROM cat_id_map m
    WHERE m.user_id = r.user_id
      AND m.old_cat_id = r.category_id;
END $$;

DELETE FROM categories WHERE user_id IS NULL;

ALTER TABLE categories ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE categories ADD CONSTRAINT uq_categories_user_name UNIQUE (user_id, name);
CREATE UNIQUE INDEX IF NOT EXISTS uq_categories_user_lower_name ON categories (user_id, lower(name));
