-- Opt-in flag for the rule-based budgeting feature (hidden until the user turns it on).
ALTER TABLE budget_rules ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT FALSE;
