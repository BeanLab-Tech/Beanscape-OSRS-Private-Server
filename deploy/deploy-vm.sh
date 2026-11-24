#!/usr/bin/env bash
# Beanscape auto-deploy (VM 123). Run by beanscape-deploy.timer every 5 minutes, as root.
#
# When `main` on GitHub has a new commit whose CI passed, this:
#   1. builds the new code next to the running server (a failed build never stops the game),
#   2. backs up game.db,
#   3. stops the game server (players are saved), installs the build, and restarts every service,
#   4. publishes the client build number so out-of-date clients are prompted to update.
# Commits that only touch docs are recorded without a restart.
#
# Manual use:  sudo /opt/osrs/deploy/deploy-vm.sh            (deploy if there is something new)
#              sudo /opt/osrs/deploy/deploy-vm.sh --force    (redeploy the current main)
# Logs:        journalctl -u beanscape-deploy -n 100
set -euo pipefail

REPO_URL="https://github.com/BeanLab-Tech/Beanscape-OSRS-Private-Server.git"
API="https://api.github.com/repos/BeanLab-Tech/Beanscape-OSRS-Private-Server"
CI_CHECK="overlay"                       # job name in .github/workflows/ci.yml
BASE=/opt/osrs/deploy
SRC=$BASE/repo
RSMOD=/opt/osrs/server/rsmod
WEB=/opt/osrs/web
BOT=/opt/osrs/discordbot
WEBCONFIG=/opt/osrs/config/webconfig
BACKUPS=/opt/osrs/backups
SERVICES_AFTER_GAME="osrs-webconfig osrs-web osrs-admin osrs-discord"
FORCE=${1:-}

log() { echo "[deploy] $*"; }
# Anything that touches code from the repo or osrs-owned paths runs as the game's service user, so
# a merged commit can do no more than the running game server already can, and root never writes
# through a path that osrs could have swapped for a symlink.
as_osrs() { runuser -u osrs -- env HOME=/opt/osrs GRADLE_USER_HOME=/opt/osrs/.gradle "$@"; }
mkdir -p "$BASE"
exec 9>"$BASE/.lock"
flock -n 9 || { log "another deploy is running"; exit 0; }

[ -d "$SRC/.git" ] || git clone -q -b main "$REPO_URL" "$SRC"
git -C "$SRC" fetch -q origin main
target=$(git -C "$SRC" rev-parse origin/main)
current=$(cat "$BASE/deployed-sha" 2>/dev/null || echo none)
failed=$(cat "$BASE/failed-sha" 2>/dev/null || echo none)

if [ "$FORCE" != "--force" ]; then
    [ "$target" = "$current" ] && exit 0
    if [ "$target" = "$failed" ]; then
        exit 0  # this commit already failed to build; wait for the next push
    fi
fi

# Only deploy commits whose CI passed.
ci=$(curl -fsS -H "Accept: application/vnd.github+json" "$API/commits/$target/check-runs" | python3 -c '
import json, sys
runs = [r for r in json.load(sys.stdin)["check_runs"] if r["name"] == sys.argv[1]]
if not runs: print("pending")
elif any(r["status"] != "completed" for r in runs): print("pending")
elif all(r["conclusion"] == "success" for r in runs): print("success")
else: print("failure")' "$CI_CHECK")
if [ "$ci" != "success" ]; then
    log "main ${target:0:7}: CI $ci, not deploying"
    exit 0
fi

git -C "$SRC" checkout -q --force --detach "$target"
if [ "$current" = none ] || [ "$FORCE" = "--force" ] || ! git -C "$SRC" cat-file -e "$current" 2>/dev/null; then
    changed=$(git -C "$SRC" ls-files)
else
    changed=$(git -C "$SRC" diff --name-only "$current" "$target")
fi
if ! echo "$changed" | grep -qE '^(api|content|web|discordbot|webconfig|client|deploy)/'; then
    log "main ${target:0:7}: docs-only change, recording without restart"
    echo "$target" > "$BASE/deployed-sha"
    exit 0
fi
log "deploying ${current:0:7} -> ${target:0:7}"

# 1. Build next to the running server. Sources change, the installed jars don't, so the running
#    game is unaffected; if compilation fails we stop here and the game keeps running.
# $RSMOD must already be owned by osrs (install.sh does that once). No recursive chown as root here:
# osrs could plant links in its own tree and have root re-own files outside it.
as_osrs rsync -a "$SRC/api" "$SRC/content" "$RSMOD/"
if ! (cd "$RSMOD" && as_osrs ./gradlew compileKotlin -q > "$BASE/build.log" 2>&1); then
    grep -E '^e:|What went wrong' -A3 "$BASE/build.log" | head -20 || true
    log "BUILD FAILED for ${target:0:7} (full log: $BASE/build.log); game left running on ${current:0:7}"
    echo "$target" > "$BASE/failed-sha"
    exit 1
fi

# 2. Back up the save database (keep the 20 newest pre-deploy backups).
ts=$(date +%Y-%m-%d-%H%M%S)
sqlite3 "$RSMOD/.data/saves/game.db" ".backup $BACKUPS/game-$ts-pre-deploy.db"
ls -1t "$BACKUPS"/game-*-pre-deploy.db 2>/dev/null | tail -n +21 | xargs -r rm -f

# 3. Web panel, Discord bot, client webconfig, systemd drop-ins (secrets/config never in repo).
rsync -a --exclude 'config.json' --exclude 'env' --exclude 'admin_config.json' \
    --exclude 'config.example.json' --exclude '__pycache__' --exclude 'venv' \
    --exclude '*.service' --exclude 'install.sh' --exclude 'README.md' "$SRC/web/" "$WEB/"
if echo "$changed" | grep -q '^web/requirements.txt'; then
    "$WEB/venv/bin/pip" install -q -r "$WEB/requirements.txt"
fi
as_osrs install -m 644 "$SRC/discordbot/bot.py" "$BOT/bot.py"
as_osrs rsync -a "$SRC/webconfig/www/" "$WEBCONFIG/"
install -m 755 "$SRC/webconfig/serve.py" /opt/osrs/config/webconfig-serve.py
mkdir -p /etc/systemd/system/osrs-webconfig.service.d /etc/systemd/system/osrs-server.service.d
install -m 644 "$SRC/webconfig/systemd/osrs-webconfig-no-cache.conf" \
    /etc/systemd/system/osrs-webconfig.service.d/no-cache.conf
install -m 644 "$SRC"/deploy/systemd/osrs-server.service.d/*.conf /etc/systemd/system/osrs-server.service.d/
install -m 644 "$SRC/deploy/systemd/beanscape-deploy.service" "$SRC/deploy/systemd/beanscape-deploy.timer" \
    /etc/systemd/system/
systemctl daemon-reload

# 4. Client build: clients ask /runelite/client/<their build>.txt for the "latest version". The
#    current build answers 2.0.20 (up to date); older builds answer 2.0.21, so Microbot shows
#    "(NEW CLIENT AVAILABLE)". Clients from before build numbers use /runelite/microbot-version.txt.
build=$(tr -dc '0-9' < "$SRC/client/microbot/CLIENT_BUILD")
[ -n "$build" ] || { log "client/microbot/CLIENT_BUILD is empty"; exit 1; }
as_osrs sh -c '
    set -e
    dir="$1/runelite/client"; build="$2"
    mkdir -p "$dir"
    n=1
    while [ "$n" -le "$build" ]; do
        if [ "$n" = "$build" ]; then echo 2.0.20; else echo 2.0.21; fi > "$dir/$n.txt"
        n=$((n + 1))
    done
    echo 2.0.21 > "$1/runelite/microbot-version.txt"
    echo "$build" > "$dir/CURRENT"
' sh "$WEBCONFIG" "$build"

# 5. Full restart: stop the game (saves online players), install the new build, start everything.
systemctl stop osrs-server
if ! (cd "$RSMOD" && as_osrs ./gradlew :server:app:installDist --offline -q > "$BASE/install.log" 2>&1); then
    log "INSTALL FAILED for ${target:0:7} (log: $BASE/install.log); starting whatever is installed"
    echo "$target" > "$BASE/failed-sha"
    systemctl start osrs-server
    exit 1
fi
systemctl start osrs-server
systemctl restart $SERVICES_AFTER_GAME

echo "$target" > "$BASE/deployed-sha"
rm -f "$BASE/failed-sha"
install -m 755 "$SRC/deploy/deploy-vm.sh" "$BASE/deploy-vm.sh"   # self-update for the next run
log "deployed ${target:0:7} (client build $build)"
