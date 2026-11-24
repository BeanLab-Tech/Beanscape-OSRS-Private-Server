# Deploying the admin console API (VM 123)

Run these on the VM as a user that can `sudo`. The agent's machine cannot SSH to the VM (the tailnet
policy refuses root), so this is a hand-run list, in the pattern of `docs/AUDIT_2026-10-29.md` §4.

**What ships:** `content/other/admin-api` and migration `V12__admin_api.sql`. Deploying this branch
also ships everything merged to `main` since the VM was last updated (prayer, firemaking, cooking,
mining, smithing, doors, eating, the Discord bot and admin panel changes). Check `git log` on the VM
against `origin/main` first so nothing lands by surprise.

## 1. Back up and stop

```bash
cd /opt/osrs/server/rsmod
sudo cp -p .data/saves/game.db /opt/osrs/backups/game-$(date +%F-%H%M).db
sudo systemctl stop osrs-server        # always stop before installDist (see AGENTS.md)
```

## 2. Update the overlay and build

```bash
rm -rf ~/beanscape
git clone -b admin-console https://github.com/BeanLab-Tech/Beanscape-OSRS-Private-Server ~/beanscape
sudo rsync -a ~/beanscape/api ~/beanscape/content /opt/osrs/server/rsmod/
sudo ./gradlew :server:app:installDist
sudo systemctl start osrs-server
journalctl -u osrs-server -f
```

Expect in the log: Flyway applying `V12`, then `[admin-api] listening on 127.0.0.1:8087` and
`Bound to ports: 43594`. If the port is taken, set `ADMIN_API_PORT`; set `ADMIN_API_ENABLED=false`
in the unit's environment to keep the listener off.

## 3. Expose it on the tailnet

```bash
sudo tailscale serve --bg --set-path /adminapi http://127.0.0.1:8087
tailscale serve status
```

## 4. Issue a token and check it

In game, as an owner: `::admintoken`. Copy the token from chat (it is shown once). Then:

```bash
curl -s -H "Authorization: Bearer <token>" https://runescape-server.tail5475a4.ts.net/adminapi/v1/me
# {"name":"...","modlevel":"owner"}
```

In the Beanscape launcher, open **Admin tools**, paste the token and press **Install**, then restart
the client. `::admintoken revoke` revokes every token on your account.

## 5. In-game checks (not yet tested on a live server)

Teleport to a player, summon, heal, give an item, set a level, kick, broadcast, create an account and
confirm it logs in. Each should appear in the console's **Log** page.

## Rollback

```bash
sudo tailscale serve --bg --set-path /adminapi off
sudo systemctl stop osrs-server
# remove content/other/admin-api from /opt/osrs/server/rsmod, then:
sudo ./gradlew :server:app:installDist && sudo systemctl start osrs-server
```

`V12` only adds two tables and is safe to leave in place. Restore the `game.db` backup only if
something else went wrong.
