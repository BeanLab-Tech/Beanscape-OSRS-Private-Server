-- Upstream V3 ships the `dev` realm with developer conveniences that are unsafe for a server real
-- players log into:
--   ignore_passwords = 1 (with dev_mode = 1): ANY password is accepted for ANY account, and
--     two-factor checks are skipped.
--   dev_mode = 1: every newly created account is given the `owner` mod level.
--   require_registration = 0: anyone can create an account just by logging in.
-- BeanLab registers accounts through the Discord bot, so enforce real authentication here.
--
-- Accounts that already received `owner` from dev_mode keep it (mod level is saved per account).
-- Review them with:  SELECT id, login_username, modlevel FROM accounts;
-- Demote with:       UPDATE accounts SET modlevel = NULL WHERE login_username = '...';
UPDATE realms
SET
    require_registration = 1,
    ignore_passwords = 0,
    dev_mode = 0
WHERE name = 'dev';
