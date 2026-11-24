# BeanLab OSRS Private Server

Ferox-inspired sandboxed PVM experience on **RsMod rev 233**, played with **Microbot** (a RuneLite
fork) or RuneLite via RSProx, locked behind the tailnet.

**Stack:** RsMod + Microbot/RSProx + SQLite + Discord registration + Tailscale-only networking  
**VM:** 123 `runescape-server` — `192.168.1.134` / `100.98.119.38` (tailnet)  
**Repo:** https://github.com/BeanLab-Tech/Beanscape-OSRS-Private-Server (mirror: https://gitea.tail5475a4.ts.net/cfiaschetti/osrs-server.git)

> This repo is an **overlay** on upstream RsMod: it holds only the BeanLab modules, migrations,
> web/bot code, client tooling and docs. Build it with `scripts/build-overlay.sh`.
> Latest audit: [`docs/AUDIT_2026-10-29.md`](docs/AUDIT_2026-10-29.md).

---

## Table of Contents
1. [What's Included](#whats-included)
2. [Architecture](#architecture)
3. [Player Setup — Microbot (recommended)](#player-setup--microbot-recommended)
4. [Player Setup — RSProx + RuneLite (alternative)](#player-setup--rsprox--runelite-alternative)
5. [Registration](#registration)
6. [Playing the Game](#playing-the-game)
7. [In-Game Commands](#in-game-commands)
8. [Server Setup & Deployment (admins)](#server-setup--deployment-admins)
9. [Admin Reference](#admin-reference)
10. [Security Notes](#security-notes)
11. [Changelog](#changelog)
12. [Current Progress](#current-progress)
13. [Roadmap / Next Steps](#roadmap--next-steps)
14. [Troubleshooting](#troubleshooting)

---

## What's Included

| Feature | Where | Notes |
|---------|-------|-------|
| Grand Exchange (`::ge`) | `content/other/grandexchange` | Price-time matching, escrow, 2% tax; V7/V8 |
| Display-name change (`::name`) | `content/other/commands/.../NameChangeCommands.kt` | All players |
| Rotten potato admin menu | `content/other/admin-potato/RottenPotatoPlugin.kt` | Options filtered by modlevel |
| Teleport menu (`::teleports` / `::tp`) | `content/other/admin-potato/Teleport*.kt` | 98 destinations, 6 categories, search |
| Complete all quests (`::questsdone`) | `content/other/admin-potato/Quest*.kt` | 204 quests/miniquests from cache data |
| Members for every account | `api/account/.../CharacterAccountApplier.kt` + V11 | Forced at login |
| Discord OAuth registration | `web/app.py` (`osrs-web`) | `https://runescape-server.tail5475a4.ts.net/oauth/discord/start` |
| Admin web panel | `web/admin_app.py` (`osrs-admin`) | `https://runescape-server.tail5475a4.ts.net/admin` |
| Discord `!register` bot | `discordbot/bot.py` (`osrs-discord`, disabled by default) | Password only ever sent by DM |
| Microbot client patcher + launcher | `client/microbot/` | Microbot 2.0.20 = RuneLite 1.11.19 = rev 233 |
| Client webconfig | `webconfig/` (`osrs-webconfig`) | `jav_config.ws`, `world_list.ws`, RuneLite `worlds.js`, version file; served no-cache |

---

## Architecture

```
┌──────────────────┐        tailnet         ┌──────────────────────────────┐
│  bean-laptop     │◄──────────────────────►│  VM 123  runescape-server     │
│  (Windows)       │     100.98.119.38      │  /opt/osrs/                   │
│                  │                        │  ├── server/rsmod  (RsMod)    │
│  Microbot 2.0.20 │── :43594 game ────────►│  │   └── .data/saves/game.db  │
│  (patched, direct)│                       │  ├── web/  (OAuth + admin)    │
│        or        │── :443 tailscale serve►│  ├── discordbot/              │
│  RSProx → RuneLite│   /      jav_config + world_list (:8080)               │
│  (proxy :43701)  │   /oauth registration (:8085)                           │
└──────────────────┘   /admin  admin panel  (:8086)                          │
                                          └──────────────────────────────┘
```

**Key ports:**

| Port | Where | What | Access |
|------|-------|------|--------|
| 43594 | VM | Game server (RsMod) | Tailnet only (`100.64.0.0/10`) |
| 443 | VM | `tailscale serve`: `/` jav_config + world list, `/oauth`, `/admin` | Tailnet only |
| 8080 / 8085 / 8086 | VM | webconfig / registration / admin backends | localhost + ufw-blocked; reached via 443 |
| 22 | VM | SSH | Tailnet only (`100.64.0.0/10`) |
| 9100 | VM | node-exporter metrics | tailscale0 only |
| 3000 | Gitea LXC | Git mirror | LAN (`192.168.1.117`) |

---

## Player Setup — Microbot (recommended)

Microbot is a RuneLite fork with automation plugins. It connects **directly** to the server; no
proxy is involved. The server is pinned to OSRS revision 233, so the client **must** be Microbot
**2.0.20** (the last release on RuneLite 1.11.19). Newer releases target newer game revisions and
cannot log in.

### Prerequisites
- **Tailscale** installed and joined to `tail5475a4.ts.net`
- Windows 11 (other OSes work with the same steps, adjusting paths)
- **Java 21** (Temurin JDK or JRE):
  ```powershell
  winget install --id EclipseAdoptium.Temurin.21.JDK --silent
  ```
- **Python 3.10+** (only for the one-time patch step)
- ~600 MB disk

### Step 1 — Create the client folder

```powershell
mkdir "$env:USERPROFILE\Desktop\OSRS-Microbot-Client\original"
```

### Step 2 — Download Microbot 2.0.20 and verify it

Download `microbot-2.0.20.jar` from the
[Microbot 2.0.20 release](https://github.com/chsami/Microbot/releases/tag/2.0.20) into
`OSRS-Microbot-Client\original\` (or `gh release download 2.0.20 -R chsami/microbot -p microbot-2.0.20.jar`).
Do **not** download `hwid-agent` jars from newer releases; they are not needed.

Verify the checksum (the patch script also refuses any other file):

```powershell
(Get-FileHash "$env:USERPROFILE\Desktop\OSRS-Microbot-Client\original\microbot-2.0.20.jar").Hash
# expected: 95A652D44AFE867B5B18879EF5DDF56EFB0F0A43B005E26C7018EAFDE8587DD6
```

### Step 3 — Patch it for the BeanLab server

The client needs the server's public RSA login key and must accept a non-Jagex host. From a clone
of this repo:

```powershell
python client\microbot\patch-microbot.py `
  "$env:USERPROFILE\Desktop\OSRS-Microbot-Client\original\microbot-2.0.20.jar" `
  "$env:USERPROFILE\Desktop\OSRS-Microbot-Client\microbot-2.0.20-beanlab.jar" `
  d6ac773e2baf663e78d82337e3f1e4ccd46e2b4b4235e6be93f11bbb445a3c7a3a01f1dac3feee2a0e14740cda715ec18f6d746e67ef7a50e876041f6ac3db95abbf8b6ce9973cc05d134d4afa35d749831fb2073f42644360d6da342d62f95479c2be2650cade3aeba76e73a05495fe8f6bb871dcb2e657b34e20b0ed94bf13
```

Expected output: three `patched ...` lines, then `wrote ...`. What it changes:

| Class | Constant | Change |
|-------|----------|--------|
| `bc.class` | RSA login modulus | Jagex's key → the BeanLab server key (the modulus above, same as in `proxy-targets.yaml`) |
| `bl.class` | host allowlist entry `127.0.0.1` | `""`, so the client accepts the BeanLab host (same trick RSProx uses) |
| `MicrobotVersionChecker.class` | `https://microbot.cloud/api/version/client` | `<server>/runelite/microbot-version.txt`, which says `2.0.20`, so the client stops marking itself "NEW CLIENT AVAILABLE" |

Everything else is copied byte for byte; the game port stays 43594. If the server's RSA key ever
changes, re-run this step with the new modulus against the **original** jar. An optional 4th
argument sets the server base URL (default `https://runescape-server.tail5475a4.ts.net`).

> **Why not the latest Microbot?** Each Microbot release bundles the game client for one OSRS
> revision. The newest (2.6.x, RuneLite 1.13.x) targets a much newer revision than rev 233, and
> RsMod upstream has no newer revision to move to, so 2.0.20 is the newest Microbot that can log in.
> The version file tells the client that 2.0.20 is current *for this server*.

### Step 4 — Install the launcher

```powershell
copy client\microbot\Play-Microbot.bat "$env:USERPROFILE\Desktop\OSRS-Microbot-Client\"
```

`Play-Microbot.bat` runs the patched jar with:

- `--jav_config=<server>/jav_config.ws`: game server address, world 1, members world flags
- `-Drunelite.http-service.url=<server>/runelite`: RuneLite's API base. RuneLite (and Microbot's
  `Rs2Player.isInMemberWorld()`) get the world list from `<base>/worlds.js`; the server publishes
  world 1 as `MEMBERS`. Without this they ask `api.runelite.net`, which no longer serves 1.11.19.
- `-Duser.home=.\home`: Microbot's profile, plugins, logs and cache live in
  `OSRS-Microbot-Client\home\` and never touch a normal RuneLite install (or your live account's
  settings)

It looks for Temurin 21 under `C:\Program Files\Eclipse Adoptium\`; edit `JAVAW` if yours is
elsewhere. `SERVER` at the top sets the server URL.

### Step 5 — Register and log in

1. [Register](#registration) to get a username and password
2. Double-click `Play-Microbot.bat`. First launch downloads the game cache from the server (~1 min)
3. **Existing user** → username + password → **Login**

4. Check membership: the **Account** tab shows 3650 days of membership, and Microbot scripts treat
   the account and world as members (see [Membership](#membership))

**Never update Microbot past 2.0.20**, even if something offers it (see Step 3). Some bundled
Microbot plugins (e.g. Quest Helper) expect live-game data RsMod doesn't implement and log errors.

---

## Player Setup — RSProx + RuneLite (alternative)

Useful for packet inspection. RSProx launches RuneLite 1.11.19 and patches it on the fly.

1. Get `rsprox-launcher.jar` from the [RSProx releases](https://github.com/blurite/rsprox) (not in
   this repo) and put it in `C:\Users\<you>\Desktop\OSRS-RsMod-Client\`
2. Install the Java 21 **JDK** (not JRE — the launcher re-signs jars)
3. Edit `C:\Users\<you>\.rsprox\proxy-targets.yaml`:
   ```yaml
   config:
     - name: BeanLab OSRS
       jav_config_url: https://runescape-server.tail5475a4.ts.net/jav_config.ws
       revision: 233.1
       modulus: d6ac773e2baf663e78d82337e3f1e4ccd46e2b4b4235e6be93f11bbb445a3c7a3a01f1dac3feee2a0e14740cda715ec18f6d746e67ef7a50e876041f6ac3db95abbf8b6ce9973cc05d134d4afa35d749831fb2073f42644360d6da342d62f95479c2be2650cade3aeba76e73a05495fe8f6bb871dcb2e657b34e20b0ed94bf13
       game_server_port: 43594
       runelite_bootstrap_commithash: 4bed6512f101aadb1ba20e56ca124dff2fba387d
   ```
   **Critical:** `runelite_bootstrap_commithash` must be set, or RSProx uses a stale fallback commit
   that 404s.
4. Launch:
   ```powershell
   cd C:\Users\<you>\Desktop\OSRS-RsMod-Client
   & "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe" -jar rsprox-launcher.jar
   ```
5. In RSProx: **Client Type** `RuneLite` → **Proxy Target** `BeanLab OSRS` → **Quick Launch**

---

## Registration

Registration is required (`require_registration=1`, V9). Two paths, both tailnet-only:

### Web (Discord OAuth) — `osrs-web`

1. Open `https://runescape-server.tail5475a4.ts.net/oauth/discord/start` and approve in Discord
2. The page shows your username and a generated 16-character password **once**. Save it.

Your username is your Discord global name (lowercased, 2-12 chars). Discord IDs listed in
`admin_discord_ids` (in `/opt/osrs/web/config.json`) get the `admin` username. The sign-in link is
CSRF-protected: it only works when started from `/oauth/discord/start` in the same browser, within
10 minutes.

### Discord bot (`!register`) — `osrs-discord` (disabled by default)

```
!register <username>        # 2-12 chars: lowercase letters, numbers, underscore
```

1. **Open your DMs first** (Server menu → Privacy Settings → allow DMs from server members)
2. The bot DMs a generated 16-character password. If it can't DM you, it creates nothing.

The bot never accepts or posts a password in the channel: a `!register` with anything after the
username is deleted (needs **Manage Messages**) and refused. If the credentials DM fails, the new
account is rolled back. Accounts use Argon2i (m=65536, t=20, p=1), the same as the game server.

To run the bot: `systemctl enable --now osrs-discord` (token file: `/opt/osrs/config/ned_discord_token`).

---

## Playing the Game

### Getting Started
- Log in with your registered credentials; you spawn in the dev realm (Lumbridge-style start)
- XP rate: 150× (`realms.player_xp_rate_in_hundreds = 15000`)
- **Every account is members** (see [Membership](#membership))
- Everyone can use `::name` and `::ge`; admin tools need a mod level (see below)

### Membership
Every account is a member on a members world. That takes five settings, because the game, the
client and Microbot each check membership differently:

| Check | Reads | Set by |
|-------|-------|--------|
| Server + login response `member` | `player.members` | `CharacterAccountApplier` forces `true` at login (the four account-creation paths default `members` to 0); V11 set the DB column to 1 |
| Membership days (Account tab, Microbot `Rs2Player.isMember()`) | varp 1780 `account_credit` > 0 | `CharacterAccountApplier` sets 3650 at login, after saved varps load |
| Client world type (`client.getWorldType()`) | `jav_config.ws` `param=5` (bitmask, 1 = members) | `webconfig/www/jav_config.ws` |
| Client world number (`client.getWorld()`) | `jav_config.ws` `param=12` | `1`, matching `world_list.ws` (was `255`, which broke the next check) |
| Microbot `Rs2Player.isInMemberWorld()` | RuneLite world list: world with `client.getWorld()`'s id has `MEMBERS` | `webconfig/www/runelite/worlds.js` + the launcher's `-Drunelite.http-service.url` |

Without the last three, Microbot scripts treat the account as free-to-play (members areas,
teleports and items skipped) even though the server allows everything.

### Controls
Standard OSRS controls; RuneLite/Microbot plugins work. **ESC** toggles the sidebar; **F1–F6** open
combat, stats, inventory, equipment, prayer, magic.

### PVM Content
RsMod's default rev 233 content: standard NPCs, combat and skills, plus the Grand Exchange and name
change. Scripted boss mechanics are mostly not implemented upstream.

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

### Upstream admin commands (`admin`+)

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
|----------------|-----|-------------|
| `::teleports` / `::tp` | moderator+ | Teleport menu (below) |
| `::questsdone [name]` | admin+ | Complete every quest/miniquest for you or an online player and set full QP; relog to refresh the quest list |
| Rotten potato (obj 5733) → **Use** | moderator+ | Admin menu; options depend on your level (below). Get one with `::invadd 5733` |

**Rotten potato menu by modlevel:**

| Level | Options |
|-------|---------|
| moderator | Teleports, My position, Kick me |
| admin | + Set all stats, Wipe inventory, Spawn item (search by name), Set my modlevel (**only up to your own level**) |
| owner | + Reboot world, Slow reboot |
| below moderator | The potato is confiscated and the attempt is logged (`[potato] non-staff ...`) |

**Teleport menu:** Cities (30), Important areas (12), Bosses & raids (12), NPCs (15), Minigames (10),
Skilling (19), plus **Search by name**, **Previous** (your last destination, until restart) and
**Teleport to coordinates** (`x z [level]`). Destinations are plain data in `TeleportCatalog.kt`
as `(x, z, level)`; to fix one, stand on the right tile, run `::mypos`, and edit that line. Each
teleport is logged as `[teleport]`.

**`::questsdone`:** the quest → variable map in `QuestProgressVars.kt` is decoded from cache
clientscript 4024 (`quest_progress_get`); the completion value is each row's `endstate` in the
cache's `quest` dbtable, and variables shared by several quests get the highest one. It only marks
quests complete; it does not give quest rewards. Regenerate the map if the cache revision changes.

---

## Server Setup & Deployment (admins)

The VM runs an upstream RsMod checkout at `/opt/osrs/server/rsmod` (pinned commit `fa13b3f6`,
rev 233) with this overlay's files copied in.

### Build and test the overlay (any machine)

```bash
scripts/build-overlay.sh                  # clones pinned upstream, copies overlay, tests + spotless
scripts/build-overlay.sh :content:other:admin-potato:spotlessApply   # fix formatting
```

### Deploy to the VM

```bash
ssh runescape-server            # or ssh root@100.98.119.38
cd /opt/osrs/server/rsmod
# 1. back up the save database
sudo cp -p .data/saves/game.db /opt/osrs/backups/game-$(date +%F-%H%M).db
# 2. copy the overlay's api/ and content/ over the checkout (from a clone in ~/beanscape)
git clone -b main https://github.com/BeanLab-Tech/Beanscape-OSRS-Private-Server ~/beanscape
sudo rsync -a ~/beanscape/api ~/beanscape/content /opt/osrs/server/rsmod/
# 3. STOP the server before building (see warning)
sudo systemctl stop osrs-server
sudo ./gradlew :server:app:installDist
sudo systemctl start osrs-server
journalctl -u osrs-server -f      # wait for "Bound to ports: 43594"; Flyway migrations log here
```

> **Always stop `osrs-server` before `installDist`.** Building replaces jars under the running JVM;
> its shutdown hook then fails (`NoClassDefFoundError`) and online players lose progress since the
> last autosave.

Client webconfig: copy `webconfig/www/*` to `/opt/osrs/config/webconfig/` (keep `jav_config.ws`'s
CRLF line endings; `.gitattributes` stores these files byte for byte) and `webconfig/serve.py` to
`/opt/osrs/config/webconfig-serve.py`, install `webconfig/systemd/osrs-webconfig-no-cache.conf` as
`/etc/systemd/system/osrs-webconfig.service.d/no-cache.conf`, then
`systemctl daemon-reload && systemctl restart osrs-webconfig`. No game-server restart is needed,
but clients only pick up a changed `jav_config.ws` on their next launch.

Web and bot code: copy `web/*.py` + `web/admin_templates/` to `/opt/osrs/web/` and
`discordbot/bot.py` to `/opt/osrs/discordbot/`, then `systemctl restart osrs-web osrs-admin`
(and `osrs-discord` if enabled). Secrets are not in this repo: `web/config.json` (see
`config.example.json`), `/opt/osrs/web/env`, `admin_config.json`, and the Discord token file.

---

## Admin Reference

### Systemd Units

| Unit | Purpose | State |
|------|---------|-------|
| `osrs-server.service` | RsMod game server (:43594) | enabled |
| `osrs-webconfig.service` | Client webconfig (`webconfig-serve.py`, no-cache, :8080, `/`) | enabled |
| `osrs-web.service` | Discord OAuth registration (:8085, `/oauth`) | enabled |
| `osrs-admin.service` | Admin panel (:8086, `/admin`) | enabled |
| `osrs-discord.service` | `!register` bot | **disabled** |

```bash
systemctl status osrs-server
journalctl -u osrs-server -f
journalctl -u osrs-server | grep -E '\[(potato|teleport|quests)\]'   # BeanLab admin-tool logs
```

### Database (SQLite)

```bash
sqlite3 /opt/osrs/server/rsmod/.data/saves/game.db
SELECT id, login_username, display_name, modlevel, members, created_at FROM accounts;
SELECT name, require_registration, ignore_passwords, dev_mode FROM realms;
SELECT version, description, success FROM flyway_schema_history;
```

### Promote a Player

```sql
-- Stop the server first: online players' mod level is written back when they are saved.
UPDATE accounts SET modlevel = 'admin' WHERE login_username = 'theirusername';
-- modlevel: NULL (player), 'moderator', 'admin', 'owner'
```

### Claude on GitHub

`.github/workflows/claude.yml` runs [Claude Code](https://github.com/anthropics/claude-code-action)
when a **trusted user** mentions `@claude` in an issue, PR comment or review. Because the repo is
public, it is locked down:

- **Trusted users** are listed in `claude.yml` (currently `chad-fiaschetti`, `jsilva7674-cmd`), in
  two places: the job `if` and `include_comments_by_actor`. Edit both to add or remove someone.
- Claude runs only if both the person mentioning it and the issue/PR author are trusted, never on
  fork PRs, and only reads trusted users' comments.
- Claude can edit files and commit (signed, via the GitHub API) and open PRs, but its shell is
  limited to read-only `git status/diff/log`; it doesn't run repo scripts. CI builds its PRs.
- Actions are pinned to commit SHAs; Dependabot opens weekly PRs to update them.

One-time setup:

1. Install the Claude GitHub app: https://github.com/apps/claude → **Install** → `BeanLab-Tech` →
   **Only select repositories** → `Beanscape-OSRS-Private-Server`
2. Create a token with your Claude subscription: `claude setup-token`
3. Add it as a repo secret (paste the token when prompted):
   ```powershell
   gh secret set CLAUDE_CODE_OAUTH_TOKEN -R BeanLab-Tech/Beanscape-OSRS-Private-Server
   ```

Alternatively run `claude` in a terminal inside a clone and use `/install-github-app`, which does all
three, but it writes its own default workflow: keep this repo's locked-down `claude.yml` instead.
Claude in a runner can't reach VM 123; it works on the overlay only. CI (`ci.yml`) also has a manual
**Run workflow** button (`workflow_dispatch`).

### Adding Content

New content goes in `content/` as a module (auto-discovered; no plugin list):

```
content/other/<feature>/
  build.gradle.kts          # plugins { id("base-conventions") } + dependencies
  src/main/kotlin/.../<Feature>Script.kt   # PluginScript with an @Inject constructor
  src/test/kotlin/.../<Feature>Test.kt
```

Check with `scripts/build-overlay.sh`; conventions in `AGENTS.md`.

### Git Workflow

This GitHub repo holds the overlay (`main`; work happens on `ferox-buildout`). The VM checkout has
its own upstream-rooted history; sync by content projection (copy files into a clone of this repo,
commit, push) as described in `AGENTS.md`. Never push to `origin` on the VM (that's upstream rsmod).

### Backups

```bash
cp /opt/osrs/server/rsmod/.data/saves/game.db /opt/osrs/backups/game-$(date +%F).db
cd /opt/osrs/server/rsmod && git bundle create /opt/osrs/backups/osrs-server.bundle --all
```

---

## Security Notes

- **Network:** everything is tailnet-only; ufw denies the rest. The Jagex IP must not be exposed.
- **Rotten potato:** menu options are gated per modlevel and re-checked before each action; a
  moderator can't reach cheats, nobody can raise their own modlevel, reboots need owner, and a
  non-staff holder loses the potato.
- **Passwords:** never sent to a Discord channel. The bot DMs only, checks DMs before creating an
  account and rolls back on failure. The web page shows the password once with `Cache-Control: no-store`.
- **Web registration:** OAuth `state` is bound to an HttpOnly, SameSite=Lax cookie and checked with
  a constant-time compare; Discord names are HTML-escaped on the result page.
- **Secrets** stay on the VM (`config.json`, `env`, `admin_config.json`, Discord token file).
- **Client:** the Microbot patch changes only the public RSA modulus and the host allowlist; the
  jar is verified by SHA-256 before patching.

---

## Changelog

### 2026-11-24
- **Rotten potato:** fixed "Set all stats" and "Spawn item" (the menu modal stayed open, so RsMod
  silently aborted the follow-up text prompt); spawn by item **name** via the native item search;
  menu gated by modlevel (privilege-escalation fix: moderators could set themselves owner).
- **Teleport menu** (`::teleports` / `::tp`, potato "Teleports"): 98 destinations in 6 categories,
  search, previous, typed coordinates.
- **`::questsdone`:** complete all 204 quests/miniquests from cache data.
- **Members for everyone:** forced at login + V11 migration.
- **Client:** Microbot 2.0.20 support (`client/microbot/` patcher + launcher); RSProx still supported.
- **Discord bot:** never posts passwords; DM-first with rollback; 16-char generated passwords;
  versioned in `discordbot/`.
- **Web registration:** CSRF-safe OAuth state, HTML escaping, no-store; restyled landing page.
- **Ops:** documented stop-before-build; `ktfmt` applied to the admin-potato module.
- **Membership in Microbot:** membership days (varp 1780) set at login; `jav_config.ws` world
  number fixed (255 → 1); RuneLite world list (`runelite/worlds.js`) served from the VM and wired
  in with `-Drunelite.http-service.url`. Microbot's `isMember()`/`isInMemberWorld()` now return true.
- **Microbot "out of date":** version check points at the server (`microbot-version.txt` = 2.0.20,
  the newest Microbot on rev 233); `prices.js` served empty to stop 404s.
- **Webconfig:** now versioned in `webconfig/` and served with `Cache-Control: no-cache` (clients
  had cached a stale `jav_config.ws` for days).
- **Repo:** moved to `BeanLab-Tech`; Claude Code workflow (trusted users only, SHA-pinned);
  Dependabot for actions; CI `workflow_dispatch`.

### 2026-10-28 / 29
Grand Exchange, `::name`, Discord registration, realm hardening (V9) — see
[`docs/SESSION_REPORT_2026-10-28.md`](docs/SESSION_REPORT_2026-10-28.md) and the audit.

---

## Current Progress

| Criterion | Status | Evidence |
|-----------|--------|----------|
| 1. Tailnet-only, no public access | ✅ Done | ufw default deny, 22+43594 scoped to `100.64.0.0/10` |
| 2. Client | ✅ Done | Microbot 2.0.20 direct (patched) and RuneLite 1.11.19 via RSProx; both reach login |
| 3. Grand Exchange | 🟡 Code complete | Engine + `::ge`, unit-tested; not yet tested in-game |
| 4. Discord registration + name change | ✅ Web live / 🟡 bot disabled | OAuth registration on `/oauth`; bot fixed but `osrs-discord` disabled |
| 5. Admin tooling | ✅ Done | Potato menu, teleports, `::questsdone`, members for all |

**Infrastructure:** `osrs-server` on 43594 · tailnet 100.98.119.38 (tag:game) · ufw baseline ·
`tailscale serve` on 443 · key-only SSH · node-exporter on tailscale0:9100 · Proxmox snapshot
`pre-phase-1-osrs` · CI runs `scripts/build-overlay.sh` on every push.

---

## Roadmap / Next Steps

Full reasoning in [`docs/AUDIT_2026-10-29.md`](docs/AUDIT_2026-10-29.md) §6.

### Now
1. **Live-test** `::ge` (incl. restart with open offers), `::name` with two accounts, and the
   teleport destinations (fix any that land off-target)
2. **Nightly `game.db` backups** (`sqlite3 .backup`) and a fresh Proxmox snapshot
3. Decide whether to re-enable `osrs-discord` alongside web registration

### Next
4. Name-change rules: cooldown, profanity filter, reserved names
5. GE: save GE state with the character save, buy limits, admin inspection; real GE interface
6. Quest rewards for `::questsdone` (XP/items/unlocks), if wanted
7. Monitoring ACL fix (`tag:monitoring → tag:game :9100`)
8. Move BeanLab migrations to a reserved version range before updating upstream

### Later
9. Ferox-style content: donator zone, high-risk PK area, minigame lobbies, vote/donator shop
10. AI-agent layer on Microbot (headless play, GE agent API)
11. Web account dashboard, hiscores, clans, multiple worlds, seasonal worlds
12. Public launch via tunnel (its own security review)

---

## Troubleshooting

### Microbot won't log in / "Error connecting to server"
1. Tailscale connected? (`tailscale status`)
2. Did you accept a Microbot update? Re-patch from `original\microbot-2.0.20.jar` (only 2.0.20 works)
3. Did the server key change? Re-run `patch-microbot.py` with the new modulus
4. Check `OSRS-Microbot-Client\home\.runelite\logs\client.log`

### Microbot scripts act free-to-play / still say "NEW CLIENT AVAILABLE"
1. Launch with the current `Play-Microbot.bat` (it must pass `-Drunelite.http-service.url`); the
   log's `Java VM arguments` line shows it
2. Re-patch from the **original** jar so the version-check constant is replaced (3 `patched` lines)
3. A client launched before a `jav_config.ws` change may have cached the old copy: close Microbot,
   delete `OSRS-Microbot-Client\home\.runelite\cache\okhttp`, relaunch (only HTTP responses live
   there)
4. Relog after a server change: membership days are set at login
5. Check the VM serves the files: `curl https://runescape-server.tail5475a4.ts.net/runelite/worlds.js`

### `patch-microbot.py` says "is not Microbot 2.0.20" or "constant not found"
You passed the already-patched jar or a different version. Use the untouched download and check its
SHA-256 (Step 2).

### "Could not find or load main class net.runelite.launcher.Launcher" / "JarSigner$Builder not found" (RSProx)
You're running the JRE; RSProx needs the JDK:
```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK --silent
```

### RSProx says "Failed to patch injected client" / bootstrap 404
Pin `runelite_bootstrap_commithash: 4bed6512f101aadb1ba20e56ca124dff2fba387d` in `proxy-targets.yaml`.

### Can't connect to the game server at all
1. `tailscale status` — you must be on `tail5475a4.ts.net`; LAN access is blocked
2. `ssh runescape-server 'systemctl is-active osrs-server'`
3. `ssh runescape-server 'sudo ufw status'`

### A potato option or the teleport menu does nothing
Check your modlevel (`SELECT modlevel FROM accounts ...`) and `journalctl -u osrs-server | grep potato`.
Non-staff players have the potato removed by design.

### Quest list still shows quests unfinished after `::questsdone`
Relog. If one stays red, check `journalctl -u osrs-server | grep '\[quests\]'` for `failed=` and
report the quest name.

### Registration page says "Sign-in link expired or invalid"
Start again from `/oauth/discord/start` in the same browser (the state cookie lasts 10 minutes and
works once).

### Discord bot offline
It's disabled by default: `systemctl enable --now osrs-discord`, then `journalctl -u osrs-discord -n 20`.

### GE commands not working
Restart after GE migrations (Flyway runs on boot) and check V7/V8 in `flyway_schema_history`. If
`::ge` says "still loading", look for `Failed to restore Grand Exchange offers` in the server log.

---

## Support

- **Vault note:** `02-Projects/OSRS Private Server/Build Plan.md`
- **Runbooks:** `05-Runbooks/Tailscale - Joining LXCs and Serve.md`
- **Gitea:** https://gitea.tail5475a4.ts.net/cfiaschetti/osrs-server.git
- **Hermes skill:** `osrs-rsmod-server`
