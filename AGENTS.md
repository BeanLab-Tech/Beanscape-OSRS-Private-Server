# AGENTS — BeanLab OSRS Private Server

Context for AI coding agents (Claude Code, Codex, OpenCode, …). This is the single source of
truth for agents; `CLAUDE.md` imports it. Latest audit: `docs/AUDIT_2026-10-29.md`.

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
api/account/.../CharacterAccountApplier.kt   forces player.members = true at login
api/config/.../BaseObjs.kt           adds objs.rotten_potato
content/other/admin-potato/          rotten potato menu, ::teleports/::tp (TeleportCatalog/Menu),
                                     ::questsdone (QuestCommands + generated QuestProgressVars)
content/other/admin-api/             loopback HTTP API for the in-client admin console (AdminRouter
                                     holds every permission rule; GameBackend runs on the game
                                     thread via GameAccess), ::admintoken (owner), tests
content/other/grandexchange/         GE engine (GeBook), ::ge commands (GeCommands), tests
content/other/commands/.../NameChangeCommands.kt   ::name
web/                                 Discord registration + admin panel (FastAPI)
discordbot/bot.py                    Discord bot (token read from /opt/osrs/config on the VM;
                                     never posts passwords in-channel)
client/microbot/                     patch-microbot.py (Microbot 2.0.20 -> BeanLab: RSA modulus +
                                     host allowlist) and Play-Microbot.bat
scripts/build-overlay.sh             builds/tests the overlay on pinned upstream
docs/                                session report, audit
```

On VM 123 the overlay is copied into an upstream checkout at `/opt/osrs/server/rsmod`. Build there
with `./gradlew :server:app:installDist` **with `osrs-server` stopped**: replacing jars under the
running JVM breaks its shutdown save hook (NoClassDefFoundError) and loses online players' progress.

Not in this repo: secrets (`/opt/osrs/web/config.json`, `env`, `admin_config.json`, the Discord
token file), RSProx client config (`~/.rsprox/` on Windows), runtime data (`.data/`).

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
