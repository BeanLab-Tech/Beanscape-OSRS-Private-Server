# AGENTS — BeanLab OSRS Private Server

This file gives AI coding agents (Claude Code, OpenCode, Codex, etc.) the context they need to work on this repo safely and productively.

---

## What This Repo Is

This is **not** the upstream RsMod framework. It is the BeanLab OSRS private server deployment, consisting of:

1. **RsMod framework** — the upstream `Tomm0017/rsmod` submodule, built from source
2. **BeanLab additions** — our custom modules (Grand Exchange, name change, etc.)
3. **Discord bot** — standalone Python bot (`/opt/osrs/discordbot/` on VM, NOT in this repo)
4. **Client config** — RSProx targets (`~/.rsprox/proxy-targets.yaml` on Windows, NOT in this repo)

When you see files in `content/other/grandexchange/`, `content/other/commands/NameChangeCommands.kt`, and `docs/SESSION_REPORT_2026-10-28.md`, those are BeanLab additions. Everything else is upstream RsMod.

---

## Build & Run

### Prerequisites
- JDK 21 (not JRE — the RuneLite launcher needs `jdk.security.jarsigner`)
- Gradle 9+ (wrapper included)
- Python 3.10+ with `discord.py` and `argon2-cffi` (for the bot, deployed separately)

### Build the server
```bash
./gradlew build -x test
```

### Run the server
```bash
# SQLite database is created at .data/saves/game.db on first run
./gradlew :server:app:run
```

Or via systemd on the VM:
```bash
sudo systemctl restart osrs-server
sudo journalctl -u osrs-server -f
```

### Run the Discord bot (separate)
```bash
cd /opt/osrs/discordbot
./venv/bin/python bot.py
# Or: sudo systemctl restart osrs-discord
```

### Run tests
```bash
./gradlew test
```

---

## Code Conventions

- **Kotlin** for server-side code
- **Java 21** target
- **Gradle** with Kotlin DSL (`build.gradle.kts`)
- RsMod plugin pattern: single `Plugin.kt` entry point + optional `Commands.kt`
- Use `Inject` for dependency injection (RsMod's built-in DI)
- Database migrations go in `api/db/src/main/resources/db/migration/` with Flyway naming (`V{N}__{description}.sql`)
- Tests: unit tests in `src/test/`, integration tests in `src/integration/`

### Example plugin structure
```
content/other/myfeature/
  build.gradle.kts
  src/main/kotlin/org/rsmod/content/other/myfeature/
    MyFeaturePlugin.kt    # plugin entry
    MyFeatureCommands.kt  # ::commands
  src/test/kotlin/.../
    MyFeatureTest.kt
```

---

## Database

- **Engine:** SQLite (Flyway-managed migrations)
- **Path:** `.data/saves/game.db`
- **Schema version:** Check `schema_version` table
- **Accounts:** `accounts` table (login_username, password_hash as Argon2id)
- **Realms:** `realms` table (id, name, require_registration, xp_rate)

### Adding a migration
```sql
-- api/db/src/main/resources/db/migration/V8__my_feature.sql
CREATE TABLE my_feature (...);
```

---

## Authentication

- **Passwords:** Argon2id, `time_cost=20, memory_cost=65536, parallelism=1, Type.I`
- **Hash format:** `$argon2id$v=19$m=65536,t=20,p=1$<salt>$<hash>`
- **Libraries:**
  - JVM: `de.mkammerer:argon2-jvm-nolibs:2.12` + `argon2-jvm:2.12` (natives)
  - Python: `argon2-cffi` (same params)
- **Interop:** JVM and Python hashes are cross-compatible (verified with test harness)

---

## What NOT to Change

- **Do NOT** modify the upstream RsMod engine code unless fixing a bug
- **Do NOT** change the Argon2 parameters — they must stay in sync with the Discord bot
- **Do NOT** move `V7__grand_exchange.sql` — Flyway migrations are ordered
- **Do NOT** commit secrets, tokens, or credentials
- **Do NOT** push directly to `origin` (upstream rsmod) — use `gitea` remote for BeanLab pushes

---

## What NOT to Add to This Repo

- Discord bot source code (deployed separately on VM)
- RSProx client configuration (`~/.rsprox/proxy-targets.yaml` on Windows)
- Server runtime data (`.data/saves/game.db`, logs)
- Proxmox snapshots, systemd unit files, or VM provisioning scripts

---

## Current Branch Layout

- `main` — BeanLab additions (GE, name change, docs) on top of upstream RsMod
- `beanlab-additions` — historical orphan branch (same content as main)

---

## Testing Checklist for New Features

Before marking a feature complete:
1. Build succeeds: `./gradlew build -x test`
2. Unit tests pass: `./gradlew test`
3. Server boots without errors
4. Feature is accessible in-game via command or UI
5. Database schema changes have Flyway migration
6. Argon2 parameters unchanged (if touching auth)

---

## Known Issues

1. RSProx's `RuneliteLauncher.kt` hardcodes a stale bootstrap fallback commit (for rev 241+) — always set `runelite_bootstrap_commithash` in `proxy-targets.yaml` for rev 233
2. The `dev` realm has `require_registration=0` — flip to 1 to enforce Discord registration
3. node-exporter scrape shows DOWN due to ACL gap (tag:game not in monitoring grant)
