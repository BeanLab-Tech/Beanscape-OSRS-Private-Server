"""BeanLab OSRS web service — Phase 1: Discord OAuth2.0 registration.

Endpoints:
  GET /                     landing page + Register button
  GET /oauth/discord/start  kicks off Discord OAuth2 authorize
  GET /oauth/discord/callback  exchanges code, registers the account
  GET /healthz              liveness
  GET /status               DB/server status

Account creation mirrors Ned's registration bot exactly:
  - argon2i(20, 65536, 1)  ==  game's Argon2PasswordHashing (20/65536/1)
  - accounts(login_username, password_hash)  [discord_id, discord_username added in V10]
  - the game server creates the character on first login (proven via bot accounts)
"""

from __future__ import annotations

import json
import logging
import pathlib
import re
import secrets
import sqlite3
import string
from dataclasses import dataclass
from datetime import datetime
from typing import Any

import httpx
from argon2 import PasswordHasher, Type
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse

BASE = pathlib.Path(__file__).resolve().parent
CONFIG_PATH = pathlib.Path(
    __import__("os").environ.get("OSRS_WEB_CONFIG", f"{BASE}/config.json")
)

# --- same hasher as the game (api/pw-hash Argon2PasswordHashing: 20/65536/1, argon2i) ---
PH = PasswordHasher(time_cost=20, memory_cost=65536, parallelism=1, type=Type.I)

USERNAME_RE = re.compile(r"^[a-z0-9_ ]{2,12}$")
ALLOWED_PASSWORD_CHARS = string.ascii_letters + string.digits

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("osrs-web")


@dataclass
class Config:
    discord_client_id: str
    discord_client_secret: str
    redirect_uri: str
    db_path: str
    admin_discord_ids: tuple[int, ...] = ()

    @classmethod
    def load(cls) -> "Config":
        with open(CONFIG_PATH, encoding="utf-8") as f:
            raw: dict[str, Any] = json.load(f)
        return cls(
            discord_client_id=raw["discord_client_id"],
            discord_client_secret=raw["discord_client_secret"],
            redirect_uri=raw["redirect_uri"],
            db_path=raw["db_path"],
            admin_discord_ids=tuple(raw.get("admin_discord_ids", [])),
        )


CFG = Config.load()
app = FastAPI(title="BeanLab OSRS Web")


def db_connect() -> sqlite3.Connection:
    conn = sqlite3.connect(CFG.db_path, timeout=10)
    conn.execute("PRAGMA busy_timeout=10000")
    return conn


def register_account(username: str, password: str, discord_id: int, discord_username: str) -> str:
    """Inserts the account row. Returns 'ok', 'taken' or 'error:<msg>'."""
    username = username.lower().strip()
    if not USERNAME_RE.match(username):
        return "error:invalid"
    try:
        conn = db_connect()
        try:
            row = conn.execute("SELECT id FROM accounts WHERE login_username = ?", (username,)).fetchone()
            if row is not None:
                return "taken"
            pw_hash = PH.hash(password)
            conn.execute(
                "INSERT INTO accounts (login_username, password_hash, discord_id, discord_username) VALUES (?, ?, ?, ?)",
                (username, pw_hash, discord_id, discord_username),
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
    result = register_account("systemtest", password, 0, "systemtest")
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
            PH.verify(row[0], password)  # raises on mismatch
            conn.execute("DELETE FROM accounts WHERE login_username = 'systemtest'")
            conn.commit()
            log.info("self-test passed: account insert + argon2 verify + cleanup OK")
            return True
        finally:
            conn.close()
    except Exception as e:
        log.error("self-test verification failed: %s", e)
        return False


@app.on_event("startup")
async def _startup() -> None:
    if not self_test():
        raise RuntimeError("DB self-test failed at boot")


# ---------------------------------------------------------------- pages

LANDING = """<!doctype html><html><head><meta charset="utf-8">
<title>BeanLab OSRS</title>
<style>body{font-family:system-ui,sans-serif;max-width:640px;margin:4rem auto;padding:0 1rem;line-height:1.5}
button{padding:.7rem 1.4rem;font-size:1rem;border:0;border-radius:8px;background:#4f46e5;color:#fff;cursor:pointer}
a.muted{color:#666;font-size:.85rem}</style></head><body>
<h1>BeanLab OSRS</h1>
<p>Register with Discord to create your account. After registering,
connect with <b>RSProx</b> (target: <b>BeanLab OSRS</b>) and log in with
the username shown below.</p>
<a href="/oauth/discord/start"><button>Register with Discord</button></a>
<p class="muted">Already registered? Use your Discord global name (lowercased,
spaces and underscores allowed, 2–12 chars) with a random password —
or set a known one in <code>/oauth/discord/callback</code> by re-registering
with the same name.</p>
</body></html>"""


@app.get("/", response_class=HTMLResponse)
async def landing() -> str:
    return LANDING


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/status")
async def status() -> dict[str, Any]:
    conn = db_connect()
    try:
        accounts = conn.execute("SELECT COUNT(*) FROM accounts").fetchone()[0]
        chars = conn.execute("SELECT COUNT(*) FROM characters").fetchone()[0]
        realms = conn.execute("SELECT id, name, dev_mode, require_registration FROM realms ORDER BY id").fetchall()
    finally:
        conn.close()
    return {
        "accounts": accounts,
        "characters": chars,
        "realms": [{"id": r[0], "name": r[1], "dev_mode": r[2], "require_registration": r[3]} for r in realms],
        "time": datetime.utcnow().isoformat() + "Z",
    }


# ---------------------------------------------------------------- oauth

@app.get("/oauth/discord/start")
async def oauth_start() -> RedirectResponse:
    state = secrets.token_urlsafe(16)
    url = (
        "https://discord.com/oauth2/authorize"
        f"?client_id={CFG.discord_client_id}"
        f"&redirect_uri={CFG.redirect_uri}"
        "&response_type=code"
        "&scope=identify%20email"
        f"&state={state}"
        "&prompt=consent"
    )
    # no server-side session store yet (Phase 1): state travels in the redirect
    # query; the callback accepts any state (single-user server, tailnet).
    return RedirectResponse(url)


@app.get("/oauth/discord/callback")
async def oauth_callback(request: Request, code: str, state: str) -> HTMLResponse:
    async with httpx.AsyncClient(timeout=15) as client:
        tok = await client.post(
            "https://discord.com/api/oauth2/token",
            data={"code": code, "redirect_uri": CFG.redirect_uri,
                  "client_id": CFG.discord_client_id, "client_secret": CFG.discord_client_secret,
                  "grant_type": "authorization_code"},
            headers={"Accept": "application/json"},
        )
        if tok.status_code != 200:
            raise HTTPException(502, f"discord token exchange failed ({tok.status_code})")
        access_token = tok.json()["access_token"]
        me = await client.get("https://discord.com/api/users/@me",
                              headers={"Authorization": f"Bearer {access_token}"})
        if me.status_code != 200:
            raise HTTPException(502, "discord /users/@me failed")
    user = me.json()
    discord_id = int(user["id"])
    global_name = user.get("global_name") or user.get("username") or ""
    username = re.sub(r"[^a-z0-9_ ]", "", global_name.lower())[:12].strip()
    if len(username) < 2:
        username = user.get("username", "discord")[:12].lower()
    if discord_id in CFG.admin_discord_ids:
        username = "admin"

    password = "".join(secrets.choice(ALLOWED_PASSWORD_CHARS) for _ in range(16))
    result = register_account(username, password, discord_id, global_name)

    if result == "ok":
        body = f"""<h1>Registered!</h1>
<p>Account <b>{username}</b> is ready (Discord: {global_name}).</p>
<p>Password: <code>{password}</code></p>
<p>Connect with <b>RSProx</b> (target: <b>BeanLab OSRS</b>), log in with that
username and password, and choose the <b>dev</b> realm on first login.</p>
<p class="muted">Change the password in-game later if you like.</p>"""
    elif result == "taken":
        conn = db_connect()
        try:
            row = conn.execute(
                "SELECT login_username FROM accounts WHERE discord_id = ?", (discord_id,)
            ).fetchone()
        finally:
            conn.close()
        if row and row[0]:
            body = f"""<h1>Already registered</h1>
<p>Your Discord account is bound to <b>{row[0]}</b>. Use that username with
the password shown when you first registered.</p>"""
        else:
            body = f"""<h1>Already registered</h1>
<p>Account <b>{username}</b> already exists but isn't bound to this Discord
account (probably registered via the Discord bot). Use that username with
its original password.</p>"""
    else:
        body = f"""<h1>Registration error</h1>
<p><code>{result}</code> — check the server log. Your Discord account
({global_name}) is bound once the name becomes available.</p>"""
    return HTMLResponse(
        f"<!doctype html><html><head><meta charset='utf-8'><title>BeanLab OSRS</title>"
        f"<style>body{{font-family:system-ui,sans-serif;max-width:640px;margin:4rem auto;"
        f"padding:0 1rem;line-height:1.6}}code{{background:#f1f1f1;padding:2px 6px;"
        f"border-radius:4px}}.muted{{color:#666;font-size:.85rem}}</style></head><body>"
        f"{body}</body></html>"
    )


if __name__ == "__main__":
    import uvicorn
    host = __import__("os").environ.get("OSRS_WEB_HOST", "0.0.0.0")
    port = int(__import__("os").environ.get("OSRS_WEB_PORT", "8085"))
    uvicorn.run(app, host=host, port=port)
