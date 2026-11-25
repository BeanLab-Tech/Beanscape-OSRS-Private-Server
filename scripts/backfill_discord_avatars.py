#!/usr/bin/env python3
"""Fill in accounts.discord_avatar for accounts created through Discord.

New registrations store the avatar hash at sign-in (web/app.py). This script covers accounts that
existed before that, and refreshes pictures people have since changed. It asks Discord for each
bound user with the bot token already on the VM and writes only the avatar hash.

    sudo python3 scripts/backfill_discord_avatars.py                # accounts with no picture stored
    sudo python3 scripts/backfill_discord_avatars.py --all          # refresh everyone
    python3 scripts/backfill_discord_avatars.py --dry-run

Needs the V15 migration (the server applies it on start). The bot token is read from a file and is
never printed. Discord rate limits user lookups, so the script paces itself and honours
Retry-After.
"""
import argparse
import pathlib
import sqlite3
import sys
import time
import urllib.error
import urllib.request

API = "https://discord.com/api/v10/users/{}"
DEFAULT_TOKEN_FILE = "/opt/osrs/config/ned_discord_token"
DEFAULT_DB = "/opt/osrs/server/rsmod/.data/saves/game.db"
PAUSE = 0.6


def fetch_avatar(discord_id: int, token: str):
    """Returns (ok, avatar_hash_or_None). ok is False when the lookup failed and should be retried."""
    request = urllib.request.Request(
        API.format(discord_id),
        headers={"Authorization": f"Bot {token}", "User-Agent": "BeanLab-Avatar-Backfill (private)"},
    )
    for _ in range(3):
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                import json

                return True, json.load(response).get("avatar")
        except urllib.error.HTTPError as err:
            if err.code == 429:
                time.sleep(float(err.headers.get("Retry-After", "2")) + 0.5)
                continue
            if err.code == 404:
                return True, None  # the Discord account is gone; keep the default avatar
            print(f"  discord answered {err.code} for {discord_id}", file=sys.stderr)
            return False, None
        except (urllib.error.URLError, TimeoutError) as err:
            print(f"  could not reach discord for {discord_id}: {err}", file=sys.stderr)
            return False, None
    return False, None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db", default=DEFAULT_DB)
    parser.add_argument("--token-file", default=DEFAULT_TOKEN_FILE)
    parser.add_argument("--all", action="store_true", help="refresh accounts that already have a picture")
    parser.add_argument("--dry-run", action="store_true", help="look up and report, write nothing")
    args = parser.parse_args()

    token = pathlib.Path(args.token_file).read_text().strip()
    conn = sqlite3.connect(args.db, timeout=10)
    conn.execute("PRAGMA busy_timeout=10000")
    where = "discord_id IS NOT NULL" + ("" if args.all else " AND discord_avatar IS NULL")
    rows = conn.execute(f"SELECT id, login_username, discord_id FROM accounts WHERE {where}").fetchall()
    print(f"{len(rows)} account(s) to check")

    updated = failed = 0
    for account_id, username, discord_id in rows:
        ok, avatar = fetch_avatar(int(discord_id), token)
        if not ok:
            failed += 1
        else:
            print(f"  {username}: {'custom picture' if avatar else 'default avatar'}")
            if not args.dry_run:
                conn.execute("UPDATE accounts SET discord_avatar = ? WHERE id = ?", (avatar, account_id))
                conn.commit()
            updated += 1
        time.sleep(PAUSE)

    print(f"done: {updated} checked, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
