"""Ned's OSRS registration bot (BeanLab OSRS private server).

Registration happens inside Discord with slash commands:

  /register [username]   create an account linked to your Discord account
  /resetpassword         new password for the account linked to your Discord account

Discord itself authenticates whoever runs a slash command, so the bot gets the user's verified
Discord ID without a browser OAuth round trip. Each Discord account can own one game account
(unique accounts.discord_id, shared with the web OAuth registration). Replies are ephemeral: only
the person who ran the command sees them, so passwords never appear in the channel and DMs don't
need to be open. The bot also keeps one pinned message in the registration channel with Register
and Reset password buttons (Register opens a short form for the login name).

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

# Installed next to this file by deploy/deploy-vm.sh (source: web/central_sync.py); the web
# panel's copy is the fallback (a deploy run by an older deploy-vm.sh only ships that one).
try:
    import central_sync
except ImportError:
    import sys

    sys.path.append("/opt/osrs/web")
    import central_sync

TOKEN_FILE = pathlib.Path("/opt/osrs/config/ned_discord_token")
DB_PATH = pathlib.Path("/opt/osrs/server/rsmod/.data/saves/game.db")
# Written by deploy/deploy-vm.sh; the bot announces each new client build once.
CLIENT_BUILD_FILE = pathlib.Path("/opt/osrs/config/webconfig/runelite/client/CURRENT")
ANNOUNCED_BUILD_FILE = pathlib.Path("/opt/osrs/discordbot/announced-client-build")
UPDATER_URL = (
    "https://raw.githubusercontent.com/BeanLab-Tech/Beanscape-OSRS-Private-Server/main/"
    "client/microbot/Update-Client.ps1"
)
CHANNEL_ID = 1555796182230638715

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
    """Same rule as the web OAuth registration: the first of the Discord global name and user
    name that Central accepts, lowercased (letters, numbers, spaces; 2-12)."""
    for raw in (getattr(user, "global_name", None) or "", user.name or ""):
        name = central_sync.clean_login_name(re.sub(r"[^a-z0-9 ]", "", raw.lower()))[:12].strip()
        if name and central_sync.name_problem(name) is None:
            return name
    return ""


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
    username: str,
    password: str,
    discord_id: int | None = None,
    discord_name: str | None = None,
    mirror: bool = True,
) -> str:
    """Inserts the account row (and copies it to OpenRune Central unless `mirror` is False).
    Returns 'ok', 'taken', 'linked:<name>', 'error:invalid:<reason>' or 'error:<msg>'."""
    problem = central_sync.name_problem(username)
    if problem is not None:
        return f"error:invalid:{problem}"
    username = central_sync.clean_login_name(username)
    try:
        conn = db_connect()
        try:
            if discord_id is not None:
                row = conn.execute(
                    "SELECT login_username FROM accounts WHERE discord_id = ?", (discord_id,)
                ).fetchone()
                if row is not None:
                    return f"linked:{row[0]}"
            # Taken, or equal ignoring spaces/underscores/case (Central refuses look-alikes).
            row = conn.execute(
                "SELECT 1 FROM accounts WHERE login_username = ? "
                "OR lower(replace(replace(login_username, ' ', ''), '_', '')) = ? LIMIT 1",
                (username, central_sync.collision_key(username)),
            ).fetchone()
            if row is not None:
                return "taken"
            pw_hash = ph.hash(password)
            conn.execute(
                "INSERT INTO accounts (login_username, password_hash, discord_id, discord_username) "
                "VALUES (?, ?, ?, ?)",
                (username, pw_hash, discord_id, discord_name),
            )
            conn.commit()
        finally:
            conn.close()
    except sqlite3.IntegrityError:
        # Lost a race on the unique username or discord_id index.
        existing = linked_username(discord_id) if discord_id is not None else None
        return f"linked:{existing}" if existing else "taken"
    except sqlite3.Error as e:
        log.error("db error during registration: %s", e)
        return "error:db"
    if mirror:
        central_sync.mirror_signup(username, pw_hash, discord_id)
    return "ok"


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
            pw_hash = ph.hash(password)
            conn.execute(
                "UPDATE accounts SET password_hash = ? WHERE discord_id = ?",
                (pw_hash, discord_id),
            )
            conn.commit()
        finally:
            conn.close()
    except sqlite3.Error as e:
        log.error("db error during password reset: %s", e)
        return None
    central_sync.mirror_password(row[0], pw_hash)
    return row[0]


def self_test() -> bool:
    """Boot self-check: create, verify, and clean up a throwaway account."""
    password = "systemtest-" + secrets.token_hex(4)
    try:
        conn = db_connect()
        try:
            conn.execute("DELETE FROM accounts WHERE login_username = 'beanselftest'")
            conn.commit()
        finally:
            conn.close()
    except sqlite3.Error:
        pass
    result = register_account("beanselftest", password, mirror=False)
    if result != "ok":
        log.error("self-test registration failed: %s", result)
        return False
    try:
        conn = db_connect()
        try:
            row = conn.execute(
                "SELECT password_hash FROM accounts WHERE login_username = 'beanselftest'"
            ).fetchone()
            if row is None:
                log.error("self-test: inserted row not found")
                return False
            ph.verify(row[0], password)  # raises on mismatch
            conn.execute("DELETE FROM accounts WHERE login_username = 'beanselftest'")
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


async def do_register(interaction: discord.Interaction, username: str | None) -> None:
    """Shared by /register and the Register button's form. Replies are ephemeral."""
    if not await guard(interaction):
        return
    await interaction.response.defer(ephemeral=True, thinking=True)
    name = central_sync.clean_login_name(username or default_username(interaction.user))
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
            "Lost the password? Use **Reset password** or `/resetpassword`.",
            ephemeral=True,
        )
    elif result == "taken":
        await interaction.followup.send(
            f"`{name}` is already taken. Press **Register** again and pick another login name.",
            ephemeral=True,
        )
    elif result.startswith("error:invalid"):
        reason = result.split(":", 2)[2] if result.count(":") >= 2 else ""
        shown = f"`{name}`" if name else "That name"
        await interaction.followup.send(
            f"{shown} can't be used as a login name. {reason} "
            "Use 2-12 letters, numbers or spaces, e.g. `beanboy`. Press **Register** and try another.",
            ephemeral=True,
        )
    else:
        await interaction.followup.send(
            "Registration failed (database error) - try again shortly.", ephemeral=True
        )


async def do_reset(interaction: discord.Interaction) -> None:
    """Shared by /resetpassword and the Reset password button."""
    if not await guard(interaction):
        return
    await interaction.response.defer(ephemeral=True, thinking=True)
    password = generate_password()
    name = await asyncio.get_running_loop().run_in_executor(
        None, reset_password, interaction.user.id, password
    )
    if name is None:
        await interaction.followup.send(
            "No account is linked to your Discord yet. Press **Register** first.", ephemeral=True
        )
        return
    log.info("password reset for %s by discord user %s (%s)", name, interaction.user, interaction.user.id)
    await interaction.followup.send(
        f"**Password reset**\nUsername: `{name}`\nNew password: `{password}`\n{CONNECT_HINT}",
        ephemeral=True,
    )


@tree.command(name="register", description="Create your BeanLab OSRS account (only you see the reply)")
@app_commands.describe(
    username="Login name: 2-12 letters, numbers or spaces (no _ or \"mod\"). Defaults to your Discord name."
)
async def register_cmd(interaction: discord.Interaction, username: str | None = None):
    await do_register(interaction, username)


@tree.command(
    name="resetpassword",
    description="New password for the BeanLab account linked to your Discord (only you see it)",
)
async def reset_cmd(interaction: discord.Interaction):
    await do_reset(interaction)


class RegisterModal(discord.ui.Modal, title="Register for BeanLab OSRS"):
    # Discord limits: label 1-45 chars, placeholder up to 100.
    username = discord.ui.TextInput(
        label="Login name (optional)",
        required=False,
        min_length=2,
        max_length=12,
        placeholder="Blank = your Discord name. 2-12: a-z, 0-9, spaces. No _ or \"mod\".",
    )

    async def on_submit(self, interaction: discord.Interaction):
        await do_register(interaction, self.username.value.strip() or None)


class RegisterView(discord.ui.View):
    """Buttons on the pinned message. timeout=None + fixed custom_ids keep them working after a
    restart (re-attached with client.add_view)."""

    def __init__(self):
        super().__init__(timeout=None)

    @discord.ui.button(label="Register", style=discord.ButtonStyle.success, custom_id="beanlab:register")
    async def register_button(self, interaction: discord.Interaction, _button: discord.ui.Button):
        if interaction.channel_id != CHANNEL_ID:
            await interaction.response.send_message(f"Use this in <#{CHANNEL_ID}>.", ephemeral=True)
            return
        await interaction.response.send_modal(RegisterModal())

    @discord.ui.button(label="Reset password", style=discord.ButtonStyle.secondary, custom_id="beanlab:reset")
    async def reset_button(self, interaction: discord.Interaction, _button: discord.ui.Button):
        await do_reset(interaction)

    async def on_error(self, interaction: discord.Interaction, error: Exception, item) -> None:
        # Never fail silently: tell the user, and keep the traceback in the log.
        log.error("registration panel %s failed for %s", getattr(item, "custom_id", item), interaction.user, exc_info=error)
        message = "Something went wrong on our side - please tell an admin. Nothing was created."
        try:
            if interaction.response.is_done():
                await interaction.followup.send(message, ephemeral=True)
            else:
                await interaction.response.send_message(message, ephemeral=True)
        except discord.HTTPException:
            pass


PANEL_MARKER = "BeanLab OSRS registration"
PANEL_TEXT = (
    f"**{PANEL_MARKER}**\n"
    "Press **Register** to create your game account. Discord confirms who you are, and your "
    "username and password are shown **only to you**. Lost your password? **Reset password**.\n"
    "You can also type `/register` or `/resetpassword`. Never post a password in this channel."
)


async def ensure_panel(channel: discord.TextChannel) -> None:
    """Posts and pins the registration message once; reuses it if it is already pinned."""
    try:
        for msg in await channel.pins():
            if msg.author.id == client.user.id and PANEL_MARKER in msg.content:
                if msg.content != PANEL_TEXT:
                    await msg.edit(content=PANEL_TEXT, view=RegisterView())
                log.info("registration panel already pinned (message %s)", msg.id)
                return
        msg = await channel.send(PANEL_TEXT, view=RegisterView())
        try:
            await msg.pin(reason="BeanLab registration panel")
        except (discord.Forbidden, discord.HTTPException):
            log.warning("posted the registration panel but could not pin it (Manage Messages?)")
        log.info("posted registration panel (message %s)", msg.id)
    except (discord.Forbidden, discord.HTTPException) as e:
        log.error("could not post the registration panel: %s", e)


@client.event
async def setup_hook():
    client.add_view(RegisterView())  # keep the pinned buttons working across restarts


@client.event
async def on_ready():
    log.info("logged in as %s (%s)", client.user, client.user.id)
    await setup_registration()


@client.event
async def on_guild_join(guild: discord.Guild):
    # Re-invited (e.g. after a kick): set up right away instead of waiting for a restart.
    log.info("joined guild %s (%s)", guild.name, guild.id)
    await setup_registration()


async def setup_registration() -> None:
    """Syncs the slash commands to the registration channel's server and ensures the panel."""
    channel = client.get_channel(CHANNEL_ID)
    if channel is None:
        log.warning(
            "channel %s not found - is the bot in the server? Invite: "
            "https://discord.com/oauth2/authorize?client_id=%s&scope=bot+applications.commands"
            "&permissions=76800",
            CHANNEL_ID,
            client.user.id,
        )
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
            "&scope=bot+applications.commands&permissions=76800",
            guild.id,
            client.user.id,
        )
        return
    await ensure_panel(channel)
    await announce_client_update(channel)


def _read_int(path: pathlib.Path) -> int | None:
    try:
        return int(path.read_text().strip())
    except (OSError, ValueError):
        return None


async def announce_client_update(channel: discord.TextChannel) -> None:
    """Posts once per new client build (deploys restart the bot, so this runs after each one)."""
    current = _read_int(CLIENT_BUILD_FILE)
    if current is None:
        return
    announced = _read_int(ANNOUNCED_BUILD_FILE) or 0
    if current <= announced:
        return
    try:
        await channel.send(
            f"**BeanLab client update: build {current}**\n"
            "Close Microbot, then run the updater in your client folder:\n"
            "```powershell\n"
            'powershell -ExecutionPolicy Bypass -File "$env:USERPROFILE\\Desktop\\OSRS-Microbot-Client'
            '\\Update-Client.ps1"\n```'
            f"No updater yet? Download it from <{UPDATER_URL}> and run it the same way. An "
            "out-of-date client also shows **(NEW CLIENT AVAILABLE)** in its title bar."
        )
    except (discord.Forbidden, discord.HTTPException) as e:
        log.error("could not announce client build %s (will retry next start): %s", current, e)
        return
    ANNOUNCED_BUILD_FILE.write_text(f"{current}\n")
    log.info("announced client build %s", current)


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
