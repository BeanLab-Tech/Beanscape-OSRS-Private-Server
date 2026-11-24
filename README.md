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
| Discord registration (`/register`, `/resetpassword`) | `discordbot/bot.py` (`osrs-discord`) | Slash commands, ephemeral replies, linked to Discord ID |
| Generic doors & gates | `content/other/beanlab-content/GenericDoorsScript.kt` | ~900 unscripted doors/gates (incl. members gates) become passable |
| Prayer, firemaking, cooking | `content/other/beanlab-skills/` | Bury/scatter, altars, light logs (fires + ashes), cook on fires/ranges |
| Mining, smelting, smithing | `content/other/beanlab-smithing/` | 72 rock types, 41 furnaces, 17 anvils; menus for bars/items |
| Eating & drinking | `content/other/beanlab-content/ConsumablesScript.kt` | Every Eat/Drink item: food heals, potions boost/restore, doses step down |
| Microbot client patcher + launcher | `client/microbot/` | Microbot 2.0.20 = RuneLite 1.11.19 = rev 233 |
| Client webconfig | `webconfig/` (`osrs-webconfig`) | `jav_config.ws`, `world_list.ws`, RuneLite `worlds.js`, version file; served no-cache |

### Not yet implemented

RsMod upstream content is thin (upstream has Lumbridge, fishing, woodcutting, combat spells,
special attacks, banks, some generic NPCs/objects; its cooking module is an empty placeholder). Still missing, roughly by impact:

- **Skills:** crafting, fletching, herblore, agility,
  thieving, runecrafting, slayer, hunter, farming, construction (fishing, woodcutting, mining,
  smithing, prayer, firemaking and basic cooking exist; quests are only marked complete by `::questsdone`)
- **Objects:** agility shortcuts, stiles, levers, most skilling objects, diagonal doors
- **NPC content:** shops, most dialogue, boss mechanics (bosses spawn with stats only)
- **Item effects:** poison/antipoison, special potions (divine, antifire, stamina's run-drain
  reduction), multi-bite foods eat in one bite, anglerfish overheal

Each is a module under `content/`; see [Adding Content](#adding-content). Use `::teleports` to
reach areas whose entrances aren't scripted.

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

Registration is required (`require_registration=1`, V9). Two self-service paths, both tailnet-only,
plus admins can create accounts in the admin panel (`/admin` → Accounts → New; same Argon2i hash as
the game).

### Admin panel sign-in

`https://runescape-server.tail5475a4.ts.net/admin` accepts:

- the panel's own login from `/opt/osrs/web/admin_config.json`, or
- a **game account that is both listed in `admin_config.json` `"panel_users"` and an unbanned
  `owner`**, using its game username and password (checked against its Argon2i hash).

To give someone panel access: make their game account `owner`, then add their login name to
`panel_users` on the VM (`sudo nano /opt/osrs/web/admin_config.json`, e.g.
`"panel_users": ["umpix"]`) and `systemctl restart osrs-admin`. Currently: none (emptied in the 2026-11-24 fresh start).

Safeguards:
- **Owner only.** The panel creates accounts and sets modlevels, so admins can't sign in (they
  could otherwise create owners). A game modlevel alone never grants access; the allowlist only
  lives on the VM.
- **Live re-check.** Game-account sessions are re-checked on every page; demoting, banning,
  deleting or unlisting the account ends the session immediately. Logout deletes the session.
- **Lockout.** Within 15 minutes, 5 failures from one device (its tailnet IP) or 20 failures for one
  game username block further attempts ("Too many attempts"). The config admin login is never
  locked by name (so nobody can lock you out by spamming it); instead, after 10 failures on it in
  15 minutes from any devices, every further attempt on it waits 5 seconds. Only the exact config
  username gets that treatment; case or spacing variants are limited like game usernames. Each
  attempt is counted before the password check, so simultaneous attempts can't slip past the limit,
  and a successful login doesn't reset a device's earlier failures. Failures all take the same
  time (a dummy Argon2 check runs for unknown or ineligible users), and at most 2 Argon2 checks run
  at once. A restart of `osrs-admin` clears all counters.
- Logins, failures, lockouts and revocations are logged:
  `journalctl -u osrs-admin | grep "admin panel"`.

### Web (Discord OAuth) — `osrs-web`

1. Open `https://runescape-server.tail5475a4.ts.net/oauth/discord/start` and approve in Discord
2. The page shows your username and a generated 16-character password **once**. Save it.

Your username is your Discord global name (lowercased, 2-12 chars). Discord IDs listed in
`admin_discord_ids` (in `/opt/osrs/web/config.json`) get the `admin` username. The sign-in link is
CSRF-protected: it only works when started from `/oauth/discord/start` in the same browser, within
10 minutes.

### In Discord (slash commands) — `osrs-discord` (recommended)

The bot keeps one **pinned message** in the registration channel with two buttons:

- **Register**: opens a short form (login name, optional; blank = your Discord name), then
  creates your account
- **Reset password**: new password for the account linked to your Discord

The same actions as slash commands:

```
/register                     # login name defaults to your Discord name
/register username:<name>     # or pick one: 2-12 lowercase letters, numbers, spaces, _
/resetpassword                # new password for the account linked to your Discord
```

The reply is **ephemeral**: only you see it, with your username and a generated 16-character
password. Save it before dismissing the message. No DMs or browser needed.

- Discord authenticates whoever runs a slash command, so the bot gets your verified Discord ID
  directly (no OAuth redirect). Your game account is **linked to that Discord ID**: one account per
  Discord user, shared with the web registration below. Running `/register` again tells you your
  existing username.
- `/resetpassword` only changes the account linked to *your* Discord, so it can't touch anyone
  else's. A 30-second cooldown applies to both commands.
- The old `!register` text command now just points to `/register`; a `!register` with anything
  after the username is deleted (needs **Manage Messages**) in case it contained a password.
- Accounts use Argon2i (m=65536, t=20, p=1), the same as the game server.

On startup the bot syncs its commands (`synced 2 slash commands`) and posts and pins the
registration message if it isn't already pinned (`posted registration panel` / `already pinned`;
the buttons keep working across restarts). If it logs `cannot register slash commands`, re-invite it with the
`applications.commands` scope using the URL in that log line. Token file:
`/opt/osrs/config/ned_discord_token`.

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

### Prayer, firemaking & cooking (`content/other/beanlab-skills`)

- **Prayer:** "Bury" bones (normal 4.5 XP up to superior dragon 150) or "Scatter" demonic ashes.
  "Pray-at" any altar to restore your Prayer points.
- **Firemaking:** use a tinderbox on logs (normal to redwood, OSRS levels/XP) to light a fire where
  you stand; you step west. The fire burns 100-200 ticks, then leaves ashes. You can't light a fire
  on top of another.
- **Cooking:** use raw food on any fire (including ones you light) or range; pick an amount if you
  have several. 26 foods from shrimps to manta ray; burn chance drops to zero at each food's
  stop-burn level, ranges burn slightly less. Burnt food goes to its burnt item.
- Data: `SkillsData.kt`. Startup logs `[beanlab] skills registered: ...`.

### Mining & smithing (`content/other/beanlab-smithing`)

- **Mining:** click a rock (or ore vein) with a pickaxe in your inventory or hand. The best pickaxe
  you can use is picked automatically (bronze to crystal; better = faster and more likely). One ore
  empties the rock; it respawns after the ore's respawn time. Levels/XP follow OSRS: clay/copper/tin
  1, limestone 10, iron 15, silver 20, coal 30, gold 40, mithril 55, soft clay & adamantite 70,
  runite 85, amethyst 92.
- **Smelting:** "Smelt" a furnace or use an ore on it, pick the bar and how many (1/5/10/All).
  Bronze, iron (50% success), silver, steel, gold, mithril, adamantite, runite.
- **Smithing:** with a hammer, use a bar on an anvil (or "Smith" it), pick the item and how many.
  22 items per metal (dagger to platebody, incl. bolts, dart tips, arrowtips, knives), bronze to rune.
- Data (levels, XP, success rates, respawn times, recipes) is in `SmithingData.kt`. Startup logs
  `[beanlab] mining/smithing registered` and lists `'Mine' locs not covered` (special rocks like gem
  rocks, essence, rubble, crashed stars need their own content).

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
| `osrs-discord.service` | Discord `/register` + `/resetpassword` bot | enabled |

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

### Admin console API

`content/other/admin-api` serves the in-client admin console (the Beanscape Admin plugin). It listens
on `127.0.0.1:8087` only, so expose it with `tailscale serve`, next to the existing paths:

```bash
sudo tailscale serve --bg --set-path /adminapi http://127.0.0.1:8087
```

- **Tokens:** an owner runs `::admintoken` in game (`::admintoken revoke` revokes them). The token is
  shown once in chat; only its SHA-256 is stored (`admin_tokens`, V12). For now only owner accounts
  can use the API (`DbTokenStore.MIN_TOKEN_RANK`). The account's mod level is read on every request.
- **Rules:** every route has a minimum rank; nobody can act on someone ranked above them or hand out
  a rank above their own; all writes land in `admin_audit` (refusals included).
- **Switches:** `ADMIN_API_ENABLED=false` keeps the listener off; `ADMIN_API_PORT` changes the port.
- **Deploying:** V12 runs on boot. Stop `osrs-server` before building, as for any overlay change.
- Not yet tested on the live server: the game-side actions (teleport, summon, give, stats, kick).

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
- **Fresh start:** all accounts, characters, stats, inventories and GE data wiped (backup:
  `/opt/osrs/backups/game-2026-11-24-134327-pre-wipe.db`); `panel_users` emptied. Owners are
  promoted manually after registering (admin panel → account → modlevel, plus `panel_users`).
- **Discord registration panel:** pinned message with Register (opens a form) and Reset password
  buttons, alongside `/register` and `/resetpassword`.
- **Prayer, firemaking, cooking:** bury 55 bone/ash types, pray at 44 altar types; tinderbox on 12
  log types lights a real fire (ashes when it burns out); cook 26 raw foods on 120 fire/range types
  with level-based burning. (Upstream's cooking module turned out to be empty.)
- **Mining & smithing:** 72 rock types (ore rocks and veins, limestone, soft clay, amethyst) with
  pickaxe tiers, success rolls, depletion/respawn; smelting 8 bars at 41 furnace types (ore on
  furnace or "Smelt"); smithing 22 items per metal (bronze to rune) at 17 anvil types with a hammer.
- **Doors & gates:** every unscripted door/gate (902, incl. members gates) is passable: using it
  walks you through. Hand-scripted animated doors are unchanged.
- **Food & potions:** RsMod had no eating or drinking; 347 foods and 510 potion doses now work
  (heal table, combat/ranging/magic/prayer/restore/brew/energy effects, dose step-down, 3-tick delay).
- **Discord registration in Discord:** `/register` and `/resetpassword` slash commands with
  ephemeral replies, linked to the Discord ID; `osrs-discord` enabled.
- **Admin panel:** allowlisted owner game accounts (`panel_users`) can sign in with their game password (before, only
  the single `admin_config.json` login worked, so staff accounts created in the panel got
  "Invalid credentials").
- **Potato spawn:** the item search lists each item's bank placeholder too (named "null"
  server-side, shown with the real name in the client); picking it now spawns the real item.
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
| 4. Discord registration + name change | ✅ Done | `/register` in Discord (ephemeral, linked to Discord ID) + web OAuth on `/oauth` |
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
3. Pick the next missing content (see [Not yet implemented](#not-yet-implemented))

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

### Members items only offer "Use" (no Wield/Wear)
The client strips the inventory options of members items when it thinks it is on a free-to-play
world, and it does that once, when it first loads each item, so the stripped copy sticks for the
rest of the session. Fully close Microbot and relaunch it with `Play-Microbot.bat` (after step 3
above if `jav_config.ws` changed). A fresh client on the current setup was verified with the Scythe
of vitur showing `Wield, Check, Uncharge, Drop`.

### "Mismatch in overlaid cache archive hash for 12/89 / 12/920" in game chat
Harmless. RuneLite ships replacement versions of a few client scripts (12/89 `rebuildpmbox`,
12/920 `toplevel_resize_customise`) built for a slightly later rev 233 cache than the server's
stock one, so it keeps the server's script and logs a warning. Microbot copies RuneLite warnings
into game chat by default. To hide them: **Microbot plugin settings → Game chat logging →** turn on
**"Show only Microbot plugin logs in game chat"** (or set the minimum level to `ERROR`, or turn
game chat logging off).

### "Nothing interesting happens" on a gate, door or object
That's RsMod's reply for an object with no script. Doors and gates are covered by the generic
pass-through (`[beanlab] generic pass-through registered for N unscripted doors/gates` at startup),
except diagonal ones. Other objects (shortcuts, stiles, levers, most skilling objects) still aren't
implemented upstream; it isn't a membership problem. Use `::teleports` to get past them.

### Food or a potion says "Nothing interesting happens"
Check the startup log for `[beanlab] consumables registered`. Items whose option isn't literally
"Eat"/"Drink", or potions without a `(n)` dose suffix, aren't covered. Foods not in `FoodTable` heal 3;
potions not in `PotionTable` are consumed with "no special effect".

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

### Discord bot offline / `/register` doesn't appear
`systemctl status osrs-discord` and `journalctl -u osrs-discord -n 30`. Look for `synced 2 slash
commands`; if it says `cannot register slash commands`, re-invite the bot with the URL it logs.

### GE commands not working
Restart after GE migrations (Flyway runs on boot) and check V7/V8 in `flyway_schema_history`. If
`::ge` says "still loading", look for `Failed to restore Grand Exchange offers` in the server log.

---

## Support

- **Vault note:** `02-Projects/OSRS Private Server/Build Plan.md`
- **Runbooks:** `05-Runbooks/Tailscale - Joining LXCs and Serve.md`
- **Gitea:** https://gitea.tail5475a4.ts.net/cfiaschetti/osrs-server.git
- **Hermes skill:** `osrs-rsmod-server`
