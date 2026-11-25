-- V13: Discord profile picture for accounts created through Discord registration.
-- Stores the avatar hash Discord returns at sign-in. The admin console builds the CDN link from
-- discord_id + this hash. NULL means "no picture known": the console falls back to Discord's
-- default avatar for that id, and scripts/backfill_discord_avatars.py can fill older accounts in.
ALTER TABLE accounts ADD COLUMN discord_avatar TEXT;
