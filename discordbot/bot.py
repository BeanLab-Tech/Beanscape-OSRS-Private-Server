"""Ned's OSRS registration bot (BeanLab OSRS private server).

Listens in its dedicated Discord channel for !register <username>, pre-creates
the account in the RsMod SQLite database (argon2i hash, identical parameters to
the game's Argon2PasswordHashing), and DMs a generated password to the
registering user.

Passwords never appear in the channel: the bot does not accept one as an
argument (and deletes a message that includes one), checks that it can DM the
user before creating anything, and removes the account again if the
credentials DM fails. The game server completes character creation on first
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
PASSWORD_LENGTH = 16

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


def delete_account(username: str) -> None:
    """Rolls back an account that was created but whose credentials never reached the user."""
    try:
        conn = db_connect()
        try:
            conn.execute("DELETE FROM accounts WHERE login_username = ?", (username,))
            conn.commit()
        finally:
            conn.close()
    except sqlite3.Error as e:
        log.error("db error rolling back %s: %s", username, e)


def generate_password() -> str:
    return "".join(secrets.choice(ALLOWED_PASSWORD_CHARS) for _ in range(PASSWORD_LENGTH))


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
            "Register with `!register <username>` - your password is generated and sent by DM "
            "(open your DMs first). Never post a password in this channel."
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
                "Usage: `!register <username>` (2-12 chars: letters, numbers, underscore). "
                "Your password is generated and sent by DM, so open your DMs first."
            )
        return

    parts = content.split()
    if len(parts) > 2:
        # Something after the username is probably a password: get it out of the channel.
        try:
            await message.delete()
        except (discord.Forbidden, discord.HTTPException):
            log.warning("could not delete a !register message with extra args (Manage Messages?)")
        await message.channel.send(
            f"{message.author.mention} don't post passwords here - I generate one and DM it. "
            f"Use `!register <username>` only. If that was a real password, don't reuse it."
        )
        return
    username = parts[1].lower() if len(parts) == 2 else ""
    if not username:
        await message.reply("Usage: `!register <username>`")
        return

    # Check we can DM before creating anything, so credentials never land in the channel.
    try:
        await message.author.send(f"Creating BeanLab OSRS account `{username}`...")
    except discord.Forbidden:
        await message.reply(
            "I can't DM you. Allow direct messages from server members "
            "(Server menu > Privacy Settings), then run `!register` again. Nothing was created."
        )
        return

    password = generate_password()
    result = await asyncio.get_running_loop().run_in_executor(
        None, register_account, username, password
    )
    await _reply(message, username, password, result)


async def _reply(message, username, password, result):
    if result == "ok":
        try:
            await message.author.send(
                f"**BeanLab OSRS account created**\n"
                f"Username: `{username}`\n"
                f"Password: `{password}`\n"
                f"Connect with the BeanLab client and log in. "
                f"You can change your display name in-game with `::name <new name>`."
            )
        except (discord.Forbidden, discord.HTTPException):
            # DMs closed between the check and now: undo, never fall back to the channel.
            await asyncio.get_running_loop().run_in_executor(None, delete_account, username)
            await message.reply(
                "I couldn't DM your password, so the account was not kept. "
                "Open your DMs and run `!register` again."
            )
            log.warning("rolled back %s: credentials DM failed", username)
            return
        await message.reply(f"{message.author.mention} registered `{username}` - password sent by DM.")
        log.info("registered %s for discord user %s", username, message.author)
    elif result == "taken":
        await message.author.send(f"`{username}` is already registered - pick another name.")
        await message.reply(f"`{username}` is already registered - pick another name.")
    elif result == "error:invalid":
        await message.reply(
            "Invalid username: use 2-12 characters, lowercase letters, numbers or underscores."
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
