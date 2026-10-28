# CLAUDE.md — BeanLab OSRS Private Server

Claude Code instructions for the BeanLab OSRS private server repo.

---

## Project Identity

This is the **BeanLab OSRS Private Server** — a RsMod (rev 233) deployment with custom Grand Exchange, Discord registration, and RuneLite client integration. It runs on VM 123 (`192.168.1.134`, tailnet `100.98.119.38`) and is locked behind Tailscale.

**This repo is NOT upstream RsMod.** It contains:
- Upstream RsMod framework (submodule-style, all source files present)
- BeanLab additions: Grand Exchange module, name-change commands, session docs
- Discord bot lives on the VM at `/opt/osrs/discordbot/` — NOT in this repo

---

## Build & Run

### Prerequisites
- **JDK 21** (not JRE — `jdk.security.jarsigner.JarSigner` is needed by the RuneLite launcher)
- Gradle wrapper (`./gradlew`) — no separate Gradle install needed
- Python 3.10+ with `discord.py` and `argon2-cffi` for the bot (deployed separately)

### Build server
```bash
./gradlew build -x test
```

### Run server
```bash
# First run creates .data/saves/game.db automatically
./gradlew :server:app:run

# Or on the VM:
sudo systemctl restart osrs-server
sudo journalctl -u osrs-server -f
```

### Run tests
```bash
./gradlew test
```

### Discord bot (separate deploy)
```bash
cd /opt/osrs/discordbot
./venv/bin/python bot.py
# or: sudo systemctl restart osrs-discord
```

---

## Code Conventions

- **Kotlin** (server), **SQL** (migrations)
- **Java 21** target, Gradle with Kotlin DSL
- RsMod plugin pattern: `Plugin.kt` entry + optional `Commands.kt`
- Inject dependencies with `@Inject` (RsMod's built-in DI)
- Database migrations: `api/db/src/main/resources/db/migration/V{N}__{desc}.sql`
- Tests: `src/test/` for unit, `src/integration/` for integration

### Plugin structure
```
content/other/<feature>/
  build.gradle.kts
  src/main/kotlin/.../<Feature>Plugin.kt
  src/main/kotlin/.../<Feature>Commands.kt   # optional
  src/test/kotlin/.../<Feature>Test.kt        # optional
```

Register new modules in `settings.gradle.kts` and add to `data/server.toml` plugin list.

---

## Database

- **SQLite** via Flyway
- **Path:** `.data/saves/game.db`
- **Schema version:** `SELECT * FROM schema_version;`
- **Accounts table:** `accounts` (login_username, password_hash as Argon2id)
- **Realms table:** `realms` (name, require_registration, xp_rate)

### Migration template
```sql
-- V{N}__description.sql
CREATE TABLE my_table (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ...
);
```

---

## Authentication

**Argon2id parameters are fixed and must stay in sync across all systems:**
```
time_cost=20, memory_cost=65536, parallelism=1, Type.I
```

- JVM: `de.mkammerer:argon2-jvm-nolibs:2.12` + `argon2-jvm:2.12`
- Python: `argon2-cffi` (same params)
- Cross-verified: JVM hashes verify in Python and vice versa

**Do NOT change these parameters** without updating both the server and the Discord bot.

---

## What NOT to Touch

| Path / Area | Reason |
|-------------|--------|
| Upstream engine code (`engine/`, `api/cache/`, etc.) | Only fix bugs, don't refactor |
| Argon2 parameters | Must stay in sync with Discord bot |
| Flyway migration ordering | V7 is GE; don't renumber |
| `.data/saves/game.db` | Runtime data, never commit |
| Discord bot source | Deployed separately on VM |
| RSProx config (`~/.rsprox/`) | Windows-side, per-user |

---

## What NOT to Commit

- Secrets, tokens, passwords
- `.data/` directory (runtime DB + logs)
- IDE files (`.idea/` is in `.gitignore`)
- Build artifacts (`build/`, `.gradle/`)

---

## Branches

- `main` — BeanLab additions (GE, name change, docs) on upstream RsMod
- Do NOT push to `origin` (upstream rsmod/rsmod)

---

## Testing Checklist

Before submitting:
1. `./gradlew build -x test` succeeds
2. `./gradlew test` passes
3. Server boots without errors
4. Feature works in-game
5. DB migrations have Flyway V-numbered SQL files
6. No hardcoded credentials
7. Argon2 params unchanged (if touching auth)

---

## Known Issues

1. **Stale bootstrap fallback** — `RuneliteLauncher.kt` line 85 hardcodes commit `1e634e64` (rev 241+, 404s). Always set `runelite_bootstrap_commithash` in `proxy-targets.yaml` for rev 233: `4bed6512f101aadb1ba20e56ca124dff2fba387d`
2. **require_registration=0** — The `dev` realm allows in-game account creation. Flip to 1 to enforce Discord-only registration.
3. **ACL monitoring gap** — node-exporter on tag:game is not scraped by Prometheus (Heimdall's call needed).

---

## VM Access

```bash
# SSH (tailnet only)
ssh root@100.98.119.38

# Server logs
journalctl -u osrs-server -f

# Bot logs
journalctl -u osrs-discord -f

# Database
sqlite3 /opt/osrs/server/rsmod/.data/saves/game.db
```
