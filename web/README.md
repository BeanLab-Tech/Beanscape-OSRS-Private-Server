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
