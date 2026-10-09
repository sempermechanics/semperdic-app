# ADR-020: Backend refusals are one exception with one status table

**Status:** Accepted, built
**Date:** 2026-10-05
**Deciders:** backend owner

## Context

The backend refuses requests by wire code (`errors.py`; the Android client and
the consoles branch on the code). Before this record the repo reported a
refusal five different ways:
- a `(code, value)` tuple (39 sites);
- an `(error, seat, invite)` triple (`add_institution_member`);
- a bare string (`set_seat_enabled`);
- an exception (`LicenseTermsRejected`);
- `None`.

Each router then turned the code into an HTTP status with a dict or a ternary
of its own (`routers/licenses.py`, `admin.py`, `institutions.py`; nine maps in
all). 44 of the codes were also typed as string literals in the repo rather than
taken from `errors.py`.

The maps had drifted apart. Two codes are answered differently by different
routes: `license_revoked` (403 on the phone's routes, 409 to staff converting
a licence and to IT holding a seat), and `license_seat_disabled` (409 to IT
adding the member, 403 to a typed key). Nothing said whether that was intended.

## Decision

- `errors.STATUS` maps every code the repo refuses with to its HTTP status. A
  status is a property of the code.
- The repo raises `errors.Refusal(code, suffix="", retry_at=None)`. A code with
  no status is refused when the `Refusal` is built (`ValueError`).
- `main.refusal_handler` is the one place a refusal becomes a response:
  `{"detail": "<code>"}` (or `"<code>: <suffix>"`) with `STATUS[code]`, plus
  `Retry-After` when `retry_at` is set (`device_change_too_soon`).
- Routes do not map codes. The two existing deviations were kept at first,
  named where they happened with `errors.restatus({code: status})`: activation
  (`license_seat_disabled` → 403), conversion and seat holds (`license_revoked`
  → 409). They are gone (TD-185): no client read those statuses. The app
  matches the `detail` code, and its one activation call has no caller
  (`data/net/SemperApi.kt:180`); the consoles match the code through
  `explain` (`firebase-hosting/public/console/messages.js:28`) and compare no
  status. `license_revoked` is 403 and `license_seat_disabled` 409 on every
  route, and `restatus` is removed.
- The transaction bodies (`claim_seat`, `claim_individual_license`, the lease
  and seat transactions) still return codes inside the repo. Their callers
  read the code: `entitlement` tells contention from a full roster, and `mint`
  reports a failed attach in the mint's answer. The code becomes a `Refusal`
  where it leaves the repo for a route.

## Options considered

1. **One table and one exception (chosen).** Every status map is deleted, and a
   new code cannot reach a route without a status.
2. **A shared `status_for(code)` helper, keeping the tuples.** That removes the
   maps, but every route still writes `if code: raise`, and the five return
   shapes stay.
3. **A typed result object (`Ok`/`Err`).** It makes failure explicit in every
   signature, but means a new type at 13 call sites and a different shape from
   every other FastAPI error.

## Consequences

- `tests/test_refusal_status.py` pins `(route, code) → (status, detail)` for 63
  cases. It was written first, and it passed against the old maps before any
  of them changed.
- Tests that check a code use `tests/refusals.attempt`, which turns a
  `Refusal` back into `(code, value)`.
- Request-shape errors (missing headers, bad ids, an empty patch) stay
  `HTTPException` in the routers and deps. `Refusal` is for what the domain
  decides.

## Action items

1. [x] `STATUS`, `Refusal`, `restatus` (removed with item 3), the handler.
2. [x] The repo raises; the nine route maps are gone; literals become `errors.*`.
3. [x] Decide the two deviations: dropped, as no client reads them (TD-185).
