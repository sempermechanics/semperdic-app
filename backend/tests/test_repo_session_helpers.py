"""Session and account repo helpers no other test names: what each one reads,
and what it leaves in the store."""
from datetime import datetime

import pytest
from google.api_core.exceptions import NotFound

import fake_firestore
from app import statuses
from app.models import FileComplete
import repo_view as repo


def _file(sid, uid="u1", **kw):
    return {"sessionId": sid, "uid": uid, "role": "raw", "name": "a.png",
            "sizeBytes": 10, "status": statuses.FILE_PENDING, "uploadUrl": None, **kw}


# ------------------------------------------------------ replace_file_content

def test_replace_file_content_records_the_new_bytes_and_moves_the_session(store):
    store._data["sessions"] = {"s1": {"uid": "u1", "updatedAt": "old"}}
    fid = repo.metadata_file_id("s1")
    store._data["files"] = {
        fid: _file("s1", role="metadata", name="metadata.json", sizeBytes=5,
                   sha256="old-sha", driveMd5="old-md5", status=statuses.FILE_COMPLETED,
                   driveFileId="drv-1"),
        "s1_raw_a.png": _file("s1", sha256="untouched"),
    }

    repo.replace_file_content("s1", fid, 42, "new-sha", "new-md5")

    f = store._data["files"][fid]
    assert (f["sizeBytes"], f["sha256"], f["driveMd5"]) == (42, "new-sha", "new-md5")
    assert isinstance(f["updatedAt"], datetime)
    # What did not change: the file's identity and pointer, and its sibling.
    assert f["driveFileId"] == "drv-1" and f["status"] == statuses.FILE_COMPLETED
    assert store._data["files"]["s1_raw_a.png"]["sha256"] == "untouched"
    assert isinstance(store._data["sessions"]["s1"]["updatedAt"], datetime)


def test_replace_file_content_keeps_a_missing_drive_md5_as_none(store):
    store._data["sessions"] = {"s1": {"uid": "u1"}}
    store._data["files"] = {"f": _file("s1", driveMd5="stale")}
    repo.replace_file_content("s1", "f", 1, "sha", None)
    assert store._data["files"]["f"]["driveMd5"] is None


def test_replace_file_content_of_a_missing_file_raises_not_found(store):
    store._data["sessions"] = {"s1": {"uid": "u1", "updatedAt": "old"}}
    with pytest.raises(NotFound):
        repo.replace_file_content("s1", "nope", 1, "sha", None)
    assert store._data["sessions"]["s1"]["updatedAt"] == "old"  # nothing half-written


# ------------------------------------------------------------- upload_target

def test_upload_target_is_the_manifest_entry_the_app_reads(store):
    assert repo.upload_target("f1", "https://up/1", {"name": "a.png", "role": "raw",
                                                     "sizeBytes": 7, "sha256": "x"}) == {
        "fileId": "f1", "uploadUrl": "https://up/1", "chunkSize": 32 * 1024 * 1024,
        "name": "a.png", "role": "raw", "sizeBytes": 7,
    }


def test_upload_target_defaults_a_missing_size_to_zero(store):
    out = repo.upload_target("f1", "u", {})
    assert out["sizeBytes"] == 0 and out["name"] is None and out["role"] is None


# --------------------------------------------------- iter_unprovisioned_files

def test_iter_unprovisioned_files_yields_only_files_with_no_uri(store):
    store._data["files"] = {
        "s1_a": _file("s1", name="a"),
        "s1_b": _file("s1", name="b", uploadUrl="https://up/b"),
        "s1_c": _file("s1", name="c", status=statuses.FILE_COMPLETED),
        "s1_d": _file("s1", name="d", uploadUrl=""),
        "s2_a": _file("s2", name="other-session"),
    }
    out = list(repo.iter_unprovisioned_files("s1"))
    assert [f["fileId"] for f in out] == ["s1_a", "s1_d"]
    assert out[0] == {"fileId": "s1_a", "name": "a", "role": "raw", "sizeBytes": 10}


def test_iter_unprovisioned_files_reads_past_one_batch(store):
    """More files than one Firestore page: every one is yielded, once."""
    n = 2 * 400 + 3
    store._data["files"] = {f"s1_{i:04d}": _file("s1") for i in range(n)}
    ids = [f["fileId"] for f in repo.iter_unprovisioned_files("s1")]
    assert len(ids) == n and len(set(ids)) == n


def test_iter_unprovisioned_files_of_an_unknown_session_is_empty(store):
    assert list(repo.iter_unprovisioned_files("nope")) == []


def test_provisioning_resumes_where_it_stopped(store):
    store._data["files"] = {f"s1_{i}": _file("s1") for i in range(4)}
    repo.set_file_upload_urls([("s1_0", "https://up/0"), ("s1_2", "https://up/2")])
    assert [f["fileId"] for f in repo.iter_unprovisioned_files("s1")] == ["s1_1", "s1_3"]


# --------------------------------------------------- find_incomplete_session

def _session(uid, local, status):
    return {"uid": uid, "localSessionId": local, "status": status}


@pytest.mark.parametrize("status", list(statuses.IN_FLIGHT_SESSION_STATUSES))
def test_find_incomplete_session_returns_the_in_flight_one(store, status):
    store._data["sessions"] = {"s1": _session("u1", "local-1", status)}
    found = repo.find_incomplete_session("u1", "local-1")
    assert found == {**store._data["sessions"]["s1"], "sessionId": "s1"}


@pytest.mark.parametrize("status", [statuses.SESSION_COMPLETED, statuses.SESSION_PROVISION_FAILED])
def test_find_incomplete_session_ignores_finished_and_failed(store, status):
    store._data["sessions"] = {"s1": _session("u1", "local-1", status)}
    assert repo.find_incomplete_session("u1", "local-1") is None


def test_find_incomplete_session_is_per_user_and_per_local_id(store):
    store._data["sessions"] = {
        "theirs": _session("u2", "local-1", statuses.SESSION_UPLOADING),
        "mine-other": _session("u1", "local-2", statuses.SESSION_UPLOADING),
    }
    assert repo.find_incomplete_session("u1", "local-1") is None


@pytest.mark.parametrize("local", ["", None])
def test_an_empty_local_session_id_never_matches(store, local):
    store._data["sessions"] = {"s1": _session("u1", "", statuses.SESSION_UPLOADING)}
    assert repo.find_incomplete_session("u1", local) is None


# ------------------------------------------------------ terms and consent

def test_record_terms_acceptance_stores_the_record_and_answers_json(store):
    store._data["users"] = {"u1": {"email": "a@b.c", "termsAccepted": {"version": "old"}}}
    out = repo.record_terms_acceptance("u1", "2026-09", "dev-1", "app")

    stored = store._data["users"]["u1"]["termsAccepted"]
    assert {k: stored[k] for k in ("version", "deviceId", "source")} == {
        "version": "2026-09", "deviceId": "dev-1", "source": "app"}
    assert stored["acceptedAt"] is fake_firestore.SERVER_TIMESTAMP  # the server clock stamps it
    assert out["version"] == "2026-09" and out["source"] == "app"
    assert datetime.fromisoformat(out["acceptedAt"]).tzinfo is not None
    assert store._data["users"]["u1"]["email"] == "a@b.c"


def test_record_terms_acceptance_for_no_account_raises(store):
    with pytest.raises(NotFound):
        repo.record_terms_acceptance("ghost", "v", None, "console")
    assert "ghost" not in store._data.get("users", {})


@pytest.mark.parametrize("granted, expected", [(True, True), (False, False), (1, True), (0, False)])
def test_record_improvement_consent_stores_a_bool(store, granted, expected):
    store._data["users"] = {"u1": {}}
    out = repo.record_improvement_consent("u1", granted, "2026-09", None, "console")
    stored = store._data["users"]["u1"]["improvementConsent"]
    assert stored["granted"] is expected and out["granted"] is expected
    assert stored["version"] == "2026-09" and stored["deviceId"] is None
    assert stored["source"] == "console" and stored["at"] is fake_firestore.SERVER_TIMESTAMP
    assert datetime.fromisoformat(out["at"]).tzinfo is not None


def test_withdrawing_consent_replaces_the_earlier_grant(store):
    store._data["users"] = {"u1": {}}
    repo.record_improvement_consent("u1", True, "v1", "d", "app")
    repo.record_improvement_consent("u1", False, "v2", "d", "app")
    stored = store._data["users"]["u1"]["improvementConsent"]
    assert stored["granted"] is False and stored["version"] == "v2"


# ------------------------------------------------------- list_user_devices

def test_list_user_devices_lists_only_this_accounts_devices(store):
    store._data["devices"] = {
        "d1": {"uid": "u1", "status": "ACTIVE", "model": "Pixel 6", "osVersion": "14",
               "appVersion": "2.0", "registeredAt": datetime(2026, 1, 2), "publicKey": "PEM"},
        "d2": {"uid": "u1", "status": "SUPERSEDED"},
        "d3": {"uid": "u2", "status": "ACTIVE"},
    }
    out = sorted(repo.list_user_devices("u1"), key=lambda d: d["deviceId"])
    assert [d["deviceId"] for d in out] == ["d1", "d2"]
    assert out[0] == {"deviceId": "d1", "status": "ACTIVE", "model": "Pixel 6",
                      "osVersion": "14", "appVersion": "2.0",
                      "registeredAt": "2026-01-02 00:00:00"}
    assert "publicKey" not in out[0]
    assert out[1]["model"] is None and out[1]["registeredAt"] == "None"


def test_list_user_devices_of_a_deviceless_account_is_empty(store):
    assert repo.list_user_devices("u1") == []


# ------------------------------------------- lost races and erased sessions

@pytest.fixture
def contended(monkeypatch):
    """Every transaction in the sessions module loses every attempt."""
    from app.repo import sessions
    monkeypatch.setattr(sessions, "_run_tx", lambda body, on_contended: on_contended())


def test_a_contended_status_write_still_lands_on_an_open_session(store, contended):
    store._data["sessions"] = {"s1": {"uid": "u1", "status": statuses.SESSION_PROVISIONING}}
    repo.set_session_status("s1", statuses.SESSION_UPLOADING)
    assert store._data["sessions"]["s1"]["status"] == statuses.SESSION_UPLOADING


def test_a_contended_status_write_never_reopens_a_completed_session(store, contended):
    store._data["sessions"] = {"s1": {"uid": "u1", "status": statuses.SESSION_COMPLETED}}
    repo.set_session_status("s1", statuses.SESSION_PROVISION_FAILED, "drive_error")
    assert store._data["sessions"]["s1"] == {"uid": "u1", "status": statuses.SESSION_COMPLETED}


def test_a_contended_status_write_on_an_erased_session_is_a_no_op(store, contended):
    repo.set_session_status("gone", statuses.SESSION_UPLOADING)
    assert "gone" not in store._data.get("sessions", {})


def test_a_new_session_status_on_an_erased_session_is_a_no_op(store):
    repo.set_new_session_status("gone", statuses.SESSION_UPLOADING)
    assert "gone" not in store._data.get("sessions", {})


def test_progress_on_an_erased_session_is_a_no_op(store):
    repo.bump_session_progress("gone")
    assert "gone" not in store._data.get("sessions", {})


def _completion(n=10):
    return FileComplete(sessionId="s1", driveFileId="drv-1", bytes=n)


def test_losing_the_completion_race_to_oneself_is_already(store, contended):
    store._data["files"] = {"f": _file("s1", status=statuses.FILE_COMPLETED)}
    assert repo.complete_file("f", "u1", _completion()) == repo.Completion.ALREADY


@pytest.mark.parametrize("files", [
    {},  # erased meanwhile
    {"f": _file("s1", uid="u2", status=statuses.FILE_COMPLETED)},  # not the caller's
    {"f": _file("s1")},  # still pending: nobody won
])
def test_losing_the_completion_race_otherwise_is_rejected(store, contended, files):
    store._data["files"] = files
    assert repo.complete_file("f", "u1", _completion()) == repo.Completion.REJECTED
