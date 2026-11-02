# Admin API — institution and staff routes as an automation contract

The routes the consoles call, written down for customer IT and Semper staff who
script against them (readiness item 5.7,
[READINESS_FRAMEWORK_2026-10.md](../ops/READINESS_FRAMEWORK_2026-10.md)). The
source of truth is the code: `backend/app/routers/roster.py`,
`backend/app/routers/institutions.py`, `backend/app/routers/admin.py`; each
route's auth tier is asserted in `backend/tests/test_route_authz_matrix.py:81-197`,
and every path must also be declared in `backend/gateway/openapi.yaml` (the gateway
404s anything else). Design background:
[CLOUD_ARCHITECTURE_GCP.md §20](CLOUD_ARCHITECTURE_GCP.md#20-licensing--entitlements).

**Limits of automation today.** Every institution route takes the browser step-up
below, which needs an interactive sign-in with a second factor no older than
`ADMIN_WEB_REAUTH_SECONDS` (default 900 s, `backend/app/config.py:203`). There are no
machine tokens (FI-20) and no SCIM (FI-18). A script can run inside one such
window, using the ID token of a signed-in session.

## Conventions

- **Base URL:** the API Gateway host (production `https://semper-gw-86wx7pp1.an.gateway.dev`;
  [ENVIRONMENTS.md](../ops/ENVIRONMENTS.md)).
- **Authentication:** `Authorization: Bearer <Firebase ID token>` from the Auth
  project. The gateway checks it and forwards it as `X-Forwarded-Authorization`;
  the backend verifies it again (`backend/app/deps.py:55-69`).
- **Errors:** JSON `{"detail": "<code>"}`, sometimes `"<code>: <suffix>"`. The
  code is stable; the status per code is `backend/app/errors.py:320-376`
  ([ADR-020](../adr/ADR-020-backend-refusal-model.md)). A 429 carries `Retry-After`.
- **Paging:** `page_size` (or `limit`) and `page_token`; the answer's `page` block
  carries `nextPageToken` and `hasMore`.
- **Ids:** `{license_id}` is the licence document id (the key's SHA-256, never the
  key); `{uid}` is the member's account id; `{invite_key}` is the invite id from the
  seats listing. Addresses never appear in a path.

## Auth tiers

| Tier | Who | Proof | Refusals |
|---|---|---|---|
| `user` | any approved account | ID token | 401 `invalid_token`, 403 `not_approved` |
| `institution-stepup` | an address in this licence's `adminEmails`, verified | ID token + TOTP second factor on a sign-in younger than `ADMIN_WEB_REAUTH_SECONDS` (`backend/app/routers/institutions.py:58-74`) | 403 `email_not_verified`; 404 `license_not_found` for a licence that does not name the caller, exactly as for one that does not exist; then 403 `mfa_required` / `reauth_required` |
| `admin` | Semper staff (`role: admin`, or a token proving an address in `ADMIN_EMAILS`) | ID token (`backend/app/deps.py:339-353`) | 403 `not_admin` |
| `admin-stepup` | staff | a signed request from the staff phone, registered from a sign-in that completed the second factor (`stepUpEligible`; [ADR-010](../adr/ADR-010-device-binding-per-app.md) amendment), or the browser step-up as above | as above; a phone registered without the second factor is 403 `mfa_required` |
| `admin-stepup-fresh` | staff | as `admin-stepup`, with a sign-in younger than `ADMIN_WEB_REVOKE_REAUTH_SECONDS` (default 120 s) | `reauth_required` |

With `CHECK_REVOKED_ON_STEP_UP=true` (after TD-214), a step-up call by a revoked or
disabled Firebase user is 401 `invalid_token`. The check spends a per-instance budget
for Firebase Auth Admin calls (`AUTH_ADMIN_CALLS_PER_SECOND`, default 10, burst
`AUTH_ADMIN_BURST` 20; `backend/app/rate_limit.py:138-147`). When it is spent the
check is not made and the call answers **503 `auth_check_busy`** with `Retry-After`
(`backend/app/step_up.py:207-215`): the token is not judged, so retry after the delay.
The consoles say "The server is busy checking sign-ins — wait a few seconds and try again." (`firebase-hosting/public/console/messages.js:19`) and do not retry by themselves.

**A paused feature.** While an operator has a kill switch on (`KILL_SWITCHES`,
[ENVIRONMENTS.md](../ops/ENVIRONMENTS.md)), the routes it guards answer **503
`feature_paused`** with `Retry-After: 300`, before any work and after the token
check (`deps.unless_paused`, `backend/app/deps.py:292-336`): `upload` or `backup`
refuse `POST /v1/sessions`, `GET /v1/sessions/{sid}/uploads`,
`PUT /v1/sessions/{sid}/metadata` (both since 2026-10-24,
`backend/app/routers/sessions.py:139-141`, `:222-224`) and
`POST /v1/files/{id}/complete`; `restore` refuses
`GET /v1/files/{id}/content` and `GET /v1/sessions/{sid}/bundle`, the bundle being
the one a script can call. Nothing is wrong with the request; send it again after
the delay. No institution or staff route is guarded.

## Institution routes (customer IT)

Prefix `/v1/institutions/licenses/{license_id}`. Rate limits: `institution_bucket`
(1 request/s, burst 10) for every route; bulk import also `roster_import_bucket`
(burst 3, then one a minute); export, summary and audit also `roster_report_bucket`
(0.5/s, burst 10) (`backend/app/rate_limit.py`). Each change writes one
`audit_logs` row with an `INSTITUTION_…` action.

| Method and path | Tier | Request | Response (200) | Errors |
|---|---|---|---|---|
| `GET /v1/institutions/licenses` (no prefix) | `user` | — | `{"licenses": [...]}`: the institution licences whose `adminEmails` hold the caller's verified address (`institutions.py:77-97`) | 403 `email_not_verified` |
| `GET …/seats` | `institution-stepup` | `page_size` (≤ 1000, default 500), `page_token` | `{license, seats[], invites[], page}`; a seat is `uid`, `email`, device locks, `status` (`active` / `disabled` / `revoked`), lease fields, times (`roster.py:130-154`) | 404 `license_not_found` |
| `POST …/seats` | `institution-stepup` | `{"email": "…"}` | `{licenseId, seat, invite}`: exactly one of the two is set (`:156-170`); an account that is pending or has not verified the address gets the invite, not a seat | 400 `invalid_email`; 409 `member_already_licensed`, `license_seats_exhausted`, `license_seat_disabled`; 503 `claim_contended` (retry) |
| `POST …/seats/import` | `institution-stepup` | `{"csv": "<file text>"}`: one column `email`, or the first column when there is no header; ≤ 500 rows, ≤ 512 000 characters (`backend/app/roster_io.py:22-25`) | `{licenseId, rows, counts, results[]}`; `counts` has `seated`, `invited`, `exists`, `duplicate`, `invalid`, `refused`, `unprocessed`; each result is `{row, email, outcome, error}` (`roster.py:172-242`) | 400 `roster_import_empty`, `roster_import_malformed: line <n>` (the line the bad record starts on, a quote never closed included; `backend/app/roster_io.py:73-89`), `roster_import_no_email_column`; 413 `roster_import_too_many_rows`, `roster_import_too_large` (over 512 000 characters, before any row is read; was a 422 until 2026-10-24). Re-import the same file to finish `unprocessed` rows: rows already on the roster answer `exists` |
| `GET …/seats/export` | `institution-stepup` | — | `text/csv`, every seat then every invite, `email` first, so the file imports back (`:244-259`); a cell starting with `=`, `+`, `-` or `@` is prefixed with `'` against spreadsheet formulas, and the import drops that one `'` again (`roster_io.undefused`, `backend/app/roster_io.py:134-143`) | — |
| `GET …/summary` | `institution-stepup` | — | `{licenseId, seating, maxSeats, seatsUsed, leasesActive (floating only), expiresAt, counts}`; `counts` holds `assigned`, `onHold`, `activeLast7Days`, `invited`, `revocationPending`, `revocationPendingNoCheckIn`, `removed`, `expiringWithin30Days` (`roster.py:261-273`; `backend/app/roster_io.py:228-273`) | — |
| `GET …/audit` | `institution-stepup` | `format` = `json` or `csv`; `since`, `until` (ISO date or instant, UTC; a bare `until` date includes that day); `page_size` (1–5000, default 100); `page_token` | JSON `{licenseId, events[], page}`, newest first; each event `{id, ts, uid, action, outcome, target, detail}`, no IP or device id. CSV: the same page, next token in the `X-Next-Page-Token` response header (`roster.py:275-314`; `backend/app/repo/audit_log.py:122-161`). The CORS answer does not expose that header, so only a non-browser client (a script) can read it and page on; a browser gets one page. The console's CSV export therefore asks for 5,000 events and says so when it stopped at that ceiling (`firebase-hosting/public/console/audit-log.js`, shared by the institution page and the operator desk); page CSV from a script, or narrow `since` / `until` | 400 `invalid_audit_range`. Events written before 2026-10-18 are absent (TD-246) |
| `PATCH …/seats/{uid}` | `institution-stepup` | `{"clearDeviceLock": true}` and/or `{"enabled": false}` | `{licenseId, seat}` (`:335-367`) | 400 `empty_patch`; 404 `seat_not_found`; 409 `seat_revoked` |
| `DELETE …/seats/{uid}` | `institution-stepup` | — | `{licenseId, uid, revoked: true}`: the holder drops to Demo, keeps their data, and the slot is freed (`:369-381`) | 404 `seat_not_found` |
| `DELETE …/invites/{invite_key}` | `institution-stepup` | — | `{licenseId, inviteId, revoked: true}` (`:316-333`) | 404 `invite_not_found` |

What a removal means for a phone: online, on its next request; offline, when its
signed entitlement's `graceUntil` passes (at most 14 days by default,
[ADR-023](../adr/ADR-023-signed-offline-entitlement.md)). The summary's
`revocationPending` counts removals the holder has not yet been seen after.

## Staff routes (Semper)

### Institution rosters at the staff tier

Every route in the table above is also mounted for staff under
`/v1/admin/licenses/{license_id}` (in place of `/v1/institutions/licenses/{license_id}`),
for any institution licence, whether or not the caller is in its `adminEmails`
(`backend/app/routers/admin.py:480-512`). An individual licence answers 404
`license_not_found`. The request and response bodies are the same.

| Routes | Tier |
|---|---|
| `GET …/seats`, `GET …/seats/export`, `GET …/summary`, `GET …/audit` | `admin` (`staff_roster_reader`, `admin.py:552-553`) |
| `POST …/seats`, `POST …/seats/import`, `PATCH …/seats/{uid}`, `DELETE …/seats/{uid}`, `DELETE …/invites/{invite_key}` | `admin-stepup` (`staff_roster_writer`, `:556-558`) |

Rate limits: `admin_bucket` on every route, plus `roster_import_bucket` on the import
and `roster_report_bucket` on export, summary and audit, as for IT. Changes write
`ADMIN_…` audit actions (`ADMIN_SEAT_ADD`, `ADMIN_INVITE_ADD`, `ADMIN_ROSTER_EXPORT`, …)
where IT's write `INSTITUTION_…`. The operator desk's roster card calls these routes
with the shared `roster.js`, `roster-tools.js` and `audit-log.js`
(`firebase-hosting/public/console/operator/roster-card.js`). An import from a sign-in
older than `ADMIN_WEB_REAUTH_SECONDS` is sent to Google for re-authentication, and the
pasted CSV does not survive the round trip: paste it and import again on return.

### Staff-only routes

| Method and path | Tier | Purpose |
|---|---|---|
| `GET /v1/admin/users` | `admin` | List accounts, `status` filter, paged |
| `POST /v1/admin/users/{uid}/approve` | `admin-stepup` | Approve a PENDING account |
| `POST /v1/admin/users/{uid}/revoke` | `admin-stepup` | Suspend an account and revoke the refresh tokens of every Auth user that signs in to it, linked subs included (best-effort until TD-214; the audit row says `sessionsRevoked`, `authUsers`, `authUsersRevoked`) |
| `PATCH /v1/admin/users/{uid}/config` | `admin-stepup` | Per-user limit overrides |
| `POST /v1/admin/device-releases` | `admin-stepup` | `{"email"}`: free a Demo account's registered phone |
| `GET /v1/admin/licenses` | `admin` | Licences, newest first, paged (`limit` ≤ 200, `page_token`, `include_demo`, `include_revoked`, `include_test`). A test account's licence (`testAccount: true` on every row, false on the rest) is left out unless `include_test=true`, filtered from the page, so a page may be short with more to come. Each row carries the licence's `app`, `maxDevices` and `devicesActive` ([ADR-033](../adr/ADR-033-licence-per-app-explicit-activation.md)), and for upgrade by issue `supersedes` (on the new licence, the one it replaces) and `upgradePendingId` (on the old, the new one waiting for its holder), "" otherwise (`backend/app/repo/mint.py` `_license_public`). `q` (exact address, domain or key prefix) is **deprecated**: it puts an address in the URL, which request logs keep. It still answers for one release and logs `deprecated_query_search` with the caller's uid, never the value (`backend/app/routers/admin.py:136-158`; removal TD-273) |
| `POST /v1/admin/licenses/search` | `admin` | The search with its terms in the body: `{"q", "limit", "page_token", "include_demo", "include_revoked", "include_test"}`, `q` required (at most 320 characters; empty pages the listing), any other field 422. On `admin_bucket`, no step-up; the answer has the GET's shape (`backend/app/routers/admin.py:161-169`; `AdminLicenseSearch`, `backend/app/models.py:458-472`). Declared at the gateway ahead of `/{licenseId}` (`backend/gateway/openapi.yaml:650-668`). The operator desk searches with it |
| `GET /v1/admin/licenses/{license_id}` | `admin` | One licence |
| `POST /v1/admin/licenses` | `admin-stepup` | Mint; the plaintext key is returned once. `app` (`semper`, `semperclassroom`) makes it that app's licence, licensing the phones activated on it up to `maxDevices` (default 1, or `maxSeats` on an assigned institution licence; not on a floating one); without `app` it holds for every app as before the split. 409 `email_already_licensed` counts per app ([ADR-033](../adr/ADR-033-licence-per-app-explicit-activation.md)) |
| `PATCH /v1/admin/licenses/{license_id}` | `admin-stepup` | Terms, renewal, `clearDeviceLock`, `maxDevices` (422 `max_devices_needs_app` on a licence without an app, 422 `max_devices_below_active` below the phones active now) |
| `POST /v1/admin/licenses/{license_id}/revoke` | `admin-stepup-fresh` | Whole-licence revoke |
| `DELETE /v1/admin/licenses/{license_id}` | `admin-stepup-fresh` | Delete into a 30-day hold |
| `GET /v1/admin/deleted-licenses`, `POST …/{license_id}/restore` | `admin`, `admin-stepup` | The hold |
| `POST /v1/admin/licenses/{license_id}/convert` | `admin-stepup` | Individual to institution; 409 `license_not_convertible` on a licence that names its app (ADR-033), which upgrades by issue instead |
| `POST /v1/admin/licenses/{license_id}/upgrade` | `admin-stepup` | Issue the holder of an individual licence that names its app a new licence for the same app ([ADR-033](../adr/ADR-033-licence-per-app-explicit-activation.md); `AdminLicenseUpgrade`): its terms carry over unless the body names `maxDevices`, `expiresAt` or `perpetual`, `maxAnalyses`, `note`; `kind: "institution"` with `domainLock`, `adminEmails`, `maxSeats` makes it an assigned institution licence. `{key, license, supersededId, holderUid, pending}`, the key once. The holder moves on Activate (`pending: true`); one who never signed in has the invite moved and the old licence revoked now. 409 `license_not_upgradable`, `upgrade_pending: <id>` (re-checked in the transaction that writes the pointers, so a double submit leaves one successor and deletes the other); 422 `convert_domain_mismatch`, `expiry_in_past`; 503 `claim_contended` when that transaction keeps losing a race. Audited `ADMIN_LICENSE_UPGRADE_ISSUE` (`backend/app/routers/upgrades.py`) |
| `POST /v1/admin/licenses/{license_id}/apply-upgrade` | `admin-stepup` | Move the holder onto the issued licence `{license_id}` now, with no phone, for a build without Activate: the old licence is revoked with `supersededBy`, and each phone activates again. 404 `no_pending_upgrade`, also when the old licence was revoked rather than replaced (its revoke withdraws the upgrade); 403 `license_expired` for an issued licence past its grace, refused before anything moves. Audited `LICENSE_UPGRADE_APPLY` |
| `PATCH /v1/admin/licenses/{license_id}/seats/{uid}/device` | `admin-stepup` | Clear one seat's device lock |
| `GET /v1/admin/licenses/{license_id}/reconcile` | `admin` | Per-seat check of what a licence actually entitles; `revokedStillRunning` |
| `GET /v1/admin/licenses/{license_id}/device-history` | `admin` | Recent device moves |
| `GET /v1/admin/test-accounts` | `admin` | Staff test accounts ([ADR-027](../adr/ADR-027-test-accounts.md)), newest first: `{"accounts": [{"uid", "email", "purpose", "expiresAt", "maxDevices", "note", "createdAt", "activeDevices", "devices", "status"}]}`. `devices` is each ACTIVE phone over both apps, oldest first: `[{"deviceId", "app", "model", "registeredAt"}]`, and `activeDevices` counts them; `status` is `active`, `expired` (past `expiresAt`, not yet ended by the daily job) or `suspended` (`backend/app/routers/admin_test_accounts.py:36-45`, `backend/app/repo/testaccounts.py:141-190`) |
| `POST /v1/admin/test-accounts` | `admin-stepup-fresh` | Create a QC or CI login. Body `{"email", "purpose": "qc"\|"ci", "days"?, "maxDevices"?, "note"?, "apps"?, "licence"?, "licenceMaxDevices"?}`: `days` 1..90 (a QC account without it lasts 30; a CI account without it never ends and holds a perpetual licence), `maxDevices` 1..10 (default 3), the phones per app that may sign in, `note` at most 200 characters. QC only (422 on a CI account): `apps` `semper`, `semperclassroom` or `both` (default), one licence per app on the activation model ([ADR-033](../adr/ADR-033-licence-per-app-explicit-activation.md)), each for `licenceMaxDevices` (1..10, default 1) activated phones, or `licence: "none"` for Demo. A CI account holds one licence for every app that binds no phone. Any other field 422 (`AdminTestAccountCreate`, `backend/app/models.py`). 201 `{"uid", "email", "password", "purpose", "expiresAt", "maxDevices", "licenseId", "licenseIds"}` (`licenseId` the first, "" for Demo) with `Cache-Control: no-store`; the password (20 URL-safe characters) is in this answer only. 403 `test_accounts_disabled` when `TEST_ACCOUNT_DOMAINS` is empty or lacks the address's domain (exact match); 409 `email_in_use` for a staff address (`ADMIN_EMAILS`) or one that has an account, a Firebase Auth user or a licence or invite; 503 `auth_check_busy` / `internal_error` with `Retry-After: 30` when the Auth project cannot be asked. Audited `test_account_created` (`backend/app/services/testaccounts.py:72-107`) |
| `POST /v1/admin/test-accounts/{uid}/extend` | `admin-stepup-fresh` | Body `{"days"}` (1..90): the account and its licence now end `days` from now (sooner too, if that is what it says; a CI account with no end gets one). Answers the account's row as the list shows it. 404 `user_not_found` for a uid that is not a test account. Audited `test_account_extended` (`services/testaccounts.py:117-132`) |
| `POST /v1/admin/test-accounts/{uid}/password` | `admin-stepup-fresh` | `{"password"}`, a new one, with `Cache-Control: no-store`; every refresh token the account holds is revoked. Audited `test_account_password_reset`, without the password (`services/testaccounts.py:135-146`) |
| `POST /v1/admin/test-accounts/{uid}/devices/{device_id}/release` | `admin-stepup-fresh` | Release one ACTIVE phone of the account, so another can take its place without ending it (TD-314). One transaction: the device REVOKED and stamped `staffReleased: true`, and the app's `activeDeviceId` dropped when it names this phone; no release hold (`releasedDeviceId` is not written) and its sessions are untouched. The released phone may not register for this account again: `POST /v1/devices/register` answers it 409 `device_released` (builds before it read any 409 there as `device_conflict`), audited `DEVICE_REGISTER` DENIED with `reason: "staff_released"`, and its `/v1/config` calls are Demo, not licensed as an unregistered phone with room would be (`repo/devlock.py`, `_test_lock_verdict`); another account may still register it. Answers the account's row as the list shows it. 404 `user_not_found` for a uid that is not a test account, `device_not_found` for a device that is not one of its ACTIVE phones. Audited `test_account_device_released` with `{deviceId, app}` (`services/testaccounts.py:149-164`, `repo/testaccounts.py:109-142`, `repo/_base.py:275-281`) |
| `DELETE /v1/admin/test-accounts/{uid}` | `admin-stepup-fresh` | 204. End the account now: suspended, tokens revoked, its test licences revoked, then the holder erasure's own steps (Drive tree, Firestore records, Firebase Auth users; `erase_data`, `backend/app/services/account.py`), run as staff. Audited `test_account_ended`, not `ACCOUNT_DELETE` (`services/testaccounts.py:167-185`) |

### Account routes a script may meet

| Method and path | Tier | Purpose |
|---|---|---|
| `POST /v1/me/claim` | the user step-up: a browser with a TOTP second factor on a sign-in younger than `ADMIN_WEB_REAUTH_SECONDS`, or a signed device request (`attested_or_mfa_user`) | Redeem a one-time claim code onto the caller's own account ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)). Body `{"code": "…"}`, 8–64 characters, case, spaces and dashes ignored, any other field 422 (`LicenseClaim`, `backend/app/models.py:182-191`). Answers `{"config": …}` as `GET /v1/config` would. Refusals: 403 `email_not_verified` first (`backend/app/routers/licenses.py:236-237`), then the activation codes, with the statuses in `errors.STATUS`: 404 `license_not_found`; 403 `license_revoked`, `license_expired`, `license_email_mismatch`, `license_device_mismatch`; 409 `already_licensed`, `license_already_redeemed`, `license_seats_exhausted`, `license_seat_disabled`; 503 `claim_contended`, `device_lock_contended` (retry). Rate limit `license_activate_bucket` (0.2/s, burst 3; `backend/app/rate_limit.py:123`), shared with the route below. A browser claim names no device, so no device lock is compared or bound (`backend/app/repo/activation.py:62-68`). Each attempt writes a `LICENSE_CLAIM_CODE` audit row with the code's prefix only and logs `event="activation"` with `via` (`licenses.py:239-255`). Declared at the gateway (`backend/gateway/openapi.yaml:207-226`) |
| `POST /v1/licenses/activate` | `user` with `X-Device-Id` | **Legacy.** The same redemption for installed builds; no current app build calls it, and the app has no screen to type a key. Kept until TD-278 retires it with its contract fixture (`licenses.py:49-81`) |

## Examples

```bash
API=https://semper-gw-86wx7pp1.an.gateway.dev
TOKEN=<ID token of a signed-in session with a fresh second factor>
LIC=<licence id from GET /v1/institutions/licenses>

curl -sS -H "Authorization: Bearer $TOKEN" "$API/v1/institutions/licenses/$LIC/summary"

curl -sS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data "$(python3 -c 'import json,sys; print(json.dumps({"csv": open(sys.argv[1]).read()}))' roster.csv)" \
  "$API/v1/institutions/licenses/$LIC/seats/import"

curl -sS -H "Authorization: Bearer $TOKEN" -o audit.csv \
  "$API/v1/institutions/licenses/$LIC/audit?format=csv&since=2026-10-01&page_size=5000"
```

## Contract tests

`backend/tests/test_roster_bulk.py` (import, export, summary, audit),
`backend/tests/test_licence_search_post.py` (the POST search and the deprecated `q`),
`backend/tests/test_claim_code.py` (the claim route), `backend/tests/test_test_accounts.py`
(test accounts and their expiry job), `backend/tests/test_kill_switches.py`
(`feature_paused`),
`backend/tests/test_route_authz_matrix.py` (tiers and cross-tenant refusals),
`backend/tests/test_gateway_parity.py` (every route declared at the gateway).
