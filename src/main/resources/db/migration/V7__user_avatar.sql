-- User's chosen avatar icon (key into the frontend avatar icon set). Nullable:
-- falls back to initials when unset.
ALTER TABLE users ADD COLUMN avatar VARCHAR(40);
