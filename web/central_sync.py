"""Copies Beanscape accounts into OpenRune Central (staging, phase 1 W3).

The live server's SQLite `accounts` table stays the source of truth until the cutover. Every
sign-up (web OAuth, Discord bot) and password reset also writes the account into Central's
Postgres `accounts`, so the same login works on the OpenRune staging world. A Central failure is
logged and never blocks the live sign-up; `sync` below repairs any account that was missed.

Name rules mirror Central's world-login check (OpenRune-Central `AccountNameAuthPolicy`, as of
d694191): only letters, digits and spaces; spaces collapsed; at most 12 characters; no "mod" or
staff-looking fragments; no blocked words (the same remote list Central loads). Names that only
differ in spaces or case collide. Central refuses anything else at login, so sign-up refuses it
first. Reimplemented here, not copied.

Used by web/app.py and discordbot/bot.py (deploy copies this file next to bot.py). Database
password: OPENRUNE_DB_PASSWORD in /opt/osrs/openrune/secrets.env (root:osrs 0640) on VM 123.

CLI (on the VM):
    python3 central_sync.py check <name>            name policy verdict
    sudo python3 central_sync.py sync <name>|--all [--apply]
        copy live accounts (hash, discord_id, rank) to Central; dry run unless --apply
"""

from __future__ import annotations

import logging
import os
import pathlib
import re
import sqlite3
import sys
import time
import urllib.request
from dataclasses import dataclass

log = logging.getLogger("central-sync")

SECRETS_FILE = pathlib.Path(os.environ.get("OPENRUNE_SECRETS", "/opt/osrs/openrune/secrets.env"))
LIVE_DB = pathlib.Path(os.environ.get("BEANSCAPE_LIVE_DB", "/opt/osrs/server/rsmod/.data/saves/game.db"))
DB_HOST = os.environ.get("OPENRUNE_DB_HOST", "127.0.0.1")
DB_NAME = os.environ.get("OPENRUNE_DB_NAME", "openrune_central")
DB_USER = os.environ.get("OPENRUNE_DB_USER", "openrune")

MAX_LENGTH = 12
# Central's account_name_deceptive_fragments list ("mod" first: it has its own message).
DECEPTIVE_FRAGMENTS = ("mod", "jagex", "support", "system", "official", "customer", "helpdesk")
# The list Central's BadWordIndex loads by default (BadWordsConfig.remoteUrl).
BAD_WORDS_URL = os.environ.get(
    "OPENRUNE_BAD_WORDS_URL",
    "https://gist.githubusercontent.com/briankung/e085841a7a13fa4945a0cf482950436a/raw/"
    "326b4078db98541204e3d192d7cf84f63cd4c87a/bad_words.txt",
)
BAD_WORDS_TTL_SECONDS = 6 * 60 * 60

# Live accounts.modlevel -> Central accounts.rights. Owners also get a beanlab.owners row,
# because Central's rights stop at ADMINISTRATOR.
RIGHTS_FOR_MODLEVEL = {"owner": "ADMINISTRATOR", "admin": "ADMINISTRATOR", "moderator": "MOD"}

_bad_words: tuple[float, frozenset[str]] = (0.0, frozenset())


# --- name policy -------------------------------------------------------------------------------


def canonicalize(raw: str) -> str:
    """Letters and digits kept, any whitespace run becomes one space, everything else dropped."""
    out: list[str] = []
    last_space = False
    for ch in raw.strip():
        if ch.isalnum():
            out.append(ch)
            last_space = False
        elif ch.isspace():
            if out and not last_space:
                out.append(" ")
                last_space = True
        else:
            last_space = False
    return "".join(out).strip()


def collision_key(name: str) -> str:
    """Two names with the same key can't both exist (Central: letters and digits, lowercased)."""
    return "".join(ch.lower() for ch in name if ch.isalnum())


def _parse_words(text: str) -> frozenset[str]:
    words = (line.split("#", 1)[0].strip().lower() for line in text.splitlines())
    return frozenset(w for w in words if w)


def bad_words() -> frozenset[str]:
    """Central's remote blocked-word list, cached; empty if it can't be fetched (as Central)."""
    global _bad_words
    fetched_at, words = _bad_words
    if words and time.time() - fetched_at < BAD_WORDS_TTL_SECONDS:
        return words
    try:
        with urllib.request.urlopen(BAD_WORDS_URL, timeout=10) as resp:
            words = _parse_words(resp.read().decode("utf-8", "replace"))
        _bad_words = (time.time(), words)
    except Exception as e:  # network: keep the last list (or none), like Central's fallback
        log.warning("bad-words list fetch failed: %s", e)
    return _bad_words[1]


def find_bad_word(name: str, words: frozenset[str]) -> str | None:
    lower = name.lower()
    compact = re.sub(r"[^a-z0-9]+", "", lower)
    for word in words:
        if word in lower:
            return word
        squashed = re.sub(r"[^a-z0-9]+", "", word)
        if squashed and squashed in compact:
            return word
    return None


def name_problem(name: str, words: frozenset[str] | None = None) -> str | None:
    """None when Central accepts `name` as a login name, else a sentence for the player."""
    raw = name.strip()
    if not raw:
        return "Enter a name."
    if any(not (ch.isalnum() or ch in " \t") for ch in raw):
        return "Use only letters, numbers and spaces (no _ or other symbols)."
    if not raw.isascii():
        return "Use only the letters a-z, numbers and spaces."
    canonical = canonicalize(raw)
    if len(canonical) < 2:
        return "Use at least 2 letters or numbers."
    if len(canonical) > MAX_LENGTH:
        return f"Use at most {MAX_LENGTH} characters."
    lower = canonical.lower()
    for fragment in DECEPTIVE_FRAGMENTS:
        if fragment in lower:
            if fragment == "mod":
                return 'Names cannot contain "mod".'
            return "That name looks like staff or official branding."
    if find_bad_word(canonical, bad_words() if words is None else words):
        return "That name contains blocked language."
    return None


def clean_login_name(raw: str) -> str:
    """The login name sign-up stores: lowercased canonical form (Central looks names up lowercased)."""
    return canonicalize(raw).lower()


# --- Central database --------------------------------------------------------------------------


def _password() -> str | None:
    try:
        for line in SECRETS_FILE.read_text(encoding="utf-8").splitlines():
            key, _, value = line.partition("=")
            if key.strip() == "OPENRUNE_DB_PASSWORD":
                return value.strip().strip('"').strip("'")
    except OSError as e:
        log.warning("Central sync disabled: can't read %s (%s)", SECRETS_FILE, e)
    return None


def connect():
    """A psycopg connection to Central, or None when this host isn't set up for it."""
    password = _password()
    if password is None:
        return None
    try:
        import psycopg
    except ImportError:
        log.warning("Central sync disabled: psycopg is not installed")
        return None
    return psycopg.connect(
        host=DB_HOST, dbname=DB_NAME, user=DB_USER, password=password, connect_timeout=5
    )


@dataclass(frozen=True)
class SyncResult:
    status: str  # created | exists | updated | conflict | skipped | error
    detail: str = ""


def _find(cur, login: str):
    cur.execute("SELECT id, discord_id FROM accounts WHERE lower(account_name) = lower(%s)", (login,))
    return cur.fetchone()


def _collides(cur, login: str) -> str | None:
    key = collision_key(login)
    cur.execute(
        "SELECT account_name FROM accounts "
        "WHERE lower(regexp_replace(account_name, '[^A-Za-z0-9]', '', 'g')) = %s "
        "AND lower(account_name) <> lower(%s) LIMIT 1",
        (key, login),
    )
    row = cur.fetchone()
    return row[0] if row else None


def upsert_account(
    login: str,
    password_hash: str,
    discord_id: int | None = None,
    rights: str = "",
    owner: bool = False,
    overwrite: bool = False,
) -> SyncResult:
    """Creates the Central account. An existing one is left alone unless `overwrite` (then its
    hash, discord_id and rights are replaced). Safe to repeat."""
    try:
        conn = connect()
    except Exception as e:
        log.error("Central connect failed: %s", e)
        return SyncResult("error", str(e))
    if conn is None:
        return SyncResult("skipped", "not configured")
    try:
        with conn, conn.cursor() as cur:
            row = _find(cur, login)
            did = str(discord_id) if discord_id is not None else None
            if row is None:
                other = _collides(cur, login)
                if other:
                    return SyncResult("conflict", f"collides with {other}")
                cur.execute(
                    "INSERT INTO accounts (account_name, password_hash, rights, discord_id) "
                    "VALUES (%s, %s, %s, %s) RETURNING id",
                    (login, password_hash, rights, did),
                )
                account_id = cur.fetchone()[0]
                status = "created"
            elif overwrite:
                account_id = row[0]
                cur.execute(
                    "UPDATE accounts SET password_hash = %s, rights = %s, "
                    "discord_id = COALESCE(%s, discord_id), updated_at = CURRENT_TIMESTAMP "
                    "WHERE id = %s",
                    (password_hash, rights, did, account_id),
                )
                status = "updated"
            elif did is not None and row[1] != did:
                # Someone else's (or an unlinked) Central account already has this name: never
                # hand it to this Discord user.
                return SyncResult("conflict", "name belongs to another Central account")
            else:
                return SyncResult("exists")
            if owner:
                cur.execute(
                    "INSERT INTO beanlab.owners (account_id) VALUES (%s) ON CONFLICT DO NOTHING",
                    (account_id,),
                )
            return SyncResult(status)
    except Exception as e:
        log.error("Central upsert of %s failed: %s", login, e)
        return SyncResult("error", str(e))
    finally:
        conn.close()


def set_password(login: str, password_hash: str, discord_id: int) -> SyncResult:
    """Replaces the Central password of `login`, but only on the Central account linked to the
    same Discord user that reset it on live (`conflict` otherwise); `skipped` if none exists."""
    try:
        conn = connect()
    except Exception as e:
        log.error("Central connect failed: %s", e)
        return SyncResult("error", str(e))
    if conn is None:
        return SyncResult("skipped", "not configured")
    try:
        with conn, conn.cursor() as cur:
            row = _find(cur, login)
            if row is None:
                return SyncResult("skipped", "no account")
            if row[1] != str(discord_id):
                return SyncResult("conflict", "Central account is linked to another Discord user")
            cur.execute(
                "UPDATE accounts SET password_hash = %s, updated_at = CURRENT_TIMESTAMP "
                "WHERE id = %s",
                (password_hash, row[0]),
            )
            return SyncResult("updated")
    except Exception as e:
        log.error("Central password update of %s failed: %s", login, e)
        return SyncResult("error", str(e))
    finally:
        conn.close()


def mirror_signup(login: str, password_hash: str, discord_id: int | None) -> None:
    """Called after a successful live sign-up. Logs the outcome; never raises."""
    result = upsert_account(login, password_hash, discord_id)
    level = logging.INFO if result.status in ("created", "exists", "skipped") else logging.WARNING
    log.log(level, "central sign-up %s: %s %s", login, result.status, result.detail)


def mirror_password(login: str, password_hash: str, discord_id: int) -> None:
    """Called after a successful live password reset by `discord_id`. Logs; never raises."""
    result = set_password(login, password_hash, discord_id)
    if result.status == "skipped" and result.detail == "no account":
        # Account predates the sync: copy it whole, now with the new hash.
        mirror_signup(login, password_hash, discord_id)
        return
    level = logging.INFO if result.status in ("updated", "skipped") else logging.WARNING
    log.log(level, "central password %s: %s %s", login, result.status, result.detail)


# --- CLI: copy existing live accounts ----------------------------------------------------------


def _live_accounts(name: str | None):
    conn = sqlite3.connect(f"file:{LIVE_DB}?mode=ro", uri=True, timeout=10)
    try:
        sql = "SELECT login_username, password_hash, discord_id, modlevel FROM accounts"
        if name is not None:
            return conn.execute(sql + " WHERE login_username = ?", (name.lower(),)).fetchall()
        return conn.execute(sql + " ORDER BY id").fetchall()
    finally:
        conn.close()


def _cli(argv: list[str]) -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    if len(argv) >= 2 and argv[0] == "check":
        problem = name_problem(" ".join(argv[1:]))
        print(problem or f"ok: {clean_login_name(' '.join(argv[1:]))!r}")
        return 0 if problem is None else 1
    if not argv or argv[0] != "sync" or len(argv) < 2:
        print(__doc__)
        return 2
    apply = "--apply" in argv
    target = argv[1]
    rows = _live_accounts(None if target == "--all" else target)
    if not rows:
        print(f"no live account {target!r}")
        return 1
    words = bad_words()
    counts: dict[str, int] = {}
    for login, pw_hash, discord_id, modlevel in rows:
        problem = name_problem(login, words)
        if problem:
            status, detail = "refused", problem
        elif not str(pw_hash or "").startswith("$argon2"):
            status, detail = "refused", "no argon2 password hash"
        elif not apply:
            status, detail = "would-sync", (modlevel or "")
        else:
            res = upsert_account(
                clean_login_name(login),
                pw_hash,
                discord_id,
                rights=RIGHTS_FOR_MODLEVEL.get(modlevel or "", ""),
                owner=modlevel == "owner",
            )
            status, detail = res.status, res.detail
        counts[status] = counts.get(status, 0) + 1
        print(f"{login:<14} {status:<10} {detail}")
    print("summary: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    return 0


if __name__ == "__main__":
    sys.exit(_cli(sys.argv[1:]))
