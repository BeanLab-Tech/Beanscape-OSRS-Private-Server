-- V10: Discord OAuth2.0 registration (Ferox buildout Phase 1)
-- Binds an account to the Discord user who registered via the web service.
-- One account per Discord user. discord_id stays nullable (accounts created
-- by other means have NULL) so a partial UNIQUE index enforces the binding
-- without tripping SQLite's "cannot add UNIQUE column to populated table".
ALTER TABLE accounts ADD COLUMN discord_id INTEGER;
ALTER TABLE accounts ADD COLUMN discord_username TEXT;
CREATE UNIQUE INDEX idx_accounts_discord_id ON accounts (discord_id) WHERE discord_id IS NOT NULL;
