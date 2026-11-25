#!/usr/bin/env bash
# One-time setup of auto-deploy on the VM (run as root from a clone of this repo):
#   sudo bash deploy/install.sh
# Afterwards beanscape-deploy.timer checks GitHub main every 5 minutes; deploy-vm.sh keeps itself
# and these units up to date on every deploy.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p /opt/osrs/deploy /etc/systemd/system/osrs-server.service.d
# Deploys build as osrs, so the RsMod checkout must be osrs-owned (one-time). Never chown by path:
# the walk is descriptor-relative (os.fwalk, no symlink following), and each entry is opened with
# O_NOFOLLOW and re-owned with fchown on that open file after fstat checks it is a directory or a
# single-link regular file on this filesystem, so a swapped path or planted link can't redirect it.
# The osrs services are also stopped for the walk so nothing is mid-write.
OSRS_UNITS="osrs-server osrs-webconfig osrs-web osrs-admin osrs-discord"
if [ -n "$(find /opt/osrs/server/rsmod -xdev ! -user osrs -print -quit)" ]; then
    systemctl stop beanscape-deploy.timer beanscape-deploy.service $OSRS_UNITS 2>/dev/null || true
    pkill -KILL -u osrs || true
    python3 -I - /opt/osrs/server/rsmod <<'PY'
import os, pwd, stat, sys
pw = pwd.getpwnam("osrs")
uid, gid = pw.pw_uid, pw.pw_gid
root = sys.argv[1]
dev = os.lstat(root).st_dev
FLAGS = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | getattr(os, "O_NOCTTY", 0)

def reown(name, dir_fd):
    try:
        fd = os.open(name, FLAGS, dir_fd=dir_fd)
    except OSError:
        return  # symlink (ELOOP), socket, vanished entry...: leave it alone
    try:
        st = os.fstat(fd)
        if st.st_dev != dev or st.st_uid == uid:
            return
        if stat.S_ISDIR(st.st_mode) or (stat.S_ISREG(st.st_mode) and st.st_nlink == 1):
            os.fchown(fd, uid, gid)
    finally:
        os.close(fd)

for _, dirnames, filenames, dfd in os.fwalk(root, follow_symlinks=False):
    for name in filenames + dirnames:
        reown(name, dfd)
    dirnames[:] = [d for d in dirnames
                   if os.stat(d, dir_fd=dfd, follow_symlinks=False).st_dev == dev]
reown(root, None)
PY
    systemctl start $OSRS_UNITS
fi
install -m 755 "$here/deploy-vm.sh" /opt/osrs/deploy/deploy-vm.sh
install -m 644 "$here/systemd/beanscape-deploy.service" "$here/systemd/beanscape-deploy.timer" /etc/systemd/system/
install -m 644 "$here"/systemd/osrs-server.service.d/*.conf /etc/systemd/system/osrs-server.service.d/
systemctl daemon-reload
systemctl enable --now beanscape-deploy.timer
echo "auto-deploy installed. Deploy now: sudo systemctl start beanscape-deploy && journalctl -u beanscape-deploy -f"
