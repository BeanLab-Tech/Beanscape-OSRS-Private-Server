import json
import pathlib
import sys

import pytest

WEB = pathlib.Path(__file__).resolve().parents[1]
REPO = WEB.parent
VECTORS = REPO / "content/other/beanlab-soulwars/src/test/resources/hub-layout-vectors.json"
sys.path.insert(0, str(WEB))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))


@pytest.fixture(scope="session")
def vectors():
    return json.loads(VECTORS.read_text(encoding="utf-8"))


@pytest.fixture()
def panel(tmp_path, monkeypatch, vectors):
    """The admin app wired to a throw-away server dir holding the sample layout as 'bundled'."""
    server = tmp_path / "server"
    bundled = (
        server
        / "content/other/beanlab-soulwars/src/main/resources/org/rsmod/content/other/beanlab/soulwars"
    )
    bundled.mkdir(parents=True)
    (bundled / "hub-layout.json").write_text(json.dumps(vectors["base"], indent=2), encoding="utf-8")
    cfg = tmp_path / "admin_config.json"
    cfg.write_text(
        json.dumps(
            {
                "admin_username": "admin",
                "admin_password": "pw",
                "db_path": str(tmp_path / "game.db"),
                "hub_server_dir": str(server),
            }
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv("OSRS_ADMIN_CONFIG", str(cfg))
    monkeypatch.delenv("HUB_LAYOUT_FILE", raising=False)
    sys.modules.pop("admin_app", None)
    import admin_app
    from fastapi.testclient import TestClient

    client = TestClient(admin_app.app, follow_redirects=False)

    class Panel:
        pass

    p = Panel()
    p.app, p.client, p.server = admin_app, client, server
    p.override = server / ".data/hub-layout.json"
    yield p
    sys.modules.pop("admin_app", None)


@pytest.fixture()
def authed(panel):
    token = panel.app.make_session("admin")
    panel.client.cookies.set("admin_session", token)
    return panel
