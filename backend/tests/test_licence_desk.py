"""The staff licence desk: listing, one-row reads, and what an edit or a
revoke costs the holders' documents."""
from datetime import datetime, timedelta, timezone

import pytest

import repo_view as repo
from license_helpers import _mint_individual, _mint_institution
from refusals import attempt

T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)


def _seed(store, doc_id, *, mode="licensed", status="unused", minutes=0, **extra):
    store._data.setdefault("licenses", {})[doc_id] = {
        "keyPrefix": f"SEMP-{doc_id[:4].upper()}", "kind": "individual", "mode": mode,
        "status": status, "createdAt": T0 + timedelta(minutes=minutes), **extra,
    }


def _ids(rows):
    return [row["id"] for row in rows]


def test_the_list_is_newest_first_without_demo_keys(store):
    _seed(store, "old1", minutes=1)
    _seed(store, "demo", mode="demo", status="redeemed", minutes=2)
    _seed(store, "new1", minutes=3)

    rows, token = repo.list_licenses(limit=10)

    assert _ids(rows) == ["new1", "old1"]
    assert token is None
    rows, _ = repo.list_licenses(limit=10, include_demo=True)
    assert _ids(rows) == ["new1", "demo", "old1"]


def test_hiding_revoked_licences_is_the_query_not_the_browser(store):
    _seed(store, "live", minutes=1)
    _seed(store, "gone", status="revoked", minutes=2)
    _seed(store, "inst", status="active", minutes=3)

    rows, _ = repo.list_licenses(limit=10, include_revoked=False)

    assert _ids(rows) == ["inst", "live"]


def test_pages_follow_the_creation_order(store):
    for i in range(5):
        _seed(store, f"lic{i}", minutes=i)

    first, token = repo.list_licenses(limit=2)
    second, token2 = repo.list_licenses(limit=2, page_token=token)
    third, token3 = repo.list_licenses(limit=2, page_token=token2)

    assert _ids(first) == ["lic4", "lic3"]
    assert _ids(second) == ["lic2", "lic1"]
    assert _ids(third) == ["lic0"]
    assert token3 is None


def test_search_finds_a_licence_on_any_page(store):
    for i in range(30):
        _seed(store, f"lic{i:02d}", minutes=i, emailLock=f"p{i}@lab.org")
    _seed(store, "inst", status="active", kind="institution", domainLock="uni.edu", minutes=40)
    _seed(store, "demo", mode="demo", status="redeemed", emailLock="p3@lab.org", minutes=50)

    assert _ids(repo.list_licenses(q="P3@Lab.org")[0]) == ["lic03"]
    assert _ids(repo.list_licenses(q="p3@lab.org", include_demo=True)[0]) == ["demo", "lic03"]
    assert _ids(repo.list_licenses(q="uni.edu")[0]) == ["inst"]
    store._data["licenses"]["lic07"]["keyPrefix"] = "SEMP-K7QX"
    assert _ids(repo.list_licenses(q="semp-k7qx")[0]) == ["lic07"]
    # The whole key typed in still finds it: only its prefix is stored.
    assert _ids(repo.list_licenses(q="SEMP-K7QX-AAAA-BBBB-CCCC")[0]) == ["lic07"]
    assert repo.list_licenses(q="nobody@lab.org")[0] == []


@pytest.mark.asyncio
async def test_list_route_passes_the_filters(store, client):
    _seed(store, "live", minutes=1, emailLock="a@b.com")
    _seed(store, "gone", status="revoked", minutes=2)
    _seed(store, "demo", mode="demo", status="redeemed", minutes=3)

    body = (await client.get("/v1/admin/licenses")).json()
    assert _ids(body["licenses"]) == ["gone", "live"]
    body = (await client.get("/v1/admin/licenses?include_revoked=false")).json()
    assert _ids(body["licenses"]) == ["live"]
    body = (await client.get("/v1/admin/licenses?include_demo=true")).json()
    assert _ids(body["licenses"]) == ["demo", "gone", "live"]
    body = (await client.get("/v1/admin/licenses?q=a@b.com")).json()
    assert _ids(body["licenses"]) == ["live"]
    assert body["page"]["hasMore"] is False


@pytest.mark.asyncio
async def test_one_licence_reads_like_a_row(store, client):
    minted = _mint_individual()
    license_id = minted["license"]["id"]

    resp = await client.get(f"/v1/admin/licenses/{license_id}")

    assert resp.status_code == 200, resp.text
    assert resp.json()["id"] == license_id
    assert resp.json()["emailLock"] == "solo@lab.org"
    missing = await client.get("/v1/admin/licenses/nope")
    assert missing.status_code == 404
    assert missing.json()["detail"] == "license_not_found"


def _two_seat_roster(store):
    store._data["users"] = {
        "u1": {"email": "a@university.edu", "emailVerified": True, "access_status": "APPROVED", "plan": "demo"},
        "u2": {"email": "b@university.edu", "emailVerified": True, "access_status": "APPROVED", "plan": "demo"},
    }
    minted = _mint_institution()
    for i, uid in enumerate(("u1", "u2")):
        err, _ = attempt(repo.activate_license, uid, store._data["users"][uid]["email"], f"dev-{i}",
                                       minted["key"])
        assert err == ""
    return minted["license"]["id"]


def _count_user_writes(monkeypatch):
    """How many user documents a call writes, however it batches them."""
    import fake_firestore

    written = []
    real = fake_firestore._DocRef.update

    def update(ref, patch, **kw):
        if ref._collection == "users":
            written.append(ref.id)
        return real(ref, patch, **kw)

    monkeypatch.setattr(fake_firestore._DocRef, "update", update)
    return written


def test_a_note_edit_writes_no_holder(store, monkeypatch):
    license_id = _two_seat_roster(store)
    written = _count_user_writes(monkeypatch)

    repo.update_license(license_id, {"note": "renewal due in March"}, "admin")
    repo.update_license(license_id, {"maxSeats": 50}, "admin")

    assert written == []
    assert store._data["licenses"][license_id]["note"] == "renewal due in March"


def test_a_term_edit_reaches_every_holder(store, monkeypatch):
    license_id = _two_seat_roster(store)
    written = _count_user_writes(monkeypatch)
    later = datetime.now(timezone.utc) + timedelta(days=400)

    repo.update_license(license_id, {"maxAnalyses": 300}, "admin")

    assert sorted(written) == ["u1", "u2"]
    for uid in ("u1", "u2"):
        assert store._data["users"][uid]["licenseMaxAnalyses"] == 300
    # A holder who has moved to another licence is left alone.
    store._data["users"]["u2"]["licenseId"] = "elsewhere"
    written.clear()
    repo.update_license(license_id, {"supportUntil": later, "maxAnalyses": 400}, "admin")
    assert written == ["u1"]
    assert store._data["users"]["u2"]["licenseMaxAnalyses"] == 300


def test_revoking_again_keeps_the_first_revoke_dates(store):
    license_id = _two_seat_roster(store)
    assert repo.revoke_institution_seat(license_id, "u1") is True
    seat_revoked_at = repo._seat_ref(license_id, "u1").get().to_dict()["revokedAt"]

    repo.revoke_license(license_id, "admin")
    licence_revoked_at = store._data["licenses"][license_id]["revokedAt"]
    repo.revoke_license(license_id, "admin2")

    # IT's earlier seat revoke is still dated when IT made it: reconciliation
    # reads that date, and the whole-licence revoke used to move it to now.
    assert repo._seat_ref(license_id, "u1").get().to_dict()["revokedAt"] == seat_revoked_at
    assert store._data["licenses"][license_id]["revokedAt"] == licence_revoked_at
    assert store._data["licenses"][license_id]["revokedByUid"] == "admin"
    assert store._data["users"]["u2"]["plan"] == "demo"


def test_revoke_repairs_a_seat_revoke_that_never_reached_its_holder(store, monkeypatch):
    license_id = _two_seat_roster(store)
    real_drop = repo._drop_user_to_demo_if_licensed
    repo.patch(monkeypatch, "_drop_user_to_demo_if_licensed", lambda *a, **k: None)
    assert repo.revoke_institution_seat(license_id, "u1") is True
    assert store._data["users"]["u1"]["plan"] == "professional"
    repo.patch(monkeypatch, "_drop_user_to_demo_if_licensed", real_drop)

    repo.revoke_license(license_id, "admin")

    assert store._data["users"]["u1"]["plan"] == "demo"


@pytest.mark.asyncio
async def test_mint_keeps_the_grace_and_support_it_was_given(store, client):
    support = "2027-06-30T00:00:00Z"
    resp = await client.post("/v1/admin/licenses", json={
        "emailLock": "grace@lab.org", "duration": "timed",
        "expiresAt": "2027-01-31T23:59:59Z", "graceDays": 3, "supportUntil": support,
    })
    assert resp.status_code == 200, resp.text
    stored = store._data["licenses"][resp.json()["license"]["id"]]
    assert stored["graceDays"] == 3
    assert stored["supportUntil"].isoformat().startswith("2027-06-30")

    resp = await client.post("/v1/admin/licenses", json={
        "kind": "institution", "domainLock": "uni.edu", "adminEmails": ["it@uni.edu"],
        "duration": "timed", "expiresAt": "2027-01-31T23:59:59Z", "graceDays": 0,
    })
    assert resp.status_code == 200, resp.text
    assert store._data["licenses"][resp.json()["license"]["id"]]["graceDays"] == 0
