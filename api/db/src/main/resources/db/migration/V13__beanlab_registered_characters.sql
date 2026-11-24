-- BeanLab: accounts created outside the game (Discord bot, web sign-up, admin panel) never got a
-- `characters` row. With require_registration=1 the game logs in with a strict search
-- (accounts JOIN characters ... WHERE realm_id = ?), so those accounts were "not found" and every
-- fresh registration failed to log in. The game only creates characters on its own sign-up path,
-- which require_registration turns off.
--
-- Fix, in the database so every registration path is covered:
--  * after an account is inserted, create its character in each realm that requires registration,
--    with what the game gives brand-new accounts: the realm spawn (0_50_50_21_18 = 3221, 3218,
--    Lumbridge) and the realm's player XP rate; and a display name from the login name when the realm
--    auto-assigns them (the game skips that for accounts it didn't create, which locks the chatbox);
--  * delete characters with their account (the Python services' SQLite connections don't enforce
--    ON DELETE CASCADE, so their self-test accounts left orphan characters);
--  * backfill existing accounts and drop existing orphans.
-- Realms without registration are untouched: there the game creates the character itself.

CREATE TRIGGER accounts_create_registered_characters
AFTER INSERT ON accounts
BEGIN
    INSERT INTO characters (account_id, realm_id, x, z, level, xp_rate_in_hundreds)
    SELECT NEW.id, r.id, 3221, 3218, 0, r.player_xp_rate_in_hundreds
    FROM realms r
    WHERE r.require_registration = 1
      AND NOT EXISTS (SELECT 1 FROM characters c WHERE c.account_id = NEW.id AND c.realm_id = r.id);

    UPDATE OR IGNORE accounts
    SET display_name = upper(substr(NEW.login_username, 1, 1)) || substr(NEW.login_username, 2)
    WHERE id = NEW.id
      AND display_name IS NULL
      AND EXISTS (SELECT 1 FROM realms r WHERE r.require_registration = 1 AND r.auto_assign_display_names = 1);
END;

CREATE TRIGGER accounts_delete_characters
AFTER DELETE ON accounts
BEGIN
    DELETE FROM characters WHERE account_id = OLD.id;
END;

DELETE FROM characters WHERE account_id NOT IN (SELECT id FROM accounts);

INSERT INTO characters (account_id, realm_id, x, z, level, xp_rate_in_hundreds)
SELECT a.id, r.id, 3221, 3218, 0, r.player_xp_rate_in_hundreds
FROM accounts a, realms r
WHERE r.require_registration = 1
  AND NOT EXISTS (SELECT 1 FROM characters c WHERE c.account_id = a.id AND c.realm_id = r.id);

UPDATE OR IGNORE accounts
SET display_name = upper(substr(login_username, 1, 1)) || substr(login_username, 2)
WHERE display_name IS NULL
  AND EXISTS (SELECT 1 FROM realms r WHERE r.require_registration = 1 AND r.auto_assign_display_names = 1);
