"""Ned's OSRS registration bot (BeanLab OSRS private server).

Listens in its dedicated Discord channel for !register <username> [password],
pre-creates the account in the RsMod SQLite database (argon2i hash, identical
parameters to the game's Argon2PasswordHashing), and DMs the credentials to
the registering user. The game server completes character creation on first
login through its own account-loader path.
"""

import asyncio
import logging
import pathlib
import re
import secrets
import sqlite3
import string

import discord
from argon2 import PasswordHasher, Type

TOKEN_FILE = pathlib.Path("/opt/osrs/config/ned_discord_token")
DB_PATH = pathlib.Path("/opt/osrs/server/rsmod/.data/saves/game.db")
CHANNEL_ID = 1555796182230638715

USERNAME_RE = re.compile(r"^[a-z0-9_ ]{2,12}$")
ALLOWED_PASSWORD_CHARS = string.ascii_letters + string.digits

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("osrs-reg")

ph = PasswordHasher(time_cost=20, memory_cost=65536, parallelism=1, type=Type.I)

intents = discord.Intents.default()
intents.message_content = True

client = discord.Client(intents=intents)


def db_connect():
    conn = sqlite3.connect(str(DB_PATH), timeout=10)
    conn.execute("PRAGMA busy_timeout=10000")
    return conn


def register_account(username: str, password: str) -> str:
    """Inserts the account row. Returns 'ok', 'taken' or 'error:<msg>'."""
    username = username.lower().strip()
    if not USERNAME_RE.match(username):
        return "error:invalid"
    try:
        conn = db_connect()
        try:
            row = conn.execute(
                "SELECT id FROM accounts WHERE login_username = ?", (username,)
            ).fetchone()
            if row is not None:
                return "taken"
            pw_hash = ph.hash(password)
            conn.execute(
                "INSERT INTO accounts (login_username, password_hash) VALUES (?, ?)",
                (username, pw_hash),
            )
            conn.commit()
            return "ok"
        finally:
            conn.close()
    except sqlite3.Error as e:
        log.error("db error during registration: %s", e)
        return "error:db"


def self_test() -> bool:
    """Boot self-check: create, verify, and clean up a throwaway account."""
    password = "systemtest-" + secrets.token_hex(4)
    try:
        conn = db_connect()
        try:
            conn.execute("DELETE FROM accounts WHERE login_username = 'systemtest'")
            conn.commit()
        finally:
            conn.close()
    except sqlite3.Error:
        pass
    result = register_account("systemtest", password)
    if result != "ok":
        log.error("self-test registration failed: %s", result)
        return False
    try:
        conn = db_connect()
        try:
            row = conn.execute(
                "SELECT password_hash FROM accounts WHERE login_username = 'systemtest'"
            ).fetchone()
            if row is None:
                log.error("self-test: inserted row not found")
                return False
            ph.verify(row[0], password)  # raises on mismatch
            conn.execute("DELETE FROM accounts WHERE login_username = 'systemtest'")
            conn.commit()
        finally:
            conn.close()
        log.info("self-test passed: account insert + argon2 verify + cleanup OK")
        return True
    except Exception as e:
        log.error("self-test verification failed: %s", e)
        return False


@client.event
async def on_ready():
    log.info("logged in as %s (%s)", client.user, client.user.id)
    channel = client.get_channel(CHANNEL_ID)
    if channel is not None:
        await channel.send(
            "BeanLab OSRS registration bot online. "
            "Register with `!register <username>` (optional: `!register <username> <password>`). "
            "Credentials are sent by DM. Then connect with RSProx (target: BeanLab OSRS)."
        )
    else:
        log.warning("channel %s not found - check bot permissions/invites", CHANNEL_ID)


@client.event
async def on_message(message):
    if message.author.bot:
        return
    if message.channel.id != CHANNEL_ID:
        return
    content = message.content.strip()
    if not content.startswith("!register"):
        if content.startswith("!help"):
            await message.reply(
                "Usage: `!register <username>` or `!register <username> <password>` "
                "(username: 2-12 chars, letters/numbers/space/underscore)."
            )
        return

    parts = content.split()
    username = parts[1].lower() if len(parts) > 1 else ""
    if len(parts) >= 3:
        password = parts[2]
    else:
        password = "".join(secrets.choice(ALLOWED_PASSWORD_CHARS) for _ in range(10))

    if not username:
        await message.reply("Usage: `!register <username> [password]`")
        return
    if len(password) < 6 or len(password) > 32:
        await message.reply("Password must be 6-32 characters.")
        return

    await asyncio.get_event_loop().run_in_executor(None, _handle, message, username, password)


def _handle(message, username, password):
    # register synchronously, then schedule the replies
    result = register_account(username, password)
    asyncio.run_coroutine_threadsafe(_reply(message, username, password, result), client.loop)


async def _reply(message, username, password, result):
    if result == "ok":
        try:
            await message.author.send(
                f"**BeanLab OSRS account created**\n"
                f"Username: `{username}`\n"
                f"Password: `{password}`\n"
                f"Connect via RSProx (proxy target: BeanLab OSRS) and log in. "
                f"You can change your display name in-game with `::name <new name>`."
            )
            await message.reply(
                f"{message.author.mention} registered `{username}` - credentials sent by DM."
            )
            log.info("registered %s for discord user %s", username, message.author)
        except discord.Forbidden:
            await message.reply(
                f"{message.author.mention} registered `{username}`. "
                f"I can't DM you (DMs closed) - password is `{password}`. "
                f"Change it later by asking an admin."
            )
    elif result == "taken":
        await message.reply(f"`{username}` is already registered - pick another name.")
    elif result == "error:invalid":
        await message.reply(
            "Invalid username: use 2-12 characters, lowercase letters, numbers, "
            "spaces or underscores."
        )
    else:
        await message.reply("Registration failed (database error) - try again shortly.")


def main():
    token = TOKEN_FILE.read_text().strip()
    if not self_test():
        log.error("self-test failed - refusing to start with a broken registration path")
        raise SystemExit(1)
    client.run(token)


if __name__ == "__main__":
    main()
