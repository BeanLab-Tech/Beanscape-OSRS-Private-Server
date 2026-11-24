"""Ned's OSRS registration bot (BeanLab OSRS private server).

Registration happens inside Discord with slash commands:

  /register [username]   create an account linked to your Discord account
  /resetpassword         new password for the account linked to your Discord account

Discord itself authenticates whoever runs a slash command, so the bot gets the user's verified
Discord ID without a browser OAuth round trip. Each Discord account can own one game account
(unique accounts.discord_id, shared with the web OAuth registration). Replies are ephemeral: only
the person who ran the command sees them, so passwords never appear in the channel and DMs don't
need to be open.

Accounts use Argon2i with the game server's parameters; the game completes character creation on
first login. The old `!register` text command now just points to /register (and deletes any message
that included a password).
"""

import asyncio
import logging
import pathlib
import re
import secrets
import sqlite3
import string
import time

import discord
from argon2 import PasswordHasher, Type
from discord import app_commands

TOKEN_FILE = pathlib.Path("/opt/osrs/config/ned_discord_token")
DB_PATH = pathlib.Path("/opt/osrs/server/rsmod/.data/saves/game.db")
CHANNEL_ID = 1555796182230638715

USERNAME_RE = re.compile(r"^[a-z0-9_ ]{2,12}$")
ALLOWED_PASSWORD_CHARS = string.ascii_letters + string.digits
PASSWORD_LENGTH = 16
COMMAND_COOLDOWN_SECONDS = 30

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("osrs-reg")

ph = PasswordHasher(time_cost=20, memory_cost=65536, parallelism=1, type=Type.I)

intents = discord.Intents.default()
intents.message_content = True

client = discord.Client(intents=intents)
tree = app_commands.CommandTree(client)
_last_command: dict[int, float] = {}


def db_connect():
    conn = sqlite3.connect(str(DB_PATH), timeout=10)
    conn.execute("PRAGMA busy_timeout=10000")
    return conn


def generate_password() -> str:
    return "".join(secrets.choice(ALLOWED_PASSWORD_CHARS) for _ in range(PASSWORD_LENGTH))


def default_username(user: discord.abc.User) -> str:
    """Same rule as the web OAuth registration: Discord global name, lowercased, 2-12 chars."""
    name = getattr(user, "global_name", None) or user.name or ""
    cleaned = re.sub(r"[^a-z0-9_ ]", "", name.lower())[:12].strip()
    if len(cleaned) < 2:
        cleaned = re.sub(r"[^a-z0-9_ ]", "", user.name.lower())[:12].strip()
    return cleaned


def linked_username(discord_id: int) -> str | None:
    conn = db_connect()
    try:
        row = conn.execute(
            "SELECT login_username FROM accounts WHERE discord_id = ?", (discord_id,)
        ).fetchone()
        return row[0] if row else None
    finally:
        conn.close()


def register_account(
    username: str, password: str, discord_id: int | None = None, discord_name: str | None = None
) -> str:
    """Inserts the account row. Returns 'ok', 'taken', 'linked:<name>' or 'error:<msg>'."""
    username = username.lower().strip()
    if not USERNAME_RE.match(username):
        return "error:invalid"
    try:
        conn = db_connect()
        try:
            if discord_id is not None:
                row = conn.execute(
                    "SELECT login_username FROM accounts WHERE discord_id = ?", (discord_id,)
                ).fetchone()
                if row is not None:
                    return f"linked:{row[0]}"
            row = conn.execute(
                "SELECT id FROM accounts WHERE login_username = ?", (username,)
            ).fetchone()
            if row is not None:
                return "taken"
            conn.execute(
                "INSERT INTO accounts (login_username, password_hash, discord_id, discord_username) "
                "VALUES (?, ?, ?, ?)",
                (username, ph.hash(password), discord_id, discord_name),
            )
            conn.commit()
            return "ok"
        finally:
            conn.close()
    except sqlite3.IntegrityError:
        # Lost a race on the unique username or discord_id index.
        existing = linked_username(discord_id) if discord_id is not None else None
        return f"linked:{existing}" if existing else "taken"
    except sqlite3.Error as e:
        log.error("db error during registration: %s", e)
        return "error:db"


def reset_password(discord_id: int, password: str) -> str | None:
    """Sets a new password on the account linked to discord_id. Returns its username or None."""
    try:
        conn = db_connect()
        try:
            row = conn.execute(
                "SELECT login_username FROM accounts WHERE discord_id = ?", (discord_id,)
            ).fetchone()
            if row is None:
                return None
            conn.execute(
                "UPDATE accounts SET password_hash = ? WHERE discord_id = ?",
                (ph.hash(password), discord_id),
            )
            conn.commit()
            return row[0]
        finally:
            conn.close()
    except sqlite3.Error as e:
        log.error("db error during password reset: %s", e)
        return None


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


async def guard(interaction: discord.Interaction) -> bool:
    """Registration channel only, plus a short per-user cooldown (each command hashes a password)."""
    if interaction.channel_id != CHANNEL_ID:
        await interaction.response.send_message(
            f"Use this in <#{CHANNEL_ID}>.", ephemeral=True
        )
        return False
    now = time.monotonic()
    last = _last_command.get(interaction.user.id, 0.0)
    if now - last < COMMAND_COOLDOWN_SECONDS:
        await interaction.response.send_message(
            "Slow down - try again in a few seconds.", ephemeral=True
        )
        return False
    _last_command[interaction.user.id] = now
    return True


CONNECT_HINT = (
    "Log in with the BeanLab client (Microbot or RSProx, see the server README). "
    "Save this password now: this message disappears and only you can see it."
)


@tree.command(name="register", description="Create your BeanLab OSRS account (only you see the reply)")
@app_commands.describe(
    username="Login name: 2-12 lowercase letters, numbers, spaces or _. Defaults to your Discord name."
)
async def register_cmd(interaction: discord.Interaction, username: str | None = None):
    if not await guard(interaction):
        return
    await interaction.response.defer(ephemeral=True, thinking=True)
    name = (username or default_username(interaction.user)).lower().strip()
    password = generate_password()
    result = await asyncio.get_running_loop().run_in_executor(
        None, register_account, name, password, interaction.user.id, str(interaction.user)
    )
    if result == "ok":
        log.info("registered %s for discord user %s (%s)", name, interaction.user, interaction.user.id)
        await interaction.followup.send(
            f"**BeanLab OSRS account created**\nUsername: `{name}`\nPassword: `{password}`\n"
            f"{CONNECT_HINT}\nChange your display name in-game with `::name <new name>`.",
            ephemeral=True,
        )
    elif result.startswith("linked:"):
        await interaction.followup.send(
            f"Your Discord account is already linked to `{result.split(':', 1)[1]}`. "
            "Lost the password? Use `/resetpassword`.",
            ephemeral=True,
        )
    elif result == "taken":
        await interaction.followup.send(
            f"`{name}` is already taken. Run `/register username:<another name>`.", ephemeral=True
        )
    elif result == "error:invalid":
        await interaction.followup.send(
            f"`{name}` isn't a valid login name: use 2-12 lowercase letters, numbers, spaces or "
            "underscores, e.g. `/register username:beanboy`.",
            ephemeral=True,
        )
    else:
        await interaction.followup.send(
            "Registration failed (database error) - try again shortly.", ephemeral=True
        )


@tree.command(
    name="resetpassword",
    description="New password for the BeanLab account linked to your Discord (only you see it)",
)
async def reset_cmd(interaction: discord.Interaction):
    if not await guard(interaction):
        return
    await interaction.response.defer(ephemeral=True, thinking=True)
    password = generate_password()
    name = await asyncio.get_running_loop().run_in_executor(
        None, reset_password, interaction.user.id, password
    )
    if name is None:
        await interaction.followup.send(
            "No account is linked to your Discord yet. Use `/register` first.", ephemeral=True
        )
        return
    log.info("password reset for %s by discord user %s (%s)", name, interaction.user, interaction.user.id)
    await interaction.followup.send(
        f"**Password reset**\nUsername: `{name}`\nNew password: `{password}`\n{CONNECT_HINT}",
        ephemeral=True,
    )


@client.event
async def on_ready():
    log.info("logged in as %s (%s)", client.user, client.user.id)
    channel = client.get_channel(CHANNEL_ID)
    if channel is None:
        log.warning("channel %s not found - check bot permissions/invites", CHANNEL_ID)
        return
    guild = channel.guild
    try:
        # Guild commands show up immediately (global ones can take up to an hour).
        tree.copy_global_to(guild=guild)
        synced = await tree.sync(guild=guild)
        log.info("synced %d slash commands to guild %s", len(synced), guild.id)
    except discord.Forbidden:
        log.error(
            "cannot register slash commands in guild %s: re-invite the bot with the "
            "applications.commands scope: https://discord.com/oauth2/authorize?client_id=%s"
            "&scope=bot+applications.commands&permissions=11264",
            guild.id,
            client.user.id,
        )
        return
    # No startup announcement: it re-posted on every restart. /register shows up in Discord's
    # command picker for everyone in the guild.


@client.event
async def on_message(message):
    if message.author.bot or message.channel.id != CHANNEL_ID:
        return
    content = message.content.strip()
    if not (content.startswith("!register") or content.startswith("!help")):
        return
    if content.startswith("!register") and len(content.split()) > 2:
        # Something after the username is probably a password: get it out of the channel.
        try:
            await message.delete()
        except (discord.Forbidden, discord.HTTPException):
            log.warning("could not delete a !register message with extra args (Manage Messages?)")
        await message.channel.send(
            f"{message.author.mention} don't post passwords here. Use `/register` - only you "
            "will see your password. If that was a real password, don't reuse it."
        )
        return
    await message.reply(
        "Registration moved to slash commands: type `/register` (or "
        "`/register username:<name>`). Only you will see your password. "
        "Lost it? `/resetpassword`."
    )


def main():
    token = TOKEN_FILE.read_text().strip()
    if not self_test():
        log.error("self-test failed - refusing to start with a broken registration path")
        raise SystemExit(1)
    client.run(token)


if __name__ == "__main__":
    main()
