"""The HTTP status every domain refusal reaches the wire with, route by route.

Each case stubs the repo call behind a route so that it refuses with one code,
and records the status and detail the client receives. The table is what the
routes answered before refusals had one status table (`errors.STATUS`), less
the two overrides TD-185 dropped: `license_revoked` is 403 and
`license_seat_disabled` 409 on every route, since no client read those
statuses. A change that moves any of these is a wire change.
"""
import pytest

from app import deps, errors
import repo_view as repo

INSTITUTION = {"kind": "institution", "status": "active", "mode": "licensed",
               "adminEmails": ["dev@local"], "keyPrefix": "SEMP-TEST"}


def _refusing(code):
    """A repo stand-in that refuses with `code`, whatever it is called with."""
    def refuse(*_a, **_k):
        raise errors.Refusal(code)
    return refuse


# (method, path, json body, repo function, code, expected status)
CASES = [
    # A key typed in the app.
    *[("POST", "/v1/licenses/activate", {"key": "SEMP-XXXX-XXXX"}, "activate_license", code, status)
      for code, status in [
          ("user_not_found", 404), ("license_not_found", 404), ("license_expired", 403),
          ("already_licensed", 409), ("license_revoked", 403), ("license_email_mismatch", 403),
          ("license_device_mismatch", 403), ("license_already_redeemed", 409),
          ("license_seats_exhausted", 409), ("license_seat_disabled", 409),
          ("claim_contended", 503),
      ]],
    # The floating-seat lease.
    *[("POST", "/v1/licenses/checkout", None, "checkout_lease", code, status)
      for code, status in [
          ("no_license", 404), ("license_not_found", 404), ("seating_not_floating", 409),
          ("license_revoked", 403), ("license_expired", 403), ("not_eligible", 403),
          ("no_floating_seat", 409),
      ]],
    *[("POST", "/v1/licenses/release", None, "release_lease", code, status)
      for code, status in [("no_license", 404), ("not_eligible", 403)]],
    # The holder's own device change.
    *[("POST", "/v1/licenses/unbind", None, "clear_device_lock", code, status)
      for code, status in [("license_not_found", 404), ("seat_not_found", 404)]],
    # Semper staff.
    *[("POST", "/v1/admin/device-releases", {"email": "a@b.org"}, "release_account_device",
       code, status)
      for code, status in [("user_not_found", 404), ("license_device_clear_required", 409)]],
    *[("PATCH", "/v1/admin/licenses/L1", {"clearDeviceLock": True}, "clear_device_lock",
       code, status)
      for code, status in [("license_not_found", 404), ("seat_not_found", 404)]],
    *[("PATCH", "/v1/admin/licenses/L1", {"note": "n"}, "update_license", code, 422)
      for code in ["expiry_in_past", "expiry_before_current", "license_perpetual",
                   "cap_on_demo_key", "institution_only", "floating_needs_max_seats",
                   "max_seats_below_used"]],
    *[("DELETE", "/v1/admin/licenses/L1", None, "delete_license", code, status)
      for code, status in [("license_not_found", 404), ("demo_key_not_deletable", 409)]],
    *[("POST", "/v1/admin/deleted-licenses/L1/restore", None, "restore_license", code, status)
      for code, status in [("deleted_license_not_found", 404), ("deleted_license_purged", 410),
                           ("license_exists", 409)]],
    *[("POST", "/v1/admin/licenses/L1/convert",
       {"domainLock": "uni.edu", "adminEmails": ["it@uni.edu"]}, "convert_to_institution",
       code, status)
      for code, status in [
          ("license_not_found", 404), ("license_revoked", 403), ("license_not_convertible", 409),
          ("convert_domain_mismatch", 422), ("claim_contended", 503),
          ("license_seats_exhausted", 409),
      ]],
    *[("PATCH", "/v1/admin/licenses/L1/seats/u1/device", None, "clear_device_lock", code, status)
      for code, status in [("license_not_found", 404), ("seat_not_found", 404)]],
    *[("GET", "/v1/admin/licenses/L1/reconcile", None, "reconcile_institution_seats",
       code, status)
      for code, status in [("license_not_found", 404), ("kind_not_institution", 400)]],
    # Institution IT.
    *[("POST", "/v1/institutions/licenses/L1/seats", {"email": "m@uni.edu"},
       "add_institution_member", code, status)
      for code, status in [
          ("license_not_found", 404), ("user_not_found", 404), ("member_already_licensed", 409),
          ("invalid_email", 400), ("invite_exists", 409), ("license_revoked", 403),
          ("license_seats_exhausted", 409), ("license_seat_disabled", 409),
          ("license_device_mismatch", 403), ("claim_contended", 503),
      ]],
    *[("PATCH", "/v1/institutions/licenses/L1/seats/u1", {"enabled": False},
       "set_seat_enabled", code, status)
      for code, status in [("seat_not_found", 404), ("seat_revoked", 409),
                           ("license_revoked", 403), ("seat_busy", 409)]],
    ("PATCH", "/v1/institutions/licenses/L1/seats/u1", {"clearDeviceLock": True},
     "clear_device_lock", "seat_not_found", 404),
]


@pytest.fixture
def staged(store, monkeypatch):
    """The dev caller holds L1, administers it, and the routes reach the repo."""
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "licenseId": "L1"})
    store._data["licenses"] = {"L1": dict(INSTITUTION)}
    repo.patch(monkeypatch, "find_user_by_email", lambda _e: {"uid": "u1", "email": "a@b.org"})
    return store


@pytest.mark.asyncio
@pytest.mark.parametrize("method,path,body,fn,code,status", CASES,
                         ids=[f"{c[0]} {c[1]} {c[4]}" for c in CASES])
async def test_a_refusal_keeps_its_status(staged, client, monkeypatch,
                                          method, path, body, fn, code, status):
    repo.patch(monkeypatch, fn, _refusing(code))
    resp = await client.request(method, path, json=body, headers={"X-Device-Id": "dev-device"})
    assert (resp.status_code, resp.json()["detail"]) == (status, code)


@pytest.mark.asyncio
async def test_a_device_change_too_soon_says_when(staged, client, monkeypatch):
    """429 with the instant after the code, and a Retry-After to match."""
    from datetime import datetime, timedelta, timezone

    when = datetime.now(timezone.utc) + timedelta(days=3)

    def too_soon(*_a, **_k):
        raise errors.Refusal(errors.DEVICE_CHANGE_TOO_SOON, when.isoformat(), retry_at=when)

    repo.patch(monkeypatch, "clear_device_lock", too_soon)
    resp = await client.post("/v1/licenses/unbind")
    assert resp.status_code == 429
    assert resp.json()["detail"] == f"device_change_too_soon: {when.isoformat()}"
    assert 3 * 86400 - 5 <= int(resp.headers["Retry-After"]) <= 3 * 86400


def test_every_status_is_a_client_error_or_a_retry():
    assert set(errors.STATUS.values()) <= {400, 403, 404, 409, 410, 422, 429, 503}


def test_an_unknown_code_cannot_be_raised():
    with pytest.raises(ValueError):
        errors.Refusal("not_a_code")
