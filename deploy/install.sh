#!/usr/bin/env bash
# One-time setup of auto-deploy on the VM (run as root from a clone of this repo):
#   sudo bash deploy/install.sh
# Afterwards beanscape-deploy.timer checks GitHub main every 5 minutes; deploy-vm.sh keeps itself
# and these units up to date on every deploy.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p /opt/osrs/deploy /etc/systemd/system/osrs-server.service.d
# Deploys build as osrs, so the RsMod checkout must be osrs-owned. One-time, link-safe: only regular
# single-link files and dirs on this filesystem, never through symlinks (-h) or hard links (-links 1).
find /opt/osrs/server/rsmod -xdev \( -type f -links 1 -o -type d \) ! -user osrs \
    -exec chown -h osrs:osrs {} +
install -m 755 "$here/deploy-vm.sh" /opt/osrs/deploy/deploy-vm.sh
install -m 644 "$here/systemd/beanscape-deploy.service" "$here/systemd/beanscape-deploy.timer" /etc/systemd/system/
install -m 644 "$here"/systemd/osrs-server.service.d/*.conf /etc/systemd/system/osrs-server.service.d/
systemctl daemon-reload
systemctl enable --now beanscape-deploy.timer
echo "auto-deploy installed. Deploy now: sudo systemctl start beanscape-deploy && journalctl -u beanscape-deploy -f"
