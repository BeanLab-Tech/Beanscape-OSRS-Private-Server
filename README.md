# BeanLab OSRS Private Server

Ferox-inspired sandboxed PVM experience on **RsMod rev 233** with **RuneLite**, locked behind Tailnet.

**Stack:** RsMod + RSProx + SQLite + Discord registration + Tailscale-only networking  
**VM:** 123 — `192.168.1.134` / `100.98.119.38` (tailnet)  
**Repo:** https://gitea.tail5475a4.ts.net/cfiaschetti/osrs-server.git

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

1. Download from the repo: `rsprox-launcher.jar` (or build from `client/rsprox/`)
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
- XP rate: 150× (dev realm — adjust in `realms` table)
- Use `::master` to toggle admin mode (requires OWNER/DEVELOPER rights)
- Use `::item <id> <qty>` to spawn items (admin only)

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
SELECT id, login_username, created_at FROM accounts;

# Promote user to OWNER
UPDATE accounts SET rights = 4 WHERE login_username = 'username';

# Check realms
SELECT * FROM realms;

# Flip require_registration on dev realm
UPDATE realms SET require_registration = 1 WHERE name = 'dev';
```

### Promote a Player to Admin

```sql
-- In the game.db SQLite:
UPDATE accounts SET rights = 4 WHERE login_username = 'theirusername';
-- Rights: 0=NONE, 1=PLAYER, 2=MODERATOR, 3=ADMIN, 4=OWNER, 5=DEVELOPER
```

### Adding Content

RsMod uses a plugin system. New content goes in `content/` as a module:

```
content/other/<feature>/
  build.gradle.kts          # module dependencies
  src/main/kotlin/.../
    <Feature>Plugin.kt       # plugin entry point
    <Feature>Commands.kt     # ::commands
  src/test/kotlin/.../
    <Feature>Test.kt         # tests
```

Register the module in `settings.gradle.kts` and add it to the server's plugin list in `data/server.toml`.

### Git Workflow

```bash
cd /opt/osrs/server/rsmod

# Make changes, then:
git add <files>
git commit -m "feat: description"
git push origin main          # upstream rsmod (if you maintain a fork)
git push gitea main           # BeanLab Gitea
```

Remote `gitea` is pre-configured:
```
https://gitea.tail5475a4.ts.net/cfiaschetti/osrs-server.git
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
2. Creates account in `game.db` with Argon2id hash (m=65536, t=20, p=1)
3. DMs credentials to the Discord user
4. On first login, the game server completes character creation automatically

**Enforcement:** Flip `require_registration=1` on the `dev` realm to make Discord the only registration path. Until then, accounts can be created in-game.

---

## In-Game Commands

### Admin (`::` prefix — requires OWNER/DEVELOPER)

| Command | Description |
|---------|-------------|
| `::master` | Toggle admin mode (spawn items, teleport, etc.) |
| `::item <id> <qty>` | Spawn item (e.g., `::item 995 1000000` for 1M gp) |
| `::npc <id> <qty>` | Spawn NPC |
| `::infinitehp` | Toggle invincibility |
| `::tele <x> <y> <plane>` | Teleport to coordinates |
| `::name <new name>` | Change display name (available to all players) |
| `::ge buy <item> <qty> <price>` | Place GE buy offer |
| `::ge sell <item> <qty> <price>` | Place GE sell offer |
| `::ge collect` | Collect completed GE items/gold |
| `::ge cancel <slot>` | Cancel active GE offer |
| `::ge history` | View GE trade history |

### Grand Exchange

**Schema:** `ge_offers` + `ge_history` tables (Flyway v7)

**Matching engine:**
- Price-time priority (earlier orders at same price fill first)
- 2% sell tax (exempt for offers < 100 gp, capped at 5M tax per offer)
- Buyer gold escrowed on offer creation, refunded on cancellation
- Partial fills supported
- Guide prices from volume-weighted trade history

---

## Current Progress

| Criterion | Status | Evidence |
|-----------|--------|----------|
| 1. Tailnet-only, no public access | ✅ Done | ufw default deny, 22+43594 scoped to `100.64.0.0/10`; nmap LAN filtered |
| 2. RuneLite client | ✅ Done | RuneLite 1.11.19 launched via RSProx, bootstrap pinned to 4bed6512 (233-era), E2E login verified |
| 3. Grand Exchange | ✅ Done | Schema v7, GeBook/GeCommands built, engine in repo, applied on server boot |
| 4. Discord registration + name change | ✅ Done | Bot online as Ned#9953; `::name` loaded; `require_registration` flip pending E2E |

**Infrastructure:**
- ✅ Server running as `osrs-server.service` on port 43594
- ✅ Tailnet joined (100.98.119.38, tag:game)
- ✅ ufw §5 baseline (default deny, 2 rules)
- ✅ `tailscale serve` on 443 → jav_config.ws + world_list.ws
- ✅ SSH hardening (key-only, root prohibited)
- ✅ Discord bot (`osrs-discord.service`) with Argon2id interop verified
- ✅ node-exporter on tailscale0:9100
- ✅ Prometheus target added (pending ACL fix)
- ✅ Proxmox snapshot `pre-phase-1-osrs`

---

## Roadmap / Next Steps

### Immediate (this week)
1. **Flip `require_registration=1`** on `dev` realm — makes Discord the only registration path
2. **Live-test GE commands** — `::ge buy/sell/collect/cancel/history` in-game
3. **Live-test `::name`** — verify name change works end-to-end
4. **Take `post-phase-1-complete` Proxmox snapshot**

### Short-term (Phase 2–3)
5. **Ferox-style content:**
   - Donator zone / high-risk PKing area
   - Minigame lobbies (Barrows, Godwars, Zulrah instances)
   - Vote rewards / Donator shop
6. **Custom RuneLite plugin** — BeanLab-branded sidebar panel with server stats, GE tracker, Discord link
7. **Web-based account dashboard** — view GE offers, change password, manage characters
8. **ACL fix** — either re-tag VM to `tag:server` or add `tag:monitoring → tag:game :9100` ACL rule (Heimdall's call)

### Medium-term (Phase 4–6)
9. **World switcher** — multiple game worlds (different XP rates, PvP/PvM modes)
10. **Hiscores / leaderboards** — web-accessible via RuneLite plugin
11. **Clan system** — clan creation, chat, Castle Wars / Clan Wars
12. **Seasonal / tournament worlds** — time-limited resets with leaderboards
13. **Cloudflare tunnel for public launch** — when ready to open to non-tailnet players
14. **Automated backups** — nightly DB dumps to NAS/R2

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
Ensure the server has been restarted after the GE migration was added (Flyway runs on boot). Check schema version:
```bash
sqlite3 /opt/osrs/server/rsmod/.data/saves/game.db "SELECT * FROM schema_version;"
```

---

## Support

- **Vault note:** `02-Projects/OSRS Private Server/Build Plan.md`
- **Runbooks:** `05-Runbooks/Tailscale - Joining LXCs and Serve.md`
- **Gitea:** https://gitea.tail5475a4.ts.net/cfiaschetti/osrs-server.git
- **Hermes skill:** `osrs-rsmod-server`
