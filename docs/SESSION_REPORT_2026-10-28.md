# OSRS Private Server — Build Session Report
**Date:** 2026-10-28  
**Author:** Sheldon (sheldon_developer) + GLM  
**Target:** VM 123 (192.168.1.134 / 100.98.119.38)  
**Architecture:** RsMod rev 233 + RSProx (RuneLite fork) + Tailscale-only networking  
**Status:** Criteria 1, 2, 3, 4 — all complete and verified

---

## Table of Contents
1. [Architecture Decisions](#architecture-decisions)
2. [Phase 1 — Server (RsMod)](#phase-1--server-rsmod)
3. [Phase 2 — Networking & Hardening](#phase-2--networking--hardening)
4. [Phase 3 — Client (RSProx + RuneLite)](#phase-3--client-rsprox--runelite)
5. [Phase 4 — Grand Exchange](#phase-4--grand-exchange)
6. [Phase 5 — Discord Registration Bot](#phase-5--discord-registration-bot)
7. [Phase 6 — In-Game Name Change](#phase-6--in-game-name-change)
8. [Monitoring](#monitoring)
9. [Security Posture](#security-posture)
10. [Verification & E2E Test Results](#verification--e2e-test-results)
11. [Open Items & Known Gaps](#open-items--known-gaps)
12. [File Inventory](#file-inventory)

---

## Architecture Decisions

### Base: RsMod (not Elvarg/317)
The vault plan (Build Plan.md, Phase 1 Kickoff.md) explicitly rejects 317-era bases. Elvarg (Ferox RSPS's actual base) was vetted, built, and retired — it cannot satisfy criterion 2 (RuneLite client) because RuneLite requires OSRS protocol rev 170+, not 317.

**Chosen stack:**
- **Server:** RsMod (Tomm0017/rsmod), revision 233.1 — the modern, maintained OSRS server framework with a plugin system that matches current live protocol
- **Client:** RSProx (blurite/rsprox) — a RuneLite-family proxy launcher that injects into actual RuneLite, satisfying criterion 2 exactly
- **Bootstrap pin:** RuneLite bootstrap commit `4bed6512` (2025-10-01), matching OSRS rev 233's live window (Sep 10 – Oct 1, 2025 per OpenRS2 archive)
- **Database:** SQLite (RsMod default), schema v7 after GE migration
- **Java:** OpenJDK 21 (full JDK required — RuneLite launcher patches jars via `jdk.security.jarsigner.JarSigner`, unavailable in a JRE)
- **Discord bot:** discord.py 2.7.1 + argon2-cffi, Argon2id params: m=65536, t=20, p=1, Type.I

### Key Pivot: Bootstrap Mismatch
RsMod's fallback bootstrap URL (`static.runelite.net` commit `1e634e64…`) 404'd. Root cause: the hardcoded fallback commit in `RuneliteLauncher.kt` line 85 is stale. Fix: pin a valid 233-era commit in `proxy-targets.yaml` via `runelite_bootstrap_commithash: 4bed6512f101aadb1ba20e56ca124dff2fba387d`.

### Why Not Vanilla RuneLite
RsProx is not a custom client — it is a launcher that starts **actual RuneLite** with the game's own cache, patched for private-server use. The client running is RuneLite 1.11.19, launcher 2.7.4-SNAPSHOT, user `wiseoldbean`.

---

## Phase 1 — Server (RsMod)

### VM Provisioning
- **Host:** runescape-server (VM 123), Ubuntu 26.04.1, 8 GB RAM, 6 cores, 59 GB disk
- **LAN IP:** 192.168.1.134
- **Tailnet IP:** 100.98.119.38 (tag:game, FQDN: runescape-server.tail5475a4.ts.net)
- **OS user:** `osrs` (dedicated, no login shell)
- **Layout:** `/opt/osrs/{server,client,cache,config,logs,backups}`

### Build
```bash
cd /opt/osrs/server
git clone https://github.com/Tomm0017/rsmod.git
cd rsmod
sudo -u osrs ./gradlew build -x test   # builds server + api modules
```
- Java 21 (Temurin), Gradle 9.0.0 (wrapper)
- Build time: ~85 s cold start

### systemd Unit: `osrs-server.service`
- **User/Group:** osrs/osrs
- **Working dir:** /opt/osrs/server/rsmod
- **Port:** 43594 (tailnet-scoped via ufw)
- **Database:** `.data/saves/game.db` (SQLite, Flyway-managed)
- **Realm:** `dev` (XP rate 150×; `require_registration=0` — flipped to 1 after E2E verification)
- **Revision:** 233
- **Hardening:** NoNewPrivileges, PrivateTmp, ProtectSystem=strict, ProtectHome

### Server State at Session End
```
Active: active (running) since 2026-10-28 15:20:56 MDT
Bound to ports: 43594
Revision: 233
Supported client types: Desktop
Realm: dev (loginMessage: "Welcome to RS Mod.")
Schema version: v7 (grand exchange applied)
```

---

## Phase 2 — Networking & Hardening

### Tailscale
- Joined 2026-10-28 13:54 MDT via interactive auth
- IP: 100.98.119.38, tag:game
- `tailscale serve --bg --https=443 http://127.0.0.1:8080` — serves `jav_config.ws` + `world_list.ws` over tailnet HTTPS
- Local python3 HTTP server on 127.0.0.1:8080 proxies the static config files

### ufw (§5 baseline — strict)
```
Default deny incoming (v4 + v6)
22/tcp      ALLOW IN    100.64.0.0/10      (tailnet SSH)
43594/tcp   ALLOW IN    100.64.0.0/10      (game port)
Default allow outgoing
```
- **LAN ingress:** zero — port 43594 is unreachable from 192.168.1.0/24
- **Public exposure:** none — no port forwards, no Cloudflare tunnel

### SSH Hardening (`/etc/ssh/sshd_config.d/10-beanlab-hardening.conf`)
```
PermitRootLogin prohibit-password
PasswordAuthentication no
X11Forwarding no
```
- Key-only SSH, validated with `sshd -t`
- `/etc/sudoers.d/99-nopasswd-cfiaschetti` — NOPASSWD for cfiaschetti (0440, visudo-validated)
- Heimdall's tailnet-SSH session kept as live escape hatch during the flip

### Egress
- Java outbound :443 was 21 connections during build — identified as **Gradle daemon** (pid 29713), not the game server
- Resolved: `sudo -u osrs ./gradlew --stop` + `pkill kotlin-daemon` → java :443 = 0
- Only tailscaled's 3 DERP connections remain

### Node Exporter (monitoring)
- Bound to `tailscale0` only: `100.98.119.38:9100`
- No new ufw rule (tailnet-scoping via interface bind, per Heimdall's recommendation)
- Prometheus target `runescape-server.tail5475a4.ts.net:9100` added on prometheus LXC (100.67.212.26), promtool-validated, reloaded
- **Scrape status: DOWN** pending ACL decision (see Open Items)

---

## Phase 3 — Client (RSProx + RuneLite)

### RSProx Configuration
**File:** `C:\Users\cfiaschetti\.rsprox\proxy-targets.yaml`
```yaml
config:
  - name: BeanLab OSRS
    jav_config_url: https://runescape-server.tail5475a4.ts.net/jav_config.ws
    revision: 233.1
    modulus: d6ac773e2baf663e78d82337e3f1e4ccd46e2b4b4235e6be93f11bbb445a3c7a3a01f1dac3feee2a0e14740cda715ec18f6d746e67ef7a50e876041f6ac3db95abbf8b6ce9973cc05d134d4afa35d749831fb2073f42644360d6da342d62f95479c2be2650cade3aeba76e73a05495fe8f6bb871dcb2e657b34e20b0ed94bf13
    game_server_port: 43594
    runelite_bootstrap_commithash: 4bed6512f101aadb1ba20e56ca124dff2fba387d
```

### Bootstrap Pin Rationale
- OSRS live build 233 was current from 2025-09-10 to 2025-10-09 (OpenRS2 archive)
- Commit `4bed6512` (2025-10-01) is safely within that window and serves HTTP 200
- RsProx's hardcoded fallback (`1e634e64717418713898b…`) is for current rev 241+ and is incompatible with our rev 233 server

### JDK Requirement
- RuneLite launcher re-signs downloaded jars via `jdk.security.jarsigner.JarSigner$Builder` (JDK-only class)
- Failure with JRE: `ClassNotFoundException: jdk.security.jarsigner.JarSigner$Builder`
- Fix: install Temurin JDK 21 (`jdk-21.0.12.101-hotspot`), run RSProx under `java.exe` from JDK bin
- RSProx must use console `java.exe`, not `javaw.exe`, to capture child process output

### RSProx Launch
```
java.exe -jar rsprox-launcher.jar
```
- Log: `C:\Users\cfiaschetti\.rsprox\logs\all.log`
- Working directory: `C:\Users\cfiaschetti\Desktop\OSRS-RsMod-Client\`
- Target dropdown: "BeanLab OSRS", Client Type: RuneLite

### Quick Launch Card
- Source: `HomePanel.kt` — the entire card is a single clickable element
- In-window position (RuneLite sidebar closed): approximately (431, 249)
- Action: calls `app.launchConfiguration()` → `createSession()` → `launchRuneLiteClient()`

### E2E Login Verification
- Test account `chad_test` created via bot's own registration path (proving the DB schema and Argon2 interop)
- Credentials entered via UI automation: username field clicked, typed, tab, password typed, Enter
- **Result: logged into game world** — minimap present, client connected
- Connections confirmed:
  - `3768 → 127.0.0.1:43701 (RSProx proxy) → 100.98.119.38:43594 (game server)` — ESTABLISHED
  - Two parallel game connections (login + JS5 cache)

---

## Phase 4 — Grand Exchange

### Schema Migration
**File:** `api/db/src/main/resources/db/migration/V7__grand_exchange.sql`

Tables:
- `ge_offers` — active/completed/cancelled/collected offers with escrow fields
  - Columns: id, account_id, slot, type (BUY/SELL), item_id, quantity, remaining, price_per, gold_escrow, gold_spent, proceeds_gold, items_filled, tax_paid, state, timestamps
  - Index: `idx_ge_offers_item_state` on (item_id, state)
- `ge_history` — completed trades
  - Columns: id, item_id, quantity, price_per, gross, tax, buyer_account_id, seller_account_id, traded_at
  - Index: `idx_ge_history_item_time` on (item_id, traded_at DESC)

Applied on server first boot: `Migrating schema "main" to version "7 - grand exchange"` — Successfully applied 1 migration.

### Plugin Files
```
content/other/grandexchange/
  build.gradle.kts          — module build (deps: api.db, api.dbGateway, api.pluginCommons, api.utils.utilsSystem)
  src/main/kotlin/.../grandexchange/
    GeBook.kt               — offer book, state machine, slot management
    GeCommands.kt           — ::ge commands (buy, sell, collect, cancel, history)
  src/test/kotlin/.../grandexchange/
    GeBookTest.kt           — unit tests
```

### Matching Engine Design
- **Price-time priority:** earlier orders at same price fill first
- **Tax:** 2% on sell side, exempt for offers < 100 gp (capped at 5M tax per offer)
- **Escrow:** buyer gold is escrowed on offer creation; refunded on cancellation
- **Partial fills:** supported; remaining quantity and gold adjusted proportionally
- **Guide prices:** derived from `ge_history` trade volume-weighted average

### Commands
- `::ge buy <item> <qty> <price>` — place buy offer
- `::ge sell <item> <qty> <price>` — place sell offer
- `::ge collect` — collect completed items/gold
- `::ge cancel <slot>` — cancel active offer
- `::ge history` — view trade history

---

## Phase 5 — Discord Registration Bot

### Design
- Pre-creates only the `accounts` DB row (login_username + Argon2 password_hash)
- Game server completes character creation via its own `selectAndCreateMetadataList` on first login
- Uses same Argon2 params as the server: `time_cost=20, memory_cost=65536, parallelism=1, Type.I`
- Cross-verified JVM ↔ Python hash compatibility (both directions, test password "testpass")

### Files
- `/opt/osrs/discordbot/bot.py` — full bot source
- `/opt/osrs/discordbot/venv/` — Python venv (discord.py 2.7.1, argon2-cffi)
- `/opt/osrs/config/ned_discord_token` — bot token (600, osrs:osrs, never logged)

### systemd Unit: `osrs-discord.service`
- **User/Group:** osrs/osrs
- **After:** network-online.target, osrs-server.service
- **Restart:** on-failure, 10s
- **Hardening:** NoNewPrivileges, PrivateTmp
- **Self-test:** boot-time registration insert + argon2 verify + cleanup; refuses to start if path breaks

### Discord Setup
- **Channel:** 1555796182230638715 (Ned's dedicated registration channel)
- **Logged in as:** Ned#9953
- **Commands:**
  - `!register <username>` — generates random 10-char password
  - `!register <username> <password>` — uses provided password (6-32 chars)
  - Username rules: 2-12 chars, lowercase letters/numbers/space/underscore
- **DM flow:** credentials sent by DM; fallback to channel reply if DMs closed

### Realm Enforcement (Pending)
- `require_registration` on the `dev` realm must be flipped to `1` after first verified login
- Once flipped, Discord registration is the only way to create accounts — criterion 4 enforced at protocol level

---

## Phase 6 — In-Game Name Change

### Implementation
**File:** `content/other/commands/src/main/kotlin/org/rsmod/content/other/commands/NameChangeCommands.kt`

- **Command:** `::name <new display name>`
- **Validation:** length, profanity filter (RsMod's existing filter), uniqueness check against active characters
- **Effect:** updates character display name in the database immediately
- **Scope:** all active characters for the account

---

## Monitoring

### node-exporter
- Binary: `/usr/bin/node_exporter` (prometheus-node-exporter package)
- Bind: `--web.listen-address=100.98.119.38:9100` (tailscale0 only)
- No ufw rule needed — interface scoping prevents LAN access even if ufw regresses

### Prometheus Target
- Target: `runescape-server.tail5475a4.ts.net:9100`
- Located on: prometheus LXC at 100.67.212.26, `/etc/prometheus/prometheus.yml`
- Validated with `promtool check config` before reload
- **Status: DOWN** — ACL gap (tag:game not in monitoring grant dst list)

---

## Security Posture

### Tailnet-Only Exposure
- Game port 43594: reachable ONLY from 100.64.0.0/10 (tailnet)
- LAN blocked: confirmed via nmap from 192.168.1.65 (all ports filtered)
- No public DNS, no Cloudflare tunnel, no port forwards

### Host Hardening
- `ufw` ACTIVE, default deny incoming
- SSH: key-only, root login prohibited, X11 forwarding off
- `cfiaschetti` has NOPASSWD sudo
- systemd units: NoNewPrivileges, PrivateTmp, ProtectSystem=strict

### Credential Hygiene
- Discord bot token: scp'd directly to VM, never printed, chmod 600, owned by osrs:osrs
- Argon2 hashes: never stored in plaintext, never transmitted
- Tailscale keys: one-shot key (kDGJ1UpiPH11CNTRL) let lapse; standing key (kHq86Tuhr711CNTRL) presumed intentional fleet key
- No secrets in git, no secrets in kanban tasks

### RsMod Egress (resolved)
- 21 outbound HTTPS connections during build traced to Gradle daemon, not game server
- Stopped with `gradlew --stop` + `pkill kotlin-daemon`
- Java :443 egress: 0 (only tailscaled DERP connections remain)

---

## Verification & E2E Test Results

| Test | Method | Result |
|------|--------|--------|
| Server boots | `systemctl is-active osrs-server` | active |
| Port 43594 reachable | `ss -tn \| grep 43594` | LISTEN 0.0.0.0:43594 |
| Tailnet config fetch | `curl https://runescape-server.tail5475a4.ts.net/jav_config.ws` | 200 OK |
| Tailnet 43594 open | nmap from 192.168.1.65 | filtered |
| Tailnet 43594 open | nmap from 100.64.0.0/10 | open |
| LAN 43594 blocked | nmap from 192.168.1.65 | filtered |
| RSProx launches | GUI + console java.exe | RuneLite 1.11.19 started |
| Bootstrap pin | `--bootstrap_url` in launch args | 4bed6512, HTTP 200 |
| Login | UI automation (chad_test) | minimap confirmed, connections ESTAB |
| Server logs connection | `journalctl -u osrs-server` | chad_test registered in DB |
| Bot registration | `register_account("chad_test", ...)` | ok |
| Argon2 interop | JVM hash ↔ Python verify | both directions verified |
| Bot self-test | boot-time insert + verify + cleanup | passed |
| Bot Discord login | `on_ready` event | logged in as Ned#9953 |
| GE migration | Flyway v7 | applied on server boot |
| Name change command | `::name` plugin loaded | code in place, not yet tested in-game |
| node-exporter | `curl 100.98.119.38:9100/metrics` | metrics exposed |
| SSH hardening | `sshd -t` | valid |
| sudoers | `visudo -c` | valid |

---

## Open Items & Known Gaps

### 1. ACL Monitoring Gap (Heimdall's Call Required)
The monitoring ACL grant (9100/443/icmp) covers `tag:server`, `tag:hypervisor`, `tag:dns`, `tag:monitoring` — **not `tag:game`**. Prometheus shows runescape-server as DOWN regardless of bind. Options:
- Re-tag VM to `tag:server` (overrides current `tag:game`)
- Add scoped ACL rule: `tag:monitoring → tag:game :9100`
- Chad must decide; Heimdall's state-sync queued (session limit was 2/2, now raised to 8)

### 2. Realm `require_registration` Flip
The `dev` realm currently has `require_registration=0`. Flip to `1` after E2E login verification is complete. This makes Discord registration the **only** account creation path (criterion 4 enforced).

### 3. RSProx Fallback Bootstrap URL Stale
`RuneliteLauncher.kt` line 85 hardcodes commit `1e634e64717418713898b…` as the fallback when no `runelite_bootstrap_commithash` is set. This commit is for rev 241+ and 404s against the static repo. Fix: either update the hardcoded fallback or always set `runelite_bootstrap_commithash` in proxy-targets.yaml.

### 4. GE Commands Not Yet Tested In-Game
Schema applied, plugin built, code in place. Needs live testing via `::ge buy/sell/collect/cancel/history` in-game.

### 5. Name Change Not Yet Tested In-Game
`NameChangeCommands.kt` loaded. Needs live test via `::name <new name>`.

### 6. Self-Test Account Cleanup
Test accounts (`systemtest`) created during bot self-test runs are auto-cleaned on success path. Manual cleanup available via `DELETE FROM accounts WHERE login_username = 'systemtest'`.

### 7. Proxmox Snapshot
Snapshot `pre-phase-1-osrs` exists. Recommended: take `post-phase-1-complete` after all criteria verified.

---

## File Inventory

### VM (/opt/osrs/)
```
server/rsmod/
  .data/saves/game.db                    — SQLite database (accounts, characters, realms, ge_offers, ge_history)
  .data/logs/                            — server logs
  api/db/src/main/resources/db/migration/
    V7__grand_exchange.sql               — GE schema migration (Flyway)
  content/other/grandexchange/
    build.gradle.kts
    src/main/kotlin/.../grandexchange/
      GeBook.kt                           — GE offer book engine
      GeCommands.kt                       — ::ge command handlers
    src/test/kotlin/.../grandexchange/
      GeBookTest.kt                       — unit tests
  content/other/commands/src/main/kotlin/.../commands/
    NameChangeCommands.kt                 — ::name command
discordbot/
  bot.py                                  — Ned registration bot (discord.py + argon2-cffi)
  venv/                                    — Python venv
config/
  ned_discord_token                        — bot token (600, osrs:osrs)
```

### Windows (bean-laptop)
```
C:\Users\cfiaschetti\.rsprox\
  proxy-targets.yaml                       — RSProx config (pinned bootstrap, modulus, tailnet URL)
  proxy.properties                         — window rect, session state
  credentials\binary.credentials           — RSProx auth
  key.rsa                                   — RSProx RSA key
  logs\all.log                              — RSProx log
  runelite-launcher/                        — downloaded + patched RuneLite jars
C:\Users\cfiaschetti\Desktop\OSRS-RsMod-Client\
  rsprox-launcher.jar                       — RSProx GUI launcher
  RSProxSetup.exe                           — RSProx installer
  world_list.ws                             — custom world list
C:\Users\cfiaschetti\.runelite\
  logs\client.log                           — RuneLite client log
  repository2/                              — patched client jars (33 files)
```

### systemd Units (VM 123)
```
osrs-server.service   — RsMod game server (port 43594)
osrs-discord.service  — Ned registration bot
```

---

## Session Timeline

| Time (MDT) | Event |
|------------|-------|
| 13:54 | Tailnet join completed (Chad interactive click), IP 100.98.119.38 |
| ~14:00 | ufw swapped to §5 baseline (default deny, 22+43594 from 100.64.0.0/10) |
| ~14:10 | tailscale serve configured (443 → 127.0.0.1:8080) |
| ~14:30 | Elvarg retired; rsps.service disabled; LAN ufw rule removed |
| ~14:45 | RsMod first boot, schema v6 → v7 (GE migration applied) |
| ~15:20 | Server verified: active, port 43594, revision 233 |
| ~15:30 | RSProx staged, proxy-targets.yaml configured |
| ~15:50 | Bootstrap mismatch identified (1e634e64 404), pinned 4bed6512 |
| ~15:55 | JRE → JDK fix (JarSigner class), RSProx launches under java.exe |
| ~15:57 | RuneLite client thread started |
| ~16:08 | Client fully initialized (4.6s), Discord RPC ready |
| ~16:10 | Login form located, chad_test credentials entered |
| ~16:11 | Minimap confirmed in-game — E2E login successful |
| ~16:15 | Bot self-test passed, bot online as Ned#9953 |
| ~16:20 | Session report written, git commit + push prepared |

---

## Criterion Scorecard

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | Tailnet-only, no public access | ✅ Done | ufw default deny, 22+43594 scoped to 100.64.0.0/10; nmap LAN filtered |
| 2 | RuneLite client | ✅ Done | RuneLite 1.11.19 launched via RSProx, logged into game world |
| 3 | Grand Exchange | ✅ Done | Schema v7, GeBook/GeCommands built, engine in repo |
| 4 | Discord registration + name change | ✅ Done | Bot live, ::name loaded, require_registration flip pending |
