import pytest
from fastapi.testclient import TestClient

import app.main as main_mod
from app.main import create_app

KEY = "test-key"
ROUTE = [[16.29888, 80.4481], [16.29915, 80.44817], [16.22658, 80.4388]]
HOSP_ROUTE = [[16.22658, 80.4388], [16.2281, 80.43919], [16.29719, 80.44497]]


def assignment(aid=10022):
    return {
        "ambulance_id": aid,
        "ambulance_lat": 16.298876267928353,
        "ambulance_lon": 80.44811487174339,
        "polyline": ROUTE,
        "hospital": {"lat": 16.29730797, "lon": 80.44516754, "polyline": HOSP_ROUTE},
        "ambulance_plate": "ignored", "duration_min": 25.28,  # extra fields are ignored
    }


def make_client(state_file, fleet_ids=()):
    return TestClient(create_app(fleet_ids=list(fleet_ids), state_file=state_file, dispatch_api_key=KEY))


@pytest.fixture
def client(tmp_path):
    with make_client(tmp_path / "state.json") as c:
        c.state_file = tmp_path / "state.json"
        yield c


@pytest.fixture
def dispatcher_btb(monkeypatch):
    """Fake of the dispatcher's Back-to-Base endpoint. Set .ok = False to simulate failure."""
    class Fake:
        ok = True

        def __init__(self):
            self.calls = []

        async def __call__(self, aid):
            self.calls.append(aid)
            return (True, "ok") if self.ok else (False, "dispatcher responded 500")

    fake = Fake()
    monkeypatch.setattr(main_mod, "notify_back_to_base", fake)
    return fake


def register(client, aid):
    r = client.post("/api/devices/register", json={"ambulance_id": aid})
    assert r.status_code == 200
    return {"Authorization": f"Bearer {r.json()['token']}"}


def dispatch(client, body):
    return client.post("/api/dispatch", json=body, headers={"X-API-Key": KEY})


def ids(client):
    return [v["id"] for v in client.get("/api/vehicles").json()]


def test_dispatch_requires_api_key(client):
    assert client.post("/api/dispatch", json={"assignments": [assignment()]}).status_code == 401


def test_fleet_is_whoever_registered(client):
    assert ids(client) == []
    register(client, 10022)
    register(client, 555)
    register(client, 10022)  # second phone in the same ambulance
    assert ids(client) == [555, 10022]
    assert client.post("/api/devices/register", json={"ambulance_id": 0}).status_code == 422


def test_unregistered_ambulance_is_rejected(client):
    r = dispatch(client, assignment(10099))
    assert r.status_code == 409
    assert r.json()["rejected"] == [{"ambulance_id": 10099, "reason": "ambulance not registered in the app"}]


def test_full_flow(client, dispatcher_btb):
    h22, h05 = register(client, 10022), register(client, 10005)

    # both phones connected; only 10022 should receive the route
    with client.websocket_connect(f"/ws?ambulance_id=10022&token={h22['Authorization'][7:]}") as ws22, \
         client.websocket_connect(f"/ws?ambulance_id=10005&token={h05['Authorization'][7:]}") as ws05:
        snap = ws22.receive_json()
        assert snap["assignment"] is None and [v["id"] for v in snap["vehicles"]] == [10005, 10022]
        assert ws05.receive_json()["type"] == "snapshot"

        # sample shape {"assignments": [...]}
        r = dispatch(client, {"assignments": [assignment()], "solver": "quantum"})
        assert r.status_code == 200 and r.json() == {"accepted": [10022], "rejected": []}

        msg = ws22.receive_json()
        assert msg["type"] == "assignment"
        a = msg["assignment"]
        assert a["to_patient"]["destination"] == {"lat": 16.22658, "lon": 80.4388}  # last polyline point
        assert a["to_hospital"]["polyline"] == HOSP_ROUTE
        assert ws22.receive_json()["vehicle"]["status"] == "BUSY"

        other = ws05.receive_json()  # other phone: status only, no route
        assert other == {"type": "status", "vehicle": other["vehicle"]}

        # busy ambulance cannot be re-assigned
        r = dispatch(client, assignment())  # single-object form
        assert r.status_code == 409 and r.json()["rejected"][0]["reason"] == "ambulance is busy"

        # only the assigned phone can read the route or press Back to Base
        assert client.get("/api/vehicles/10022/assignment", headers=h05).status_code == 403
        assert client.post("/api/vehicles/10022/back-to-base", headers=h05).status_code == 403
        assert client.get("/api/vehicles/10022/assignment", headers=h22).json()["ambulance_id"] == 10022

        # driver taps Back to Base -> dispatcher endpoint called -> AVAILABLE everywhere
        r = client.post("/api/vehicles/10022/back-to-base", headers=h22)
        assert r.status_code == 200 and r.json()["status"] == "AVAILABLE"
        assert dispatcher_btb.calls == [10022]
        assert ws22.receive_json() == {"type": "assignment_cleared"}
        assert ws22.receive_json()["vehicle"]["status"] == "AVAILABLE"
        assert ws05.receive_json()["vehicle"]["status"] == "AVAILABLE"

        # a new ambulance registering shows up on every phone
        register(client, 10031)
        assert [v["id"] for v in ws05.receive_json()["vehicles"]] == [10005, 10022, 10031]

    # repeated tap is harmless and does not call the dispatcher again
    assert client.post("/api/vehicles/10022/back-to-base", headers=h22).status_code == 200
    assert dispatcher_btb.calls == [10022]
    assert dispatch(client, assignment()).status_code == 200  # assignable again


def test_back_to_base_failure_keeps_busy(client, dispatcher_btb):
    dispatcher_btb.ok = False
    h = register(client, 10022)
    dispatch(client, assignment())
    assert client.post("/api/vehicles/10022/back-to-base", headers=h).status_code == 502
    assert client.get("/api/vehicles").json()[0]["status"] == "BUSY"
    dispatcher_btb.ok = True  # network back -> retry succeeds
    assert client.post("/api/vehicles/10022/back-to-base", headers=h).json()["status"] == "AVAILABLE"


def test_unregister(client):
    h1, h2 = register(client, 10022), register(client, 10022)
    assert client.post("/api/vehicles/10022/unregister", headers=h1).status_code == 204
    assert ids(client) == [10022]  # second phone still registered
    assert client.post("/api/vehicles/10022/unregister", headers=h1).status_code == 403  # token gone
    assert client.post("/api/vehicles/10022/unregister", headers=h2).status_code == 204
    assert ids(client) == []  # last phone gone -> ambulance removed

    # a busy ambulance stays listed even if its phone un-registers
    h = register(client, 10005)
    dispatch(client, assignment(10005))
    client.post("/api/vehicles/10005/unregister", headers=h)
    assert ids(client) == [10005]


def test_stats(client):
    h = register(client, 10022)
    register(client, 10022)
    register(client, 10005)
    dispatch(client, assignment(10022))
    assert client.get("/api/stats").status_code == 401
    with client.websocket_connect(f"/ws?ambulance_id=10022&token={h['Authorization'][7:]}") as ws:
        ws.receive_json()
        s = client.get("/api/stats", headers={"X-API-Key": KEY}).json()
    assert (s["registered_vehicles"], s["registered_phones"], s["online_vehicles"], s["busy_vehicles"]) == (2, 3, 1, 1)
    row = {v["id"]: v for v in s["vehicles"]}[10022]
    assert row["phones"] == 2 and row["online"] is True and row["status"] == "BUSY" and row["last_seen"]


def test_allow_list(tmp_path):
    with make_client(tmp_path / "s.json", fleet_ids=range(10000, 10040)) as c:
        assert c.post("/api/devices/register", json={"ambulance_id": 10039}).status_code == 200
        assert c.post("/api/devices/register", json={"ambulance_id": 20000}).status_code == 404


def test_batch_partial_and_unknown(client):
    register(client, 10001)
    r = dispatch(client, {"assignments": [assignment(10001), assignment(10001), assignment(99999)]})
    assert r.status_code == 200
    assert r.json()["accepted"] == [10001]
    assert [x["reason"] for x in r.json()["rejected"]] == ["ambulance is busy", "ambulance not registered in the app"]


def test_bad_polyline_rejected(client):
    bad = assignment()
    bad["polyline"] = [[16.2, 80.4]]
    assert dispatch(client, bad).status_code == 422


def test_state_survives_restart(client):
    h = register(client, 10010)
    dispatch(client, assignment(10010))
    with make_client(client.state_file) as c2:
        assert c2.get("/api/vehicles").json()[0] == {"id": 10010, "status": "BUSY", "busy_since": c2.get("/api/vehicles").json()[0]["busy_since"]}
        assert c2.get("/api/vehicles/10010/assignment", headers=h).status_code == 200  # phone token still valid
