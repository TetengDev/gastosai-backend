-- The language the AI writes in, chosen per user. Two independent settings: insight_language
-- covers the generated monthly summary and recommendations, chat_language covers the assistant's
-- replies — a user may want English insights and a Filipino assistant, or the reverse.
-- BCP-47 codes, allow-listed in the application to 'en' and 'fil'.
-- Nullable: NULL means the user has not chosen, and English is used.
-- Expand step: additive, nullable, no backfill needed.
ALTER TABLE users ADD COLUMN insight_language VARCHAR(16);
ALTER TABLE users ADD COLUMN chat_language VARCHAR(16);
