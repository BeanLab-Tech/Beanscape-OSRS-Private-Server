"""BeanLab OSRS Admin Panel.

Web-based admin dashboard for managing accounts, stats, items, and realms.
Runs on port 8086, served via tailscale serve at /admin.
"""

from __future__ import annotations

import hmac
import httpx
import json
import logging
import os
import pathlib
import secrets
import sqlite3
import subprocess
import time
from datetime import datetime, timedelta
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.templating import Jinja2Templates

BASE = pathlib.Path(__file__).resolve().parent
CONFIG_PATH = BASE / "admin_config.json"
TEMPLATES_DIR = BASE / "admin_templates"

# fine_xp = xp * XP_FINE_PRECISION (see PlayerStatMap.XP_FINE_PRECISION = 10)
XP_FINE_PRECISION = 10
# osrs-server systemd unit for live service status / log tail
SERVICE_UNIT = os.environ.get("OSRS_SERVICE_UNIT", "osrs-server")

STAT_NAMES = [
    "Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer",
    "Magic", "Cooking", "Woodcutting", "Fletching", "Fishing",
    "Firemaking", "Crafting", "Smithing", "Mining", "Herblore",
    "Agility", "Thieving", "Slayer", "Farming", "Runecrafting",
    "Hunter", "Construction", "Sailing", "Unreleased",
]

OBJ_PATH = pathlib.Path(os.environ.get("OSRS_OBJ_SYM", str(BASE / "obj.sym")))
OSRS_API = "https://osrs-api.t-cs.co"

def load_obj_names() -> dict[int, str]:
    names = {}
    if OBJ_PATH.exists():
        with open(OBJ_PATH) as f:
            for line in f:
                parts = line.strip().split("\t", 1)
                if len(parts) == 2:
                    try:
                        names[int(parts[0])] = parts[1]
                    except ValueError:
                        pass
    return names

OBJ_NAMES = load_obj_names()

def build_xp_table() -> list[int]:
    """Mirror the engine's PlayerSkillXPTable exactly.

    engine:  accumulatedXp += (level + 300 * 2.0^(level/7)).toInt()
             table[level]   = accumulatedXp / 4   (integer)
    Verified: level 9 = 1154, level 10 = 1358, level 99 = 14391160.
    """
    table = [0] * 128
    accumulated = 0
    for level in range(1, 100):
        accumulated += int(level + 300 * 2.0 ** (level / 7.0))
        table[level] = accumulated // 4
    return table

XP_TABLE = build_xp_table()

def xp_for_level(level: int) -> int:
    if level <= 1:
        return 0
    if level > 99:
        level = 99
    return XP_TABLE[level]

MOD_LEVELS = ["player", "moderator", "admin", "owner"]

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("osrs-admin")


class AdminConfig:
    def __init__(self, raw: dict):
        self.admin_username = raw["admin_username"]
        self.admin_password = raw["admin_password"]
        self.db_path = raw["db_path"]
        self.session_secret = raw.get("session_secret", secrets.token_hex(32))
        # Game accounts allowed to sign in to the panel (must also be unbanned owners). Editable only
        # on the VM, so a game modlevel alone never grants panel access.
        self.panel_users = {str(u).strip().lower() for u in raw.get("panel_users", [])}

    @classmethod
    def load(cls) -> "AdminConfig":
        with open(CONFIG_PATH, encoding="utf-8") as f:
            return cls(json.load(f))


CFG = AdminConfig.load()
app = FastAPI(title="BeanLab OSRS Admin")
templates = Jinja2Templates(directory=str(TEMPLATES_DIR))


def render(name: str, request: Request, context: dict | None = None) -> HTMLResponse:
    ctx = {"request": request}
    if context:
        ctx.update(context)
    try:
        return templates.TemplateResponse(request, name, ctx)
    except TypeError:
        return templates.TemplateResponse(name, ctx)


def db_connect() -> sqlite3.Connection:
    conn = sqlite3.connect(CFG.db_path, timeout=15)
    conn.execute("PRAGMA busy_timeout=15000")
    conn.execute("PRAGMA journal_mode=WAL")
    conn.row_factory = sqlite3.Row
    return conn


SESSIONS: dict[str, dict] = {}


def make_session(username: str, game_account: bool = False) -> str:
    token = secrets.token_hex(32)
    SESSIONS[token] = {"user": username, "created": time.time(), "game": game_account}
    return token


def verify_session(token: str | None) -> bool:
    if not token:
        return False
    if token not in SESSIONS:
        return False
    if time.time() - SESSIONS[token]["created"] > 43200:
        del SESSIONS[token]
        return False
    return True


def require_admin(request: Request) -> str:
    token = request.cookies.get("admin_session")
    if not verify_session(token):
        raise HTTPException(status_code=307, headers={"Location": "/admin/login"})
    session = SESSIONS[token]
    # Game-account sessions are re-checked on every request, so a demotion, ban or deletion takes
    # effect immediately instead of when the 12h session expires.
    if session.get("game") and not game_account_is_panel_staff(session["user"]):
        SESSIONS.pop(token, None)
        log.warning("admin panel session revoked for %s (no longer an unbanned owner)", session["user"])
        raise HTTPException(status_code=307, headers={"Location": "/admin/login"})
    return session["user"]


def obj_name(obj_id: int) -> str:
    return OBJ_NAMES.get(obj_id, f"obj_{obj_id}")


def _form_int(form, key: str, default: int = 0) -> int:
    """Safe int coercion for form values (which may be UploadFile or str)."""
    v = form.get(key)
    if v is None or hasattr(v, "read"):
        return default
    try:
        return int(str(v))
    except (ValueError, TypeError):
        return default


def item_image_url(obj_id: int) -> str:
    return f"https://osrs-images.t-cs.co/items/{obj_id}.png"


def search_osrs_items(query: str, limit: int = 20) -> list[dict]:
    try:
        r = httpx.get(f"{OSRS_API}/items", params={"search": query, "limit": limit},
                      timeout=8, headers={"User-Agent": "BeanLabAdmin/1.0"})
        if r.status_code == 200:
            return r.json().get("items", [])
    except Exception as e:
        log.warning("OSRS-API search failed: %s", e)
    return []


def service_status() -> dict:
    """Live status of the osrs-server systemd unit."""
    def _run(cmd: list) -> str:
        try:
            return subprocess.run(cmd, capture_output=True, text=True, timeout=10).stdout.strip()
        except (subprocess.SubprocessError, FileNotFoundError):
            return "unknown"
    active = _run(["systemctl", "is-active", SERVICE_UNIT])
    enabled = _run(["systemctl", "is-enabled", SERVICE_UNIT])
    return {"unit": SERVICE_UNIT, "active": active, "enabled": enabled}


def log_tail(n: int = 80) -> list[str]:
    """Last N lines of the osrs-server journal."""
    try:
        out = subprocess.run(
            ["journalctl", "-u", SERVICE_UNIT, "-n", str(n), "--no-pager", "-q"],
            capture_output=True, text=True, timeout=10,
        ).stdout
    except (subprocess.SubprocessError, FileNotFoundError):
        return []
    return out.splitlines()[-n:]


# --- login ---
@app.get("/admin/login", response_class=HTMLResponse)
async def login_page(request: Request, error: str = "") -> HTMLResponse:
    return render("login.html", request, {"error": error, "user": None})


# Game accounts with this modlevel may also sign in to the panel (password checked against the same
# Argon2i hash the game server uses). Owner only: the panel can create accounts and set modlevels, so
# letting admins in would let them create owners. The account must also be listed in
# admin_config.json "panel_users". The admin_config.json login keeps working.
PANEL_MODLEVELS = ("owner",)

_PANEL_HASHER = None
_DUMMY_HASH = None
# Lockout within the window: per client IP (the real tailnet address; tailscale serve forwards it)
# and, more loosely, per game username. The config admin is only limited per IP, so nobody can lock
# it out by spamming its username.
LOGIN_MAX_FAILURES_PER_IP = 5
LOGIN_MAX_FAILURES_PER_USER = 20
LOGIN_WINDOW_SECONDS = 900
_LOGIN_FAILURES: dict[str, list[float]] = {}
# Each Argon2 verify uses 64 MB; cap how many run at once so login spam can't exhaust memory.
_ARGON2_SLOTS = None


def _argon2_slots():
    global _ARGON2_SLOTS
    if _ARGON2_SLOTS is None:
        import asyncio

        _ARGON2_SLOTS = asyncio.Semaphore(2)
    return _ARGON2_SLOTS


def _hasher():
    global _PANEL_HASHER, _DUMMY_HASH
    if _PANEL_HASHER is None:
        from argon2 import PasswordHasher, Type

        _PANEL_HASHER = PasswordHasher(time_cost=20, memory_cost=65536, parallelism=1, type=Type.I)
        _DUMMY_HASH = _PANEL_HASHER.hash(secrets.token_hex(16))
    return _PANEL_HASHER


def _staff_row(username: str):
    conn = db_connect()
    try:
        return conn.execute(
            "SELECT password_hash, modlevel, banned FROM accounts WHERE login_username = ?",
            (username.strip().lower(),),
        ).fetchone()
    finally:
        conn.close()


def _eligible(username: str, row) -> bool:
    return (
        username.strip().lower() in CFG.panel_users
        and row is not None
        and row["modlevel"] in PANEL_MODLEVELS
        and not row["banned"]
    )


def game_account_is_panel_staff(username: str) -> bool:
    return _eligible(username, _staff_row(username))


def verify_game_staff(username: str, password: str) -> bool:
    """True if `username` is a non-banned owner game account and `password` matches its hash.

    Always runs one Argon2 verify, against a dummy hash when the account is missing or ineligible,
    so failures take the same time and don't reveal which usernames exist or are staff.
    """
    from argon2.exceptions import Argon2Error

    hasher = _hasher()
    row = _staff_row(username)
    eligible = _eligible(username, row)
    try:
        ok = hasher.verify(row["password_hash"] if eligible else _DUMMY_HASH, password)
    except Argon2Error:
        ok = False
    return ok and eligible


def _recent_failures(key: str, now: float) -> list[float]:
    times = [t for t in _LOGIN_FAILURES.get(key, []) if now - t < LOGIN_WINDOW_SECONDS]
    _LOGIN_FAILURES[key] = times
    return times


@app.post("/admin/login")
async def login_submit(request: Request) -> RedirectResponse:
    from starlette.concurrency import run_in_threadpool

    form = await request.form()
    username = str(form.get("username", ""))
    password = str(form.get("password", ""))
    source = request.client.host if request.client else "?"
    limits = {f"ip:{source}": LOGIN_MAX_FAILURES_PER_IP}
    if username.strip().lower() != CFG.admin_username.strip().lower():
        limits[f"user:{username.strip().lower()}"] = LOGIN_MAX_FAILURES_PER_USER
    now = time.time()
    if any(len(_recent_failures(k, now)) >= limit for k, limit in limits.items()):
        log.warning("admin panel login locked out for %r from %s", username, source)
        return RedirectResponse("/admin/login?error=Too+many+attempts,+try+again+later", status_code=302)
    # Count this attempt now, before any await, so concurrent requests can't all pass the check
    # above while their failures are still pending. A success clears the counts below.
    for k in limits:
        _LOGIN_FAILURES.setdefault(k, []).append(now)
    keys = tuple(limits)

    game_account = False
    ok = hmac.compare_digest(username, CFG.admin_username) and hmac.compare_digest(password, CFG.admin_password)
    if not ok and username and password:
        # Argon2 with t=20 takes ~1s: keep it off the event loop.
        async with _argon2_slots():
            ok = await run_in_threadpool(verify_game_staff, username, password)
        if ok:
            username, game_account = username.strip().lower(), True
    if ok:
        for k in keys:
            _LOGIN_FAILURES.pop(k, None)
        log.info("admin panel login: %s from %s", username, source)
        token = make_session(username, game_account)
        resp = RedirectResponse("/admin/", status_code=302)
        resp.set_cookie("admin_session", token, httponly=True, samesite="strict", max_age=43200)
        return resp
    log.warning("admin panel login failed for %r from %s", username, source)
    return RedirectResponse("/admin/login?error=Invalid+credentials", status_code=302)


@app.get("/admin/logout")
async def logout(request: Request) -> RedirectResponse:
    SESSIONS.pop(request.cookies.get("admin_session") or "", None)
    resp = RedirectResponse("/admin/login", status_code=302)
    resp.delete_cookie("admin_session")
    return resp


# --- dashboard ---
@app.get("/admin/", response_class=HTMLResponse)
async def dashboard(request: Request) -> HTMLResponse:
    admin = require_admin(request)
    conn = db_connect()
    try:
        account_count = conn.execute("SELECT COUNT(*) as c FROM accounts").fetchone()["c"]
        char_count = conn.execute("SELECT COUNT(*) as c FROM characters").fetchone()["c"]
        banned_count = conn.execute("SELECT COUNT(*) as c FROM accounts WHERE banned=1").fetchone()["c"]
        recent_accounts = conn.execute(
            "SELECT id, login_username, display_name, modlevel, created_at, banned FROM accounts ORDER BY created_at DESC LIMIT 10"
        ).fetchall()
        realms = conn.execute("SELECT * FROM realms ORDER BY id").fetchall()
    finally:
        conn.close()

    return render("dashboard.html", request, {
        "admin": admin,
        "active_nav": "dashboard",
        "accounts": account_count,
        "chars": char_count,
        "banned_count": banned_count,
        "recent_accounts": [dict(r) for r in recent_accounts],
        "realms": [dict(r) for r in realms],
        "service": service_status(),
        "logs": log_tail(60),
        "now": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    })


# --- accounts ---
@app.get("/admin/accounts", response_class=HTMLResponse)
async def accounts_page(request: Request) -> HTMLResponse:
    admin = require_admin(request)
    conn = db_connect()
    try:
        accounts = conn.execute("""
            SELECT a.*, c.id as char_id, c.realm_id
            FROM accounts a LEFT JOIN characters c ON c.account_id = a.id
            ORDER BY a.created_at DESC
        """).fetchall()
    finally:
        conn.close()
    return render("accounts.html", request, {
        "admin": admin,
        "active_nav": "accounts",
        "accounts": [dict(a) for a in accounts],
        "mod_levels": MOD_LEVELS,
    })


@app.get("/admin/accounts/new", response_class=HTMLResponse)
async def account_new_page(request: Request) -> HTMLResponse:
    admin = require_admin(request)
    return render("account_new.html", request, {
        "admin": admin,
        "active_nav": "accounts",
    })


@app.post("/admin/accounts/new")
async def create_account(request: Request) -> RedirectResponse:
    admin = require_admin(request)
    form = await request.form()
    username = form.get("username", "").lower().strip()
    password = form.get("password", "")
    display_name = form.get("display_name", "").strip() or None
    modlevel = form.get("modlevel", "player")
    if not username or not password:
        return RedirectResponse("/admin/accounts/new?error=Username+and+password+required", status_code=302)
    if modlevel not in MOD_LEVELS:
        return RedirectResponse(f"/admin/accounts/new?error=Invalid+modlevel", status_code=302)
    from argon2 import PasswordHasher, Type
    PH = PasswordHasher(time_cost=20, memory_cost=65536, parallelism=1, type=Type.I)
    pw_hash = PH.hash(password)
    conn = db_connect()
    try:
        row = conn.execute("SELECT id FROM accounts WHERE login_username=?", (username,)).fetchone()
        if row:
            return RedirectResponse(f"/admin/accounts/new?error=Account+{username}+already+exists", status_code=302)
        conn.execute(
            "INSERT INTO accounts (login_username, password_hash, display_name, modlevel) VALUES (?, ?, ?, ?)",
            (username, pw_hash, display_name, modlevel),
        )
        conn.commit()
    finally:
        conn.close()
    log.info("created account %s (modlevel=%s) by %s", username, modlevel, admin)
    return RedirectResponse("/admin/accounts", status_code=302)


@app.get("/admin/accounts/{account_id}", response_class=HTMLResponse)
async def account_detail(request: Request, account_id: int) -> HTMLResponse:
    admin = require_admin(request)
    conn = db_connect()
    try:
        acct = conn.execute("SELECT * FROM accounts WHERE id=?", (account_id,)).fetchone()
        if not acct:
            raise HTTPException(404, "Account not found")
        acct = dict(acct)
        chars = conn.execute("SELECT * FROM characters WHERE account_id=?", (account_id,)).fetchall()
        char_list = []
        for ch in chars:
            ch_data = dict(ch)
            stats = conn.execute("SELECT * FROM stats WHERE character_id=? ORDER BY stat_id", (ch["id"],)).fetchall()
            ch_data["stats"] = {STAT_NAMES[i]: s["vis_level"] for i, s in enumerate(stats) if i < len(STAT_NAMES)}
            invs = conn.execute("SELECT id, inv_type FROM inventories WHERE character_id=?", (ch["id"],)).fetchall()
            inv_items = {}
            for inv in invs:
                items = conn.execute(
                    "SELECT obj, count FROM inventory_objs WHERE inventories_id=?", (inv["id"],)
                ).fetchall()
                inv_items[inv["inv_type"]] = [dict(i) for i in items]
            ch_data["inventories"] = inv_items
            char_list.append(ch_data)
    finally:
        conn.close()

    return render("account_detail.html", request, {
        "admin": admin,
        "active_nav": "accounts",
        "account": acct,
        "characters": char_list,
        "stat_names": STAT_NAMES,
        "obj_name": obj_name,
    })


@app.post("/admin/accounts/{account_id}/modlevel")
async def set_modlevel(account_id: int, request: Request) -> RedirectResponse:
    admin = require_admin(request)
    form = await request.form()
    modlevel = form.get("modlevel", "player")
    if modlevel not in MOD_LEVELS:
        raise HTTPException(400, f"Invalid modlevel: {modlevel}")
    conn = db_connect()
    try:
        conn.execute("UPDATE accounts SET modlevel=? WHERE id=?", (modlevel, account_id))
        conn.commit()
    finally:
        conn.close()
    log.info("set modlevel %s for account %d by %s", modlevel, account_id, admin)
    return RedirectResponse(f"/admin/accounts/{account_id}", status_code=302)


@app.post("/admin/accounts/{account_id}/password")
async def set_password(account_id: int, request: Request) -> RedirectResponse:
    admin = require_admin(request)
    form = await request.form()
    new_password = form.get("password", "")
    if len(new_password) < 4:
        raise HTTPException(400, "Password too short")
    from argon2 import PasswordHasher, Type
    PH = PasswordHasher(time_cost=20, memory_cost=65536, parallelism=1, type=Type.I)
    pw_hash = PH.hash(new_password)
    conn = db_connect()
    try:
        conn.execute("UPDATE accounts SET password_hash=? WHERE id=?", (pw_hash, account_id))
        conn.commit()
    finally:
        conn.close()
    log.info("set password for account %d by %s", account_id, admin)
    return RedirectResponse(f"/admin/accounts/{account_id}", status_code=302)


@app.post("/admin/accounts/{account_id}/ban")
async def ban_account(account_id: int, request: Request) -> RedirectResponse:
    admin = require_admin(request)
    form = await request.form()
    reason = form.get("reason", "").strip()
    conn = db_connect()
    try:
        conn.execute("UPDATE accounts SET banned=1, ban_reason=? WHERE id=?", (reason or "Banned", account_id))
        conn.commit()
    finally:
        conn.close()
    log.info("banned account %d by %s", account_id, admin)
    return RedirectResponse(f"/admin/accounts/{account_id}", status_code=302)


@app.post("/admin/accounts/{account_id}/unban")
async def unban_account(account_id: int, request: Request) -> RedirectResponse:
    admin = require_admin(request)
    conn = db_connect()
    try:
        conn.execute("UPDATE accounts SET banned=0, ban_reason=NULL WHERE id=?", (account_id,))
        conn.commit()
    finally:
        conn.close()
    log.info("unbanned account %d by %s", account_id, admin)
    return RedirectResponse(f"/admin/accounts/{account_id}", status_code=302)


@app.post("/admin/accounts/{account_id}/delete")
async def delete_account(account_id: int, request: Request) -> RedirectResponse:
    admin = require_admin(request)
    conn = db_connect()
    try:
        char_ids = [r["id"] for r in conn.execute(
            "SELECT id FROM characters WHERE account_id=?", (account_id,)
        ).fetchall()]
        for cid in char_ids:
            inv_ids = [r["id"] for r in conn.execute(
                "SELECT id FROM inventories WHERE character_id=?", (cid,)
            ).fetchall()]
            for iid in inv_ids:
                conn.execute("DELETE FROM inventory_objs WHERE inventories_id=?", (iid,))
            conn.execute("DELETE FROM inventories WHERE character_id=?", (cid,))
            conn.execute("DELETE FROM stats WHERE character_id=?", (cid,))
        conn.execute("DELETE FROM characters WHERE account_id=?", (account_id,))
        conn.execute("DELETE FROM accounts WHERE id=?", (account_id,))
        conn.commit()
    finally:
        conn.close()
    log.info("deleted account %d by %s", account_id, admin)
    return RedirectResponse("/admin/accounts", status_code=302)


# --- characters ---
@app.get("/admin/characters", response_class=HTMLResponse)
async def characters_page(request: Request) -> HTMLResponse:
    admin = require_admin(request)
    conn = db_connect()
    try:
        characters = conn.execute("""
            SELECT c.*, a.login_username, a.display_name, a.modlevel, a.banned
            FROM characters c
            JOIN accounts a ON a.id = c.account_id
            ORDER BY c.last_login DESC
        """).fetchall()
    finally:
        conn.close()
    return render("characters.html", request, {
        "admin": admin,
        "active_nav": "characters",
        "characters": [dict(c) for c in characters],
        "stat_names": STAT_NAMES,
    })


@app.get("/admin/characters/{char_id}", response_class=HTMLResponse)
async def character_detail(request: Request, char_id: int) -> HTMLResponse:
    admin = require_admin(request)
    conn = db_connect()
    try:
        ch = conn.execute("""
            SELECT c.*, a.login_username, a.display_name, a.modlevel, a.banned
            FROM characters c JOIN accounts a ON a.id = c.account_id WHERE c.id=?
        """, (char_id,)).fetchone()
        if not ch:
            raise HTTPException(404, "Character not found")
        ch = dict(ch)
        stats = conn.execute("SELECT * FROM stats WHERE character_id=? ORDER BY stat_id", (char_id,)).fetchall()
        ch["stats"] = {
            STAT_NAMES[i]: {"vis": s["vis_level"], "base": s["base_level"], "xp": s["fine_xp"] // XP_FINE_PRECISION}
            for i, s in enumerate(stats) if i < len(STAT_NAMES)
        }
        invs = conn.execute("SELECT id, inv_type FROM inventories WHERE character_id=?", (char_id,)).fetchall()
        ch["inventories"] = {}
        for inv in invs:
            items = conn.execute(
                "SELECT obj, count FROM inventory_objs WHERE inventories_id=?", (inv["id"],)
            ).fetchall()
            ch["inventories"][inv["inv_type"]] = [
                {"obj": i["obj"], "count": i["count"], "name": obj_name(i["obj"]),
                 "img": item_image_url(i["obj"])}
                for i in items
            ]
    finally:
        conn.close()

    return render("character_detail.html", request, {
        "admin": admin,
        "active_nav": "characters",
        "char": ch,
        "stat_names": STAT_NAMES,
    })


@app.post("/admin/characters/{char_id}/stats")
async def set_stats(char_id: int, request: Request) -> RedirectResponse:
    admin = require_admin(request)
    form = await request.form()
    conn = db_connect()
    try:
        for i in range(len(STAT_NAMES)):
            key = f"stat_{i}"
            if key in form:
                raw = form[key].strip()
                if raw:
                    try:
                        level = int(raw)
                        if level < 1 or level > 99:
                            raise HTTPException(400, f"Level out of range: {STAT_NAMES[i]}={level}")
                        xp = xp_for_level(level)
                        fine_xp = xp * XP_FINE_PRECISION
                        conn.execute(
                            "UPDATE stats SET vis_level=?, base_level=?, fine_xp=? WHERE character_id=? AND stat_id=?",
                            (level, level, fine_xp, char_id, i),
                        )
                        conn.commit()
                    except ValueError:
                        raise HTTPException(400, f"Invalid level for {STAT_NAMES[i]}: {raw}")
    finally:
        conn.close()
    log.info("updated stats for character %d by %s", char_id, admin)
    return RedirectResponse(f"/admin/characters/{char_id}", status_code=302)


@app.post("/admin/characters/{char_id}/master")
async def master_stats(char_id: int, request: Request) -> RedirectResponse:
    admin = require_admin(request)
    conn = db_connect()
    try:
        xp99 = xp_for_level(99) * XP_FINE_PRECISION
        conn.execute(
            "UPDATE stats SET vis_level=99, base_level=99, fine_xp=? WHERE character_id=?",
            (xp99, char_id),
        )
        conn.commit()
    finally:
        conn.close()
    log.info("mastered character %d by %s", char_id, admin)
    return RedirectResponse(f"/admin/characters/{char_id}", status_code=302)


@app.post("/admin/characters/{char_id}/reset")
async def reset_stats(char_id: int, request: Request) -> RedirectResponse:
    admin = require_admin(request)
    conn = db_connect()
    try:
        conn.execute(
            "UPDATE stats SET vis_level=1, base_level=1, fine_xp=0 WHERE character_id=?",
            (char_id,),
        )
        conn.commit()
    finally:
        conn.close()
    log.info("reset character %d by %s", char_id, admin)
    return RedirectResponse(f"/admin/characters/{char_id}", status_code=302)


# --- items ---
@app.post("/admin/characters/{char_id}/mute")
async def mute_char(char_id: int, request: Request) -> RedirectResponse:
    """Mute a character for 1 hour (or unmute if already muted)."""
    admin = require_admin(request)
    conn = db_connect()
    try:
        row = conn.execute("SELECT muted_until FROM characters WHERE id=?", (char_id,)).fetchone()
        if not row:
            raise HTTPException(404, "Character not found")
        if row["muted_until"]:
            conn.execute("UPDATE characters SET muted_until=NULL WHERE id=?", (char_id,))
            msg = "unmuted"
        else:
            until = (datetime.utcnow() + timedelta(hours=1)).strftime("%Y-%m-%d %H:%M:%S")
            conn.execute("UPDATE characters SET muted_until=? WHERE id=?", (until, char_id))
            msg = f"muted until {until}"
        conn.commit()
    finally:
        conn.close()
    log.info("%s character %d by %s", msg, char_id, admin)
    return RedirectResponse(f"/admin/characters/{char_id}", status_code=302)


# --- items ---
@app.get("/admin/items", response_class=HTMLResponse)
async def items_page(request: Request) -> HTMLResponse:
    admin = require_admin(request)
    query = request.query_params.get("q", "")
    results = search_osrs_items(query) if query else []
    return render("items.html", request, {
        "admin": admin,
        "active_nav": "items",
        "query": query,
        "results": results,
    })


@app.post("/admin/items/spawn")
async def spawn_item(request: Request) -> RedirectResponse:
    admin = require_admin(request)
    form = await request.form()
    char_id = _form_int(form, "char_id")
    item_id = _form_int(form, "item_id")
    count = _form_int(form, "count", 1)
    inv_type = _form_int(form, "inv_type", 93)

    if not item_id or not char_id:
        return RedirectResponse("/admin/items?flash=Character+and+item+required", status_code=302)

    conn = db_connect()
    try:
        row = conn.execute(
            "SELECT id FROM inventories WHERE character_id=? AND inv_type=?",
            (char_id, inv_type),
        ).fetchone()
        if row is None:
            conn.execute(
                "INSERT INTO inventories (character_id, inv_type) VALUES (?, ?)",
                (char_id, inv_type),
            )
            row = conn.execute(
                "SELECT id FROM inventories WHERE character_id=? AND inv_type=?",
                (char_id, inv_type),
            ).fetchone()
        inv_id = row["id"]
        # Check if item already exists in inventory (composite PK is inventories_id+slot; no id column)
        existing = conn.execute(
            "SELECT 1 FROM inventory_objs WHERE inventories_id=? AND obj=? LIMIT 1",
            (inv_id, item_id),
        ).fetchone()
        if existing:
            conn.execute(
                "UPDATE inventory_objs SET count=count+? WHERE inventories_id=? AND obj=?",
                (count, inv_id, item_id),
            )
        else:
            used_slots = {r["slot"] for r in conn.execute(
                "SELECT slot FROM inventory_objs WHERE inventories_id=?", (inv_id,)
            ).fetchall()}
            slot = 0
            while slot in used_slots:
                slot += 1
            conn.execute(
                "INSERT INTO inventory_objs (inventories_id, slot, obj, count, vars) VALUES (?, ?, ?, ?, 0)",
                (inv_id, slot, item_id, count),
            )
        conn.commit()
    finally:
        conn.close()

    name = obj_name(item_id)
    msg = f"Spawned {name} x{count} for character {char_id}"
    log.info("spawned item %s x%d for char %d by %s", name, count, char_id, admin)
    return RedirectResponse(f"/admin/items?flash={msg}", status_code=302)


# --- realms ---
@app.get("/admin/realms", response_class=HTMLResponse)
async def realms_page(request: Request) -> HTMLResponse:
    admin = require_admin(request)
    conn = db_connect()
    try:
        realms = conn.execute("SELECT * FROM realms ORDER BY id").fetchall()
    finally:
        conn.close()
    return render("realms.html", request, {
        "admin": admin,
        "active_nav": "realms",
        "realms": [dict(r) for r in realms],
    })


@app.post("/admin/realms/{realm_id}")
async def update_realm(realm_id: int, request: Request) -> RedirectResponse:
    admin = require_admin(request)
    form = await request.form()
    conn = db_connect()
    try:
        conn.execute(
            """UPDATE realms SET dev_mode=?, require_registration=?, ignore_passwords=?,
               player_xp_rate_in_hundreds=?, global_xp_rate_in_hundreds=? WHERE id=?""",
            (
                form.get("dev_mode", "0"),
                form.get("require_registration", "0"),
                form.get("ignore_passwords", "0"),
                _form_int(form, "player_xp_rate_in_hundreds", 100),
                _form_int(form, "global_xp_rate_in_hundreds", 100),
                realm_id,
            ),
        )
        conn.commit()
    finally:
        conn.close()
    log.info("updated realm %d by %s", realm_id, admin)
    return RedirectResponse("/admin/realms", status_code=302)


# --- health ---
@app.get("/admin/healthz")
async def admin_healthz() -> dict[str, str]:
    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn
    import os
    host = os.environ.get("OSRS_ADMIN_HOST", "0.0.0.0")
    port = int(os.environ.get("OSRS_ADMIN_PORT", "8086"))
    uvicorn.run(app, host=host, port=port)
