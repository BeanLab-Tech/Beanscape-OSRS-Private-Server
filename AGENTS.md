# AGENTS — BeanLab OSRS Private Server

Context for AI coding agents (Claude Code, Codex, OpenCode, …). This is the single source of
truth for agents; `CLAUDE.md` imports it. Latest audit: `docs/AUDIT_2026-10-29.md`.

**Keep this file current.** Any PR that adds a module, migration, service, port, repo or workflow
updates this file (and `README.md` for player/admin-facing changes) in the same PR. The staging
server's guide is `UPSTREAM.md` in `BeanLab-Tech/Beanscape-OpenRune`; keep the two consistent.

---

## What This Repo Is

An **overlay** on upstream [RsMod](https://github.com/rsmod/rsmod) (rev 233). It contains only
the BeanLab additions — there is no Gradle wrapper, engine or `settings.gradle.kts` here:

```
api/db/src/main/resources/db/migration/
  V7__grand_exchange.sql             GE tables
  V8__grand_exchange_revisions.sql   GE revision column + trade dedupe keys
  V9__beanlab_realm_hardening.sql    real auth + Discord-only registration on the `dev` realm
  V10__discord_oauth.sql             Discord OAuth columns
  V11__beanlab_members_for_all.sql   every account members
  V12__admin_api.sql                 admin_tokens + admin_audit (admin console API)
  V13__beanlab_registered_characters.sql    characters rows for accounts made outside the game
  V14__beanlab_safe_auto_display_names.sql  safer auto display names (no case clashes, no staff names)
  V15__discord_avatar.sql            accounts.discord_avatar (profile picture in the admin console)
  V16__beanlab_farming_and_poh.sql   beanlab_farming_patches + beanlab_poh_furniture
api/account/.../CharacterAccountApplier.kt   forces player.members = true at login; moves players
                                     saved inside an instance (x >= 6400) to the Rimmington portal
api/death/.../NpcDeath.kt            upstream file + publishes NpcKilled(npc, hero) on every npc
                                     death (slayer kill credit; pets/drop tables can use it too)
api/config/.../BaseObjs.kt           adds objs.rotten_potato
content/other/admin-potato/          rotten potato menu, ::teleports/::tp (TeleportCatalog/Menu),
                                     ::questsdone (QuestCommands + generated QuestProgressVars)
content/other/admin-api/             loopback HTTP API for the in-client admin console (AdminRouter
                                     holds every permission rule; GameBackend runs on the game
                                     thread via GameAccess), ::admintoken (owner), tests
content/other/beanlab-smithing/      mining, smelting, smithing (data in SmithingData.kt)
content/other/beanlab-skills/        prayer, firemaking, cooking (data in SkillsData.kt)
content/other/beanlab-content/       generic door/gate pass-through (unimplemented-op slot; skips
                                     Pick-lock doors), eating/drinking, shops (shops.txt)
content/other/beanlab-{crafting,thieving,fletching,herblore,runecraft,fishing,magic}/
                                     skill modules; crafting has Jewellery*, thieving ThievingExtras*
content/other/beanlab-agility/       Gnome, Barbarian, Draynor rooftop (fixed tiles, AgilityData.kt)
content/other/beanlab-slayer/        tasks from cache dbtables slayer_task/slayer_master_task, OSRS
                                     slayer vars, NpcKilled credit; SlayerTasks is unit-tested
content/other/beanlab-hunter/        snares/box traps (in-memory, StartCycle), nets, impling jars
content/other/beanlab-farming/       patches via region farming_transmit varbits, growth stages
                                     parsed from multiloc names, DB per character (V16), tests
content/other/beanlab-construction/  sawmill + instanced starter house (RegionTemplate copy of the
                                     Rimmington garden/parlour/workshop templates), DB (V16)
content/other/beanlab-worldmap/      minimap globe -> worldmap (595) in map_container; sends
                                     worldmap_transmitdata (1749) on movement while open
content/other/beanlab-soulwars/      Soul Wars portal at the GE -> Market Hub (x 5120-5219); layout in
                                     hub-layout.json (docs/HUB_LAYOUT.md), terrain packed into cache
content/other/beanlab-spawns/        static NPC spawns outside Lumbridge (WorldNpcSpawnScript)
content/other/grandexchange/         GE engine (GeBook), ::ge commands (GeCommands), tests
content/other/commands/.../NameChangeCommands.kt   ::name
web/                                 Discord registration + admin panel (FastAPI)
discordbot/bot.py                    Discord bot (token read from /opt/osrs/config on the VM;
                                     never posts passwords in-channel)
client/microbot/                     patch-microbot.py (Microbot 2.0.20 -> BeanLab: RSA modulus,
                                     host allowlist, version-check URL) and Play-Microbot.bat
client/microbot/admin-plugin/        Beanscape Admin plugin (Java 11, Gradle wrapper): side-loaded
                                     into home\.runelite\microbot-plugins; Install-AdminPlugin.ps1
                                     builds and installs it. Talks to content/other/admin-api.
client/microbot/companion-plugin/    Beanscape Companion plugin (character stats and feed for the
                                     launcher's Home tab)
client/launcher/                     Electron launcher: one entry per server in
                                     electron/server-config.cjs (live = rev 233 / Microbot 2.0.20,
                                     staging = OpenRune 242 / Microbot 2.6.57); Settings -> Server
                                     picks one; plugins/ bundles the Admin + Companion jars
deploy/                              VM auto-deploy: deploy-vm.sh (timer every 5 min, CI-green main
                                     only, build-then-restart), systemd units, install.sh
webconfig/                           www/ = /opt/osrs/config/webconfig on the VM (jav_config.ws,
                                     world_list.ws, runelite/worlds.js + version + prices), served
                                     no-cache by serve.py (osrs-webconfig); keep bytes exact
scripts/build-overlay.sh             builds/tests the overlay on pinned upstream
docs/                                session report, audit
```

On VM 123 the overlay is copied into an upstream checkout at `/opt/osrs/server/rsmod`. Build there
with `./gradlew :server:app:installDist` **with `osrs-server` stopped**: replacing jars under the
running JVM breaks its shutdown save hook (NoClassDefFoundError) and loses online players' progress.

Not in this repo: secrets (`/opt/osrs/web/config.json`, `env`, `admin_config.json`, the Discord
token file), RSProx client config (`~/.rsprox/` on Windows), runtime data (`.data/`).

---

## Repositories and Local Layout

| Repo | What | Clone on bean-laptop |
|---|---|---|
| `BeanLab-Tech/Beanscape-OSRS-Private-Server` (this one) | Live server overlay (RsMod rev 233), launcher, plugins, web panel, Discord bot, VM deploy | `C:\Users\cfiaschetti\Beanscape-OSRS-Private-Server` |
| `BeanLab-Tech/Beanscape-OpenRune` | Fork of OpenRune-Server (rev 242), the migration target, running as **staging**. Its `UPSTREAM.md` is its Beanscape guide | `C:\Users\cfiaschetti\Beanscape-OpenRune` |

The launcher lives only here (`client/launcher`), for live and staging alike.

---

## OpenRune Migration (Staging)

Plan: `docs/OPENRUNE_PHASE1_PLAN.md`. Phase 1 status, 2026-11-25:

| Workstream | Status |
|---|---|
| W1 fork + CI, W2 Postgres + Central on the VM | Done |
| W3 accounts | `require_registration` enforced at login (fork, `// BeanLab:`). Staff accounts (`wiseoldbean`, `umpix`) copied from live with their Argon2 hashes. Discord sign-up and password reset still write only the live SQLite DB, not Central |
| W4 admin API | Done (`content/beanlab/beanlab-admin-api` in the fork). Verified 2026-11-25: the Admin plugin on RuneLite 1.13.6 logs in with an owner token and shows staging data |
| W5 Grand Exchange | Done (`beanlab-ge`) |
| W6 small features | `::teleports` / `::tp` done (fork `beanlab-admin-tools`, MOD and up; `TeleportCatalog` now in `beanlab-core`). Rotten potato, `::questsdone`, `::name` not started |
| W7 client pipeline, W9 data migration | Not started |
| W8 deploy | Done: `beanscape-staging-deploy.timer` runs the fork's `deploy/staging/deploy-staging.sh` every 5 minutes and deploys each CI-green fork `main` (Postgres dump first, `game.yml` rendered from `deploy/game.staging.yml`). Merging to the fork restarts staging, never live |

Staging on VM 123, next to live:

| Piece | Where |
|---|---|
| Game | `beanscape-staging` service, `/opt/osrs/openrune/server` (fork `main`, auto-deployed), world 2, **port 40002**. Deploy log: `journalctl -u beanscape-staging-deploy` |
| Why 40002 | The client has no port setting. With jav_config `param=15` (build) non-zero it connects to `40000 + world`. Staging's jav_config sets that: `https://runescape-server.tail5475a4.ts.net/staging/jav_config.ws` (files in `/opt/osrs/config/webconfig/staging/`) |
| Accounts | OpenRune Central (`openrune-central`, Postgres `openrune_central`, `public.accounts`). Registration required, plus `world_login_whitelist` for world 2. Owner = `ADMINISTRATOR` rights plus a row in `beanlab.owners` |
| Admin API | `127.0.0.1:8088`, exposed as `https://runescape-server.tail5475a4.ts.net/adminapi-staging`. Tokens: `::admintoken` in game (owners), or `beanlab.admin_tokens` (SHA-256 of the raw token) |
| Central | Runs OpenRune-Central `d694191` **plus the fork's `deploy/central/patches/`**: unpatched, every login to a whitelisted world fails with "No reply from loginserver" |
| Client side | Plugins must run on both RuneLite 1.11.19 (live) and 1.13.6 (staging): a `NoSuchMethodError` on the client thread freezes the game on login (Companion 1.0.1 fixed `getItemPrice` returning `long`). The launcher's staging entry uses `https://runescape-server.tail5475a4.ts.net/staging/runelite` (world list with world 2 only) and pins Microbot AutoLogin to world 2. AutoLogin works on Microbot 2.6.57: the first attempt lands on the rev 242 "The move is almost here" notice and fails, the retry ~5 s later logs in. A failed login clears the password field, so a blank password after failures is a symptom, not the cause |
| Bots | OpenRune's cosmetic bots are on (`gameplay.bots-enabled`); `::bots count|on|off|here` in game |
| Secrets | `/opt/osrs/openrune/secrets.env` and the VM copy of `central-config.yaml` only. `game.yml` is regenerated from the repo on every deploy, so edit `deploy/game.staging.yml` instead |

---

## Build & Test

Prerequisites: JDK 21 (a full JDK; the RuneLite launcher also needs `jdk.security.jarsigner`).

```bash
scripts/build-overlay.sh                  # GE tests + compile ::name + spotless checks
scripts/build-overlay.sh build -x test    # any Gradle args, run inside the upstream checkout
```

The script clones upstream at the pinned commit into `build/upstream/` (git-ignored), copies
`api/` and `content/` over it and runs `./gradlew`. CI (`.github/workflows/ci.yml`) runs the same
script. Fix formatting with `scripts/build-overlay.sh :content:other:grandexchange:spotlessApply`
and copy the result back (formatting is ktfmt kotlinlang style, max width 100).

On the VM: `sudo systemctl restart osrs-server`, logs `journalctl -u osrs-server -f`.

**Merging to `main` can restart live.** `deploy/deploy-vm.sh` deploys every CI-green `main` within
5 minutes and restarts the live server when the change touches `api/`, `content/`, `web/`,
`discordbot/`, `webconfig/`, `client/` or `deploy/` (online players are saved first). Changes to
anything else (docs, `AGENTS.md`, `README.md`, `CHANGELOG.md`) deploy without a restart.

Launcher: `cd client/launcher; npm install; npm test; npm start`.

---

## Code Conventions (upstream's, enforced by its build)

- Kotlin, Java 21 target, Gradle Kotlin DSL; modules use `plugins { id("base-conventions") }`.
- Plugins are `PluginScript` subclasses with `@Inject` constructors (Guice); register handlers in
  `ScriptContext.startup()`.
- New content modules are **auto-discovered**: any `content/**/build.gradle.kts` is included by
  `settings.gradle.kts` and put on the server classpath by `server/shared`. No plugin list to edit.
- Commands: `onCommand("x") { desc = …; cheat { … } }` from `org.rsmod.api.script`. Set
  `modLevel = modlevels.admin` for admin commands. The internal `onCommand(cmd, desc, ::fn)`
  helper in `content/other/commands/ScriptHelpers.kt` **always** makes a command admin-only.
- Konsist rules: PascalCase enum entries, no wildcard imports, properties before functions,
  companion object last.
- Database access: `GameDbManager.request(request, response)`. The request runs in its own
  transaction on a **multi-threaded** worker pool and may be retried; the response runs on the
  game thread. So: pass immutable snapshots, keep writes idempotent, and resolve players in
  callbacks via a captured `PlayerUid`, not a held `Player`.
- Tests: `src/test/kotlin` (JUnit via `kotlin("test")`).

---

## Database

- SQLite via Flyway, `.data/saves/game.db`. Version: `SELECT * FROM flyway_schema_history;`
- Upstream owns V1–V6; BeanLab owns V7+. Never edit or renumber an applied migration — add a new
  one. Upstream will eventually ship its own V7 (see audit R9).
- `accounts`: `login_username`, `display_name`, `password_hash`, `modlevel` (TEXT: `owner`,
  `admin`, `moderator`, or NULL for a normal player).
- `realms`: `require_registration`, `ignore_passwords`, `dev_mode`, `auto_assign_display_names`,
  `player_xp_rate_in_hundreds` (dev realm: 15000 = 150×).

**Never set `ignore_passwords = 1` with `dev_mode = 1` on a realm players can reach**: together
they accept any password for any account, and `dev_mode` gives every new account `owner`.

---

## Authentication

Fixed parameters, shared with the Discord bot — do not change one side without the other:

```
Argon2i (argon2-jvm Argon2Factory.create(), argon2-cffi Type.I)
time_cost=20, memory_cost=65536, parallelism=1      hash prefix: $argon2i$
```

JVM: `de.mkammerer:argon2-jvm:2.12` (upstream `api/pw-hash`). Python: `argon2-cffi`.

---

## What NOT to Change

| Area | Reason |
|------|--------|
| Upstream code not in this overlay | Lives upstream; fix bugs there or overlay a file deliberately |
| Argon2 parameters/type | Must match the Discord bot |
| Applied migrations (V7–V9) | Flyway checksums; add a new version instead |
| `.data/`, secrets, tokens | Runtime data / credentials, never commit |

---

## Git

- `origin` here is the BeanLab GitHub repo. `main` holds the overlay.
- The VM checkout is different: its `origin` is upstream rsmod (never push there) and `gitea` is
  the BeanLab Gitea mirror.

### VM ↔ overlay reconciliation (two lineages, by design)

The VM checkout (`/opt/osrs/server/rsmod`) is a **full upstream RsMod checkout** whose git history
is rooted in upstream (`fa13b3f6` → BeanLab commits). This overlay's history is rooted in its own
curated commits. The two `ferox-buildout` branches are **unrelated lineages** — do NOT push the
VM's git history directly to the overlay (it would be non-fast-forward and would flood the
overlay with `engine/`, `server/`, `build-logic/`, `.data/`, etc.).

Reconciliation = **content projection**: copy the VM's `api/` + `content/` (the overlay-relevant
subset) into a clone of this overlay, commit, push. Verify byte-consistency with
`diff -rq <vm>/api <overlay>/api` (only "Only in <vm>" lines are expected — the overlay carries a
subset). The overlay additionally holds `docs/`, `web/`, `scripts/`, `AGENTS.md`, `CLAUDE.md`,
`.github/`, which the VM does not.

**Working push path from the VM** (the Gitea LXC bare repo is root-owned, and the VM's git 2.53
sends `Authorization: Bearer` over HTTP where Gitea wants `Basic` — the LAN HTTP push is broken).
Use the sudo-SSH form instead:

```bash
# key: /root/.ssh/id_ed25519_gitea  (pubkey installed on the Gitea LXC authorized_keys)
GIT_SSH="ssh -i /root/.ssh/id_ed25519_gitea -o StrictHostKeyChecking=no"
REPO=/var/lib/gitea/data/gitea-repositories/cfiaschetti/osrs-server.git
# read overlay state:
$GIT_SSH cfiaschetti@192.168.1.117 "sudo git --git-dir=$REPO for-each-ref"
# push a local ref (e.g. after syncing overlay content into a clone on the VM):
$GIT_SSH cfiaschetti@192.168.1.117 "sudo git --git-dir=$REPO push origin <branch>"
```

The agent's own box (Sheldon profile) reaches Gitea over the tailnet
(`https://gitea.tail5475a4.ts.net`, `GITEA_TOKEN` in `~/.hermes/profiles/sheldon/.env`) — that push
path works with plain `git push`. The VM cannot reach the tailnet, hence sudo-SSH.

---

## Testing Checklist

1. `scripts/build-overlay.sh` passes (tests, compile, spotless).
2. Schema changes come with a new Flyway migration.
3. Server boots on the VM without errors; the feature works in-game.
4. No credentials committed; Argon2 untouched (if touching auth).

---

## Known Issues

1. RSProx's `RuneliteLauncher.kt` falls back to a rev-241 bootstrap commit that 404s; always set
   `runelite_bootstrap_commithash: 4bed6512f101aadb1ba20e56ca124dff2fba387d` for rev 233.
2. V8/V9 must be deployed, and accounts that received `owner` from dev mode demoted
   (audit §4).
3. node-exporter on `tag:game` is not scraped (ACL decision pending).
4. GE state and player inventories are saved in separate transactions (audit R6).
5. Launcher on Node 26: `npm install` can leave Electron unpacked ("Electron failed to install
   correctly"). The download is cached; unpack
   `%LOCALAPPDATA%\electron\Cache\<hash>\electron-v33.4.11-win32-x64.zip` into
   `client\launcher\node_modules\electron\dist` and write `electron.exe` (no newline) to
   `node_modules\electron\path.txt`.
