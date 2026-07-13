-- Two concurrent deliveries of the same webhook event must not double-insert a
-- subscription: the second insert with the same (user_id, provider_ref) fails,
-- rolls back as 5xx, and the provider's retry hits the idempotent already-PAID path.
-- NULL provider_ref rows (seeded/manual subscriptions) are unaffected (NULLS DISTINCT).
ALTER TABLE user_subscriptions
    ADD CONSTRAINT uq_user_subscriptions_user_provider_ref UNIQUE (user_id, provider_ref);
