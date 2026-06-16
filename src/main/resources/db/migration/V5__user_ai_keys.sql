ALTER TABLE users ADD COLUMN IF NOT EXISTS openai_api_key_enc TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS claude_api_key_enc TEXT;
