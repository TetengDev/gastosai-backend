-- Track the last entity a conversation referenced, so follow-ups ("delete it", "make it 500")
-- can resolve the referent deterministically.
ALTER TABLE conversations ADD COLUMN last_entity_type VARCHAR(32);
ALTER TABLE conversations ADD COLUMN last_entity_id   BIGINT;
