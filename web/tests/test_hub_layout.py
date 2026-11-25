import copy
import json

import hub_layout
from conftest import REPO, VECTORS

CASES = json.loads(VECTORS.read_text(encoding="utf-8"))["cases"]


def build(vectors, case):
    if "raw" in case:
        return case["raw"]
    layout = copy.deepcopy(vectors["base"])
    for k, v in case["merge"].items():
        if v is None:
            layout.pop(k, None)
        else:
            layout[k] = v
    return json.dumps(layout)


def pytest_generate_tests(metafunc):
    if "case" in metafunc.fixturenames:
        metafunc.parametrize("case", CASES, ids=[c["name"] for c in CASES])


def test_vectors(vectors, case):
    problems = hub_layout.validate_text(build(vectors, case))
    expect = case["expect"]
    assert len(problems) == len(expect), problems
    for problem, fragment in zip(problems, expect):
        assert fragment in problem


def test_real_bundled_layout_is_valid():
    path = REPO / hub_layout.BUNDLED_RELATIVE
    assert hub_layout.validate_text(path.read_text(encoding="utf-8")) == []


def test_classify_changes(vectors):
    base = vectors["base"]
    assert hub_layout.classify_changes(base, copy.deepcopy(base))["summary"] == "none"

    moved_npc = copy.deepcopy(base)
    moved_npc["npcs"][0]["x"] = 26
    c = hub_layout.classify_changes(base, moved_npc)
    assert c["summary"] == "restart" and not c["needs_repack"]

    door = copy.deepcopy(base)
    door["rooms"][0]["doors"][0]["to"] = 27
    assert hub_layout.classify_changes(base, door)["summary"] == "restart"

    floor = copy.deepcopy(base)
    floor["floors"].append({"rect": [1, 1, 2, 2], "floor": "in"})
    assert hub_layout.classify_changes(base, floor)["summary"] == "repack"

    room_floor = copy.deepcopy(base)
    room_floor["rooms"][0]["floor"] = "path"
    assert hub_layout.classify_changes(base, room_floor)["needs_repack"]

    grown = copy.deepcopy(base)
    grown["rooms"][0]["rect"] = [20, 20, 31, 30]
    assert hub_layout.classify_changes(base, grown)["needs_repack"]


def test_store_atomic_write_and_single_backup(tmp_path, vectors):
    store = hub_layout.HubLayoutStore(tmp_path)
    first = store.write(vectors["base"])
    assert first["backup"] is None and store.override.is_file()
    layout = copy.deepcopy(vectors["base"])
    for i in range(3):
        layout["arrival"]["x"] = 6 + i
        store.write(layout)
    backups = list(store.override.parent.glob(hub_layout.BACKUP_PREFIX + "*"))
    assert len(backups) == 1
    assert json.loads(store.override.read_text())["arrival"]["x"] == 8
    assert not list(store.override.parent.glob("*.tmp"))
