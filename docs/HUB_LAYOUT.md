# Market Hub layout file

The Market Hub (the area behind the clan hall portal on the Grand Exchange plaza) is described by
one JSON file. The server, the terrain generator and the planned editors (web, in-game commands,
client plugin) all read and write this same format.

- Bundled copy: `content/other/beanlab-soulwars/src/main/resources/org/rsmod/content/other/beanlab/soulwars/hub-layout.json`
- Override (wins when present and valid): `.data/hub-layout.json` in the server directory, or the
  path in the `HUB_LAYOUT_FILE` environment variable. A bad override is logged and ignored.
- Code: `HubMap.kt` (model, parser, validator, `toJson`), `HubMapLoader.kt`.

## What changes need what

| Change | Needs |
|--------|-------|
| Walls, objects (`locs`), NPCs, arrival, leave portal | Edit the override file, restart the server |
| Floors (`ground`, `floors`, `rooms[].floor`, `detail`, `palette`) | `generateHubTerrain`, a cache repack, a server restart, clients refetch |
| The hub's position or size above 128 | Code change (`ARENA_MIN`, map squares) |

Floors live in the packed cache because the client reads terrain from there. The server never
reads floors from the layout at runtime.

To rebuild floors after editing the bundled file:

```bash
scripts/build-overlay.sh :content:other:beanlab-soulwars:generateHubTerrain
# copy the rewritten map/m* files back, commit, then on the VM with osrs-server stopped:
./gradlew packCache :server:app:installDist
```

`-PhubLayout=path/to/layout.json` generates from another file.

## Format (version 1)

All coordinates are arena-local tiles, `0 until size` in x and z, z grows north. A `rect` is
`[x0, z0, x1, z1]` with the south-west corner first.

```json
{
  "version": 1,
  "size": 100,
  "palette": { "ground": 56, "path": 55 },
  "ground": "ground",
  "floors": [ { "rect": [46, 0, 53, 99], "floor": "path" } ],
  "rooms": [
    { "name": "Weapons", "rect": [58, 82, 75, 95], "floor": "interior",
      "wall": "blackpaintedbrickwall_1",
      "doors": [ { "side": "west", "from": 87, "to": 89 } ] }
  ],
  "detail": [ { "rect": [38, 43, 61, 56], "floor": "rug" } ],
  "walls": [ { "loc": "blackpaintedbrickwall_1", "line": [5, 95, 40, 95], "angle": "north" } ],
  "locs": [ { "loc": "noticeboard", "x": 9, "z": 95,
              "shape": "WallDecorStraightNoOffset", "angle": "north" } ],
  "npcs": [ { "npc": "zeke", "x": 66, "z": 88 } ],
  "arrival": { "x": 80, "z": 12 },
  "leavePortal": { "x": 84, "z": 8 }
}
```

- `palette` maps a name to a cache underlay id (0 to 173). Black, grey and purple ids in use:
  71 black, 45 dark purple-black, 56 dark grey, 55 light grey, 43 and 27 purples.
- Floors are painted in this order, later wins: `ground`, `floors`, each room's `floor`, `detail`.
- A room gets a wall ring: straight pieces on the edges, corner pieces on the four corners, and a
  gap for each door. `doors[].from`/`to` are z for west/east edges and x for north/south edges, and
  must fall between the corners.
- `walls` are straight runs along one row or column, all facing `angle` (`west`, `north`, `east`,
  `south`). A wall faces the side its angle names.
- `locs` are single objects. `shape` is a `LocShape` name (for example `CentrepieceStraight`,
  `WallDecorStraightNoOffset`). Loc and NPC names are cache internal names; unknown ones are
  logged at startup and skipped.
- `arrival` is where the portal puts you. `leavePortal` is the south-west tile of the 3x3 portal
  that returns you to the Exchange.

## Validation

`HubMap.parse` returns every problem at once rather than stopping at the first: bad JSON, version,
size, unknown floor names, rects outside the area or with the wrong corner order, door ranges,
overlapping rooms, unknown shapes and angles, and NPCs or the arrival tile standing on a wall.
Editors should show these messages as they are.
