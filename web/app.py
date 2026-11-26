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

import html
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

import central_sync

BASE = pathlib.Path(__file__).resolve().parent
CONFIG_PATH = pathlib.Path(
    __import__("os").environ.get("OSRS_WEB_CONFIG", f"{BASE}/config.json")
)

# --- same hasher as the game (api/pw-hash Argon2PasswordHashing: 20/65536/1, argon2i) ---
PH = PasswordHasher(time_cost=20, memory_cost=65536, parallelism=1, type=Type.I)

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


def name_taken(conn: sqlite3.Connection, username: str) -> bool:
    """Taken, or equal ignoring spaces/underscores/case (Central refuses such look-alikes)."""
    key = central_sync.collision_key(username)
    row = conn.execute(
        "SELECT 1 FROM accounts WHERE login_username = ? "
        "OR lower(replace(replace(login_username, ' ', ''), '_', '')) = ? LIMIT 1",
        (username, key),
    ).fetchone()
    return row is not None


def pick_username(*candidates: str) -> str | None:
    """First Discord-derived name Central would accept as a login name."""
    for raw in candidates:
        name = central_sync.clean_login_name(re.sub(r"[^a-z0-9 ]", "", (raw or "").lower()))[:12].strip()
        if name and central_sync.name_problem(name) is None:
            return name
    return None


def register_account(username: str, password: str, discord_id: int, discord_username: str,
                     discord_avatar: str | None = None, mirror: bool = True) -> str:
    """Inserts the account row (and copies it to OpenRune Central unless `mirror` is False).
    Returns 'ok', 'taken' or 'error:<msg>'."""
    username = central_sync.clean_login_name(username)
    if central_sync.name_problem(username) is not None:
        return "error:invalid"
    try:
        conn = db_connect()
        try:
            if name_taken(conn, username):
                return "taken"
            pw_hash = PH.hash(password)
            conn.execute(
                "INSERT INTO accounts (login_username, password_hash, discord_id, discord_username, discord_avatar) "
                "VALUES (?, ?, ?, ?, ?)",
                (username, pw_hash, discord_id, discord_username, discord_avatar),
            )
            conn.commit()
        finally:
            conn.close()
    except sqlite3.Error as e:
        log.error("db error during registration: %s", e)
        return "error:db"
    if mirror:
        central_sync.mirror_signup(username, pw_hash, discord_id or None)
    return "ok"


# Reserved for the boot self-test: sign-up can never create it (the underscore fails the name
# rules), so deleting it can't remove a player's account.
SELF_TEST_NAME = "_selftest_"


def self_test() -> bool:
    """Boot self-check: insert a throwaway account, verify its Argon2 hash, delete it."""
    password = "selftest-" + secrets.token_hex(4)
    try:
        conn = db_connect()
        try:
            conn.execute("DELETE FROM accounts WHERE login_username = ?", (SELF_TEST_NAME,))
            conn.execute(
                "INSERT INTO accounts (login_username, password_hash) VALUES (?, ?)",
                (SELF_TEST_NAME, PH.hash(password)),
            )
            row = conn.execute(
                "SELECT password_hash FROM accounts WHERE login_username = ?", (SELF_TEST_NAME,)
            ).fetchone()
            if row is None:
                log.error("self-test: inserted row not found")
                return False
            PH.verify(row[0], password)  # raises on mismatch
            conn.execute("DELETE FROM accounts WHERE login_username = ?", (SELF_TEST_NAME,))
            conn.commit()
        finally:
            conn.close()
        log.info("self-test passed: account insert + argon2 verify + cleanup OK")
        return True
    except Exception as e:
        log.error("self-test failed: %s", e)
        return False


@app.on_event("startup")
async def _startup() -> None:
    if not self_test():
        raise RuntimeError("DB self-test failed at boot")


# ---------------------------------------------------------------- pages

LANDING = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>BeanLab OSRS</title>
<style>
:root { --bg:#1a1a2e; --surface:#16213e; --accent:#e94560; --text:#eaeaea; --muted:#8888aa; --border:#2a2a4a; }
* { box-sizing:border-box; margin:0; padding:0; }
body { font-family:"Segoe UI",system-ui,sans-serif; background:var(--bg); color:var(--text); min-height:100vh; display:flex; align-items:center; justify-content:center; }
.card { background:var(--surface); border:1px solid var(--border); border-radius:12px; padding:2.5rem; max-width:480px; width:100%; margin:2rem 1rem; text-align:center; }
h1 { font-size:1.8rem; margin-bottom:.5rem; letter-spacing:-.02em; }
h1 span { color:var(--accent); }
.sub { color:var(--muted); font-size:.9rem; margin-bottom:1.5rem; line-height:1.5; }
.btn { display:inline-block; padding:.8rem 2rem; border:0; border-radius:8px; background:var(--accent); color:#fff; font-size:1rem; font-weight:600; cursor:pointer; text-decoration:none; transition:opacity .15s; }
.btn:hover { opacity:.85; }
.div { border:none; border-top:1px solid var(--border); margin:1.5rem 0; }
.hint { color:var(--muted); font-size:.8rem; line-height:1.5; text-align:left; }
.hint code { background:var(--bg); padding:1px 5px; border-radius:4px; font-size:.8rem; }
.badge { display:inline-block; padding:.2rem .6rem; border-radius:4px; font-size:.75rem; font-weight:600; background:rgba(233,69,96,.15); color:var(--accent); margin-bottom:1rem; }
</style>
</head>
<body>
<div class="card">
  <div class="badge">Private Server</div>
  <h1><span>BeanLab</span> OSRS</h1>
  <p class="sub">Register with Discord to create your account. After registering, connect with <b>RSProx</b> (target: <b>BeanLab OSRS</b>) and log in with the username shown below.</p>
  <a href="/oauth/discord/start" class="btn">Register with Discord</a>
  <hr class="div">
  <div class="hint"><b>Already registered?</b><br>Use your Discord global name (lowercased, 2-12 chars) with the password shown at registration. Or reset via the <code>/admin</code> panel.</div>
</div>
</body>
</html>"""


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

STATE_COOKIE = "beanlab_oauth_state"


@app.get("/oauth/discord/start")
async def oauth_start() -> RedirectResponse:
    state = secrets.token_urlsafe(32)
    url = (
        "https://discord.com/oauth2/authorize"
        f"?client_id={CFG.discord_client_id}"
        f"&redirect_uri={CFG.redirect_uri}"
        "&response_type=code"
        "&scope=identify%20email"
        f"&state={state}"
        "&prompt=consent"
    )
    # CSRF protection without a session store: the state also goes in an HttpOnly cookie, and the
    # callback only proceeds when the query state matches it. SameSite=Lax still sends the cookie
    # on Discord's top-level redirect back to us.
    resp = RedirectResponse(url)
    resp.set_cookie(
        STATE_COOKIE,
        state,
        max_age=600,
        httponly=True,
        samesite="lax",
        secure=CFG.redirect_uri.startswith("https://"),
        path="/oauth/discord",
    )
    return resp


@app.get("/oauth/discord/callback")
async def oauth_callback(
    request: Request,
    code: str = "",
    state: str = "",
    error: str = "",
    error_description: str = "",
) -> HTMLResponse:
    if error or not code:
        # Discord sends ?error=... when the user cancels or the authorize URL is wrong
        # (e.g. a bot-invite link with this redirect_uri and extra scopes).
        log.warning("oauth callback without code: error=%r description=%r", error, error_description)
        detail = html.escape(error_description or error or "no authorization code")
        resp = HTMLResponse(
            "<!doctype html><html><head><meta charset='utf-8'><title>BeanLab OSRS</title></head>"
            "<body style='font-family:system-ui,sans-serif;max-width:640px;margin:4rem auto'>"
            f"<h1>Discord sign-in didn't complete</h1><p>Discord said: <code>{detail}</code></p>"
            "<p>To register, use the <b>Register</b> button in the Discord registration channel, "
            "or start again from <a href='/oauth/discord/start'>/oauth/discord/start</a>.</p>"
            "<p>Inviting the bot? Use a bot invite link (scopes <code>bot applications.commands</code>, "
            "no redirect URI), not this page.</p></body></html>",
            status_code=400,
        )
        resp.headers["Cache-Control"] = "no-store"
        return resp
    expected = request.cookies.get(STATE_COOKIE, "")
    if not state or not expected or not secrets.compare_digest(state, expected):
        log.warning("oauth callback rejected: missing or mismatched state")
        raise HTTPException(
            400, "Sign-in link expired or invalid. Start again from the registration page."
        )
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
    username = pick_username(global_name, user.get("username", ""))
    if discord_id in CFG.admin_discord_ids:
        username = "admin"
    if username is None:
        resp = HTMLResponse(
            "<!doctype html><html><head><meta charset='utf-8'><title>BeanLab OSRS</title></head>"
            "<body style='font-family:system-ui,sans-serif;max-width:640px;margin:4rem auto'>"
            "<h1>Pick a login name</h1><p>Your Discord name can't be used as a login name "
            "(letters, numbers and spaces only, at most 12, no \"mod\" or staff-looking names). "
            "Register with <b>/register</b> in the Discord registration channel and choose one.</p>"
            "</body></html>",
            status_code=400,
        )
        resp.delete_cookie(STATE_COOKIE, path="/oauth/discord")
        resp.headers["Cache-Control"] = "no-store"
        return resp

    password = "".join(secrets.choice(ALLOWED_PASSWORD_CHARS) for _ in range(16))
    avatar_hash = user.get("avatar") or None  # Discord CDN hash; the admin console shows it
    result = register_account(username, password, discord_id, global_name, avatar_hash)

    # Discord names are user-controlled: escape everything that goes into the page.
    e_user, e_name, e_pw = html.escape(username), html.escape(global_name), html.escape(password)
    if result == "ok":
        body = f"""<h1>Registered!</h1>
<p>Account <b>{e_user}</b> is ready (Discord: {e_name}).</p>
<p>Password: <code>{e_pw}</code></p>
<p>Launch the BeanLab client (Microbot, or RSProx with target <b>BeanLab OSRS</b>) and log in
with that username and password. Save the password now: it is only shown once.</p>
<p class="muted">Change the password in-game later if you like.</p>"""
    elif result == "taken":
        conn = db_connect()
        try:
            # Keep the stored picture current for accounts already bound to this Discord user.
            conn.execute("UPDATE accounts SET discord_avatar = ? WHERE discord_id = ?", (avatar_hash, discord_id))
            conn.commit()
            row = conn.execute(
                "SELECT login_username FROM accounts WHERE discord_id = ?", (discord_id,)
            ).fetchone()
        finally:
            conn.close()
        if row and row[0]:
            body = f"""<h1>Already registered</h1>
<p>Your Discord account is bound to <b>{html.escape(row[0])}</b>. Use that username with
the password shown when you first registered.</p>"""
        else:
            body = f"""<h1>Already registered</h1>
<p>Account <b>{e_user}</b> already exists but isn't bound to this Discord
account (probably registered via the Discord bot). Use that username with
its original password.</p>"""
    else:
        body = f"""<h1>Registration error</h1>
<p><code>{html.escape(result)}</code> — check the server log. Your Discord account
({e_name}) is bound once the name becomes available.</p>"""
    resp = HTMLResponse(
        f"<!doctype html><html><head><meta charset='utf-8'><title>BeanLab OSRS</title>"
        f"<style>body{{font-family:system-ui,sans-serif;max-width:640px;margin:4rem auto;"
        f"padding:0 1rem;line-height:1.6}}code{{background:#f1f1f1;padding:2px 6px;"
        f"border-radius:4px}}.muted{{color:#666;font-size:.85rem}}</style></head><body>"
        f"{body}</body></html>"
    )
    resp.delete_cookie(STATE_COOKIE, path="/oauth/discord")  # state is single-use
    resp.headers["Cache-Control"] = "no-store"  # page may contain a password
    return resp


if __name__ == "__main__":
    import uvicorn
    host = __import__("os").environ.get("OSRS_WEB_HOST", "0.0.0.0")
    port = int(__import__("os").environ.get("OSRS_WEB_PORT", "8085"))
    uvicorn.run(app, host=host, port=port)
