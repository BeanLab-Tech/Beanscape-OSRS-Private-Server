-- BeanLab: tighten V13's auto-assigned display names (security review).
-- The UNIQUE index on accounts.display_name is case-sensitive, so V13 could give an account a name
-- that differs from another player's only by case, or a staff-looking name like "Admin"/"Mod ...".
-- Now a name is only auto-assigned when no other account uses it (display or login name, any case)
-- and it doesn't start with a reserved word. Otherwise display_name stays NULL and the player picks
-- one with ::name, which runs the game's own validation. (V13 is applied, so it isn't edited.)

DROP TRIGGER IF EXISTS accounts_create_registered_characters;

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
      AND EXISTS (SELECT 1 FROM realms r WHERE r.require_registration = 1 AND r.auto_assign_display_names = 1)
      AND NOT EXISTS (
          SELECT 1 FROM accounts o
          WHERE o.id <> NEW.id
            AND (lower(o.display_name) = lower(NEW.login_username)
                 OR lower(o.login_username) = lower(NEW.login_username))
      )
      AND lower(NEW.login_username) NOT GLOB 'mod*'
      AND lower(NEW.login_username) NOT GLOB 'jmod*'
      AND lower(NEW.login_username) NOT GLOB 'admin*'
      AND lower(NEW.login_username) NOT GLOB 'jagex*'
      AND lower(NEW.login_username) NOT GLOB 'owner*';
END;

-- Undo any V13-assigned name that breaks the same rules (players then pick one with ::name).
UPDATE accounts
SET display_name = NULL
WHERE display_name IS NOT NULL
  AND display_name = upper(substr(login_username, 1, 1)) || substr(login_username, 2)
  AND (
      lower(login_username) GLOB 'mod*' OR lower(login_username) GLOB 'jmod*'
      OR lower(login_username) GLOB 'admin*' OR lower(login_username) GLOB 'jagex*'
      OR lower(login_username) GLOB 'owner*'
      OR EXISTS (
          SELECT 1 FROM accounts o
          WHERE o.id <> accounts.id
            AND (lower(o.display_name) = lower(accounts.display_name)
                 OR lower(o.login_username) = lower(accounts.login_username))
      )
  );
