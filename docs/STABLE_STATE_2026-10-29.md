# Stable State Snapshot — 2026-10-29 (pre Ferox buildout)

Taken before the Ferox feature buildout begins. Proxmox snapshot
**`pre-ferox-buildout`** exists on VM 123 with this state.

## VM / host
- PVE node: `networkbean` (192.168.1.117), VM ID **123**, name `runescape-server`
- VM IP: **192.168.1.134** (LAN), **100.98.119.38** (tailnet)
- Proxmox snapshots on 123: `pre-phase-1-osrs`, **`pre-ferox-buildout`** (this one)

## Services (systemd)
| Unit | State | Notes |
|---|---|---|
| `osrs-server` | active (since Oct 28 19:07 MDT, PID 52720) | RsMod, `-Xmx4g -Xms2g -XX:+UseG1GC`, runs `install/app/bin/app` |
| `osrs-discord` (Ned's registration bot) | active | Python 3 `discord.Client` at `/opt/osrs/discordbot/bot.py`, argon2i(20,65536,1) — matches game hasher exactly |
| `osrs-webconfig` | active | `python3 -m http.server 8181 --bind 127.0.0.1` serving `/opt/osrs/server/web` (client webconfig only) |
| `rsps` | failed (old Elvarg) | candidate for removal |
| `osrs-discordbot` | dead (legacy unit) | not the real bot; cleanup candidate |

## Deployed source
- `/opt/osrs/server/rsmod` — branch **main** @ `7c834604`
  "feat: Grand Exchange, client GE window + item defs, web docs"
- Working tree: **4 modified `.kt` files (uncommitted audit fixes)** + untracked
  `api/db/src/main/resources/db/migration/V8__grand_exchange_revisions.sql`,
  `V9__beanlab_realm_hardening.sql`
- Those changes are identical to scratch branch
  `claude/trusting-bardeen-pf87qp` (`8efb2fa` docs runbook → `7ff9fb6` fmt →
  `e4e206e` audit fixes). The audit branch was never pushed anywhere
  (local scratch clone only) — pushed to Gitea with the Ferox buildout.

## Repos
- GitHub (source of truth): `BeanLab-Tech/Beanscape-OSRS-Private-Server`
  main @ `55175cc`
- Gitea mirror: `cfiaschetti/osrs-server` @ `http://gitea.tail5475a4.ts.net:3000`
  (also `http://192.168.1.117:3000`), main @ `55175cc`

## Database (`/opt/osrs/server/rsmod/.data/saves/game.db`, SQLite)
- **Realms**: 1 = `dev`, 2 = `main`. Both `require_registration=1`,
  `ignore_passwords=0`, `dev_mode=0` (V9 hardening reset dev-mode to 0 at the
  Oct 28 19:07 startup — no stray `owner` privileges; both current accounts
  have `modlevel=NULL`).
- **Accounts** (2, both created via Ned's Discord bot, dev realm):
  - id 6 `wiseoldbeaan` — created Oct 28 19:11 MDT
  - id 7 `beandilf` — created Oct 28 19:23 MDT
- **Characters** (3, all realm 1/dev):
  - char 2 (account 6), char 3 (account 7) — last login Oct 28 23:53 MDT
    (verified-working logins after the deploy)
  - char 1 — **orphan**: belongs to deleted account id 3, last login Oct 28 16:20
- Login verified end-to-end Oct 28 23:31–23:53 MDT (Chad logged in after the
  realm-hardening deploy).

## Migrations in flight
- `V8__grand_exchange_revisions.sql` — GE table (applied Oct 28)
- `V9__beanlab_realm_hardening.sql` — dev-mode reset, applied Oct 28 19:07
- `V10+` will carry the Ferox buildout (next: `V10__discord_oauth.sql`)

## Secrets (locations, never values)
- Ned's bot token: `/opt/osrs/config/ned_discord_token`
- Gitea: `BL_GITEA_KEY` in `~AppData/Local/hermes/.env` (user `cfiaschetti`)
- GitHub: `BL_GITHUB_KEY` in same env
- VM SSH: `root@100.98.119.38` (tunnel), key-based

## Known open items carried into the buildout
1. `rsps` + `osrs-discordbot` legacy units still present (cleanup)
2. Orphan character id 1 (decide: keep or delete)
3. `osrs-webconfig` (python http.server) is the only web thing today — the
   Ferox web app (OAuth + admin) will replace/supersede it
4. Audit fixes uncommitted on the VM (committed on Gitea branch
   `claude/trusting-bardeen-pf87qp`)
