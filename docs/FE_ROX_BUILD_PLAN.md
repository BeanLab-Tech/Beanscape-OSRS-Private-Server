# Ferox-Style Buildout — Plan (started 2026-10-29)

Goal: make BeanLab OSRS *feel like Ferox* (fx.lets.wiki). Source: 10 Ferox wiki
pages + key-feature list pasted by Chad, Oct 28 23:27 MDT (full text preserved
in `ferox_request.md` on the dev box at
`.../cache/scratch/ferox_request.md`).

Stable state before starting: see `STABLE_STATE_2026-10-29.md`. Proxmox
snapshot: `pre-ferox-buildout` (VM 123).

Working branch: `ferox-buildout` (off `claude/trusting-bardeen-pf87qp`).
Convention: one commit per feature, small; deploy from Gitea
`cfiaschetti/osrs-server` like the Oct 28 deploy (build on VM, `install/app`,
restart `osrs-server`).

## Phase 1 — Discord OAuth2.0 registration (THIS PHASE)
Ferox flow: click "Register" → Discord OAuth popup → account created → join.

- New `web/` Python service (FastAPI) on the VM — rsmod has no HTTP stack,
  Ned's bot already proved the argon2i(20,65536,1) + sqlite insert path.
  Service: `osrs-web`, port 8085, binds 0.0.0.0 (via tailnet/LAN).
  - `GET /` landing + "Register with Discord" button
  - `GET /oauth/discord/start` → discord.com authorize (`identify email`)
  - `GET /oauth/discord/callback` → code→token→user; insert
    `accounts(login_username, password_hash, discord_id)`; reply page
    "registered as X — connect with RSProx"
  - `GET /healthz`, `GET /status` (accounts/chars counts, server up)
- Migration `V10__discord_oauth.sql`: `accounts.discord_id INTEGER UNIQUE`,
  `accounts.discord_username TEXT`
- Config: `/opt/osrs/web/config.json` (client_id/secret from Discord Developer
  Portal — same application as Ned's bot token; secret fetched by Chad from
  portal once, stored only in config.json, 0640)
- Supersedes `osrs-webconfig` (python http.server, localhost:8181) later; keep
  running for client webconfig during buildout.
- Acceptance: Chad clicks Register on the web page, Discord popup, account
  appears in DB, logs in via RSProx on dev realm.

## Phase 2 — Web admin app
Same `web/` service, `/admin` (login = Discord OAuth admin role or local admin
token): realm list/switch, account & character browser (stats, killcounts,
bank top items), item grant, ban/mute, realm config, live service status
(systemd), log tail. SQLite read + careful writes; no game API needed yet.

## Phase 3 — Spawn hub
Central hub at spawn: **GE** (done — `content/other/grandexchange`),
standard shops, teleporter to major areas (wilderness, desert, ferox areas).

## Phase 4 — Game modes
Regular (fast XP), **Realism** (lower XP, +5% drops), **Ironman** (working),
separate **PvP worlds**. Implementation: per-character `mode` varp (new
`characters.xp_rate_in_hundreds` column already exists — realism = 50;
ironman/pvp = varp + world split).

## Phase 5 — Town board & daily tasks
Town board NPC at hub: daily tasks (kill/craft/gather/collect), points, XP
rewards, account upgrades (fishing catch-rate boost). Points feed other
systems (Phase 8 relics, diaries).

## Phase 6 — Vote store + Donator ranks
- Vote store NPC (home teleport, south of bank): exact Ferox item/cost table
  (fighter torso 15, fire cape 15, dragon defender 5, rune defender 2,
  bolt pouch 5, instance token 30, raid layout tablet 2/5, bonus scrolls
  (pet 15, CoX/ToA/ToB/NM/Gauntlet 5, slayer 10, clues 5), slayer skip 2,
  pet tokens 50x=25, ectophial 5, master scroll book 25, dh pouch 30, clue
  sack 75, soulbearer 15, ash sanctifier 20, crystal tools...). Ironman
  restrictions (no fire cape/giant/mossy/ecumenical key).
- Donator ranks: Regular $25 → Supreme $5000, full Ferox benefit matrix
  (chat crown, donator zone, yell CD, bank/trading/post/preset/coal caps,
  GWS killcount req, town board task count, slayer cancel, wilderness key
  cap, auto-complete clues, crystal key rolls, barrows repair discount,
  pet reclaim/Zahur/mill discounts, raid point loss reduction, bonus PKP,
  vote chance, pest control, pet/Mark of Grace/steel-bar/harvest boosts).

## Phase 7 — Pet shrine & pet perks
Shrine at spawn: roll perks with pets (low tier 30–49 rating, very low 0–29),
boost chances, lock/keep perks, shared + unique perk pools, ratings table.

## Phase 8 — Relics & weapon upgrades (tier 5)
- Relic system: build-defining relic slots/drops.
- Weapon upgrade path up to **tier 5** with unique passives
  (e.g. Twisted Bow → double-arrow chance), per Ferox "Weapon upgrades" page
  (mechanics, costs, passive list).

## Phase 9 — Achievement diary
Tiers, GP rewards, milestone cosmetics/uniques, rings & blessings, regional
capes/swords/helms, Clue Hunter set, completionist cape (+trimmed),
titles & broadcasts.

## Phase 10 — Raids & custom bosses
CoX, ToB, ToA (check current content availability first — RsMod fork state),
**Yama Doom of Mokhaiotl**, **Galvek** (wilderness).

## Phase 11 — Wilderness keys
Red–Chronozon, Blue–Meiyu, Magenta–Deathly Ranger, Green–Ulfric:
attack styles, drop tables, strategy per Ferox "Wilderness Keys" page.

## Phase 12 — Doomsayer's sanctum + sanctum bounties
Sanctum: exchange list, shop, reward shop, titles/achievements. Bounties:
easy/medium/hard/elite item lists, rules, cancel, rewards.

## Phase 13 — Breaches
Wildy breaches (monster spawns, rewards, barrows/equipment drops) +
staff-triggered breaches.

## Phase 14 — AFK zone + BeanLab wiki
Ferox main page mentions a working AFK zone; stand up our own wiki
(lets.wiki or static) mirroring the Ferox pages above with BeanLab values.

## Explicitly deferred / optional
- Orphan character id 1 cleanup, legacy `rsps`/`osrs-discordbot` unit removal
  (do during Phase 1 deploy housekeeping).
