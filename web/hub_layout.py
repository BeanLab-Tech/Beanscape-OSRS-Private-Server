"""Market Hub layout file: validation, storage and change classification.

Mirrors `HubMap.parse` in content/other/beanlab-soulwars (HubMap.kt): same rules, same message
text, same order. Shared test vectors live in
content/other/beanlab-soulwars/src/test/resources/hub-layout-vectors.json.

Format and workflow: docs/HUB_LAYOUT.md.
"""

from __future__ import annotations

import copy
import hashlib
import json
import os
import pathlib
import tempfile
from datetime import datetime
from typing import Any

VERSION = 1
MAX_UNDERLAY = 173
MAX_SIZE = 128

SIDES = ("west", "east", "north", "south")
ANGLES = ("west", "north", "east", "south")
# org.rsmod.game.loc.LocShape entries (names are matched case-insensitively, like the parser).
SHAPES = (
    "WallStraight", "WallDiagonalCorner", "WallL", "WallSquareCorner",
    "WallDecorStraightNoOffset", "WallDecorStraightOffset", "WallDecorDiagonalOffset",
    "WallDecorDiagonalNoOffset", "WallDecorDiagonalBoth", "WallDiagonal",
    "CentrepieceStraight", "CentrepieceDiagonal", "RoofStraight", "RoofDiagonalWithRoofEdge",
    "RoofDiagonal", "RoofLConcave", "RoofLConvex", "RoofFlat", "RoofEdgeStraight",
    "RoofEdgeDiagonalCorner", "RoofEdgeL", "RoofEdgeSquareCorner", "GroundDecor",
)

BUNDLED_RELATIVE = (
    "content/other/beanlab-soulwars/src/main/resources/org/rsmod/content/other/beanlab/"
    "soulwars/hub-layout.json"
)
OVERRIDE_RELATIVE = ".data/hub-layout.json"
BACKUP_PREFIX = "hub-layout.json.bak-"


# ---------------------------------------------------------------- validation


def _is_int(v: Any) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _as_int(v: Any, default: int = -1) -> int:
    """Jackson's `asInt(default)` for the node types a layout can hold."""
    if _is_int(v):
        return v
    if isinstance(v, float):
        return int(v)
    if isinstance(v, str):
        try:
            return int(v.strip())
        except ValueError:
            return default
    return default


def _as_text(v: Any, default: str = "") -> str:
    """Jackson's `asText(default)`: scalars as text, missing/null/containers as the default."""
    if v is None or isinstance(v, (dict, list)):
        return default
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def _items(v: Any) -> list:
    """Elements a Kotlin `withIndex()` would visit (array elements, or object values)."""
    if isinstance(v, list):
        return v
    if isinstance(v, dict):
        return list(v.values())
    return []


def _get(node: Any, key: str) -> Any:
    return node.get(key) if isinstance(node, dict) else None


def _match(options: tuple[str, ...], name: str) -> str | None:
    low = name.lower()
    return next((o for o in options if o.lower() == low), None)


class _Parser:
    def __init__(self, root: Any):
        self.root = root
        self.problems: list[str] = []
        self.rooms: list[tuple[str, tuple]] = []

    def parse(self) -> list[str]:
        root, problems = self.root, self.problems
        if _as_int(_get(root, "version")) != VERSION:
            problems.append(f"version must be {VERSION}")
        size = _as_int(_get(root, "size"))
        if not 8 <= size <= MAX_SIZE:
            problems.append(f"size must be 8..{MAX_SIZE}")
        safe = size if 8 <= size <= MAX_SIZE else 100
        self.size = safe

        palette: dict[str, int] = {}
        pal_node = _get(root, "palette")
        if isinstance(pal_node, dict):
            for name, node in pal_node.items():
                pid = _as_int(node)
                if not 0 <= pid <= MAX_UNDERLAY:
                    problems.append(f"palette.{name} must be 0..{MAX_UNDERLAY}")
                palette[name] = min(max(pid, 0), MAX_UNDERLAY)
        if not palette:
            problems.append("palette is empty")
        self.palette = palette

        self.floor_name(_get(root, "ground"), "ground")
        floors = self.paints("floors")
        detail = self.paints("detail")
        del floors, detail

        rooms: list[tuple[str, tuple, str, list]] = []
        for i, node in enumerate(_items(_get(root, "rooms"))):
            path = f"rooms[{i}]"
            rect = self.rect(_get(node, "rect"), f"{path}.rect")
            name = _as_text(_get(node, "name"))
            if not name.strip():
                problems.append(f"{path}: name is required")
            wall = _as_text(_get(node, "wall"))
            if not wall.strip():
                problems.append(f"{path}: wall is required")
            self.floor_name(_get(node, "floor"), f"{path}.floor")
            doors = [
                self.door(d, rect, f"{path}.doors[{j}]")
                for j, d in enumerate(_items(_get(node, "doors")))
            ]
            rooms.append((name, rect, wall, doors))
        for i in range(len(rooms)):
            for j in range(i + 1, len(rooms)):
                if _overlaps(rooms[i][1], rooms[j][1]):
                    problems.append(f"rooms '{rooms[i][0]}' and '{rooms[j][0]}' overlap")

        walls: list[tuple[str, tuple, str]] = []
        for i, node in enumerate(_items(_get(root, "walls"))):
            path = f"walls[{i}]"
            line = self.rect(_get(node, "line"), f"{path}.line")
            if line[0] != line[2] and line[1] != line[3]:
                problems.append(f"{path}: line is not straight")
            loc = _as_text(_get(node, "loc"))
            if not loc.strip():
                problems.append(f"{path}: loc is required")
            walls.append((loc, line, self.angle(_get(node, "angle"), f"{path}.angle")))

        for i, node in enumerate(_items(_get(root, "locs"))):
            path = f"locs[{i}]"
            if not _as_text(_get(node, "loc")).strip():
                problems.append(f"{path}: loc is required")
            shape_name = _as_text(_get(node, "shape"))
            if _match(SHAPES, shape_name) is None:
                problems.append(f"{path}: unknown shape '{shape_name}'")
            self.coord(_get(node, "x"), f"{path}.x")
            self.coord(_get(node, "z"), f"{path}.z")
            self.angle(_get(node, "angle"), f"{path}.angle")

        npcs: list[tuple[str, int, int]] = []
        for i, node in enumerate(_items(_get(root, "npcs"))):
            path = f"npcs[{i}]"
            npc = _as_text(_get(node, "npc"))
            if not npc.strip():
                problems.append(f"{path}: npc is required")
            npcs.append(
                (npc, self.coord(_get(node, "x"), f"{path}.x"), self.coord(_get(node, "z"), f"{path}.z"))
            )

        arrival = self.point(_get(root, "arrival"), "arrival")
        self.point(_get(root, "leavePortal"), "leavePortal")

        if not problems:
            blocked = {(x, z) for x, z in _wall_tiles(rooms, walls)}
            for npc, x, z in npcs:
                if (x, z) in blocked:
                    problems.append(f"npc {npc} at {x},{z} is inside a wall")
            if arrival in blocked:
                problems.append("arrival is inside a wall")
        return problems

    # -- helpers, each mirrors a Parser method

    def floor_name(self, node: Any, path: str) -> str:
        name = _as_text(node)
        if name not in self.palette:
            self.problems.append(f"{path}: unknown floor '{name}'")
        return name

    def paints(self, key: str) -> list:
        out = []
        for i, node in enumerate(_items(_get(self.root, key))):
            self.rect(_get(node, "rect"), f"{key}[{i}].rect")
            out.append(self.floor_name(_get(node, "floor"), f"{key}[{i}].floor"))
        return out

    def coord(self, node: Any, path: str) -> int:
        v = _as_int(node)
        if not _is_int(node) or not 0 <= v < self.size:
            self.problems.append(f"{path} must be 0..{self.size - 1}")
        return min(max(v, 0), self.size - 1)

    def point(self, node: Any, path: str) -> tuple[int, int]:
        return (self.coord(_get(node, "x"), f"{path}.x"), self.coord(_get(node, "z"), f"{path}.z"))

    def rect(self, node: Any, path: str) -> tuple[int, int, int, int]:
        if not isinstance(node, list) or len(node) != 4 or not all(_is_int(n) for n in node):
            self.problems.append(f"{path} must be [x0, z0, x1, z1]")
            return (0, 0, 0, 0)
        x0, z0, x1, z1 = node
        if x0 > x1 or z0 > z1:
            self.problems.append(f"{path}: first corner must be south-west")
        if x0 < 0 or z0 < 0 or x1 >= self.size or z1 >= self.size:
            self.problems.append(f"{path} is outside 0..{self.size - 1}")
        return (x0, z0, x1, z1)

    def angle(self, node: Any, path: str) -> str:
        name = _as_text(node)
        found = _match(ANGLES, name)
        if found is None:
            self.problems.append(f"{path}: unknown angle '{name}'")
        return found or "north"

    def door(self, node: Any, room: tuple, path: str) -> tuple[str, int, int]:
        side_name = _as_text(_get(node, "side"))
        side = _match(SIDES, side_name)
        if side is None:
            self.problems.append(f"{path}: unknown side '{side_name}'")
            return ("west", 0, 0)
        frm = _as_int(_get(node, "from"))
        to = _as_int(_get(node, "to"))
        vertical = side in ("west", "east")
        lo = room[1] + 1 if vertical else room[0] + 1
        hi = room[3] - 1 if vertical else room[2] - 1
        if frm > to or frm < lo or to > hi:
            self.problems.append(
                f"{path}: from/to must sit within {lo}..{hi} on the {side.capitalize()} wall"
            )
        return (side, frm, to)


def _overlaps(a: tuple, b: tuple) -> bool:
    return a[0] <= b[2] and b[0] <= a[2] and a[1] <= b[3] and b[1] <= a[3]


def _wall_tiles(rooms: list, walls: list):
    """Tiles holding a wall piece: room outlines (doorways left open) plus explicit runs."""
    for _name, (x0, z0, x1, z1), _wall, doors in rooms:

        def open_(side: str, coord: int) -> bool:
            return any(d[0] == side and d[1] <= coord <= d[2] for d in doors)

        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                west, east, south, north = x == x0, x == x1, z == z0, z == z1
                if not (west or east or south or north):
                    continue
                if (west or east) and (north or south):
                    yield (x, z)  # corner
                elif west:
                    if not open_("west", z):
                        yield (x, z)
                elif east:
                    if not open_("east", z):
                        yield (x, z)
                elif south:
                    if not open_("south", x):
                        yield (x, z)
                elif north:
                    if not open_("north", x):
                        yield (x, z)
    for _loc, (x0, z0, x1, z1), _angle in walls:
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                yield (x, z)


def validate_layout(root: Any) -> list[str]:
    """Every problem with a parsed layout, in `HubMap.parse` order. Empty means valid."""
    return _Parser(root).parse()


def validate_text(text: str) -> list[str]:
    """Like `HubMap.parse(text)`: bad JSON is reported as a single problem."""
    try:
        root = json.loads(text)
    except ValueError as e:
        return [f"not valid JSON: {e}"]
    return validate_layout(root)


# ---------------------------------------------------------------- change classification


def classify_changes(old: dict | None, new: dict) -> dict:
    """What a change needs, per docs/HUB_LAYOUT.md.

    floors (palette, ground, floors, detail, room rect/floor) -> terrain regenerate + cache repack;
    walls, locs, npcs, arrival, leavePortal, room wall/doors -> server restart only.
    """
    if old is None:
        old = {}
    repack: list[str] = []
    restart: list[str] = []

    for key in ("palette", "ground", "floors", "detail", "size"):
        if old.get(key) != new.get(key):
            repack.append(key)

    def room_part(room: dict, keys: tuple[str, ...]):
        return {k: room.get(k) for k in keys}

    old_rooms = {r.get("name"): r for r in old.get("rooms", []) if isinstance(r, dict)}
    new_rooms = {r.get("name"): r for r in new.get("rooms", []) if isinstance(r, dict)}
    for name in sorted(set(old_rooms) | set(new_rooms), key=str):
        a, b = old_rooms.get(name), new_rooms.get(name)
        if a is None or b is None:
            # a room adds/removes both its floor rect and its wall ring
            repack.append(f"rooms[{name}]")
            restart.append(f"rooms[{name}]")
            continue
        if room_part(a, ("rect", "floor")) != room_part(b, ("rect", "floor")):
            repack.append(f"rooms[{name}] rect/floor")
        if room_part(a, ("rect", "wall", "doors")) != room_part(b, ("rect", "wall", "doors")):
            restart.append(f"rooms[{name}] walls/doors")
    if [r.get("name") for r in old.get("rooms", []) if isinstance(r, dict)] != [
        r.get("name") for r in new.get("rooms", []) if isinstance(r, dict)
    ] and not (repack or restart):
        restart.append("rooms order")

    for key in ("walls", "locs", "npcs", "arrival", "leavePortal"):
        if old.get(key) != new.get(key):
            restart.append(key)

    return {
        "needs_repack": bool(repack),
        "needs_restart": bool(repack or restart),
        "repack": repack,
        "restart": restart,
        "summary": (
            "none"
            if not (repack or restart)
            else "repack" if repack else "restart"
        ),
    }


# ---------------------------------------------------------------- storage


class HubLayoutStore:
    """Reads the effective layout and writes the override file atomically."""

    def __init__(self, server_dir: str | os.PathLike, bundled: str | os.PathLike | None = None,
                 override: str | os.PathLike | None = None):
        self.server_dir = pathlib.Path(server_dir)
        self.bundled = pathlib.Path(bundled) if bundled else self.server_dir / BUNDLED_RELATIVE
        self.override = pathlib.Path(override) if override else self.server_dir / OVERRIDE_RELATIVE

    def source(self) -> tuple[str, pathlib.Path]:
        """('override' | 'bundled', path) of the file a read returns."""
        if self.override.is_file():
            return "override", self.override
        return "bundled", self.bundled

    def read(self) -> tuple[dict, bytes, str]:
        kind, path = self.source()
        raw = path.read_bytes()
        return json.loads(raw), raw, kind

    @staticmethod
    def etag(raw: bytes) -> str:
        return hashlib.sha256(raw).hexdigest()[:16]

    def current_etag(self) -> str:
        return self.etag(self.source()[1].read_bytes())

    def write(self, layout: dict) -> dict:
        """Atomically replaces the override; keeps one timestamped backup of the previous one."""
        self.override.parent.mkdir(parents=True, exist_ok=True)
        text = json.dumps(layout, indent=2, ensure_ascii=False) + "\n"
        backup = None
        if self.override.is_file():
            stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
            backup = self.override.with_name(f"{BACKUP_PREFIX}{stamp}")
            n = 1
            while backup.exists():
                backup = self.override.with_name(f"{BACKUP_PREFIX}{stamp}-{n}")
                n += 1
            backup.write_bytes(self.override.read_bytes())
        fd, tmp = tempfile.mkstemp(prefix=".hub-layout-", suffix=".tmp", dir=self.override.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
                f.write(text)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, self.override)
        except BaseException:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise
        # one backup only: drop older ones
        for old in self.override.parent.glob(BACKUP_PREFIX + "*"):
            if backup is None or old != backup:
                try:
                    old.unlink()
                except OSError:
                    pass
        return {"path": str(self.override), "backup": str(backup) if backup else None,
                "etag": self.etag(text.encode("utf-8"))}


def deep_copy(v: Any) -> Any:
    return copy.deepcopy(v)
