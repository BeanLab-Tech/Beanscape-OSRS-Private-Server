-- V10: Discord OAuth2.0 registration (Ferox buildout Phase 1)
-- Binds an account to the Discord user who registered via the web service.
-- discord_id is UNIQUE: one account per Discord user (re-registration is a no-op).
ALTER TABLE accounts ADD COLUMN discord_id INTEGER UNIQUE;
ALTER TABLE accounts ADD COLUMN discord_username TEXT;
CREATE INDEX idx_accounts_discord_id ON accounts (discord_id);
