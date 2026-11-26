# BeanLab OSRS Web (Phase 1: Discord OAuth registration)

FastAPI service that registers OSRS accounts via **Discord OAuth2.0**,
Ferox-style ("Register with Discord" button).

- **Hasher**: argon2i(time=20, memory=65536, par=1) — identical to the game's
  `Argon2PasswordHashing` (`api/pw-hash`), so web-registered accounts log in
  without any game-side change.
- **DB**: writes `accounts` rows in the game's SQLite
  (`/opt/osrs/server/rsmod/.data/saves/game.db`). `V10__discord_oauth.sql`
  adds `discord_id UNIQUE` + `discord_username`.
- **Username**: Discord global name, lowercased, non-`[a-z0-9_ ]` stripped,
  ≤12 chars (game's username rule). Password: 16 random alnum chars, shown
  once on the result page.
- **Character**: created by the game server on first login (same as Ned's bot
  accounts — no character row needed here).

## Run (VM)
```
bash install.sh          # venv + deps + systemd unit (osrs-web, port 8085)
vim /opt/osrs/web/config.json   # client_id + client_secret from Discord portal
systemctl start osrs-web
```
Discord Developer Portal: add **Redirect URI**
`http://100.98.119.38:8085/oauth/discord/callback` (exact match).

## Endpoints
| Route | Purpose |
|---|---|
| `GET /` | landing + Register button |
| `GET /oauth/discord/start` | Discord authorize (scope `identify email`) |
| `GET /oauth/discord/callback` | code→token→user, register, show credentials |
| `GET /healthz` | liveness |
| `GET /status` | accounts/characters/realms counts |

Boot self-test creates/verifies/deletes a `systemtest` account (same as Ned's
bot) — the service fails fast if the DB or hasher is wrong.

## Phase 2 (not yet)
`/admin` on the same service (Discord-OAuth admin role or local token):
realm switching, account/character browser, item grants, bans, service status.

## Market Hub map editor (admin panel)

`/admin/hub` in the admin panel (`admin_app.py`, port 8086) edits the Market Hub layout file
described in `docs/HUB_LAYOUT.md`. Same sign-in as the rest of the panel (config admin or an owner
listed in `panel_users`).

- Reads the override `<hub_server_dir>/.data/hub-layout.json` if present, else the bundled
  `<hub_server_dir>/content/other/beanlab-soulwars/src/main/resources/.../hub-layout.json`.
  Set `"hub_server_dir"` (default `/opt/osrs/server/rsmod`) in `admin_config.json`; an explicit
  override path can be given as `"hub_layout_file"` or the `HUB_LAYOUT_FILE` env var.
- Save validates (a Python port of `HubMap.parse` in `hub_layout.py`, same messages), then writes
  the override atomically (temp file + rename) and keeps one timestamped backup
  (`hub-layout.json.bak-YYYYMMDD-HHMMSS`). It never restarts the server: restart `osrs-server`
  afterwards, and for floor changes also regenerate terrain and repack the cache (the page shows
  which one an edit needs).
- API (all need the panel session cookie, 401 otherwise): `GET /admin/api/hub/layout`,
  `PUT /admin/api/hub/layout` (`{"layout": {...}, "base_etag": "..."}`; 422 with
  `{"problems": [...]}` when invalid, 409 if the file changed since loaded),
  `POST /admin/api/hub/validate`, `GET /admin/api/hub/underlays`.
- `hub_layout.py` must be deployed next to `admin_app.py`. No new dependencies.
- Floor colours: `static/underlay_colors.json` (ids and rgb of cache underlays). Regenerate it from
  the VM probe output with `python tools/import_underlay_probe.py probe.out > static/underlay_colors.json`.
  Ids missing from the file draw in a placeholder colour.

Tests: `pip install pytest httpx` then `python -m pytest web/tests` (the validator is also checked
against `content/other/beanlab-soulwars/src/test/resources/hub-layout-vectors.json`, written so a
Kotlin test can read the same cases).
