# BeanLab OSRS Private Server

Ferox-inspired sandboxed PVM experience on **RsMod rev 233** with **RuneLite**, locked behind Tailnet.

**Stack:** RsMod + RSProx + SQLite + Discord registration + Tailscale-only networking  
**VM:** 123 — `192.168.1.134` / `100.98.119.38` (tailnet)  
**Repo:** https://github.com/chad-fiaschetti/Beanscape-OSRS-Private-Server (mirror: https://gitea.tail5475a4.ts.net/cfiaschetti/osrs-server.git)

> This repo is an **overlay** on upstream RsMod: it holds only the BeanLab modules, migrations and docs. Build it with `scripts/build-overlay.sh`. Latest audit: [`docs/AUDIT_2026-10-29.md`](docs/AUDIT_2026-10-29.md).

---

## Table of Contents
1. [Architecture](#architecture)
2. [Quick Start (End User)](#quick-start-end-user)
3. [Playing the Game](#playing-the-game)
4. [Admin Reference](#admin-reference)
5. [Discord Registration](#discord-registration)
6. [In-Game Commands](#in-game-commands)
7. [Current Progress](#current-progress)
8. [Roadmap / Next Steps](#roadmap--next-steps)
9. [Troubleshooting](#troubleshooting)

---

## Architecture

```
┌──────────────┐     tailnet      ┌──────────────────┐     LAN      ┌──────────────┐
│  bean-laptop │◄────────────────►│  VM 123           │────────────►│  Gitea LXC   │
│  (Windows)   │  100.98.119.38  │  192.168.1.134    │  192.168.1.117│ 192.168.1.117│
│              │                  │  /opt/osrs/       │             │  :3000       │
│  RSProx GUI  │                  │  ├── server/rsmod │             └──────────────┘
│  RuneLite    │                  │  │   └── .data/    │
│  (via proxy) │                  │  │       game.db   │
└──────┬───────┘                  │  ├── discordbot/  │
       │                          │  └── config/      │
       │ :43701 (proxy)           │  osrs-server.svc  │
       │ :43600 (jav_config)      │  osrs-discord.svc │
       ▼                          └──────────────────┘
  RSProx proxy (localhost)
       │
       ▼ tailnet HTTPS :443
  tailscale serve → python3 :8080
       │
       ▼
  jav_config.ws + world_list.ws
```

**Key ports:**

| Port | Where | What | Access |
|------|-------|------|--------|
| 43594 | VM | Game server (RsMod) | Tailnet only (`100.64.0.0/10`) |
| 443 | VM | `jav_config.ws` + `world_list.ws` | Tailnet only (via `tailscale serve`) |
| 22 | VM | SSH | Tailnet only (`100.64.0.0/10`) |
| 9100 | VM | node-exporter metrics | Tailnet0 only |
| 3000 | Gitea LXC | Git repo | LAN (`192.168.1.117`) |

---

## Quick Start (End User)

### Prerequisites
- **Tailscale** installed and joined to `tail5475a4.ts.net` (Chad's tailnet)
- Windows host (tested on Windows 11 with Temurin JDK 21)
- ~500 MB disk for RuneLite cache + launcher

### Step 1 — Install RSProx

1. Get `rsprox-launcher.jar` from the [RSProx releases](https://github.com/blurite/rsprox) (it is not in this repo)
2. Place in `C:\Users\<you>\Desktop\OSRS-RsMod-Client\`
3. Ensure Java 21 JDK is installed (not JRE — the launcher re-signs jars)

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK --silent
```

### Step 2 — Configure Proxy Target

Edit `C:\Users\<you>\.rsprox\proxy-targets.yaml`:

```yaml
config:
  - name: BeanLab OSRS
    jav_config_url: https://runescape-server.tail5475a4.ts.net/jav_config.ws
    revision: 233.1
    modulus: d6ac773e2baf663e78d82337e3f1e4ccd46e2b4b4235e6be93f11bbb445a3c7a3a01f1dac3feee2a0e14740cda715ec18f6d746e67ef7a50e876041f6ac3db95abbf8b6ce9973cc05d134d4afa35d749831fb2073f42644360d6da342d62f95479c2be2650cade3aeba76e73a05495fe8f6bb871dcb2e657b34e20b0ed94bf13
    game_server_port: 43594
    runelite_bootstrap_commithash: 4bed6512f101aadb1ba20e56ca124dff2fba387d
```

**Critical:** the `runelite_bootstrap_commithash` must be set. Without it, RSProx uses a stale fallback commit that 404s and the client will fail to launch.

### Step 3 — Launch

```powershell
cd C:\Users\<you>\Desktop\OSRS-RsMod-Client
"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe" -jar rsprox-launcher.jar
```

In RSProx:
1. Select **Client Type:** `RuneLite`
2. Select **Proxy Target:** `BeanLab OSRS`
3. Click the **Quick Launch** card (large card on the left of the top row)

RuneLite will download, patch, and launch automatically. First launch takes ~60 seconds (cache download).

### Step 4 — Register & Login

1. Open Discord, go to the registration channel
2. Send `!register <username>` (or `!register <username> <password>`)
3. Check your DMs for credentials
4. In RuneLite: **Existing user** → enter username/password → **Login**

---

## Playing the Game

### Getting Started
- Log in with your Discord-registered credentials
- You spawn in the dev realm (Lumbridge-style starting area)
- XP rate: 150× (`realms.player_xp_rate_in_hundreds = 15000`)
- Everyone can use `::name` and `::ge`; admin commands (`::master`, `::invadd`, …) need the `admin` mod level

### Controls
Standard OSRS controls — RuneLite plugins work. Notable defaults:
- **ESC** → toggle RuneLite sidebar
- **F1** → combat tab
- **F2** → stats tab
- **F3** → inventory
- **F4** → equipment
- **F5** → prayer
- **F6** → magic

### PVM Content
The server runs RsMod's default content at rev 233, which includes:
- Standard NPCs, combat, and skills
- Grand Exchange (see below)
- Name change (`::name`)

---

## Admin Reference

### Server Management (VM 123)

```bash
# SSH in (from tailnet only)
ssh root@100.98.119.38

# Server status
systemctl status osrs-server
journalctl -u osrs-server -f

# Restart server
systemctl restart osrs-server

# Discord bot status
systemctl status osrs-discord
journalctl -u osrs-discord -f

# Database path
/opt/osrs/server/rsmod/.data/saves/game.db
```

### Database (SQLite)

```bash
# Open DB
sqlite3 /opt/osrs/server/rsmod/.data/saves/game.db

# List accounts
SELECT id, login_username, display_name, modlevel, created_at FROM accounts;

# Promote user to owner
UPDATE accounts SET modlevel = 'owner' WHERE login_username = 'username';

# Check realms
SELECT name, require_registration, ignore_passwords, dev_mode FROM realms;

# Applied migrations
SELECT version, description, success FROM flyway_schema_history;
```

### Promote a Player to Admin

```sql
-- Stop the server first: online players' mod level is written back when they are saved.
UPDATE accounts SET modlevel = 'admin' WHERE login_username = 'theirusername';
-- modlevel: NULL (player), 'moderator', 'admin', 'owner'
```

### Adding Content

RsMod uses a plugin system. New content goes in `content/` as a module:

```
content/other/<feature>/
  build.gradle.kts          # plugins { id("base-conventions") } + dependencies
  src/main/kotlin/.../
    <Feature>Script.kt       # PluginScript subclass with an @Inject constructor
  src/test/kotlin/.../
    <Feature>Test.kt
```

Modules are auto-discovered (any `content/**/build.gradle.kts`); there is no plugin list to edit.
Check your change with `scripts/build-overlay.sh`. See `AGENTS.md` for conventions.

### Git Workflow

This GitHub repo holds the overlay. On the VM, `/opt/osrs/server/rsmod` is an upstream checkout
with the overlay copied in:

```bash
cd /opt/osrs/server/rsmod
git push gitea main           # BeanLab Gitea mirror — never push to origin (upstream rsmod)
```

### Systemd Units

| Unit | Purpose | User |
|------|---------|------|
| `osrs-server.service` | RsMod game server | osrs |
| `osrs-discord.service` | Discord registration bot | osrs |

### Backups

```bash
# DB backup
cp /opt/osrs/server/rsmod/.data/saves/game.db /opt/osrs/backups/game-$(date +%F).db

# Full server bundle
cd /opt/osrs/server/rsmod && git bundle create /opt/osrs/backups/osrs-server.bundle main
```

---

## Discord Registration

The bot (`osrs-discord.service`) listens in its dedicated channel for:

```
!register <username>              # random 10-char password
!register <username> <password>   # custom password (6-32 chars)
```

**Username rules:** 2-12 chars, lowercase letters/numbers/spaces/underscores only.

**What happens:**
1. Bot validates username format
2. Creates account in `game.db` with an Argon2i hash (m=65536, t=20, p=1 — same as the server)
3. DMs credentials to the Discord user
4. On first login, the game server completes character creation automatically

**Enforcement:** migration V9 sets `require_registration=1` (Discord is the only way to create accounts) and turns off `ignore_passwords`/`dev_mode`, which until then let anyone log into any account with any password. See the audit §4 for the deploy steps.

---

## In-Game Commands

### Everyone

| Command | Description |
|---------|-------------|
| `::name <new name>` | Change display name (2-12 chars: letters, numbers, space, `_`, `-`; unique, case-insensitive) |
| `::ge buy <item id> <qty> <price each>` | Place a buy offer (coins are escrowed) |
| `::ge sell <item id> <qty> <price each>` | Place a sell offer (noted or un-noted items) |
| `::ge offers` | List your offers and their slots |
| `::ge cancel <slot>` | Cancel an active offer |
| `::ge collect` | Collect items/coins from finished or cancelled offers |
| `::ge price <item id>` | Show the last 5 trades for an item |

### Admin (`admin` mod level or higher)

| Command | Description |
|---------|-------------|
| `::master` / `::reset` | Max out / reset all stats |
| `::invadd <obj> [count]` | Spawn an item into your inventory |
| `::invclear` | Empty your inventory |
| `::npcadd <npc>` | Spawn an NPC |
| `::tele <coords>` / `::mypos` | Teleport / show your coordinates |
| `::realm` | Update realm configs |
| `::reboot` / `::slowreboot` | Reboot the game world |

Full list: `content/other/commands/.../AdminCommands.kt` upstream.

### BeanLab admin tools (`content/other/admin-potato`)

| Command / item | Who | Description |
|---------|-----|-------------|
| Rotten potato (obj 5733) → Use | moderator+ | Menu: set all stats, wipe inventory, spawn item (search by name), teleports, my position, set modlevel, kick, reboot |
| `::teleports` / `::tp` | moderator+ | Teleport menu: cities, important areas, bosses & raids, NPCs, minigames, skilling, search by name, previous, typed coords. Destinations are data in `TeleportCatalog.kt` (x, z, level) |
| `::questsdone [name]` | admin | Marks every quest/miniquest complete for you or an online player and sets full QP. The quest→var map in `QuestProgressVars.kt` is decoded from cache clientscript 4024 (`quest_progress_get`); the completion value is the `quest` dbtable's `endstate` |

### Membership

Every account is members: `CharacterAccountApplier` forces `player.members = true` at login (accounts
are created by the server, web, admin panel and Discord bot, all with `members DEFAULT 0`), and V11
sets the column to 1 for existing accounts. The world list already flags world 1 as members.

### Grand Exchange

**Schema:** `ge_offers` + `ge_history` (Flyway V7, V8)

**Rules:**
- Price-time priority; trades execute at the resting offer's price
- 2% seller tax per item, exempt below 100 gp per item, capped at 5m per item
- Buyer coins are escrowed when the offer is placed; unspent coins come back on collect
- Partial fills supported; 8 slots per player, and a finished offer holds its slot until collected
- Untradeable items and coins cannot be listed; noted items trade in the same market as un-noted
- Offer totals are limited to 2,147,483,647 gp (one coin stack)

## Current Progress

| Criterion | Status | Evidence |
|-----------|--------|----------|
| 1. Tailnet-only, no public access | ✅ Done | ufw default deny, 22+43594 scoped to `100.64.0.0/10`; nmap LAN filtered |
| 2. RuneLite client | ✅ Done | RuneLite 1.11.19 launched via RSProx, bootstrap pinned to 4bed6512 (233-era), E2E login verified |
| 3. Grand Exchange | 🟡 Code complete | Engine + `::ge` commands, unit-tested; persistence bugs fixed in the audit; not yet tested in-game |
| 4. Discord registration + name change | 🟡 Deploy pending | Bot online as Ned#9953; enforcement + real password checks need V9 deployed; `::name` fixed for non-admins |

**Infrastructure:**
- ✅ Server running as `osrs-server.service` on port 43594
- ✅ Tailnet joined (100.98.119.38, tag:game)
- ✅ ufw §5 baseline (default deny, 2 rules)
- ✅ `tailscale serve` on 443 → jav_config.ws + world_list.ws
- ✅ SSH hardening (key-only, root prohibited)
- ✅ Discord bot (`osrs-discord.service`) with Argon2 interop verified
- ✅ node-exporter on tailscale0:9100
- 🟡 Prometheus target added (DOWN until the ACL fix)
- ✅ Proxmox snapshot `pre-phase-1-osrs`
- ✅ CI: `scripts/build-overlay.sh` on every push

---

## Roadmap / Next Steps

Full reasoning in [`docs/AUDIT_2026-10-29.md`](docs/AUDIT_2026-10-29.md) §6.

### Now (before anyone else logs in)
1. **Deploy V8/V9** and demote accounts that got `owner` from dev mode (audit §4)
2. **Live-test** `::ge` (incl. restart with open offers) and `::name` with two accounts
3. **Version the Discord bot** — it is the only account-creation path and lives only on the VM
4. **Nightly `game.db` backups** (`sqlite3 .backup`) and the `post-phase-1-complete` snapshot

### Next
5. Name-change rules: cooldown, profanity filter, reserved names
6. GE: save GE state with the character save, buy limits, admin inspection
7. Real GE interface instead of `::ge` text commands
8. Monitoring ACL fix (`tag:monitoring → tag:game :9100`)
9. Move BeanLab migrations to a reserved version range before updating upstream

### Later
10. Ferox-style content: donator zone, high-risk PK area, minigame lobbies, vote/donator shop
11. BeanLab RuneLite plugin (server stats, GE tracker, Discord link)
12. Web account dashboard, hiscores, clans, multiple worlds, seasonal worlds
13. Public launch via tunnel

---

## Troubleshooting

### "Could not find or load main class net.runelite.launcher.Launcher"
You're running the JRE instead of the JDK. Install Temurin JDK 21:
```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK --silent
```

### "JarSigner$Builder not found"
Same root cause — JRE doesn't include `jdk.security.jarsigner`. Use the JDK's `java.exe`.

### RSProx says "Failed to patch injected client" / bootstrap 404
Pin the bootstrap commit in `proxy-targets.yaml`:
```yaml
runelite_bootstrap_commithash: 4bed6512f101aadb1ba20e56ca124dff2fba387d
```

### Can't connect to game server
1. Verify Tailscale is connected: `tailscale status`
2. Verify the server is up: `ssh root@100.98.119.38 'systemctl is-active osrs-server'`
3. Verify ufw allows your tailnet IP: `ssh root@100.98.119.38 'ufw status'`
4. You must be on the `tail5475a4.ts.net` tailnet — LAN access is blocked

### "Fatal error starting RuneLite"
Check the RuneLite client log:
```
C:\Users\<you>\.runelite\logs\client.log
```
Common causes: wrong Java version, corrupted cache (`rm -rf ~/.runelite/repository2`), missing JDK.

### Discord bot offline
```bash
ssh root@100.98.119.38
systemctl status osrs-discord
journalctl -u osrs-discord -n 20
```

### GE commands not working
Ensure the server has been restarted after the GE migrations were added (Flyway runs on boot). Check that V7 and V8 applied:
```bash
sqlite3 /opt/osrs/server/rsmod/.data/saves/game.db "SELECT version, description, success FROM flyway_schema_history;"
```
If `::ge` says "still loading", the offer restore failed at boot — look for `Failed to restore Grand Exchange offers` in `journalctl -u osrs-server`.

---

## Support

- **Vault note:** `02-Projects/OSRS Private Server/Build Plan.md`
- **Runbooks:** `05-Runbooks/Tailscale - Joining LXCs and Serve.md`
- **Gitea:** https://gitea.tail5475a4.ts.net/cfiaschetti/osrs-server.git
- **Hermes skill:** `osrs-rsmod-server`
