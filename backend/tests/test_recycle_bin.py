"""The recycle bin (ADR-035): a deleted cloud backup is held 30 days.

`DELETE /v1/sessions/{sid}` moves the session into `deleted_sessions` and
flags its files; it no longer touches Drive. While binned it is absent from
every listing, count and session route, a re-upload of the same analysis is
refused, and its files refuse completion and download. The holder can
restore it (if there is room) or delete it for good; the daily job purges
what is past its date, leaving an erased marker of ids alone.
"""
import json
from datetime import datetime, timedelta, timezone

import pytest
import requests

from app import apps, drive, errors, statuses
from app.config import settings
from app.services import account as account_service
from app.services import recycle_bin as bin_service
import repo_view as repo

UID = "dev-user"  # deps._DEV_USER under DEV_INSECURE_AUTH
DEVICE = "dev-device"  # deps._DEV_DEVICE
_SHA = "a" * 64
_MD5 = "b" * 32
MT_HEADER = {"X-App-Id": "com.sempermechanics.semperclassroom"}


def _now():
    return datetime.now(timezone.utc)


def _seed(store, sid="s1", *, uid=UID, app=apps.SEMPER, local="loc-1",
          status=statuses.SESSION_COMPLETED, folder="fold-1", files=2, device=DEVICE):
    store._data.setdefault("sessions", {})[sid] = {
        "uid": uid, "app": app, "deviceId": device, "localSessionId": local,
        "specimen": "Steel 24", "name": f"Sweep {sid}", "status": status,
        "driveFolderId": folder, "fileCount": files, "completedCount": files,
        "totalBytes": 10 * files, "metrics": {"frameCount": 12},
        "createdAt": _now() - timedelta(days=2), "updatedAt": _now() - timedelta(days=1),
    }
    for i in range(files):
        store._data.setdefault("files", {})[f"{sid}_bundle_f{i}.zip"] = {
            "sessionId": sid, "uid": uid, "role": "bundle", "name": f"f{i}.zip",
            "sizeBytes": 10, "sha256": _SHA, "status": statuses.FILE_COMPLETED,
            "driveFileId": f"{sid}-d{i}", "driveMd5": _MD5,
        }
    return sid


def _files_of(store, sid):
    return {k: v for k, v in store._data.get("files", {}).items() if v["sessionId"] == sid}


@pytest.fixture
def world(store, audited, monkeypatch):
    """The dev caller with a stored analysis; Drive deletions recorded."""
    store._data["users"] = {UID: {"email": "dev@local", "access_status": "APPROVED"}}
    deleted: list[str] = []
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(drive, "delete_file", lambda token, fid: deleted.append(fid))
    monkeypatch.setattr(drive, "ensure_session_folders",
                        lambda *a, **k: {"sessionFolderId": "sf", "userFolderId": "uf", "bundle": "sf"})
    monkeypatch.setattr(drive, "init_resumable", lambda *a, **k: "https://drive/resumable")
    _seed(store)
    return {"store": store, "audit": audited, "drive_deleted": deleted}


def _quota(monkeypatch, **over):
    """The caller's config with `over` on top (`maxSessions`, `mode`)."""
    real = repo.resolve_user_config
    repo.patch(monkeypatch, "resolve_user_config",
               lambda user, app=apps.SEMPER: {**real(user, app), **over})


def _actions(world):
    return [row["action"] for row in world["audit"]]


async def _create(client, local="loc-1", headers=None):
    return await client.post("/v1/sessions", headers=headers or {}, json={
        "specimen": "s", "localSessionId": local,
        "files": [{"name": "Session.zip", "role": "bundle", "bytes": 10, "sha256": _SHA}]})


# ------------------------------------------------------------------ binning


async def test_a_delete_bins_the_session_without_touching_drive(world, client):
    store = world["store"]
    before = dict(store._data["sessions"]["s1"])

    r = await client.delete("/v1/sessions/s1")

    assert r.status_code == 200, r.text
    body = r.json()
    assert (body["deleted"], body["filesRemoved"], body["binned"]) == ("s1", 2, True)
    purge_at = datetime.fromisoformat(body["purgeAt"])
    assert timedelta(days=29, hours=23) < purge_at - _now() <= timedelta(days=30)
    assert world["drive_deleted"] == []
    assert "s1" not in store._data["sessions"]
    tomb = store._data["deleted_sessions"]["s1"]
    # Every field of the live session, plus the bin's own.
    assert {k: tomb[k] for k in before if k != "schemaVersion"} == {
        k: v for k, v in before.items() if k != "schemaVersion"}
    assert (tomb["state"], tomb["priorStatus"], tomb["deletedByDeviceId"], tomb["deletedByApp"],
            tomb["filesBinned"]) == ("binned", statuses.SESSION_COMPLETED, DEVICE, "semper", 2)
    assert isinstance(tomb["deletedAt"], datetime)
    assert all(f.get("binnedAt") for f in _files_of(store, "s1").values())


async def test_a_delete_is_audited_as_a_bin_not_an_erasure(world, client):
    await client.delete("/v1/sessions/s1")
    assert "SESSION_DELETE" not in _actions(world)
    [row] = [r for r in world["audit"] if r["action"] == "SESSION_BIN"]
    assert row["uid"] == UID and row["target"] == {"type": "session", "id": "s1"}
    assert row["detail"]["localSessionId"] == "loc-1"
    assert (row["detail"]["app"], row["detail"]["filesBinned"]) == ("semper", 2)
    assert isinstance(row["detail"]["purgeAt"], datetime)


async def test_a_repeated_delete_answers_the_same(world, client):
    first = (await client.delete("/v1/sessions/s1")).json()
    again = await client.delete("/v1/sessions/s1")
    assert again.status_code == 200
    assert again.json() == first
    assert _actions(world).count("SESSION_BIN") == 1


async def test_a_delete_of_another_accounts_session_is_not_found(world, client):
    _seed(world["store"], "s-other", uid="someone-else")
    r = await client.delete("/v1/sessions/s-other")
    assert (r.status_code, r.json()["detail"]) == (404, errors.SESSION_NOT_FOUND)
    assert "s-other" in world["store"]._data["sessions"]


async def test_a_delete_of_another_accounts_binned_session_is_not_found(world, client):
    _seed(world["store"], "s-other", uid="someone-else")
    bin_service.bin_session({"uid": "someone-else"}, repo.get_session("s-other"), "dev-x", "semper")
    r = await client.delete("/v1/sessions/s-other")
    assert (r.status_code, r.json()["detail"]) == (404, errors.SESSION_NOT_FOUND)


async def test_a_delete_of_an_erased_session_is_not_found(world, client):
    await client.delete("/v1/sessions/s1")
    await client.delete("/v1/recycle-bin/s1")
    r = await client.delete("/v1/sessions/s1")
    assert (r.status_code, r.json()["detail"]) == (404, errors.SESSION_NOT_FOUND)


async def test_a_binned_session_counts_toward_no_quota(world, client):
    _seed(world["store"], "s2", local="loc-2")
    listed = (await client.get("/v1/sessions")).json()
    assert listed["quota"]["used"] == 2
    await client.delete("/v1/sessions/s1")

    listed = (await client.get("/v1/sessions")).json()
    assert [s["sessionId"] for s in listed["sessions"]] == ["s2"]
    assert listed["quota"]["used"] == 1
    assert repo.count_device_sessions(DEVICE) == 1


async def test_a_crash_between_flag_and_move_fails_closed(world, client, monkeypatch):
    """The files are flagged before the session moves: a delete that dies in
    between leaves files that refuse completion and download, and the
    session still listed until the delete is sent again."""
    store = world["store"]

    def dies(*_a, **_k):
        raise RuntimeError("instance killed")

    real_move = repo.move_to_bin
    repo.patch(monkeypatch, "move_to_bin", dies)
    with pytest.raises(RuntimeError):
        await client.delete("/v1/sessions/s1")
    assert "s1" in store._data["sessions"]
    file_id = next(iter(_files_of(store, "s1")))
    store._data["files"][file_id]["status"] = statuses.FILE_PENDING
    complete = await client.post(f"/v1/files/{file_id}/complete", json={
        "sessionId": "s1", "driveFileId": "d", "bytes": 10, "md5": _MD5})
    assert complete.status_code == 409
    content = await client.get(f"/v1/files/{file_id}/content")
    assert (content.status_code, content.json()["detail"]) == (404, errors.FILE_NOT_FOUND)

    repo.patch(monkeypatch, "move_to_bin", real_move)
    assert (await client.delete("/v1/sessions/s1")).status_code == 200
    assert store._data["deleted_sessions"]["s1"]["state"] == "binned"


# ------------------------------------------------------------------ while binned


async def test_a_reupload_of_a_binned_analysis_is_refused(world, client):
    await client.delete("/v1/sessions/s1")
    r = await _create(client, local="loc-1")
    assert r.status_code == 409, r.text
    assert r.json()["detail"] == f"{errors.SESSION_IN_RECYCLE_BIN}: s1"
    assert world["store"]._data["sessions"] == {}


async def test_another_analysis_and_another_account_upload_as_before(world, client):
    store = world["store"]
    _seed(store, "s-other", uid="someone-else", local="loc-9")
    bin_service.bin_session({"uid": "someone-else"}, repo.get_session("s-other"), "dev-x", "semper")
    await client.delete("/v1/sessions/s1")

    assert (await _create(client, local="loc-2")).status_code == 200
    # Someone else's bin holds loc-9; this account's upload of it goes ahead.
    assert (await _create(client, local="loc-9")).status_code == 200


async def test_an_erased_analysis_may_be_backed_up_again(world, client):
    """A permanent delete leaves the holder free to back it up again: only a
    binned entry blocks the upload, not the erased marker."""
    await client.delete("/v1/sessions/s1")
    await client.delete("/v1/recycle-bin/s1")
    r = await _create(client, local="loc-1")
    assert r.status_code == 200, r.text


async def test_a_binned_file_refuses_completion(world, client):
    store = world["store"]
    file_id = next(iter(_files_of(store, "s1")))
    store._data["files"][file_id]["status"] = statuses.FILE_PENDING
    await client.delete("/v1/sessions/s1")

    r = await client.post(f"/v1/files/{file_id}/complete", json={
        "sessionId": "s1", "driveFileId": "d-new", "bytes": 10, "md5": _MD5})

    assert r.status_code == 409, r.text
    assert r.json()["detail"] == f"{errors.SESSION_IN_RECYCLE_BIN}: s1"
    assert store._data["files"][file_id]["status"] == statuses.FILE_PENDING


async def test_a_binned_file_is_not_served(world, client, monkeypatch):
    monkeypatch.setattr(drive, "open_download",
                        lambda *a, **k: pytest.fail("a binned file reached Drive"))
    await client.delete("/v1/sessions/s1")
    file_id = next(iter(_files_of(world["store"], "s1")))
    r = await client.get(f"/v1/files/{file_id}/content")
    assert (r.status_code, r.json()["detail"]) == (404, errors.FILE_NOT_FOUND)


@pytest.mark.parametrize("method,path,body", [
    ("GET", "/v1/sessions/s1/uploads", None),
    ("GET", "/v1/sessions/s1/files", None),
    ("PUT", "/v1/sessions/s1/metadata", {"schema": "indic.session.metadata/6", "localSessionId": "loc-1"}),
    ("GET", "/v1/sessions/s1/bundle", None),
])
async def test_a_binned_session_is_absent_from_every_session_route(world, client, method, path, body):
    await client.delete("/v1/sessions/s1")
    r = await client.request(method, path, json=body)
    assert (r.status_code, r.json()["detail"]) == (404, errors.SESSION_NOT_FOUND)


# ------------------------------------------------------------------ listing


async def test_the_bin_lists_items_and_erased_markers(world, client):
    store = world["store"]
    _seed(store, "s2", local="loc-2")
    await client.delete("/v1/sessions/s1")
    await client.delete("/v1/sessions/s2")
    await client.delete("/v1/recycle-bin/s2")

    body = (await client.get("/v1/recycle-bin")).json()

    assert body["retentionDays"] == 30
    [item] = body["items"]
    assert set(item) == {"sessionId", "localSessionId", "name", "specimen", "app", "status",
                         "fileCount", "totalBytes", "deletedAt", "purgeAt"}
    assert (item["sessionId"], item["localSessionId"], item["status"], item["app"]) == (
        "s1", "loc-1", statuses.SESSION_COMPLETED, "semper")
    assert (item["name"], item["fileCount"], item["totalBytes"]) == ("Sweep s1", 2, 20)
    assert body["erased"] == [{"sessionId": "s2", "localSessionId": "loc-2",
                               "erasedAt": body["erased"][0]["erasedAt"]}]
    assert body["page"] == {"size": 50, "count": 2, "nextPageToken": None, "hasMore": False}


async def test_the_bin_lists_the_asking_apps_entries_alone(world, client):
    store = world["store"]
    _seed(store, "s-mt", app=apps.SEMPER_CLASSROOM, local="loc-mt")
    _seed(store, "s-theirs", uid="someone-else", local="loc-x")
    for sid in ("s1", "s-mt"):
        await client.delete(f"/v1/sessions/{sid}")
    bin_service.bin_session({"uid": "someone-else"}, repo.get_session("s-theirs"), "dev-x", "semper")

    def ids(body):
        return [i["sessionId"] for i in body["items"]]

    assert ids((await client.get("/v1/recycle-bin")).json()) == ["s1"]
    assert ids((await client.get("/v1/recycle-bin", headers=MT_HEADER)).json()) == ["s-mt"]
    assert ids((await client.get("/v1/recycle-bin?app=semperclassroom")).json()) == ["s-mt"]
    assert ids((await client.get("/v1/recycle-bin?app=all")).json()) == ["s-mt", "s1"]
    assert (await client.get("/v1/recycle-bin?app=nope")).status_code == 400


async def test_the_bin_pages(world, client):
    store = world["store"]
    for i in range(3):
        _seed(store, f"p{i}", local=f"loc-p{i}")
        await client.delete(f"/v1/sessions/p{i}")

    first = (await client.get("/v1/recycle-bin?page_size=2")).json()
    assert [i["sessionId"] for i in first["items"]] == ["p0", "p1"]
    assert first["page"]["hasMore"] is True
    rest = (await client.get(f"/v1/recycle-bin?page_size=2&page_token={first['page']['nextPageToken']}")).json()
    assert [i["sessionId"] for i in rest["items"]] == ["p2"]
    assert rest["page"]["hasMore"] is False


async def test_the_listing_purges_up_to_three_due_entries(world, client):
    store = world["store"]
    for i in range(4):
        _seed(store, f"d{i}", local=f"loc-d{i}", folder=f"fold-d{i}")
        await client.delete(f"/v1/sessions/d{i}")
        store._data["deleted_sessions"][f"d{i}"]["purgeAt"] = _now() - timedelta(hours=1)
    await client.delete("/v1/sessions/s1")

    body = (await client.get("/v1/recycle-bin")).json()

    assert world["drive_deleted"] == ["fold-d0", "fold-d1", "fold-d2"]
    assert [e["sessionId"] for e in body["erased"]] == ["d0", "d1", "d2"]
    assert [i["sessionId"] for i in body["items"]] == ["d3", "s1"]
    reasons = [r["detail"]["reason"] for r in world["audit"] if r["action"] == "SESSION_PURGE"]
    assert reasons == ["expired"] * 3


async def test_a_purge_that_fails_on_the_listing_leaves_the_entry_listed(world, client, monkeypatch):
    await client.delete("/v1/sessions/s1")
    world["store"]._data["deleted_sessions"]["s1"]["purgeAt"] = _now() - timedelta(hours=1)

    def drive_down(token, fid):
        raise requests.ConnectionError("down")

    monkeypatch.setattr(drive, "delete_file", drive_down)
    r = await client.get("/v1/recycle-bin")
    assert r.status_code == 200
    assert [i["sessionId"] for i in r.json()["items"]] == ["s1"]
    assert world["store"]._data["deleted_sessions"]["s1"]["state"] == "binned"


# ------------------------------------------------------------------ restore


async def test_a_restore_puts_the_session_back_as_it_was(world, client):
    store = world["store"]
    before = dict(store._data["sessions"]["s1"])
    await client.delete("/v1/sessions/s1")

    r = await client.post("/v1/recycle-bin/s1/restore")

    assert r.status_code == 200, r.text
    assert r.json() == {"sessionId": "s1", "localSessionId": "loc-1",
                        "status": statuses.SESSION_COMPLETED, "restored": True}
    assert "s1" not in store._data.get("deleted_sessions", {})
    restored = store._data["sessions"]["s1"]
    assert not set(repo.TOMBSTONE_FIELDS) & set(restored)
    assert {k: restored[k] for k in before if k != "updatedAt"} == {
        k: v for k, v in before.items() if k != "updatedAt"}
    assert not any(f.get("binnedAt") for f in _files_of(store, "s1").values())
    assert [s["sessionId"] for s in (await client.get("/v1/sessions")).json()["sessions"]] == ["s1"]
    [row] = [a for a in world["audit"] if a["action"] == "SESSION_BIN_RESTORE"]
    assert row["detail"] == {"localSessionId": "loc-1", "app": "semper"}


async def test_a_restore_keeps_the_status_it_had(world, client):
    _seed(world["store"], "s-up", local="loc-up", status=statuses.SESSION_UPLOADING)
    await client.delete("/v1/sessions/s-up")
    await client.post("/v1/recycle-bin/s-up/restore")
    assert world["store"]._data["sessions"]["s-up"]["status"] == statuses.SESSION_UPLOADING


async def test_a_restored_file_completes_and_downloads_again(world, client, monkeypatch):
    store = world["store"]
    file_id = next(iter(_files_of(store, "s1")))
    await client.delete("/v1/sessions/s1")
    await client.post("/v1/recycle-bin/s1/restore")

    class _Download:
        status_code = 200
        headers = {"Content-Length": "10"}

        def iter_chunks(self):
            yield b"0123456789"

    monkeypatch.setattr(drive, "open_download", lambda *a, **k: _Download())
    r = await client.get(f"/v1/files/{file_id}/content")
    assert r.status_code == 200, r.text


async def test_a_repeated_restore_answers_the_same_and_unflags_the_files(world, client):
    """A restore that stopped after the move but before the unflag is
    finished by its retry."""
    store = world["store"]
    await client.delete("/v1/sessions/s1")
    first = (await client.post("/v1/recycle-bin/s1/restore")).json()
    for f in _files_of(store, "s1").values():
        f["binnedAt"] = _now()

    again = await client.post("/v1/recycle-bin/s1/restore")

    assert again.status_code == 200
    assert again.json() == first
    assert not any(f.get("binnedAt") for f in _files_of(store, "s1").values())
    assert [a["action"] for a in world["audit"]].count("SESSION_BIN_RESTORE") == 1


async def test_a_restore_past_its_purge_date_is_refused(world, client, monkeypatch):
    """The job can run a day late; the entry is gone as far as the holder is
    concerned, whether or not there would be room for it."""
    await client.delete("/v1/sessions/s1")
    world["store"]._data["deleted_sessions"]["s1"]["purgeAt"] = _now() - timedelta(minutes=1)
    _quota(monkeypatch, maxSessions=0)
    r = await client.post("/v1/recycle-bin/s1/restore")
    assert (r.status_code, r.json()["detail"]) == (404, errors.SESSION_NOT_FOUND)
    assert "s1" not in world["store"]._data["sessions"]


def test_the_restore_transaction_refuses_past_the_date_too(world):
    """The service checks first; the transaction checks again, so a restore
    racing the date cannot slip through between the two reads."""
    bin_service.bin_session({"uid": UID}, repo.get_session("s1"), DEVICE, "semper")
    world["store"]._data["deleted_sessions"]["s1"]["purgeAt"] = _now() - timedelta(seconds=1)
    with pytest.raises(errors.Refusal) as refused:
        repo.restore_from_bin("s1", UID, "semper")
    assert refused.value.code == errors.SESSION_NOT_FOUND


@pytest.mark.parametrize("case", ["missing", "another account", "another app", "purging", "erased"])
async def test_a_restore_of_what_is_not_the_callers_binned_entry_is_not_found(world, client, case):
    store = world["store"]
    headers = {}
    if case == "another account":
        _seed(store, "s-x", uid="someone-else", local="loc-x")
        bin_service.bin_session({"uid": "someone-else"}, repo.get_session("s-x"), "dev-x", "semper")
        sid = "s-x"
    else:
        sid = "s1"
        if case != "missing":
            await client.delete("/v1/sessions/s1")
        else:
            del store._data["sessions"]["s1"]
        if case == "another app":
            headers = MT_HEADER
        elif case == "purging":
            store._data["deleted_sessions"]["s1"].update(state="purging", purgingAt=_now())
        elif case == "erased":
            await client.delete("/v1/recycle-bin/s1")

    r = await client.post(f"/v1/recycle-bin/{sid}/restore", headers=headers)

    assert (r.status_code, r.json()["detail"]) == (404, errors.SESSION_NOT_FOUND)
    assert sid not in store._data.get("sessions", {})


async def test_a_restore_needs_room_under_the_quota(world, client, monkeypatch):
    """Binned analyses count toward no cap, so the place freed by the delete
    may have been taken; the restore is then refused as a new backup is."""
    store = world["store"]
    await client.delete("/v1/sessions/s1")
    _seed(store, "s-new", local="loc-new")
    _quota(monkeypatch, maxSessions=1)

    r = await client.post("/v1/recycle-bin/s1/restore")

    assert r.status_code == 409, r.text
    assert r.json()["detail"].startswith(f"{errors.SESSION_QUOTA_EXCEEDED}: 1/1")
    assert store._data["deleted_sessions"]["s1"]["state"] == "binned"
    assert "s1" not in store._data["sessions"]


async def test_a_demo_restore_needs_room_on_the_device(world, client, monkeypatch):
    store = world["store"]
    await client.delete("/v1/sessions/s1")
    _seed(store, "s-theirs", uid="someone-else", local="loc-t")  # same phone, another account
    _quota(monkeypatch, mode="demo", maxSessions=1)

    r = await client.post("/v1/recycle-bin/s1/restore")

    assert r.status_code == 409, r.text
    assert r.json()["detail"].startswith(errors.DEVICE_QUOTA_EXCEEDED)


def test_a_released_phone_is_forgotten_by_the_bin_too(world):
    """A staff release drops the phone from the account's sessions so its
    next holder's demo cap starts clean (TD-276). A binned one comes back on
    restore with the `deviceId` it was binned with, so it is dropped too."""
    store = world["store"]
    bin_service.bin_session({"uid": UID}, repo.get_session("s1"), DEVICE, "semper")
    _seed(store, "s2", local="loc-2")

    assert repo.forget_device_sessions(UID, [DEVICE]) == 2
    assert "deviceId" not in store._data["deleted_sessions"]["s1"]
    repo.restore_from_bin("s1", UID, "semper")
    assert repo.count_device_sessions(DEVICE) == 0


# ------------------------------------------------------------------ delete for good


async def test_a_permanent_delete_erases_drive_files_and_leaves_a_marker(world, client):
    store = world["store"]
    await client.delete("/v1/sessions/s1")

    r = await client.delete("/v1/recycle-bin/s1")

    assert r.status_code == 200, r.text
    assert r.json() == {"sessionId": "s1", "localSessionId": "loc-1", "filesRemoved": 2,
                        "erased": True}
    assert world["drive_deleted"] == ["fold-1"]
    assert _files_of(store, "s1") == {}
    marker = store._data["deleted_sessions"]["s1"]
    assert set(marker) == {"state", "uid", "app", "localSessionId", "erasedAt", "erasedReason",
                           "expireAt"}
    assert (marker["state"], marker["uid"], marker["app"], marker["localSessionId"],
            marker["erasedReason"]) == ("erased", UID, "semper", "loc-1", "user")
    assert marker["expireAt"] - marker["erasedAt"] == timedelta(days=settings.ERASED_MARKER_DAYS)
    [row] = [a for a in world["audit"] if a["action"] == "SESSION_PURGE"]
    assert (row["uid"], row["deviceId"]) == (UID, DEVICE)
    assert row["detail"] == {"reason": "user", "filesRemoved": 2, "localSessionId": "loc-1"}


async def test_a_repeated_permanent_delete_answers_the_same(world, client):
    await client.delete("/v1/sessions/s1")
    first = (await client.delete("/v1/recycle-bin/s1")).json()
    again = await client.delete("/v1/recycle-bin/s1")
    assert again.status_code == 200
    assert again.json() == {**first, "filesRemoved": 0}
    assert world["drive_deleted"] == ["fold-1"]


async def test_a_drive_failure_leaves_the_entry_in_the_bin(world, client, monkeypatch):
    store = world["store"]
    await client.delete("/v1/sessions/s1")

    def drive_5xx(token, fid):
        resp = requests.Response()
        resp.status_code = 503
        raise requests.HTTPError(response=resp)

    monkeypatch.setattr(drive, "delete_file", drive_5xx)
    r = await client.delete("/v1/recycle-bin/s1")

    assert (r.status_code, r.json()["detail"]) == (502, errors.DRIVE_WRITE_FAILED)
    tomb = store._data["deleted_sessions"]["s1"]
    assert tomb["state"] == "binned" and "purgingAt" not in tomb
    assert len(_files_of(store, "s1")) == 2
    assert "SESSION_PURGE" not in _actions(world)


async def test_a_drive_permission_failure_is_a_502_too(world, client, monkeypatch):
    await client.delete("/v1/sessions/s1")

    def refused(token, fid):
        raise PermissionError("organizer rights")

    monkeypatch.setattr(drive, "delete_file", refused)
    r = await client.delete("/v1/recycle-bin/s1")
    assert (r.status_code, r.json()["detail"]) == (502, errors.DRIVE_WRITE_FAILED)
    assert world["store"]._data["deleted_sessions"]["s1"]["state"] == "binned"


async def test_a_drive_token_failure_is_a_502_too(world, client, monkeypatch):
    from google.auth import exceptions as google_auth_errors

    await client.delete("/v1/sessions/s1")

    def no_token():
        raise google_auth_errors.RefreshError("metadata server down")

    monkeypatch.setattr(drive, "access_token", no_token)
    r = await client.delete("/v1/recycle-bin/s1")
    assert (r.status_code, r.json()["detail"]) == (502, errors.DRIVE_WRITE_FAILED)
    assert world["store"]._data["deleted_sessions"]["s1"]["state"] == "binned"


async def test_a_permanent_delete_of_what_is_not_the_callers_is_not_found(world, client):
    _seed(world["store"], "s-x", uid="someone-else", local="loc-x")
    bin_service.bin_session({"uid": "someone-else"}, repo.get_session("s-x"), "dev-x", "semper")
    for sid, headers in (("s-x", {}), ("s-none", {})):
        r = await client.delete(f"/v1/recycle-bin/{sid}", headers=headers)
        assert (r.status_code, r.json()["detail"]) == (404, errors.SESSION_NOT_FOUND)
    await client.delete("/v1/sessions/s1")
    r = await client.delete("/v1/recycle-bin/s1", headers=MT_HEADER)
    assert r.status_code == 404
    assert world["drive_deleted"] == []


async def test_a_permanent_delete_while_another_purge_runs_asks_for_a_retry(world, client):
    await client.delete("/v1/sessions/s1")
    world["store"]._data["deleted_sessions"]["s1"].update(state="purging", purgingAt=_now())
    r = await client.delete("/v1/recycle-bin/s1")
    assert (r.status_code, r.json()["detail"]) == (503, errors.CLAIM_CONTENDED)
    assert world["drive_deleted"] == []


def test_a_purge_that_died_is_taken_over_after_the_lease(world):
    store = world["store"]
    bin_service.bin_session({"uid": UID}, repo.get_session("s1"), DEVICE, "semper")
    tomb = store._data["deleted_sessions"]["s1"]
    tomb.update(state="purging", purgingAt=_now() - repo.PURGE_LEASE + timedelta(minutes=1))
    assert repo.claim_purge("s1") is None
    tomb["purgingAt"] = _now() - repo.PURGE_LEASE - timedelta(minutes=1)
    assert repo.claim_purge("s1")["state"] == "purging"


# ------------------------------------------------------------------ the daily job


async def test_the_job_purges_what_is_due_and_nothing_else(world, client):
    store = world["store"]
    _seed(store, "s-due", local="loc-due", folder="fold-due")
    _seed(store, "s-theirs", uid="someone-else", local="loc-t", folder="fold-t")
    _seed(store, "s-gone", local="loc-gone", folder="fold-gone")
    for sid in ("s1", "s-due", "s-gone"):
        await client.delete(f"/v1/sessions/{sid}")
    bin_service.bin_session({"uid": "someone-else"}, repo.get_session("s-theirs"), "dev-x", "semper")
    await client.delete("/v1/recycle-bin/s-gone")  # an erased marker: not due again
    for sid in ("s-due", "s-theirs"):
        store._data["deleted_sessions"][sid]["purgeAt"] = _now() - timedelta(hours=2)
    world["drive_deleted"].clear()

    r = await client.post("/v1/tasks/recycle-bin-purge")

    assert r.status_code == 200, r.text
    assert r.json() == {"due": 2, "purged": ["s-due", "s-theirs"], "failed": []}
    assert sorted(world["drive_deleted"]) == ["fold-due", "fold-t"]
    assert store._data["deleted_sessions"]["s1"]["state"] == "binned"
    assert store._data["deleted_sessions"]["s-theirs"]["erasedReason"] == "expired"
    rows = {r["target"]["id"]: r for r in world["audit"] if r["action"] == "SESSION_PURGE"
            and r["detail"]["reason"] == "expired"}
    assert rows["s-theirs"]["uid"] == "someone-else"


async def test_one_failed_purge_does_not_stop_the_rest(world, client, monkeypatch, caplog):
    store = world["store"]
    _seed(store, "s2", local="loc-2", folder="fold-2")
    for sid in ("s1", "s2"):
        await client.delete(f"/v1/sessions/{sid}")
        store._data["deleted_sessions"][sid]["purgeAt"] = _now() - timedelta(hours=1)
    gone = []

    def flaky(token, fid):
        if fid == "fold-1":
            raise requests.ConnectionError("drive down")
        gone.append(fid)

    monkeypatch.setattr(drive, "delete_file", flaky)
    report = (await client.post("/v1/tasks/recycle-bin-purge")).json()

    assert report == {"due": 2, "purged": ["s2"], "failed": ["s1"]}
    assert "recycle_bin_purge_failed" in caplog.text
    assert store._data["deleted_sessions"]["s1"]["state"] == "binned"
    assert [e["sessionId"] for e in repo.due_for_purge(10)] == ["s1"]


async def test_the_job_takes_one_batch_a_run(world, client, monkeypatch):
    store = world["store"]
    for i in range(3):
        _seed(store, f"b{i}", local=f"loc-b{i}")
        await client.delete(f"/v1/sessions/b{i}")
        store._data["deleted_sessions"][f"b{i}"]["purgeAt"] = _now() - timedelta(hours=3 - i)
    monkeypatch.setattr(settings, "RECYCLE_BIN_PURGE_BATCH", 2)
    report = (await client.post("/v1/tasks/recycle-bin-purge")).json()
    assert report == {"due": 2, "purged": ["b0", "b1"], "failed": []}


async def test_the_job_takes_the_task_invoker_only(world, client, monkeypatch):
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    r = await client.post("/v1/tasks/recycle-bin-purge",
                          headers={"Authorization": "Bearer user-id-token"})
    assert r.status_code == 401
    assert r.json()["detail"] == errors.INVALID_TASK_TOKEN


# ------------------------------------------------------------------ erasure and export


def test_account_erasure_removes_the_bin(world):
    store = world["store"]
    _seed(store, "s2", local="loc-2")
    bin_service.bin_session({"uid": UID}, repo.get_session("s1"), DEVICE, "semper")
    bin_service.bin_session({"uid": UID}, repo.get_session("s2"), DEVICE, "semper")
    bin_service.purge_permanently({"uid": UID}, "s2", DEVICE, "semper")
    _seed(store, "s-x", uid="someone-else", local="loc-x")
    bin_service.bin_session({"uid": "someone-else"}, repo.get_session("s-x"), "dev-x", "semper")

    counts = repo.delete_all_user_data(UID)

    assert counts["binnedSessions"] == 2
    assert set(store._data["deleted_sessions"]) == {"s-x"}
    assert _files_of(store, "s1") == {}


def test_the_erasure_fallback_deletes_the_binned_folders_too(world, monkeypatch):
    """No stored user folder: each session's folder is deleted by its own
    pointer, the binned ones' included (they stay in Drive until the purge)."""
    store = world["store"]
    _seed(store, "s2", local="loc-2", folder="fold-2")
    bin_service.bin_session({"uid": UID}, repo.get_session("s1"), DEVICE, "semper")
    asked = []
    monkeypatch.setattr(drive, "delete_files", lambda token, ids: asked.extend(ids) or len(ids))
    monkeypatch.setattr(drive, "find_user_folders", lambda *a, **k: [])
    monkeypatch.setattr(drive, "probe_files", lambda token, ids, **k: {i: drive.ALIVE for i in ids if i})

    account_service._purge_drive("tok", {"uid": UID})

    assert sorted(asked) == ["fold-1", "fold-2"]


async def test_the_export_lists_the_bin_before_complete(world, client):
    store = world["store"]
    _seed(store, "s2", local="loc-2")
    _seed(store, "s3", local="loc-3")
    await client.delete("/v1/sessions/s1")
    await client.delete("/v1/sessions/s2")
    await client.delete("/v1/recycle-bin/s2")

    r = await client.get("/v1/me/export")

    assert r.status_code == 200, r.text
    body = json.loads(r.text)
    assert body["schema"] == "indic.account.export/1"
    assert [s["sessionId"] for s in body["sessions"]] == ["s3"]
    [binned] = body["recycleBin"]
    assert (binned["sessionId"], binned["status"], binned["localSessionId"]) == (
        "s1", statuses.SESSION_COMPLETED, "loc-1")
    assert binned["deletedAt"] and binned["purgeAt"]
    assert sorted(f["fileId"] for f in binned["files"]) == sorted(_files_of(store, "s1"))
    assert all("driveFileId" not in f for f in binned["files"])
    assert [(e["sessionId"], e["localSessionId"]) for e in body["erasedBackups"]] == [("s2", "loc-2")]
    assert list(body)[-1] == "complete" and body["complete"] is True


# ------------------------------------------------------------------ the repo's edges


def test_moving_what_is_gone_or_another_accounts_moves_nothing(world):
    _seed(world["store"], "s-x", uid="someone-else", local="loc-x")
    assert repo.move_to_bin("s-none", UID, device_id=DEVICE, app="semper", files_binned=0) is None
    assert repo.move_to_bin("s-x", UID, device_id=DEVICE, app="semper", files_binned=0) is None
    assert "s-x" in world["store"]._data["sessions"]
    assert world["store"]._data.get("deleted_sessions", {}) == {}


def _contended(monkeypatch):
    """Every transaction write loses the race, as a commit that kept aborting."""
    import fake_firestore
    from google.api_core.exceptions import Aborted

    def aborted(*_a, **_k):
        raise Aborted("contention")

    monkeypatch.setattr(fake_firestore._Transaction, "set", aborted)
    monkeypatch.setattr(fake_firestore._Transaction, "update", aborted)


async def test_a_bin_that_keeps_losing_the_race_moves_nothing(world, client, monkeypatch):
    _contended(monkeypatch)
    r = await client.delete("/v1/sessions/s1")
    assert (r.status_code, r.json()["detail"]) == (404, errors.SESSION_NOT_FOUND)
    assert "s1" in world["store"]._data["sessions"]


async def test_a_restore_that_keeps_losing_the_race_asks_for_a_retry(world, client, monkeypatch):
    await client.delete("/v1/sessions/s1")
    _contended(monkeypatch)
    r = await client.post("/v1/recycle-bin/s1/restore")
    assert (r.status_code, r.json()["detail"]) == (503, errors.CLAIM_CONTENDED)
    assert world["store"]._data["deleted_sessions"]["s1"]["state"] == "binned"


def test_a_bin_that_lost_to_a_delete_of_the_same_session_answers_as_it(world, monkeypatch):
    """Two deletes of one session at once: the loser's transaction finds it
    binned already and answers the winner's entry."""
    store = world["store"]
    _seed(store, "s2", local="loc-2")
    live = dict(store._data["sessions"]["s2"])
    bin_service.bin_session({"uid": UID}, repo.get_session("s2"), DEVICE, "semper")
    store._data["sessions"]["s2"] = live  # the loser read it before the winner moved it
    _contended(monkeypatch)
    assert repo.move_to_bin("s2", UID, device_id=DEVICE, app="semper", files_binned=0)["state"] == "binned"


def test_the_upload_guard_finds_only_a_binned_entry(world):
    assert repo.find_binned_session(UID, "") is None
    assert repo.find_binned_session(UID, "loc-1") is None
    bin_service.bin_session({"uid": UID}, repo.get_session("s1"), DEVICE, "semper")
    assert repo.find_binned_session(UID, "loc-1")["sessionId"] == "s1"
    assert repo.find_binned_session("someone-else", "loc-1") is None


def test_a_purge_claims_only_the_callers_binned_entry(world):
    bin_service.bin_session({"uid": UID}, repo.get_session("s1"), DEVICE, "semper")
    assert repo.claim_purge("s-none") is None
    assert repo.claim_purge("s1", "someone-else") is None
    assert repo.claim_purge("s1", UID)["state"] == "purging"
    repo.erase_binned("s1", repo.get_binned_session("s1"), repo.ERASED_BY_USER)
    assert repo.claim_purge("s1") is None
