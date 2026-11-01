"""`?verify=true` purges Firestore session metadata when Drive says the folder
is gone. Drive being *unreachable* is not Drive saying that — mapping a probe
failure to "missing" turned a transient outage into permanent data loss."""
import fake_firestore
import pytest
import requests

from app import audit, drive


def _session(sid, folder):
    return {
        "uid": "dev-user", "status": "COMPLETED", "driveFolderId": folder,
        "localSessionId": f"l-{sid}", "fileCount": 1, "completedCount": 1, "totalBytes": 1,
    }


@pytest.fixture
def seeded(monkeypatch):
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    store._data["sessions"] = {
        "s1": _session("s1", "fold-1"),
        "s2": _session("s2", "fold-2"),
    }
    return store


async def test_drive_outage_purges_nothing(seeded, client, monkeypatch):
    """Every probe fails. Nothing may be deleted, and the caller is told the
    verification was incomplete rather than being handed a clean bill."""
    monkeypatch.setattr(drive, "file_exists", _raise_unreachable)

    r = await client.get("/v1/sessions?verify=true")

    assert r.status_code == 200
    body = r.json()
    assert body["verify"]["purged"] == 0
    assert body["verify"]["indeterminate"] == 2
    assert set(seeded._data["sessions"]) == {"s1", "s2"}, "an outage deleted user data"
    assert {s["sessionId"] for s in body["sessions"]} == {"s1", "s2"}


async def test_confirmed_miss_still_purges(seeded, client, monkeypatch):
    """The feature must keep working: a 404 from Drive is a real answer."""
    monkeypatch.setattr(drive, "file_exists", lambda token, fid, **_: fid != "fold-2")

    r = await client.get("/v1/sessions?verify=true")

    body = r.json()
    assert body["verify"]["purged"] == 1
    assert body["verify"]["indeterminate"] == 0
    assert set(seeded._data["sessions"]) == {"s1"}


async def test_partial_outage_purges_only_the_confirmed_miss(seeded, client, monkeypatch):
    """One folder confirmed gone, one unreachable: purge exactly one."""
    def probe(token, fid, **_):
        if fid == "fold-1":
            raise requests.ConnectionError("drive down")
        return False  # fold-2 confirmed absent

    monkeypatch.setattr(drive, "file_exists", probe)

    body = (await client.get("/v1/sessions?verify=true")).json()
    assert body["verify"] == {"requested": True, "purged": 1, "indeterminate": 1}
    assert set(seeded._data["sessions"]) == {"s1"}


def test_probe_files_reports_unknown_not_missing(monkeypatch):
    """The unit-level guarantee the route depends on."""
    monkeypatch.setattr(drive, "file_exists", _raise_unreachable)
    assert drive.probe_files("tok", ["a", "b"]) == {"a": drive.UNKNOWN, "b": drive.UNKNOWN}


def test_probe_files_distinguishes_all_three_states(monkeypatch):
    def probe(token, fid, **_):
        if fid == "boom":
            raise TimeoutError("slow")
        return fid == "here"

    monkeypatch.setattr(drive, "file_exists", probe)
    assert drive.probe_files("tok", ["here", "gone", "boom"]) == {
        "here": drive.ALIVE,
        "gone": drive.MISSING,
        "boom": drive.UNKNOWN,
    }


def _raise_unreachable(token, file_id, **_):
    raise requests.ConnectionError("drive unreachable")
