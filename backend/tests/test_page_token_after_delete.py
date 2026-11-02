"""A page token resumes after its id even when that document is gone (review
L3-6 / L10-6).

The token is the id of the last document a page returned. The cursor used to
be that document's snapshot, so a token whose document was deleted between
pages restarted from the first page: the export repeated every session before
it, and `?verify=true`, which purges the orphans on the page it lists (the
last one included), handed the app the same page again. In document-id order
the id is the whole cursor, and no document is needed behind it.
"""
import pytest

from app import audit, drive, statuses
import repo_view as repo

DEV_UID = "dev-user"  # deps._DEV_USER in DEV_INSECURE_AUTH mode
_IDS = ("s1", "s2", "s3", "s4", "s5")


def _session(sid: str) -> dict:
    return {"uid": DEV_UID, "status": statuses.SESSION_COMPLETED, "localSessionId": f"l-{sid}",
            "fileCount": 1, "completedCount": 1, "totalBytes": 1,
            "driveFolderId": f"fold-{sid}"}


@pytest.fixture
def sessions(store, monkeypatch):
    monkeypatch.setattr(audit, "record", lambda *a, **k: None)
    store._data["sessions"] = {sid: _session(sid) for sid in _IDS}
    return store


@pytest.mark.parametrize("app", [None, "semper"], ids=["whole-account", "one-app"])
def test_a_token_for_a_deleted_session_resumes_after_it(sessions, app):
    """Both listings: the whole account (`_cursor_page`) and one app's (`_scan`)."""
    page, token = repo.list_user_sessions(DEV_UID, limit=2, app=app)
    assert [s["sessionId"] for s in page] == ["s1", "s2"] and token == "s2"  # noqa: S105

    del sessions._data["sessions"]["s2"]
    page, token = repo.list_user_sessions(DEV_UID, limit=2, page_token=token, app=app)

    assert [s["sessionId"] for s in page] == ["s3", "s4"]


def test_the_export_lists_each_session_once_when_one_goes_mid_walk(sessions):
    walk = repo.iter_all_user_sessions(DEV_UID, page_size=2)
    seen = [next(walk)["sessionId"], next(walk)["sessionId"]]

    # Deleted while the export holds a token naming it.
    del sessions._data["sessions"]["s2"]
    seen += [s["sessionId"] for s in walk]

    assert seen == list(_IDS)


@pytest.mark.parametrize("app", ["", "&app=all"], ids=["asking-app", "whole-account"])
async def test_verify_purging_the_pages_last_session_does_not_repeat_the_page(
    sessions, client, monkeypatch, app,
):
    """The purge deletes the document the token names; the next page still
    starts after it."""
    monkeypatch.setattr(drive, "access_token", lambda: "tok")
    monkeypatch.setattr(drive, "file_exists", lambda token, fid, **_: fid != "fold-s2")

    first = (await client.get(f"/v1/sessions?verify=true&page_size=2{app}")).json()
    assert first["verify"]["purged"] == 1
    assert "s2" not in sessions._data["sessions"]
    token = first["page"]["nextPageToken"]
    assert token == "s2"  # noqa: S105 - a page cursor

    second = (await client.get(f"/v1/sessions?page_size=2&page_token={token}{app}")).json()

    assert [s["sessionId"] for s in second["sessions"]] == ["s3", "s4"]
