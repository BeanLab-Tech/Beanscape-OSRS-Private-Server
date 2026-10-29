# VM 123 Deploy Runbook — Audit Fixes (V8/V9, GE, ::name)

**Audience:** an AI agent with shell access to VM 123, working for the server owner (the "user").
**Goal:** deploy branch `claude/trusting-bardeen-pf87qp` to the live server safely.
**Background (read only if needed):** `docs/AUDIT_2026-10-29.md` in the same repo.

---

## Rules for the agent — follow exactly

1. Run the tasks **in order**. Do not skip, merge or reorder steps.
2. Run each command exactly as written. Do not "improve" commands.
3. After each step, compare the output with **Expected**. If it differs, **STOP**, show the user
   the command and full output, and wait for instructions.
4. Wherever a step says **ASK THE USER**, stop and wait for an answer. Never guess.
5. **Never** do any of these:
   - edit or delete files in `/opt/osrs/discordbot/` (only read them)
   - print or `cat` `/opt/osrs/config/ned_discord_token`
   - run `DELETE` or `DROP` SQL, or any SQL that is not written in this file
   - `git push` anywhere, or change ufw, SSH or Tailscale settings
   - change Argon2 parameters anywhere
6. All commands run **on VM 123 as root**. Connect with `ssh root@100.98.119.38` (tailnet only).
7. Keep a log: for every step write `STEP <n>: OK` or `STEP <n>: STOPPED - <reason>`. Give the
   user that log at the end.

### Facts about this server

| Thing | Value |
|-------|-------|
| Server checkout (upstream RsMod + BeanLab files) | `/opt/osrs/server/rsmod` |
| Database | `/opt/osrs/server/rsmod/.data/saves/game.db` |
| Backups dir | `/opt/osrs/backups` |
| Game service | `osrs-server` (runs as user `osrs`) |
| Discord bot service | `osrs-discord` |
| Bot source (read only) | `/opt/osrs/discordbot/bot.py` |
| Repo with the fixes | `https://github.com/chad-fiaschetti/Beanscape-OSRS-Private-Server`, branch `claude/trusting-bardeen-pf87qp` |

---

## Task 1 — Pre-flight checks (no changes yet)

### Step 1.1 — Tools present
```bash
which sqlite3 git java && java -version 2>&1 | head -1
```
**Expected:** three paths, then a line containing `21`.
If `sqlite3` is missing: `apt-get install -y sqlite3`, then re-run this step.

### Step 1.2 — Services are running
```bash
systemctl is-active osrs-server osrs-discord
```
**Expected:** `active` twice.

### Step 1.3 — Password hash type (CRITICAL)
The server verifies passwords as **Argon2i**. Until now passwords were not checked at all, so a
wrong hash type was invisible. After this deploy, an account with the wrong hash type cannot log in.
```bash
sqlite3 /opt/osrs/server/rsmod/.data/saves/game.db \
  "SELECT substr(password_hash, 1, 10) AS prefix, COUNT(*) FROM accounts GROUP BY prefix;"
```
**Expected:** every `prefix` is `$argon2i$v` (output looks like `$argon2i$v|5`, meaning prefix | count).
**If any row starts with `$argon2id$` or anything else: STOP.** Tell the user:
"Some accounts have non-Argon2i hashes; they would be locked out by V9. The Discord bot must be
fixed to use Type.I first." Do not continue.

### Step 1.4 — Bot hashing settings (read only)
```bash
grep -nE "argon2|Type\.|time_cost|memory_cost|parallelism|PasswordHasher|hash_secret" /opt/osrs/discordbot/bot.py
```
**Expected:** the bot uses `Type.I` with `time_cost=20`, `memory_cost=65536`, `parallelism=1`.
**If it uses `Type.ID`, or no type at all** (argon2-cffi defaults to Argon2**id**): **STOP** and
tell the user exactly what you found. Do not edit the bot.

### Step 1.5 — How the service starts
```bash
systemctl cat osrs-server | grep -E "ExecStart|WorkingDirectory|User"
```
Record the `ExecStart` line. You need it in Step 3.3.

### Step 1.6 — Current migration state
```bash
sqlite3 /opt/osrs/server/rsmod/.data/saves/game.db \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```
**Expected:** versions 1 to 7, all with `success` = `1`. No 8 or 9 yet.
If 8 or 9 already exist, or any `success` is `0`: **STOP**.

### Step 1.7 — Accounts with staff rights
```bash
sqlite3 -header -column /opt/osrs/server/rsmod/.data/saves/game.db \
  "SELECT id, login_username, display_name, modlevel FROM accounts WHERE modlevel IS NOT NULL AND modlevel != 'player';"
```
Show the full output to the user.
**ASK THE USER:** "Which of these accounts should keep staff rights, and at what level
(`owner`, `admin` or `moderator`)? All others will become normal players."
Write down the answer exactly. Do not continue without it.

---

## Task 2 — Get the new files

### Step 2.1 — Download the branch
```bash
rm -rf /opt/osrs/overlay-new
git clone --depth 1 --branch claude/trusting-bardeen-pf87qp \
  https://github.com/chad-fiaschetti/Beanscape-OSRS-Private-Server.git /opt/osrs/overlay-new
```
**Expected:** `Cloning into '/opt/osrs/overlay-new'...` and no error.
**If it asks for a username/password or says "not found":** the repo is private. **STOP** and
**ASK THE USER** to copy the repo to `/opt/osrs/overlay-new` themselves (for example with `scp -r`
from a machine that has it). Continue once `/opt/osrs/overlay-new/AGENTS.md` exists.

### Step 2.2 — Check the files are there
```bash
cd /opt/osrs/overlay-new && ls api/db/src/main/resources/db/migration/ && \
  ls content/other/grandexchange/src/main/kotlin/org/rsmod/content/other/grandexchange/ && \
  ls content/other/commands/src/main/kotlin/org/rsmod/content/other/commands/
```
**Expected:**
- `V7__grand_exchange.sql  V8__grand_exchange_revisions.sql  V9__beanlab_realm_hardening.sql`
- `GeBook.kt  GeCommands.kt`
- `NameChangeCommands.kt`

### Step 2.3 — V7 must be byte-identical to the deployed one
Flyway refuses to start if an applied migration changes.
```bash
cmp /opt/osrs/overlay-new/api/db/src/main/resources/db/migration/V7__grand_exchange.sql \
    /opt/osrs/server/rsmod/api/db/src/main/resources/db/migration/V7__grand_exchange.sql && echo SAME
```
**Expected:** `SAME`. Otherwise **STOP**.

---

## Task 3 — Deploy (the server is down during this task, about 5 minutes)

**ASK THE USER:** "Ready to take the game server down for about 5 minutes?" Continue only on yes.

### Step 3.1 — Stop the server and back up the database
```bash
systemctl stop osrs-server
systemctl is-active osrs-server
mkdir -p /opt/osrs/backups
BACKUP=/opt/osrs/backups/game-$(date +%F-%H%M)-pre-v9.db
sqlite3 /opt/osrs/server/rsmod/.data/saves/game.db ".backup '$BACKUP'"
sqlite3 "$BACKUP" "PRAGMA integrity_check;"
echo "$BACKUP"
```
**Expected:** `inactive`, then `ok`, then the backup path. **Write the backup path down**; you
need it if something goes wrong (see "Rollback").

### Step 3.2 — Copy the new files into the server checkout
```bash
cd /opt/osrs/overlay-new
cp api/db/src/main/resources/db/migration/V8__grand_exchange_revisions.sql \
   api/db/src/main/resources/db/migration/V9__beanlab_realm_hardening.sql \
   /opt/osrs/server/rsmod/api/db/src/main/resources/db/migration/
rm -rf /opt/osrs/server/rsmod/content/other/grandexchange
cp -r content/other/grandexchange /opt/osrs/server/rsmod/content/other/
cp content/other/commands/src/main/kotlin/org/rsmod/content/other/commands/NameChangeCommands.kt \
   /opt/osrs/server/rsmod/content/other/commands/src/main/kotlin/org/rsmod/content/other/commands/
chown -R osrs:osrs /opt/osrs/server/rsmod/api/db/src/main/resources/db/migration \
   /opt/osrs/server/rsmod/content/other/grandexchange \
   /opt/osrs/server/rsmod/content/other/commands
```
**Expected:** no output.

### Step 3.3 — Build
```bash
cd /opt/osrs/server/rsmod && sudo -u osrs ./gradlew build -x test 2>&1 | tail -20
```
**Expected:** `BUILD SUCCESSFUL`. Takes 1–3 minutes.
**If it fails:** **STOP**, show the user the last 60 lines of output, and do the Rollback.

Then stop the Gradle daemon (it keeps outbound connections open):
```bash
cd /opt/osrs/server/rsmod && sudo -u osrs ./gradlew --stop; pkill -u osrs -f kotlin-daemon || true
```
If the `ExecStart` from Step 1.5 is not `gradlew ... run`, show the user the line and **ASK THE
USER** whether an extra install step (for example `installDist`) is needed. Do not guess.

### Step 3.4 — Remove staff rights the user did not approve
Use the answer from Step 1.7. The server must still be stopped (online players write their rights
back when saved).

First make everyone a normal player:
```bash
sqlite3 /opt/osrs/server/rsmod/.data/saves/game.db \
  "UPDATE accounts SET modlevel = NULL WHERE modlevel IS NOT NULL;"
```
Then, **for each account the user approved**, run this once, replacing `LEVEL` and `USERNAME`:
```bash
sqlite3 /opt/osrs/server/rsmod/.data/saves/game.db \
  "UPDATE accounts SET modlevel = 'LEVEL' WHERE login_username = 'USERNAME';"
```
`LEVEL` must be exactly one of `owner`, `admin`, `moderator`. Check the result:
```bash
sqlite3 -header -column /opt/osrs/server/rsmod/.data/saves/game.db \
  "SELECT login_username, modlevel FROM accounts WHERE modlevel IS NOT NULL;"
```
**Expected:** exactly the accounts and levels the user approved. If it doesn't match, fix it with
the same two commands before continuing.

### Step 3.5 — Start the server (this applies V8 and V9)
```bash
systemctl start osrs-server
sleep 60
systemctl is-active osrs-server
journalctl -u osrs-server --since "-3 min" --no-pager | grep -iE "migrat|grand exchange|error|exception" | tail -20
```
**Expected:**
- `active`
- log lines about migrating to version 8 and 9, and `Grand Exchange restored N offer(s).`
- no `Failed to restore Grand Exchange offers`, no stack traces

If the service is not active or there are errors: **STOP**, show the user, and do the Rollback.

### Step 3.6 — Check the database
```bash
DB=/opt/osrs/server/rsmod/.data/saves/game.db
sqlite3 $DB "SELECT version, success FROM flyway_schema_history WHERE version IN ('8','9');"
sqlite3 -header -column $DB "SELECT name, require_registration, ignore_passwords, dev_mode FROM realms;"
```
**Expected:** `8|1` and `9|1`; the `dev` row shows `require_registration=1`,
`ignore_passwords=0`, `dev_mode=0`.

### Step 3.7 — The bot still works
```bash
systemctl restart osrs-discord
sleep 15
systemctl is-active osrs-discord
journalctl -u osrs-discord --since "-1 min" --no-pager | tail -15
```
**Expected:** `active`, and the self-test passes or the bot logs in (no tracebacks).

---

## Task 4 — In-game tests (the user does these; the agent records results)

Give the user this checklist and **ASK THE USER** to report pass/fail for each:

1. Log in with a Discord-registered account and its **correct** password → works.
2. Same account, **wrong** password → rejected.
3. A username that was **never registered** → rejected (no new account is created).
4. `!register <newname>` in Discord, then log in with the DM'd password → works.
5. As a normal (non-staff) player: `::master` → nothing happens / no permission.
6. As a normal player: `::name Testname1` → "Your display name is now Testname1."
   A second player can see the new name. `::name` with a name another player already has → "already taken".
7. Two accounts, GE:
   - A: `::ge sell 4151 1 1000` (A needs a whip, item 4151; an admin can `::invadd` one)
   - B: `::ge buy 4151 1 1500` → "filled instantly"
   - B: `::ge collect` → gets the whip and a 500 gp refund
   - A: `::ge collect` → gets 980 gp (1000 minus 2% tax)
8. Restart test: A `::ge sell 4151 2 1000`, B `::ge buy 4151 1 1000`. Then on the VM run
   `systemctl restart osrs-server`. After it is back: A `::ge offers` shows `1/2` filled, still active.

If any test fails: tell the user which one and the exact message seen. Do **not** try to fix code.

---

## Task 5 — Finish

### Step 5.1 — Clean up
```bash
rm -rf /opt/osrs/overlay-new
```

### Step 5.2 — Snapshot
**ASK THE USER** to take the Proxmox snapshot `post-phase-1-complete` (the agent cannot do this
from the VM).

### Step 5.3 — Report
Give the user the step log, the backup path from Step 3.1, and the Task 4 results.

---

## Task 6 (optional — only if the user says yes) — Nightly database backup

**ASK THE USER:** "Set up a nightly database backup at 04:17, keeping 14 days?"

```bash
cat > /etc/systemd/system/osrs-backup.service <<'EOF'
[Unit]
Description=Nightly game.db backup

[Service]
Type=oneshot
User=osrs
ExecStart=/bin/sh -c 'sqlite3 /opt/osrs/server/rsmod/.data/saves/game.db ".backup /opt/osrs/backups/game-$(date +%%F).db" && find /opt/osrs/backups -name "game-????-??-??.db" -mtime +14 -delete'
EOF
cat > /etc/systemd/system/osrs-backup.timer <<'EOF'
[Unit]
Description=Nightly game.db backup

[Timer]
OnCalendar=*-*-* 04:17:00
Persistent=true

[Install]
WantedBy=timers.target
EOF
chown osrs:osrs /opt/osrs/backups
systemctl daemon-reload
systemctl enable --now osrs-backup.timer
systemctl start osrs-backup.service
ls -l /opt/osrs/backups/ | tail -3
```
**Expected:** a file named `game-<today>.db` in the listing.

---

## Rollback (only if a step above says so)

```bash
systemctl stop osrs-server
cp /opt/osrs/server/rsmod/.data/saves/game.db /opt/osrs/backups/game-failed-$(date +%F-%H%M).db
cp "<BACKUP PATH FROM STEP 3.1>" /opt/osrs/server/rsmod/.data/saves/game.db
chown osrs:osrs /opt/osrs/server/rsmod/.data/saves/game.db
rm -f /opt/osrs/server/rsmod/api/db/src/main/resources/db/migration/V8__grand_exchange_revisions.sql \
      /opt/osrs/server/rsmod/api/db/src/main/resources/db/migration/V9__beanlab_realm_hardening.sql
cd /opt/osrs/server/rsmod && git status --short
```
Show the user the `git status` output and **ASK THE USER** how to restore the old GE and
`NameChangeCommands.kt` files (for example `git checkout -- <file>` if they are tracked on the VM,
or from the Gitea mirror). Then rebuild (Step 3.3) and `systemctl start osrs-server`.
