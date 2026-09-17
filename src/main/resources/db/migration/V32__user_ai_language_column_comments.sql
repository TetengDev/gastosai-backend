-- V31 created insight_language / chat_language with a script comment stating the accepted values
-- were allow-listed "to 'en' and 'fil'". Since TEN-388 the allow-list is application configuration
-- and no longer a fixed pair, so that sentence describes a constraint the system does not have.
-- Migrations are append-only, so V31 stays as written; this step attaches the authoritative
-- description to the columns themselves, where anyone inspecting the schema (\d+ users, an ERD
-- tool, information_schema) will read it instead.
--
-- Expand step: COMMENT ON COLUMN only. No DDL, no data movement, nothing deployed code reads.
COMMENT ON COLUMN users.insight_language IS
    'BCP-47 language tag the generated insights are written in. The accepted set is allow-listed in application configuration, not in the schema. NULL means the user has not chosen and the application default is used.';
COMMENT ON COLUMN users.chat_language IS
    'BCP-47 language tag the assistant replies in. The accepted set is allow-listed in application configuration, not in the schema. NULL means the user has not chosen and the application default is used.';
