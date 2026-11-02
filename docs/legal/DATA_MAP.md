# Data map — records of processing (2026-10-18)

Internal working document for readiness item 8.1
([READINESS_FRAMEWORK_2026-10.md](../ops/READINESS_FRAMEWORK_2026-10.md)): what
personal data Semper holds, where, why, and for how long, **derived from the
code**, not from the policy. It is the input to a DPIA and to the Play Data
safety answers ([PLAY_DATA_SAFETY.md](PLAY_DATA_SAFETY.md)); the public text
remains [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

This file is **not rendered** to Hosting: `scripts/render_legal_pages.py` renders
only the Privacy Policy and the Terms (its `PAGES` table), so adding it changes
nothing that `render_legal_pages.py --check` compares. Where this map and the
policy disagree, the map describes the code and the disagreement is a finding.

Paths are from the repository root; app paths from
`app/src/main/java/com/sempermechanics/semper/`. Brought up to the branch
`claude/readiness-followups-jhfhj9` at `75bac5f` (2026-10-18): hashed addresses
in audit rows, the wider erasure and the POST licence search are in code there,
not yet deployed. The claim-code audit row and the per-device count are at `2371bec` on the same
branch ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)).

## Categories of data subject

- **Account holders**: anyone who signs in to the app or a console.
- **Invitees**: addresses an institution or staff added to a licence before the
  person has an account (`licenseInvites`).
- **Institution IT contacts**: addresses in a licence's `adminEmails`.
- **Semper staff**: `role: admin` accounts and `ADMIN_EMAILS`.

No location, contacts, camera, phone-state or advertising data is read by the app
(`app/src/main/AndroidManifest.xml:6-14`). Firebase Analytics merges in
`com.google.android.gms.permission.AD_ID` and the Privacy Sandbox
`ACCESS_ADSERVICES_*` permissions (present in the CI release APK at `968637cb`); the
manifest removes all three and turns advertising-ID collection off
(`app/src/main/AndroidManifest.xml:16-27, 53-57`; TD-232).

## Stores

### Firebase Authentication (project `indicvision-dic-app-auth`)

Email address, display name, sign-in provider, password hash (password accounts),
TOTP enrolment (staff and console users). Purpose: identity. Retention: until the
account is erased; `DELETE /v1/me` deletes the Auth user, and every other Auth
user linked to the account (`linkedAuthUids`, `auth_links`; `repo.sign_in_uids`), once
the runtime SA holds Firebase Auth admin (`backend/app/services/account.py:142`; TD-214). Both apps sign in to this one Auth project, so deleting the account removes the sign-in for both, consistent with the account-wide erase ([ADR-023](../adr/ADR-023-signed-offline-entitlement.md), decision note).

### Firestore (project `indicvision-dic-app`; location unrecorded, TD-227)

Every collection the backend touches is listed in
`firestore-rules-tests/collections.mjs` (a CI guard keeps the list complete), and
no client can read or write any of them (`firestore.rules:7-9`).

| Collection | Personal data | Purpose | Retention |
|---|---|---|---|
| `users/{uid}` | `email`, `emailVerified`, `displayName`, `signInProvider`, `role`, `access_status`, `claimedDeviceId`, per-app `activeDeviceId`, `linkedAuthUids`, licence mirror (`licenseId`, mode, terms), `termsAccepted` and `improvementConsent` records (each with a device id and source), `createdAt`, `lastSeenAt` (`backend/app/repo/users.py:260-280`; schema `docs/backend/CLOUD_ARCHITECTURE_GCP.md:544-579`) | Identity, access, licensing, consent records | Until erasure (`backend/app/repo/account.py:206-244`) |
| `auth_links/{firebaseSub}` | the canonical uid a Firebase subject maps to (`backend/app/repo/users.py:96`) | Merging sign-in methods | Until erasure: every row whose `uid` is the account is deleted (`backend/app/repo/account.py:237-239`). Rows left by erasures before that change stay until a sweep (TD-271) |
| `devices/{deviceId}` | `uid`, `app`, `publicKeyPem`, `model`, `osVersion`, `appVersion`, `status`, registration and last-assertion times (`backend/app/repo/devices.py:80-92`) | Device binding, request signatures | Until erasure |
| `challenges/{nonce}` | `uid`, `deviceId` | Replay protection | TTL on `expireAt` (`backend/firestore.indexes.json:104-122`); deleted on use |
| `sessions/{sid}` | `uid`, `deviceId`, `app`, `specimen` (the reference image's file name), `name` (the analysis name), small run metrics, Drive folder id (`backend/app/repo/sessions.py:531-551`) | Cloud backup index; `deviceId` also counts a demo device's stored analyses across accounts (`count_device_sessions`, `:415-439`) | Until the session or account is erased |
| `files/{fileId}` | `uid`, `sessionId`, file `name`, size, `sha256`, Drive ids | Upload tracking | Until the session or account is erased |
| `licenses/{id}` | `emailLock` and `redeemedByUid` (individual); `adminEmails`, `domainLock` (institution); per-app `deviceIdLock`; `createdByUid` | Licensing | Until deleted; then 30 days in `deleted_licenses` |
| `licenses/{id}/seats/{uid}` | `uid`, `email`, per-app `deviceIdLock`, lease fields (`backend/app/repo/claims.py:246-254`) | Institution roster | The current seat's `email` follows its holder's verified sign-in address (TD-297). A revoked seat keeps `email` until its holder's account is erased, which blanks it on every seat the account held, live seats revoked first (`_blank_seat_addresses`, `backend/app/repo/account.py:186-203`). Seats on other licences are found by a collection-group query whose index must be deployed first (TD-271) |
| `licenseInvites/{id}` | `email`, `licenseId`, `invitedByUid` (`backend/app/repo/invites.py:155-163`) | Promise of a seat before sign-up | Until redeemed or withdrawn; an individual licence is re-invited to its address after its holder's erasure (`backend/app/repo/account.py:146-148`) |
| `deleted_licenses/{id}`, `…/deleted_seats/{uid}` | copies of the above | 30-day hold for restore | TTL on `purgeAt` (`backend/firestore.indexes.json:123-160`); a held seat's `email` is blanked on its holder's erasure |
| `audit_logs/{autoId}` | `uid`, `deviceId`, `action`, `target`, `detail`, `licenseId` (`backend/app/audit.py:71-83`, written by `backend/app/repo/audit_log.py`). An `ip` field exists but no caller sets it. `detail` holds no address: mint, convert and an `adminEmails` edit record hashes (`emailLockHash`, `adminEmailHashes`, `adminEmailCount`; `audit.address_tag`, `backend/app/audit.py:27-38`). A licence edit records only that its free-text `note` changed and its length (`noteChanged`, `noteLength`; `backend/app/routers/admin.py:29-40`). A claim-code redemption (`POST /v1/me/claim`, since 2026-10-18) writes `LICENSE_CLAIM_CODE` with the account's `uid`, the device id when a signed device redeemed it (none from a browser), `outcome` `OK` or `DENIED`, and `detail` holding `keyPrefix` (`SEMP-` and the code's first group, never the code), `via` (`web` or `app`) and `mode` or `errorCode`; an `OK` row's `target` is the licence id (`backend/app/routers/licenses.py:239-255`) | Security and accountability; customer-visible per licence | **Kept after erasure, 365 days** (owner, 2026-10-22): each row's `expireAt`, for a TTL policy declared but not yet enabled (TD-237) |

### Google Drive (Shared Drive, runtime SA as Manager)

Per user and session folders holding what the app uploads: reference and deformed
images, `.dat` results, reports, CSV, `metadata.json` (analysis name, specimen,
parameters). Purpose: backup and restore. Retention: deleted with the session or
account (`DELETE /v1/me` removes every user folder of the account, then each
session's folder still there, before any record, `backend/app/services/account.py:72-158`); Shared Drive trash per Workspace policy.

### Cloud Logging (project `indicvision-dic-app`)

- Structured lines from `backend/app/observability.py`: `requestId`, `uid`,
  `deviceId`, `appId`, `appVersion`, route, status, latency, error code, and on
  licensing events `licenseId` (allowed fields, `observability.py:24-33`). An email
  that must be logged is a 12-hex hash (`short_hash`, `:189-196`).
- uvicorn's access line and the Cloud Run / API Gateway request logs carry the full
  URL. The staff licence search sends its address in a POST body
  (`POST /v1/admin/licenses/search`, `backend/app/routers/admin.py:161-169`); the
  GET's `?q=` still answers for one release and would put an address in the URL
  (TD-273).
- Retention: the project's log bucket setting, unverified (policy §4 lists it
  UNKNOWN).

### Firebase Crashlytics and Analytics (opt-in)

Off in the manifest (`app/src/main/AndroidManifest.xml:42-52`) until the user
agrees (`AppSettings.diagnosticsEnabled`, `data/prefs/AppSettings.kt:61`).
Crash reports with device and app version; breadcrumbs and non-fatals only with
consent (`diagnostics/CrashReportingTree.kt:23-41`); Analytics events with enum and
bucket parameters only (CONTEXT.md, Invariants), and the approximate location
Google derives from the masked IP (Google's own disclosure for the SDK). Never
the advertising ID (`app/src/main/AndroidManifest.xml:16-27, 53-57`). On a staff-made QC or CI test
account only, and only with the same consent, the user property and Crashlytics
key `account_type` (`test` or `ci`; `diagnostics/Diagnostics.kt`); a real
account carries none. Retention: Firebase project
settings, unverified (policy §4).

### Resend (email)

On a new PENDING account, one mail to the support address with the applicant's
email, display name, sign-in provider and uid (`backend/app/notify.py:193-215`).
When a new device registers to an account, one mail to the holder's verified
address with the app, the device model and id and the time, once per account and
device, and only while `NOTIFY_SECURITY_FROM` is set (`backend/app/notify.py:222-250`,
TD-295). Retention: Resend's, unverified.

### On the device

Analyses (images, `.dat`, reports) in app-private storage; the Firebase session; the
device key in the Android Keystore; the sealed entitlement token. Backup and device
transfer exclude preferences and session files
(`app/src/main/res/xml/data_extraction_rules.xml`).

## Identifiers

- **Device id**: `and-{ANDROID_ID}` (`data/account/DeviceKeys.kt:54-62`), unique
  per app-signing key, user and device on Android 8+; a legacy `dev-{uuid}` is kept
  when already stored. Changes on factory reset.
- **Device key thumbprint** (`dkt`): SHA-256 of the device public key, inside the
  entitlement token (`backend/app/entitlement_token.py:102-111`).
- **uid**: the Firebase Auth uid.

## Retention summary

| Data | Retention | Mechanism |
|---|---|---|
| Account, devices, sessions, files, Drive content | Until erasure | `DELETE /v1/me`; per session `DELETE /v1/sessions/{sid}` |
| Nonces | Minutes | TTL `challenges.expireAt`, delete on use |
| Deleted licences and seats | 30 days | TTL `purgeAt` |
| Audit rows | 365 days (TTL enabled 2026-10-23); their log copy 365 days in the locked `audit-trail` bucket | TTL `audit_logs.expireAt`; older rows stamped by the TD-246 backfill |
| Firestore exports | Bucket lifecycle (35 days recommended, `docs/backend/FIRESTORE_DATA_PROTECTION.md:19-26`; applied state unverified) | GCS lifecycle |
| Logs, crash reports, email | Vendor settings, unverified | Gate "Confirm Crashlytics / Cloud Logging / Resend retention" |

## Findings recorded elsewhere

TD-271
(erasure's seat index; erasures before the change), TD-273 (the deprecated search
query), TD-227 (location), TD-232
(Data safety submission), TD-317 (policy omits Analytics, App Check and IP-derived location), TD-36 (policy placeholders). `docs/backend/CLOUD_ARCHITECTURE_GCP.md`
§5 lists `ua` on `audit_logs`; the code writes none.
