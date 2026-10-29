#!/usr/bin/env bash
# Deploy the BeanLab OSRS web service on the VM (run as root).
# Usage: bash install.sh   (from the repo's web/ directory)
set -euo pipefail
cd "$(dirname "$0")"

DEST=/opt/osrs/web
mkdir -p "$DEST"

# app code
cp app.py requirements.txt "$DEST/"
chmod 644 "$DEST/app.py" "$DEST/requirements.txt"

# config (keep existing, create from example otherwise)
if [ ! -f "$DEST/config.json" ]; then
  cp config.example.json "$DEST/config.json"
  chmod 640 "$DEST/config.json"
  echo "!! created $DEST/config.json — fill in Discord client_id/secret before starting"
fi

# env file (ports/host, overridable)
[ -f "$DEST/env" ] || printf 'OSRS_WEB_HOST=0.0.0.0\nOSRS_WEB_PORT=8085\n' > "$DEST/env"
chmod 600 "$DEST/env"

# venv (rsmod user owns the game dir; web user = root for simplicity in Phase 1)
python3 -m venv "$DEST/venv"
"$DEST/venv/bin/pip" install --quiet --upgrade pip
"$DEST/venv/bin/pip" install --quiet -r "$DEST/requirements.txt"

# systemd unit (runs as root in Phase 1; the db is root-owned and busy-timeout handles locks)
sed 's/User=rsmod/User=root/' osrs-web.service > /etc/systemd/system/osrs-web.service
systemctl daemon-reload
systemctl enable osrs-web

echo "installed. To go live:"
echo "  1) edit $DEST/config.json (discord client id/secret)"
echo "  2) systemctl start osrs-web && journalctl -u osrs-web -f"
