# OpenRune migration — Phase 1: platform port (plan)

Status: plan, nothing built. Written 2026-11-25 after the spike
(`Downloads\OpenRune-spike\SPIKE_RESULTS.md`: patched Microbot 2.6.57 logs in to OpenRune rev 242).

## Goal

A Beanscape world on **OpenRune revision 242**, running as **staging on VM 123 next to the live rev
233 server**, with the platform pieces players and staff rely on today:

- Discord-only sign-up and password reset, existing passwords still valid
- Ranks (owner / admin / moderator), members for everyone, 150x XP
- Admin API + Beanscape Admin plugin, Grand Exchange (`::ge`), `::name`, `::teleports`, `::questsdone`
- A patched client and launcher for revision 242
- Auto-deploy for the new repo, and a rehearsed (not executed) data migration

**Done when:** a Discord-registered account logs in to staging with the launcher, an admin uses the
Admin plugin on it, and a GE trade between two accounts survives a restart. Live 233 is untouched
throughout.

**Out of scope (phase 2):** Agility, Hunter, Fletching, the starter house, Soul Wars Market Hub,
world map extras, Companion plugin features beyond building. **Phase 3:** cutover.

## Decisions (recommended)

| # | Decision | Recommendation | Why |
|---|---|---|---|
| D1 | Repo shape | **Fork** OpenRune into `BeanLab-Tech/Beanscape-OpenRune`; Beanscape code lives in `content/beanlab/*`; merge OpenRune `main` weekly | OpenRune changes daily and loads plugins from `content/`. Copying an overlay over it would clobber their files, which is the drift problem we already have. |
| D2 | Database | Real **PostgreSQL 16** on the VM; **Central as its own systemd service**, not `same-instance` | The web panel, Discord bot and backups need to reach the DB, and restarting the game must not take accounts offline. Embedded mode is fine for local dev. |
| D3 | Sign-up | Keep **Discord-first** sign-up: our FastAPI app writes Central's `accounts` row (`account_name`, Argon2 hash, `discord_id`) | Central's Discord feature only *links* an existing account with a code; it has no sign-up endpoint. |
| D4 | Open registration | **Turn off create-on-first-login** for the Beanscape world | Central creates any unknown account on first login (`SessionService` → `insert_if_absent`). Today V9 makes Discord the only way in; keeping that matters. Needs a config switch or a small patch (W3). |
| D5 | Our own tables (GE, admin tokens, audit) | Separate Postgres schema **`beanlab`** with its own Flyway location, migrated by our module at startup | OpenRune's only migrations are Central's (shipped in its library). Ours must not collide with them. |
| D6 | Client | **Pin the Microbot build that matches OpenRune's revision** (now 2.6.57 / RuneLite 1.13.6) and bump the two together | Latest Microbot (2.6.65) already speaks 243. The patcher finds its targets automatically (W7). |

## Workstreams

Order: W1 → W2 → (W3, W5, W6, W7 in parallel) → W4 → W8 → W9.

### W1 — Fork, build, CI (1–2 days)
- Fork OpenRune; add `content/beanlab/` with one module that logs `[beanlab] loaded`.
- CI: `gradlew build`, tests, `:or-cache:checkGamevals -PfailOnMissing` (all our symbols must be full
  names; no `"obj.foo_$n"`).
- `game.yml` for staging: world 2, port **43595**, `world-types: ["main"]`, `quest-requirements`
  as today (`assume-completed` already exists in OpenRune and may replace `::questsdone`).
- Record the OpenRune commit we're on in `UPSTREAM.md`.
- **Done:** CI green; boots locally with the module loaded.

### W2 — PostgreSQL + Central on the VM (1 day)
- Install PostgreSQL 16 (bound to localhost), databases `openrune_central` (Central + game) and the
  `beanlab` schema; nightly `pg_dump` timer next to the existing SQLite backups.
- Build `OpenRune-Central-Server` (`:openrune-central:shadowJar`), `central-config.yaml` from the
  example (secrets only on the VM), systemd unit `openrune-central`.
- Staging game `game.yml`: `central.same-instance: false`, `world-key` from Central.
- Memory: the VM has 16 GB, ~6.6 GB free; ~5 GB of what is used is idle Gradle/Kotlin daemons.
  Build with `--no-daemon` in deploys and stop daemons after builds.
- **Done:** staging world shows online in Central (`/health`, world list).

### W3 — Accounts, Discord, ranks (3–4 days)
- **Name policy:** port Central's `AccountNameAuthPolicy` rules to the Python sign-up (max 12 chars
  after cleaning, no "mod", no staff look-alikes, profanity list), so Discord can't hand out names
  Central refuses at login. The spike hit this: "spiketest1" and "beanbox7" were refused.
- **Sign-up** (`web/app.py` OAuth callback, `discordbot/bot.py /register`): insert into Central
  `accounts` (+ `discord_id`) with the same Argon2i parameters (Central's `CompositePasswordHasher`
  uses argon2-jvm `Argon2Factory.create()`, like us, and verifies by the encoded parameters).
- **`/resetpassword`:** update `accounts.password_hash`.
- **Create-on-login off (D4):** find or add the switch; if it needs a code change, send it upstream
  to OpenRune as a config option rather than patching locally.
- **Ranks:** map `modlevel` (owner/admin/moderator) to Central `accounts.rights`; confirm the values
  OpenRune reads for staff mod level.
- **Members/XP:** `account_characters.members = true` for all (or a login hook like today's
  `CharacterAccountApplier`); realm `baseXpRate` 150 (already OpenRune's default).
- **Admin web panel:** compare `openrune-central-admin-web` with `web/admin_app.py`; keep whichever
  covers ours (accounts, ranks, bans, mutes, stats, items, realms) with less work.
- **Done:** register via Discord → log in on staging; reset password works; staff rank applies.

### W4 — Admin API + Admin plugin (4–5 days)
- Port `content/other/admin-api` (≈1,450 lines) to `content/beanlab/admin-api`: routes `/v1/me`,
  `players`, `player/{name}`, `world`, `teleports`, `items/search`, `accounts`, `audit`, player
  actions, teleport, broadcast, reboot, account create/rank/password/delete.
  Account routes go through Central's tables; tokens and audit move to the `beanlab` schema.
- Keep `AdminRouter` as the single place for permission rules; keep its tests.
- Rebuild the Admin plugin (≈6,800 lines Java) against RuneLite **1.13.6**; fix API breaks.
- **Done:** every route's test passes; plugin logs in and runs each action on staging.

### W5 — Grand Exchange (3–4 days)
- `GeBook` is plain Kotlin; move it and its tests unchanged.
- Rewrite persistence for Postgres in `beanlab` (port V7/V8: offers, history, revision guard,
  trade dedupe keys); keep "one request = one transaction, idempotent writes".
- `::ge` commands on OpenRune's command DSL; item names through gamevals.
- **Done:** tests pass; offers and a completed trade survive a server restart.

### W6 — Small Beanscape features (2 days)
- `::name` → check Central's display-name support (`DisplayNamePolicy`,
  `CharacterDisplayNameNotifyHandler`); use it instead of our own if it covers rename.
- `::teleports` / `::tp` menu and catalogue (re-check coordinates on 242).
- `::questsdone` only if `assume-completed` doesn't cover it.
- Rotten potato admin menu.
- **Done:** each works on staging.

### W7 — Client pipeline (2–3 days)
- `patch-microbot.py`: **find** the two targets instead of hardcoding them (the only 256-hex-char
  constant = Jagex modulus; the class holding the `127.0.0.1` allowlist string); verify a pinned
  SHA-256 per supported Microbot version.
- `Update-Client.ps1` / launcher: download the pinned 2.6.57, patch, `CLIENT_BUILD` +1.
- `jav_config`: either Central's `/jav_config.ws` + world list or our `webconfig/` with
  `param=25=242`; pick one and retire the other.
- Rebuild the Companion plugin on 1.13.6.
- Expect RuneLite's "Mismatch in overlaid cache archive hash" chat warning wherever our cache
  changes scripts RuneLite overlays; list the affected scripts.
- **Done:** a clean machine runs `Update-Client.ps1` and logs in to staging.

### W8 — Deploy (2 days)
- New `deploy/deploy-vm.sh` for the fork: CI-green `main` only, build next to the running server,
  back up Postgres, stop → install → start, self-update **before** risky steps (today's script only
  updates itself at the end, which left it stuck on a bug).
- systemd: `beanscape-staging` (game, port 43595) and `openrune-central`; Tailscale exposure for
  staging only to staff.
- **Done:** a merge to the fork's `main` reaches staging within 5 minutes.

### W9 — Data migration rehearsal (3 days, run against staging only)
- Exporter from `.data/saves/game.db` (SQLite) to Postgres:
  accounts (copy Argon2 hashes as-is), `discord_id`, ranks; characters (position, stats, XP,
  run energy); inventories, equipment, bank; varps (**our numeric IDs → OpenRune's varp names**;
  report anything unmapped); GE offers and history.
- Item and NPC IDs are stable 233 → 242; verify with a diff of `obj` names.
- Rehearse on a copy of the live DB; check 10 real characters field by field.
- **Done:** a rehearsal report with zero unexplained differences.

**Total:** about 3–4 weeks of focused work for one person; W3–W7 can be split between two.

## Risks and open questions

1. **Central has no licence file.** The game server is ISC; Central's repo has none, which by default
   means all rights reserved. Ask the OpenRune maintainers before depending on it.
2. **Create-on-login (D4)** may need an upstream change.
3. **Revision churn.** OpenRune follows live within days (240 → 241 → 242 in two months). Each bump:
   new pinned Microbot, re-run the patcher, rebuild both plugins. W7 must make that a one-command job.
4. **Name rules differ.** Some existing Beanscape names may fail Central's policy; W9 must report
   them, and staff decide renames before cutover.
5. **Varp names.** Settings saved by number today must map to OpenRune's names; unmapped ones are
   lost unless handled.
6. **VM memory** once three JVMs (live, staging, Central) and Postgres run together; see W2.

## First week

1. W1 fork + CI.
2. W2 Postgres + Central on the VM.
3. Answer D4 (create-on-login switch) and risk 1 (Central licence) — both can block W3.
4. Start W5 (GE), the most self-contained port.
