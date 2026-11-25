import copy
import json


def test_api_requires_login(panel):
    assert panel.client.get("/admin/api/hub/layout").status_code == 401
    assert panel.client.put("/admin/api/hub/layout", json={}).status_code == 401
    assert panel.client.post("/admin/api/hub/validate", json={}).status_code == 401
    assert panel.client.get("/admin/api/hub/underlays").status_code == 401
    assert panel.client.get("/admin/hub").status_code == 307  # the page redirects to login


def test_get_bundled_then_override(authed, vectors):
    c = authed.client
    j = c.get("/admin/api/hub/layout").json()
    assert j["source"] == "bundled" and j["problems"] == [] and j["layout"] == vectors["base"]

    layout = copy.deepcopy(vectors["base"])
    layout["npcs"][0]["x"] = 26
    r = c.put("/admin/api/hub/layout", json={"layout": layout, "base_etag": j["etag"]})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["ok"] and body["needs"]["summary"] == "restart"
    assert authed.override.is_file()

    j2 = c.get("/admin/api/hub/layout").json()
    assert j2["source"] == "override" and j2["layout"]["npcs"][0]["x"] == 26


def test_put_invalid_returns_messages_and_writes_nothing(authed, vectors):
    layout = copy.deepcopy(vectors["base"])
    layout["version"] = 9
    layout["ground"] = "lava"
    r = authed.client.put("/admin/api/hub/layout", json=layout)
    assert r.status_code == 422
    assert r.json()["problems"] == ["version must be 1", "ground: unknown floor 'lava'"]
    assert not authed.override.exists()


def test_put_keeps_one_backup(authed, vectors):
    c = authed.client
    layout = copy.deepcopy(vectors["base"])
    for x in (6, 7, 8):
        layout["arrival"]["x"] = x
        assert c.put("/admin/api/hub/layout", json=layout).status_code == 200
    backups = list(authed.override.parent.glob("hub-layout.json.bak-*"))
    assert len(backups) == 1
    assert json.loads(backups[0].read_text())["arrival"]["x"] == 7


def test_put_conflict_on_stale_etag(authed, vectors):
    c = authed.client
    etag = c.get("/admin/api/hub/layout").json()["etag"]
    layout = copy.deepcopy(vectors["base"])
    layout["arrival"]["x"] = 6
    assert c.put("/admin/api/hub/layout", json={"layout": layout}).status_code == 200
    layout["arrival"]["x"] = 7
    r = c.put("/admin/api/hub/layout", json={"layout": layout, "base_etag": etag})
    assert r.status_code == 409


def test_validate_endpoint_reports_needs(authed, vectors):
    layout = copy.deepcopy(vectors["base"])
    layout["floors"].append({"rect": [1, 1, 2, 2], "floor": "in"})
    j = authed.client.post("/admin/api/hub/validate", json={"layout": layout}).json()
    assert j["problems"] == [] and j["needs"]["summary"] == "repack"
    layout["ground"] = "nope"
    assert authed.client.post("/admin/api/hub/validate", json={"layout": layout}).json()["problems"]


def test_bad_body(authed):
    assert authed.client.put("/admin/api/hub/layout", json=[1, 2]).status_code == 400


def test_underlays_and_page(authed):
    j = authed.client.get("/admin/api/hub/underlays").json()
    assert "WallStraight" in j["shapes"] and "north" in j["angles"]
    assert any(u["id"] == 71 for u in j["underlays"])
    r = authed.client.get("/admin/hub")
    assert r.status_code == 200 and "Market Hub editor" in r.text
