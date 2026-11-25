# Beanscape Market Hub: point of interest (draft)

Status: draft for review, nothing built. Working title; rename freely.

## Idea

One walkable hub with everything a player needs between trips: a bank, a Grand Exchange, a
ring of shops, and message boards. It is reached from the Soul Wars portal at the Grand Exchange
and replaces the blank 100x100 arena (x 9024-9123, z 3072-3171, four packed map squares). The
Exchange portal stays where it is; the hub is the destination.

## Layout (100 x 100, north is up)

```
+----------------------------------------------------------+
|  Weapons & armour row  |  Skilling row (axes, pickaxes,   |
|  (scimitars, legs, ...) |  runes, herblore, crafting)     |
|------------------------+----------------------------------|
|  Message boards        |  GRAND EXCHANGE hall + bank      |
|  (north-west plaza)    |  (centre, 3 clerks, 8 booths)    |
|------------------------+----------------------------------|
|  Food & general stores |  Arrival: portal + fountain       |
|  (south-west)          |  (south-east, exit to the GE)     |
+----------------------------------------------------------+
```

- **Arrival** (9074, 3122 today) moves to the south edge so the first thing a player sees is the
  Exchange hall. The leave portal sits beside it.
- Four quadrants of about 45 x 45 tiles, separated by 5-tile paths. Every building is a single
  room with one door, so the pathing stays trivial.

## What each part needs

| Part | Already in the server | To build |
|------|----------------------|----------|
| Shops (about 10 stalls) | 58 shop stocks in `shops.txt`; `ShopCatalog` opens a keeper on the right stock by NPC name | Spawn the keepers at fixed stalls inside the hub |
| Bank | Generic bank booth handler | Place booths and a banker |
| Grand Exchange | Full matching engine (`GeBook`), persistence, `::ge` commands | A clerk "Exchange" option; today there is no interface, only commands. Cache has `ge_clerk_1..4` NPCs |
| Message boards | Cache has the `noticeboard` loc; nothing handles it | New: a database table (author, text, time), a read dialog, a post command, owner/admin delete |
| Ground and walls | Flat grass terrain packed (`m141_48` to `m142_49`) | Floors, paths and walls: new terrain files, or runtime-spawned wall locs |

## Phases

1. **Layout and shops.** Replace the flat terrain with paths and floors, spawn walls and the shop
   keepers. Needs a small terrain generator (the current files are hand-built bytes). Needs a
   cache pack.
2. **Bank and Exchange hall.** Booths, banker, the three clerks, and an Exchange option that
   explains or opens the `::ge` flow.
3. **Message boards.** One Flyway migration (next free number is V16), a board loc script, and
   moderation.
4. **Polish.** Signs, ambient NPCs, and a respawn point for the hub.

Phases 1 and 2 reuse existing systems and are the low-risk start. Phase 3 is the only new
database work.

## Decisions needed

1. **Name and theme.** Plain "Market Hub", or tied to the purple Soul Wars portal?
2. **Board scope.** One global board, or one per theme (trading, help, news)? Anyone can read; who
   can post?
3. **Exchange.** Is a command-driven Exchange acceptable for now, or should the clerk wait for a
   real interface? A real interface is the largest single item here.
4. **Hub vs. wilderness safety.** Safe zone, no combat inside the hub?

## Risks

- The client only draws what the cache gives it, so every terrain change needs a `packCache` run
  on the VM and a server restart, with a cache backup first.
- Walls and doors have collision rules that are easy to get wrong; test each building in game.
- The Exchange clerks and shops draw from the existing NPC fallback options, which may add
  options (Talk-to, Bank) that need to be hidden on hub NPCs.
