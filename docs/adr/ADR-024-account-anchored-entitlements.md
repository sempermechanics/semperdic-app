# ADR-024: Paid entitlements are anchored to the account, never entered in the app

**Status:** Accepted (2026-10-18); merged in #448 and #449 (2026-10-19) and deployed 2026-10-22 from `b940cc29` ([CHANGELOG](../ops/CHANGELOG.md))
**Date:** 2026-10-18
**Deciders:** product owner
**Supersedes in part:** [ADR-007](ADR-007-licence-lifecycle.md) (its grant point "a key typed in the app"), TD-259's in-app key screen
**Framework items:** 4.1, 4.4, 5.6, 6.4; Part B "First activation", "Resilience controls"; Part C "Provisioning", "EMM" ([READINESS_FRAMEWORK_2026-10.md](../ops/READINESS_FRAMEWORK_2026-10.md))

Paths are from `app/src/main/java/com/sempermechanics/semper/` unless they start
at the repository root. Line cites are at `2371bec`.

## Context

Three ways of reaching a paid entitlement had grown up side by side:

1. **Account-anchored delivery.** A staff mint against an address writes a
   pending invite; an institution roster add seats an address. The person
   signs in and the backend attaches what they are owed on their first request
   (`ensure_entitlement`, `backend/app/repo/entitlement.py:181`, called for
   every authenticated request through `get_or_create_user`,
   `backend/app/repo/users.py:221, 227, 316`). Nothing is typed.
2. **A typed key.** `POST /v1/licenses/activate` redeems a key for the calling
   account. The branch added an in-app screen for it (c748630, "Licence
   activation screen (TD-259)"); no released build ever had one: v1.2-beta.4
   (`33dce87d`) carried `SemperApi.activateLicense` with no caller.
3. **An EMM enrolment invite.** `enrollmentInvite` in the managed
   configuration was parsed and never read (TD-259).

A key in the app is a second, weaker identity beside the account: it can be
shared, typed on the wrong account, and it puts a purchase-shaped step inside an
app that is to be distributed through Google Play. Play's Payments policy lets
an app give access to something bought elsewhere only when nothing in the app
sells it, prices it or links to a way to pay
([Understanding Google Play's Payments policy](https://support.google.com/googleplay/android-developer/answer/10281818),
the "consumption-only" provision, as the decision records it; the page was not
reachable from the environment this record was written in).

## Decision

1. **Every user signs in; Demo is the default entitlement.** An approved
   account with nothing attached resolves to `mode: demo`
   (`resolve_user_config`, `backend/app/repo/user_config.py:183`).
2. **"Paid" is a set of entitlements the server attaches to the account.** The
   app never redeems, enters or stores a key. It reads `/v1/config` and the
   signed token that rides in it ([ADR-023](ADR-023-signed-offline-entitlement.md))
   and renders around them.
3. **The app is consumption-only.** No buy button, no price, no link to a
   payment page, no "email us to renew" or "request a higher limit" line. A
   feature the licence lacks is shown **locked**: present, disabled, with one
   note naming what includes it (`license_lock_note`,
   `app/src/main/res/values/strings.xml:422`; `FeatureLocks`,
   `data/policy/FeatureLocks.kt:41-67`; `LicenseLock`,
   `ui/common/dialog/LicenseLock.kt:14-36`). A kill switch or an EMM setting
   still hides or pauses, because it says nothing about the licence.
4. **Anything that touches the cloud is checked by the backend on every
   request** (below). The app's own gates are presentation.
5. **Share runs only on the device**, so no server check guards it: it follows
   the cached `/v1/config`, which a signed token can only narrow, never widen
   ([ADR-023](ADR-023-signed-offline-entitlement.md) decision 7); with no token
   (none is issued until TD-215) the cache alone decides. A modified APK can
   unlock it; that risk is accepted (TD-274).
6. **The demo cap stays "stored at once"** (deleting an analysis frees its
   place), by the product owner's choice, and is now counted per device as well
   as per account (TD-275). A phone staff or IT take off an account stops
   counting that account's analyses (TD-276, closed 2026-10-19).

### Entitlement sources

| Source | How it attaches | Where |
|---|---|---|
| Roster seat | Institution IT (or staff) adds an address; an address with an account is seated at once, one without becomes an invite claimed at its first sign-in | `routers/roster.py` (IT under `/v1/institutions/licenses/{id}`, staff under `/v1/admin/licenses/{id}`); [CLOUD_ARCHITECTURE_GCP.md §20.7](../backend/CLOUD_ARCHITECTURE_GCP.md#207-floating-seats) |
| Email-locked individual licence | Staff mint with `emailLock`; it attaches to an existing account at mint, or through the invite at first sign-in | `POST /v1/admin/licenses`; §20.1 |
| One-time claim code | Redeemed **only** on the web account page, by a signed-in account with a verified address and a recent second factor. Covers reseller deals, joining an institution by email domain, and recovery of an invite that failed. The code is the licence key a mint returns once | `POST /v1/me/claim` (`backend/app/routers/licenses.py:195-256`); `firebase-hosting/public/console/account/claim.js` |
| Google Play purchase record | Future work: a server-verified purchase attaches the entitlement | FI-24 |

All four end in the same account fields (`licenseId`, `mode`, the mirrored
terms), so one-licence-per-person ([ADR-007](ADR-007-licence-lifecycle.md)),
revocation, grace and the 30-day hold apply unchanged.

**The claim route.** `POST /v1/me/claim`, body `{"code"}` of 8–64 characters,
any other field 422 (`LicenseClaim`, `backend/app/models.py:182-191`). Auth is
`attested_or_mfa_user` (`backend/app/step_up.py:110`): a browser with a second
factor on a recent sign-in, or a signed device request. An unverified address is
refused first, 403 `email_not_verified` (`licenses.py:236-237`). It runs the same
`repo.activate_license` as the legacy route, so refusal codes and statuses are
shared (`errors.STATUS`, `backend/app/errors.py:320-376`). A browser names no
device, so no device lock is compared or bound
(`backend/app/repo/activation.py:62-68`); the phone's next request binds it
(`revalidate_device_lock`, `backend/app/repo/devlock.py:236`). It shares
`license_activate_bucket` with the legacy route (`licenses.py:197`;
`backend/app/rate_limit.py:123`, 0.2/s, burst 3) and declares it in the
decorator, so a 429 leaves a signed request's nonce unspent. Each attempt logs
`event="activation"` with `via` = `web` or `app` (`licenses.py:189-192`,
`:244-250`) and writes a `LICENSE_CLAIM_CODE` audit row holding only the code's
prefix (`key_prefix`, `backend/app/licenses.py:198-202`), the outcome and, when
refused, the error code (`licenses.py:240-255`). Declared at the gateway
(`backend/gateway/openapi.yaml:207-226`). Tests `backend/tests/test_claim_code.py`.

The account page offers its card only while `/v1/me` shows no licence
(`firebase-hosting/public/console/account/account.js:130`), sends the code only
in the POST body, writes it nowhere else and clears the field once used
(`claim.js:9-15`, `:111-112`), and maps each refusal code to a sentence
(`claim.js:27-44`; `firebase-hosting/public/console/messages.js`, `ACCOUNT.claim`).
Tests `firebase-hosting/tests/account-claim.test.mjs`.

**The legacy route.** `POST /v1/licenses/activate` stays, unchanged, for
installed builds and support recovery (`licenses.py:49-81`). No current app
build calls it. Retiring it retires `contracts/license_activate_request.json`
with it (TD-278).

### How the app learns of a change

There is nothing to type, so the app has to ask:

- **First sign-in.** The status check reads `/v1/config` before it registers
  the device, and the token is issued only to a registered device, so a first
  sign-in on a phone asks again before Home opens
  (`EntitlementRefresh.afterSignIn`, `data/account/entitlement/EntitlementRefresh.kt:81`;
  `AccessStatusResolver`, `data/account/AccessStatusResolver.kt:62-79`).
- **Refresh licence** in Settings → Account and on the analysis-limit screen:
  `/v1/config` now through `SeatLease.fetchConfig`, falling back to the retrying
  one-off `LicenseConfigWorker.refreshNow` (`LicenseRefresh.run`,
  `ui/limit/LicenseRefresh.kt:62-72`; `ui/settings/SettingsAccountSection.kt:55`;
  `ui/limit/SessionLimitActivity.kt:86`). It says one line: licensed
  (institution or individual), still on the demo, or could not reach Semper.
- **ADR-023's triggers** stay: launch, return to the foreground past
  `refreshAfter`, the 4-hour periodic refresh, after each floating checkout.

### Server-side checks per request

Every authenticated request re-resolves the account (`get_or_create_user` →
`ensure_entitlement`) and, when it carries `X-Device-Id`, re-validates the
licence or seat device lock and drops to Demo on a mismatch or revocation
(`backend/app/deps.py:183-193`). On top of that:

| Route | Caller | Gates, in order |
|---|---|---|
| `POST /v1/sessions` (record an analysis) | signed device | rate limit; kill switch `upload` or `backup` → 503 `feature_paused`; files per session → 413; account cap: stored analyses (less `PROVISION_FAILED`) ≥ `maxSessions` → 409 `session_quota_exceeded`; **demo only**, device cap: every account's stored analyses from this device ≥ `maxSessions` → 409 `device_quota_exceeded` (TD-279; `backend/app/routers/sessions.py:339-370`, `backend/app/services/sessions.py:125-139`) |
| `POST /v1/files/{id}/complete` | signed device | rate limit; kill switch `upload` or `backup` → 503 (`backend/app/routers/files.py:139-143`) |
| `GET /v1/files/{id}/content` (restore) | signed device | rate limit; kill switch `restore` → 503; demo → 403 `feature_not_licensed` (`files.py:38-58`) |
| `GET /v1/sessions/{sid}/bundle` (download) | signed device, or browser with a second factor | rate limit; kill switch `restore` → 503; demo → 403 `feature_not_licensed` (`sessions.py:278-310`) |
| `GET /v1/sessions` | user | lists; `quota.used` is the **account's** count (`sessions.py:98`) |
| `DELETE /v1/sessions/{sid}` | signed device | rate limit only: a delete frees a place (`sessions.py:109-110`) |
| `GET /v1/sessions/{sid}/uploads` (resume) | signed device | rate limit; kill switch `upload` or `backup` → 503 `feature_paused` (`backend/app/routers/sessions.py:139-141`) |
| `PUT /v1/sessions/{sid}/metadata` | signed device | rate limit; kill switch `upload` or `backup` → 503 `feature_paused` (`sessions.py:222-224`) |
| `POST /v1/licenses/checkout` | user + `X-Device-Id` | floating pool; 409 `no_floating_seat` when full (`licenses.py:84-113`) |
| `POST /v1/me/claim` | browser with a second factor, or signed device | activation rate limit; verified address; `activate_license`'s checks |
| Share (PNG, GIF, PDF, CSV, ZIP) | — | **No route.** The app checks the licence and the token (`LicenseEntitlements.shareLicensed`, `data/account/LicenseEntitlements.kt:85-86`; `ShareCenter.access`, `ui/viewer/share/ShareCenter.kt:208-209`; the sweep lattice's Save graph PNG takes the same check, `ui/analysis/VsgLatticeActivity.kt` `bindStrainControls`) |

**Kill switches are enforced, not only published.** `deps.unless_paused`
(`backend/app/deps.py:301-336`) runs after the ID token and approval and before
the route's own parameters, so a paused request reads no record and, on a signed
route, leaves its nonce unspent. The refusal is 503 `feature_paused` with
`Retry-After: 300` (`:293`), logged `event="feature_paused"` with the switch and
route template; `feature_paused` is in `CLIENT_BRANCHED`
(`backend/app/errors.py:311-314`). `share` has no route and stays published only
(`backend/app/config.py:336-349`). The app holds the work: an upload is
`UploadErrors.Kind.PAUSED` → retry (`data/cloud/UploadErrors.kt:60`;
`data/cloud/UploadWorkOutcomes.kt:33`), a restore or download is
`DownloadFailure.Paused` and says "paused", not "failed"
(`data/cloud/restore/DownloadFailure.kt:44`; `data/DicRestoreWorker.kt:131`;
`data/DicBundleDownloadWorker.kt:153`). A build that predates the code sees a
503 and retries it as any 5xx (`UploadErrors.classify` at `6c6d059` ends
`else -> Kind.TRANSIENT`). Tests `backend/tests/test_kill_switches.py`.

**The demo device cap.** `repo.count_device_sessions`
(`backend/app/repo/sessions.py:466-490`) counts sessions whose `deviceId` is
this device, across accounts, less `PROVISION_FAILED`, with two single-field
counts (no composite index). `_device_cap_reached`
(`backend/app/services/sessions.py:81-94`) applies it to demo accounts only; a
licensed account is held to its own cap. The refusal says neither the count nor
whose analyses they are (`_device_quota_detail`, `:97-109`). Tests
`backend/tests/test_demo_device_cap.py`. A demo create costs 11 reads where it
cost 9 ([request-volume.md](../perf/request-volume.md) Pass 4).

### Why stored-at-once

The product owner kept the demo cap as analyses **stored at once**, not ever
created. A demo user can delete and continue, and an account erased and signed
up again starts at zero on both counts, because erasure deletes its sessions
(TD-275). The cap is a nudge toward a licence, not a meter; counting every
analysis ever created would turn Delete into a dead end and would need a
counter that survives erasure, which erasure is meant to remove. The per-device
count closes the cheaper bypass, a fresh account on the same phone.

## Options considered

- **Keep a key field in the app.** Rejected: it is a purchase-shaped step
  inside a Play-distributed app, a second identity beside the account, and
  every grant path already reaches the account without it.
- **Redeem claim codes in the app as well as on the web.** Rejected for the
  same reason. The route accepts a signed device request (`via: app`), but no
  screen sends one.
- **Ever-created demo cap.** Rejected by the product owner (above).
- **A server route for share** (render or sign exports server-side). Rejected
  for now: analysis and export are offline by design, and exports carry no
  secret the server could withhold. Revisit if share gains a cloud step (TD-274).

## Trade-offs

- A paid entitlement that attaches while the app is open shows up at the next
  refresh, or when the user taps Refresh licence; the app cannot be told.
- Share is enforced by the device. A patched APK can share without a licence;
  it gains no cloud access, since every cloud route checks the account.
- The device cap keys on the registered device id, an app-scoped `ANDROID_ID`
  (`data/account/DeviceKeys.kt:54-62`): a factory reset gives a new id. A phone
  released by staff or IT stops counting the previous holder's analyses
  (`forget_device_sessions`, TD-276, closed 2026-10-19); one the holder moves
  off themselves keeps them counted.
- The hosted Terms keep fees "as stated in your order" for access arranged
  outside the app (`docs/legal/TERMS_OF_SERVICE.md:149-180`). Their wording was
  aligned on 2026-10-19 (account-anchored sources in 6.2, "no purchases in the
  App" in 6.6); counsel's review is owed (TD-277).

## Consequences

- App: `ui/account/` is gone (the activation screen, its view model, error
  mapping, key format and tests), with the `activate_*` strings,
  `CloudApi`/`SemperApi.activateLicense`, `LicenseActivateRequest` and ten
  `ApiErrors` codes only that screen read (e084f39); `enrollmentInvite` left
  `app_restrictions.xml` and `ManagedConfig` (ffc41b7). New: `ui/limit/LicenseRefresh`,
  `ui/home/HomeLicenseNotice`, `ui/common/dialog/LicenseLock`,
  `data/policy/FeatureLocks` (package counts in [ADR-015](ADR-015-package-layout.md)).
  Home's expiry notice names the grace end date and no call to action
  (`HomeLicenseNotice`; `AppRemoteConfig` stores `licenseGraceEndsAt`,
  `data/net/AppRemoteConfig.kt:104`).
- Backend: `deps.unless_paused`, `errors.FEATURE_PAUSED`,
  `repo.count_device_sessions`, `POST /v1/me/claim`, the activation's
  device-less path, the `switch` and `via` log fields
  (`backend/app/observability.py:29-31`).
- Console: the account page's claim card.
- [CONTEXT.md](../../CONTEXT.md)'s trap "Demo uploads silently; only retrieval
  is gated" still holds for the licence; a kill switch now also refuses the
  upload, with a 503 every build retries.

## Action items

- [x] App, backend and console changes on `claude/readiness-followups-jhfhj9`
      (`6c6d059..2371bec`). Their tests first run in that branch's CI (TD-219).
- [x] Deploy backend, gateway (the claim route is a new path) and Hosting, in
      the order in TD-216 (2026-10-22; `/v1/me/claim` among the 14 new gateway paths).
- [x] Terms wording aligned with consumption-only (6.2, 6.6; 2026-10-19).
- [ ] Counsel reviews the Terms' fees section against consumption-only (TD-277).
- [ ] Retire `POST /v1/licenses/activate` and its contract fixture once no
      installed build can call it (TD-278).
- [ ] Later: Google Play Billing as a fourth source (FI-24).
