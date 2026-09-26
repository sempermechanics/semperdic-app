# Changelog

The history that used to live under "Current state" in
[CONTEXT.md](../../CONTEXT.md), moved here on 2026-09-23 so the agent map stays
short. Entries are newest first; the text is as it stood in CONTEXT.md, with
relative links re-pointed for this folder and dated `[Note …]` corrections
where a statement had gone stale. Headings and dates are added; undated entries
keep the order CONTEXT.md gave them. Git history and PR descriptions remain the
source of truth: `gh pr list --state all`, and `git log -p CONTEXT.md` for the
text as it evolved.
Decisions that outlive their PR are recorded in
[CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md) §20,
[ARCHITECTURE.md](../app/ARCHITECTURE.md) and [../adr/](../adr/).

## 2026-09-26 — Changing device works again (#248, #249)

A cleared device lock ("New device" in the consoles, or the holder's own "Use Semper on a
different device") emptied the licence's `deviceIdLock` but left `users/{uid}.activeDeviceId`
naming the old phone, so `POST /v1/devices/register` refused the new phone with
`409 device_conflict` and the device change never happened. Found smoke-testing #235 on an
emulator: two 409s at 05:59 and 06:00 UTC after a staff clear. #248 makes the clear release
the holder's binding (`_release_holder_device`: `activeDeviceId` deleted, the old
`devices/{id}` marked `SUPERSEDED`). #249 holds the released phone off for
`DEVICE_RELEASE_HOLD_HOURS` (24) so its upload worker's automatic re-register cannot take the
account back before the new phone signs in.

Deployed from `main`, staging first each time: #248 as `semper-api-staging-36223348648-1`
and `semper-api-36223604531-1` (`5b7d0642`); #249 as `semper-api-staging-36224232418-1` and
`semper-api-36224429945-1` (`dea4fcc7`). #248's gateway dry-run found nothing to change. Left
open in [TECH_DEBT.md](TECH_DEBT.md): TD-126 (a demo account cannot change phone) and the
#248 review findings TD-127..132, of which TD-127 (the release skips neither a revoked
licence nor a held seat) matters most.

## 2026-09-26 — Firestore TTL policies declared as field overrides (#242)

`scripts/deploy-firestore.sh indexes` had created the licence desk's composite indexes and
then stopped: the Firebase CLI lists each live TTL policy as a field override, and in
non-interactive mode it will not leave one that the indexes file omits ("Pass the --force
flag"). `--force` would have deleted the three policies on the `(default)` database, which
staging and production share. `backend/firestore.indexes.json` now declares
`challenges.expireAt`, `deleted_licenses.purgeAt` and `deleted_seats.purgeAt` under
`fieldOverrides` with `"ttl": true`. Each one restates the field's live single-field
indexes (ascending, descending, array-contains), because an empty `indexes` list turns
single-field indexing off. `test_firestore_indexes_declare_ttl_policies` checks the three.
The script header notes a Firebase CLI on Node >= 20 (Git Bash, not WSL) and warns off
`--force`. BACKEND_SETUP_CONSOLE.md §3a and §3b, BACKEND_SETUP_GCP.md A2b and
CLOUD_ARCHITECTURE_GCP.md §5 were corrected to match the file.

Deployed without `--force` from the PR head (`6a42c879`, whose indexes file and script
match `main`): `firebase deploy --only firestore:indexes --non-interactive --debug` exited 0,
skipped all four composite indexes and all three overrides, and sent Firestore no writes.
Afterwards all three policies were still `ACTIVE` and still inherited the default index
config, and all four composite indexes were `READY`.

## 2026-09-26 — Backend and console deploy: scale to zero (#240), licence desk (#236–#239)

Production `semper-api-36220429126-1` from `141ddcae`. Staging went first: run 36219875207 →
`semper-api-staging-36219875207-1`. Production run 36220059465 → `semper-api-36220059465-1`
with the gateway dry-run, then run 36220429126 with `apply`. The candidate `/readyz` smoke
passed on all three. `apply` switched `semper-gw` from `v202609251206-56` to
`v202609260522-60`. The diff added the licence desk's new admin routes and changed
descriptions. From outside with no token, `GET /v1/admin/deleted-licenses`, `POST
…/deleted-licenses/{id}/restore` and `POST …/licenses/{id}/convert` answer 401 and an
unknown path 404. The console went out afterwards with `scripts/deploy-console.sh` to
`indicvision-dic-app-auth` (API base `semper-gw-86wx7pp1.an.gateway.dev`). The hosted
`operator.js`, `operator/index.html`, `util.js`, `auth.js`, `institution.js` and
`console.css` hash-match `main`, and the operator page loads with no console errors.

- #240: Cloud Run scales to zero in both environments. `min instances` is unset on the live
  service, where production had `minScale=1`. `backend/deploy/` holds a 15-day cleanup
  policy for the registry and the run-sources bucket, applied the same day by the owner
  ([BACKEND_SETUP_GCP.md A7](../backend/BACKEND_SETUP_GCP.md#a7-storage-hygiene)). The
  registry policy is enforcing (dry run off). The serving digests of `semper-api` and
  `semper-api-staging` equal their `latest` tags, so the keep rule covers them. The bucket
  deletes at age 15; the Firestore backup bucket keeps its own 35-day rule. The registry
  held 1.35 GB in 35 versions, the oldest from 2026-09-23, so nothing qualifies before
  about 2026-10-08.
  Measurement: [perf/backend-cost.md](../perf/backend-cost.md).
- The deploy also shipped the licence desk, backend and console ([ADR-007](../adr/ADR-007-licence-lifecycle.md)).
  - #236: a fast, filtered list. It hides Demo keys unless asked and searches by address,
    domain or key prefix.
  - #237: one licence per person. A mint for an address that already holds one is 409
    `email_already_licensed`.
  - #238: edit, upgrade and convert in place.
  - #239: delete into a 30-day hold, with restore.
  - What they need was in place by the deploy: the three `licenses` composite indexes are READY
    and the TTL policies on `deleted_licenses.purgeAt` and `deleted_seats.purgeAt` are ACTIVE
    (checked 2026-09-26).

## 2026-09-25 — Backend and console deploy: no analysis cap on a demo key (#232)

Production `semper-api-36132773366-1` from `eb18ef8` (staging first, run
36132056688 → `semper-api-staging-36132056688-1`; production run 36132401032 →
`semper-api-36132401032-1` with the gateway dry-run, then run 36132773366 with
`apply`). Candidate smoke passed on all three. The gateway diff was descriptions
only; `apply` switched `semper-gw` from `v202609250953-51` to `v202609251206-56`,
and the outside check answered 401 on `GET /v1/config` and 200 on the preflight.
The console went out with `scripts/deploy-console.sh` to `indicvision-dic-app-auth`
(only `operator/operator.js` changed).

- #232: `PATCH /v1/admin/licenses/{id}` refuses `maxAnalyses` on a demo-mode key
  with `422 cap_on_demo_key`. A demo holder gets `DEMO_MAX_ANALYSES` whatever the
  key stores, so the edit had answered 200 and changed nothing. `clearMaxAnalyses`
  is still accepted. `GET /v1/admin/licenses` returns `demoMaxAnalyses`; the
  operator desk shows "demo (25)" on demo rows and disables their Cap button.
- The case that found it: the system demo key SEMP-8AKN was given a cap of 100 at
  11:17 UTC and its holder still saw "of 25". The stored cap was cleared from the
  desk at 11:53 UTC (audited `clearMaxAnalyses`), before the new console shipped.

## 2026-09-25 — Measured optimisation, all six passes

Request volume ([perf/request-volume.md](../perf/request-volume.md)):
- Pass 1 (#191) runs `CloudSync.reconcile` one call at a time.
- Pass 2 (#206) shares the launch `/v1/config` fetch.
- Together: 12 → 3 requests per app open on the Pixel 6. Both are merged and
  ship with the next app build.

Pass 4 (#202) is deployed: an inline `POST /v1/sessions` costs 6 + N
Firestore reads instead of 8 + 2N, with no rise in latency (median 2487 ms, n = 10)
(see the 2026-09-25 inline-create deploy below).

Measured, no change:
- Pass 3: one ~1.7 s App Check attestation per cold open, which only the Play
  account can remove.
- Pass 5: no cold-start gain from precompiling ([perf/startup.md](../perf/startup.md)).
- Pass 6: no engine or viewer regression
  ([perf/engine-viewer-check-2026-09.md](../perf/engine-viewer-check-2026-09.md)).

## 2026-09-25 — Backend deploy: a session-delete bucket (#226)

Production `semper-api-36122511953-1` from `cb0e893` (staging first, run
36122073964 → `semper-api-staging-36122073964-1`; production run 36122511953).
Candidate smoke passed on both; the gateway dry-run found the live config
`v202609250953-51` already serves the spec.

- #226: `DELETE /v1/sessions/{sid}` draws on its own `session_erase_bucket`
  (1/s, burst 10) instead of sharing account erasure's (0.2/s, burst 3). Ten
  deletes from Home had taken 61 requests over 100 s, 38 of them 429s. The app
  half (#227: one paced queue, no re-sent deletes) ships with the next app release.

## 2026-09-25 — Backend and console deploy: wrong-information audit (#211, #215, #216, #221, #224)

Production `semper-api-36120337264-1` from `fd14374` (staging first, run
36119768491 → `semper-api-staging-36119768491-1`; production run 36120311362 →
`semper-api-36120311362-1` with the gateway dry-run, then run 36120337264 with
`apply`). Candidate smoke passed on all three. The gateway diff was descriptions
and documented response codes only; `apply` switched `semper-gw` from
`v202609241122-44` to `v202609250953-51`, and the outside check answered 401 on
`GET /v1/config` and 200 on the preflight. The console went out with
`scripts/deploy-console.sh` to `indicvision-dic-app-auth`; `app.sempermechanics.com`
serves it against the production gateway.

- #211: a licensed analysis ceiling is never below demo's; staff can clear a
  licence's per-person cap (`clearMaxAnalyses`); the operator form and table name
  the field "Analyses / person".
- #215: seat and lease counters — no resume on a removed seat, a lease that
  expired unswept is still decremented, whole-licence revoke and account delete
  free their seats and leases, a stranded invite is retried.
- #216: console labels and dates — Mode column, expired/in-grace pills, expiry as
  a UTC date, reconcile reasons instead of "never claimed", `held` on `/v1/me`.
- #221: the lease sweep reclaims each seat in its own transaction; a lost race is
  `503 claim_contended`, not "no seats left"; the quota refusal names an ended
  licence or a missing seat; Extend refuses a past, earlier or perpetual expiry;
  claims mirror the licence terms read inside the transaction.
- #224: the device-change cooldown refusal carries its date (and `Retry-After`);
  `maxSeats` below seats in use is `422 max_seats_below_used`; revoke returns the
  reset counts; invites are not claimed onto a lapsed licence; a provisioning
  retry cannot undo COMPLETED; failed uploads leave the quota and show no size.
  The quota count now costs one more Firestore read on `GET` and `POST
  /v1/sessions` (accepted by the owner over a new composite index;
  [perf/request-volume.md](../perf/request-volume.md)).

The audit's app half (#211's ceiling, #212, #218, #219, #220, #223, #225) ships
with the next app release.

## 2026-09-25 — Backend deploy: inline create reads (#202), deployer IAM (TD-71)

Production `semper-api-36096112375-1` from `300fb7f` (staging first, run
36095277741 → `semper-api-staging-36095277741-1`; production run 36096112375).
Candidate smoke passed on both; the gateway dry-run found the live config
`v202609241122-44` already serves the spec.

- #202: an inline `POST /v1/sessions` answers from the upload targets it opened
  instead of reading the session and file docs back: 8 + 2N → 6 + N Firestore
  reads (N = 3: 14 → 9). `backend/tests/test_read_budget.py` holds each route's
  cost ([perf/request-volume.md](../perf/request-volume.md) Pass 4). Production
  latency after the deploy: median 2487 ms over 10 uploads (was 3158 ms, n = 9), so
  no rise.
- TD-71: every deploy failed after the clean-up with `403 … storage.buckets.list`,
  because a source deploy lists the project's buckets to find `run-sources-*`.
  `indic-deployer` now also holds `roles/storage.bucketViewer` (buckets get/list,
  no object access); both deploys ran on the narrowed roles.

## 2026-09-24 — Deny-all Firestore rules deployed (#185)

#185 (TD-70) replaced Hosting's undeployable `firestore` block with
`scripts/deploy-firestore.sh`; the deny-all `firestore.rules` then went to
`indicvision-dic-app`. Anonymous REST reads and a create answer
`403 PERMISSION_DENIED`; the Auth project has no Firestore database.

## 2026-09-24 — App release v1.2-beta.2 (#182)

`v1.2-beta.2` (beta channel, private GitHub Release) from `fab33cb`, main CI
green through Tier 3 and Tier 5: `v1.2-beta.1` plus #182, which keeps the
upload CSV's `#` field-stats rows above the point section (TD-66).

## 2026-09-24 — Backend deploy, first CI gateway apply (#173, #179, #182, #183)

Production `semper-api-35992296245-1` from `4d5a0ab` (staging first, run
35990629608). It carries #173 (firebase-admin 7.6.0, google-auth 2.58.0,
google-cloud-firestore 2.31.0, uvicorn 0.53.0) and #179 (`python:3.12-slim`
digest `2f17fc0`). After the owner's IAM grant, ADR-006's `gateway` job ran for
the first time: a dry-run failed decoding the live document (`base64: invalid
input`), fixed by #183; the next dry-run's diff was a comment and the removed
`/v1/campus` invite-revoke alias (TD-45); `apply` (run 35992296245) switched
`semper-gw` from `v202609230845` to `v202609241122-44` and passed its
401/preflight checks. TD-27 closed. #182 (TD-66: the upload CSV's stats rows
stay above the point section) merged as `5c4fe19`; app-only, so it ships with
the release after `v1.2-beta.1`.

## 2026-09-24 — App release v1.2-beta.1; strain window in points, engine v0.2.2 (#180)

#180 ported two material_testing changes. The strain window is entered in
data points (odd 3–31, default 5); the engine gets the VSG,
`(window − 1) × step + 1` px, and sessions still store and export the VSG
(material_testing #20). The `native/` submodule moved to engine `v0.2.2`,
which rejects displacement outliers with an iterated 5×5 normalized median
test before the VSG fit (semper-dic-engine#3, material_testing #21: on a
published PMMA bend, 45 px strain RMSE over all frames 915 / 3874 / 2117 →
412 / 394 / 392 µε). Merged as `2214860`; main CI green through Tier 3 and
Tier 5. Released as `v1.2-beta.1` (beta channel, private GitHub Release,
Release run 35987799464), which also ships the #155–#168 burn-down's app
half and everything app-side since `v1.2-beta.0`. No backend change.

## 2026-09-24 — Tech-debt burn-down (#155–#168)

Fourteen stacked PRs from the verified register in
[TECH_DEBT.md](TECH_DEBT.md), with [ADR-001..006](../adr/README.md). Merged
2026-09-24 05:40Z; main CI green through Tier 3 and Tier 5. The backend half
(`app/repo/` package behind the `firestore_repo` facade, route-aware access
log, `rate_limit.enforce`, bounded outbound `Retry-After`) went to staging in
Deploy Backend run 35963140403 and to production in run 35963412251, revision
`semper-api-35963412251-1` from `11eddc5` (with #171); `readyz ok`, 100 %
traffic. The app half (`RunSpec`, `ViewerArgs`, wizard draft, `CloudApi` seam,
checked PDF write) waits for the next release. The same production run was
ADR-006's first `gateway` run, `dry-run`: it stopped at the diff step with
`apigateway.apis.get` denied, before the owner's IAM grant. The live gateway
config therefore still routes the removed campus invite-revoke alias (TD-45),
which the backend now 404s; nothing calls it.

## 2026-09-24 — Terms 16+ (#169), reverted to 18+ (#171)

**Terms: age 16+, then back to 18+ (owner decisions, 2026-09-24).** #169
changed Terms §1.3 to admit users from 16, with a parent or legal guardian
agreeing for anyone under 18 (Privacy §8 to match), and moved `TERMS_VERSION`
to `2026-09-24`. It was deployed to staging and production (revision
`semper-api-35959026266-1`) and to Hosting. The owner then asked for 18+
again: #171 restores the legal documents, hosted pages, `backend/app/legal.py`
and `LegalTerms.kt` to version `2026-09-15` byte for byte, so users who
accepted `2026-09-15` stay accepted and only those who accepted `2026-09-24`
while it was live are asked again. Ported from, and reverted with,
material_testing #12 and #15.

## 2026-09-24 — A 429 no longer spends the nonce (#154)

**A 429 no longer spends the nonce (#154, live 2026-09-24).**
Erasing seven analyses from a Pixel 6 left two in the cloud: past the erase
bucket's burst of three, each delete got a 429, and the app's unchanged retry
came back 401 `nonce_invalid_or_replayed`, because the bucket was checked in
the handler after `verified_device` had claimed the nonce. The 401 also made
the app drop client nonces for the rest of its process, so from then on the
retry replayed a consumed server challenge and nothing recovered. The 21
signed routes now take their bucket as `dependencies=[rate_limited(...)]`,
resolved before the nonce is touched, and their 429s send `Retry-After`. No app
change; installed builds are fixed by the deploy. Nothing was lost: a failed
cloud erase keeps the local copy. Production revision `semper-api-35957034833-1`
(from `2fdb44c`) has served since 04:48Z; the same phone then erased four at
once, the fourth got a 429, waited 2 s and its unchanged resend returned 200,
with no 401 — the two left over are gone from the cloud too.

## 2026-09-23 — Starved device-lock binds (`fix/deflake-device-lock-test`)

**A starved device-lock bind is not a loss (`fix/deflake-device-lock-test`).**
The emulator test `test_the_first_device_wins_an_unbound_lock` flaked (2 in 10
locally, and on PR #148) with **zero** winners: all eight binds exhausted their
transaction retries and `bind_device_lock` read that as "another device won",
returning False with the lock still empty — which `_activate_individual` then
answered as `license_device_mismatch`. A starved round now re-reads the lock
and binds again (three jittered rounds); if every round starves it raises
`DeviceLockContended` (`503 device_lock_contended` on activate; swallowed on
the request path, leaving the licence unbound for the next request). Details:
[CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md) §20.1.

## 2026-09-23 — Upload gives up on missing files (#148)

**Upload stops retrying a session whose files are gone
(#148, merged 2026-09-23).** `DicUploadWorker` returned `Result.retry()`
forever when report staging came out incomplete, so a session whose `.dat`
files had been deleted sat on "upload pending" for days (Pixel 6, since
2026-09-21). `UploadWorkOutcomes.classifyIncompleteStaging` now keeps retrying
only while the inputs are on disk, the row was saved under 15 min ago, or they
have been missing for under 10 min (timed by an `upload_inputs_missing_since`
marker in the session folder and restarted whenever the row is re-saved, so a
re-run's brief `.dat` gap does not count). Otherwise the worker fails with
`cloud_backup_failed_missing_files`, which shows on the Home FAILED badge and
its dialog.

## 2026-09-23 — Video sampling and long snackbars (`fix/video-estimate-snackbar`)

**Video sampling and long snackbars (`fix/video-estimate-snackbar`).** The
sampling sheet promised one frame more than a fixed-interval extraction
delivered whenever the segment reached the clip's end: it sampled at the end
itself, where no frame starts. The sheet now caps the segment at
`VideoKeyframeHelper.lastFrameStartMs` and counts `uniformTimestampsUs`, which
the retriever fallback now uses too. `FaqRedirect.snackbar` shows up to five
lines and stays up as long as its message takes to read, capped at 10 s.
Same fix as material_testing #9.

## 2026-09-23 — Quicker session setup (#149)

**Quicker session setup (#149, deployed 2026-09-23).** Two Pixel 6 backups
spent 5.37 s and 3.43 s provisioning inline (a bundle is 3 files, under the
queue threshold). The folder step made three Drive calls in a row; now the
cached `session/` check and the new session folder's create run together, with
no name search for an id minted in the same request, and a retry reuses the
stored folder. `session_provisioned` logs `folderMs`. The next Pixel backup
provisioned in 2.40 s (folders 1.23 s), `POST /v1/sessions` 3.12 s.

## 2026-09-23 — App Check on real phones (`fix/appcheck-real-phones`, merged as #153)

**App Check attests on real phones (branch `fix/appcheck-real-phones`).** The
provider was skipped whenever the `DEV_AUTH_BYPASS` build flag was on — every
debug build, real phones included. It now keys on the bypass being active
(emulator only). That exposed that the Auth project has the App Check and Play
Integrity APIs disabled, so no build had ever sent a token. Both APIs are now
enabled and the app is registered with Play Integrity; the Play Console link
waits for a Play developer account (AUTH_SETUP §3.2, gate). Harmless while
`APP_CHECK_MODE=off`.

## 2026-09-23 — Deploys prune their tags (#151)

**Deploys prune their tags (#151, deployed 2026-09-23).** The promote
routes `--to-latest` and drops every `cand-*` tag in the same call (TD-32
closed; #151, deployed to both environments with no tags left).

## 2026-09-23 — Backend names standardised (#146)

**Backend names standardised (2026-09-23, #146, deployed).** Cloud Run
services are rebuilt as `semper-api` / `semper-api-staging` with queues
`semper-provision` / `semper-provision-staging`; the gateways point at them and
the old `indic-api*` services, their images and the `indic-provision` queue are
deleted. Rollback images: `semper-api:rollback-c0c0ce3`,
`semper-api-staging:rollback-prev`.
Staging now has its own queue and task target (environment-scoped GitHub vars).
The legacy `indic-gw` gateway is deleted. Service-account emails and project IDs
keep their `indic-*` names — see the names table in
[docs/ops/ENVIRONMENTS.md](ENVIRONMENTS.md).

[Note 2026-09-23: unverified from the repo — [TECH_DEBT.md](TECH_DEBT.md) TD-30
still lists the legacy `indic-gw` / `indic-api` pair as provisioned. Check with
`gcloud api-gateway gateways list` before relying on either statement.]

## 2026-09-23 — Video import reads AVI (#136, #137, #139)

**Video import reads AVI (#136, #137, #139, merged 2026-09-23).** #136 landed
the keyframe/uniform extraction path that pulls frames out of the raw Y plane;
#137 fixed its fixed-interval mode, which had returned the preceding I-frame
for every sample (the decoder now decodes forward to the requested timestamp);
#139 adds the container Android itself cannot open. `AviReader` demuxes RIFF,
`AviLuma` reads the uncompressed layouts losslessly, `MjpegHuffman` repairs
tableless motion-JPEG frames and `AviCodecDecoder` hands Xvid/H.264 samples to
the platform codecs — no new dependency, no APK growth. A codec the device
cannot decode is now named in the error instead of failing blank. On an emulator
(`VideoFrameExtractionDeviceTest`) two more decoder faults surfaced and are fixed on
`test/video-extraction-emulator`: a fixed-interval segment ending at the clip's
duration asked for a time past the last frame, failed, and dropped the whole batch
to the retriever's I-frame seek; and a flush before the codec's first output lost
its SPS/PPS, so an extraction intermittently fell back the same way. Neither path
has run on a physical device yet (vendor strides, crop; a real camera AVI):
[docs/app/WORKFLOWS.md](../app/WORKFLOWS.md) §5.1a. The same change is being ported to `sempermechanics/material_testing`.

## 2026-09-23 — Faster sign-in, upload and restore (#142)

**Faster sign-in, upload and restore; failures that say so (#142, merged
and deployed 2026-09-23).** Launch opens Home
from the cached approval and re-checks in the background (`StatusRecheck`);
`/v1/me` and `/v1/config` run in parallel. Signed calls carry a device-minted
`t1.` nonce instead of fetching a challenge first — one round-trip fewer per
call, falling back to challenges after any refusal
([CLOUD_ARCHITECTURE_GCP.md §3](../backend/CLOUD_ARCHITECTURE_GCP.md)).
The backend caches the user's Drive folder IDs, provisions manifests of eight
files or fewer inline, keeps one warm instance in production with one worker,
logs a failed Cloud Tasks enqueue at ERROR, and answers `drive_file_gone`
instead of a retried 502 when a backed-up file is gone from Drive. `/readyz`
is off the gateway, staging deploys private, and the setup guide no longer
advises `allUsers`. It first served as `c0c0ce3` on `indic-api`; after the
rename (#146) production is `semper-api-35844549945-1` (`d518179`, one warm
instance) behind gateway config `v202609230845`, and the old service, its
`cand-*` tags and the older gateway configs are deleted. The Cloud Tasks
`serviceAccountUser` grant and the staging deployer invoker are applied.
Still owed: proof that the next backup goes through `semper-provision`, and
the app side (ships with the next release) —
[PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md).
Rollback: `gcloud run deploy semper-api --image
…/cloud-run-source-deploy/semper-api:rollback-c0c0ce3`.

## 2026-09-21 to 2026-09-23 — Licensing live in production, and the console fixes (#116–#138)

**Licensing is live in production (2026-09-21/22).** `indic-api` serves
`d6b1b64` (`main` after #116) behind the `semper-gw` gateway config
`v202609211150`; `MAX_SESSIONS_PER_USER` is gone from the service env and
`DEMO_MAX_ANALYSES=25` is the demo cap. Every pre-existing account resolved to
demo on its first request; the installed pre-licensing build was verified
against production (backup, delete, export work; restore is refused once
with a readable sentence). The dashboards are deployed on
`app.sempermechanics.com` (Firebase Hosting custom domain, Netlify DNS) with
Identity Platform + TOTP-only MFA, and `sempermechanics.com/login|account|terms`
redirect there.

Everything since is the staff console meeting a real browser, plus one
backend bug it found. The consoles: #117–#118 (refused cloud download says
why; the Firestore index file kept only the one real composite — production
had none before this rollout), then #120–#128 — `<base>` href, theme, CSP
origins, `init.json`, one SDK origin, no-cache — and #129 the TOTP QR,
#130 the role gate (a non-operator is told where they *can* go instead of
being shown a mint form that refuses), #132 the re-authentication loop
(firebase-auth resolves a redirect's second-factor challenge against
`auth.redirectUser`, so the fresh `auth_time` never reached `currentUser`
and a revoke bounced to Google forever; the desk now also resumes the
revoke itself on the return leg). The backend: #131 — the mint routes
returned the `SERVER_TIMESTAMP` sentinel they had just written, so a
successful mint answered 500 and the licence existed anyway — and #134,
`ADMIN_EMAILS` parsed like `CONSOLE_ORIGINS` because the deploy action
splits `env_vars` pairs on commas and the list now names two operators.
#133 made the emulator tier's two invite races deterministic: a round the
emulator starves grants nothing, so it is re-raced rather than asserted on.
`fix/deflake-individual-licence-test` does the same for
`test_one_individual_licence_reaches_exactly_one_account` (test-only; a
starved round must answer `_contended` to all and leave the licence unused).
The repository is public as of 2026-09-22 (CI minutes; the engine submodule
was already public), and `damodar@indicvision.com` is the second operator.

**#131 is deployed** (2026-09-23): production serves `7593692` as revision
`indic-api-35821056144-1`, the first with both operators' `ADMIN_EMAILS`
pinned by the workflow. #138 (merged and deployed to Hosting 2026-09-23) fixes the desk's silence: a revoke that
failed said nothing and one that worked stayed listed until a reload,
because redirect re-authentication started the page twice and the list
reload cleared the result. It also makes a duplicate mint for one address
ask first — the backend is licence-first by design, so the 500 retry had
left `SEMP-5MZP` beside `SEMP-EQUZ` (revoked 2026-09-23)
([CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md) §20.3,
§20.8, now with the browser and gateway in §1). Still open in
[docs/ops/PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md)
"Licensing rollout": the first minted licence, the staff
hand-check of `/login` as an ordinary account holder, the new-build
demo-key check, and the 24 h log watch. Rollback targets: Cloud Run
revision `indic-api-00067-mbp` (pre-licensing `indic-api-31896308319-1`),
gateway config `v202608081145`.

## 2026-09-19 — Licensing (#100, #110, #111)

**Licensing ([#100](https://github.com/sempermechanics/semperdic-app/pull/100),
merged 2026-09-19; go-live follow-up
[#110](https://github.com/sempermechanics/semperdic-app/pull/110) and the
link fix #111 merged the same day).** `main` is deployable; nothing has been
deployed yet — production Cloud Run is still `bcc467a` and the gateway config
predates licensing. What is left is ops, at the end of this
section and, checkbox by checkbox, in the "Licensing rollout" section of
[docs/ops/PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md). It extends flat demo/licensed into two licensed
shapes and one roster mechanism. Organised below by subject, not by the order
the branch built it in. Full model:
[docs/backend/CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md)
§20. What follows is the map of decisions that are expensive to rediscover and
easy to undo by accident.

*Shape.* `kind` is `individual` or `institution`, `duration` is `perpetual` or
`timed`, `seating` is `assigned` or `floating`. The three are orthogonal, and
contradictory combinations are refused at mint. Institution seats live at
`licenses/{id}/seats/{uid}`. A floating licence's `maxSeats` caps *concurrent*
leases while the roster stays uncapped — fifty people sharing ten slots — and a
member between leases is demo, the ordinary state rather than a failure. The
lease lives on the seat document, not a `leases` collection, because
`check_device_lock` already reads that document on every institution request;
its expiry is mirrored onto the user so `effective_mode` needs no Firestore
read. `assigned` is the default and means what every pre-existing licence
already meant, so neither seating nor duration needed a migration.

*Terms are mirrored, so editing a licence reaches nobody on its own.*
`resolve_user_config` is deliberately free of Firestore reads, which means
expiry and grace are copied onto each user at activation, and
`PATCH /v1/admin/licenses/{id}` has to fan renewed terms out to the individual
redeemer or every non-revoked seat. A timed licence keeps **full** entitlements
for `graceDays` past `expiresAt` — grace is inside the licensed branch, not a
reduced tier — and a licence stored with no `graceDays` reads as ZERO rather
than the fleet default, or deploying it would have reinstated everyone who had
expired inside the window. Activating past grace is refused (`license_expired`,
403) rather than landing the user silently on demo. The app shows a Home notice
inside 14 days of expiry or during grace, suppressed when its cached config is
over a week old: advisory only, `mode` is still the only gate.

*Revoke and downgrade.* Whole-key revoke drops every seat to demo and frees all
slots; single-seat revoke frees only that slot; disable drops to demo but keeps
the slot held. Downgrade never deletes data — it only blocks new analyses past
the cap, pinned by a test that seeds 30 sessions, downgrades, reactivates, and
loses nothing.

*Nothing is ever typed.* Both shapes are minted against an email address, which
writes `licenseInvites/{sha256(email)}`; that person's next sign-in redeems it
through `claim_pending_invite`, which grants either the licence itself or a
seat. Top-level and hashed: one document read on a hot path rather than a
collection-group query, and a plaintext address would be both an illegal
document id and enumerable. Redemption requires a **verified** email, since the
address is the whole claim to the licence. `ensure_entitlement` tries the
invite *before* `ensure_demo_license`, which stamps a `licenseId` that every
later call short-circuits on. `POST /v1/licenses/activate` and
`IndicApi.activateLicense()` survive as support recovery, with no caller in
`app/src/`.

*The device lock binds on first use.* `_device_lock_state` answers **unbound**
for a licence or seat that has not met a device, and `revalidate_device_lock` —
already on every authed request carrying `X-Device-Id` — binds it
transactionally, first writer wins. `ensure_demo_license` is a compare-and-set
for the same reason: it used to write blind, so of several requests racing at
launch the loser could stamp a demo key over the licence a sibling had just
granted. A claim also deletes the auto-minted demo key it supersedes
(`_drop_superseded_demo`, after the commit rather than inside it — reading the
account in the claim's own transaction locks it, and six concurrent sign-ins
then starve each other out), discriminating on `mode: demo` **and**
`createdByUid: "system"` on the licence, never the holder's mode mirror, which
revocation deliberately leaves demoted-in-place. A claim that merely lost the
race answers with a private `_CONTENDED` logged at `info` and mapped back by
`_public_claim_error`, so contention stops reading in the logs like a licence
with no room left; and a failed claim re-reads the account instead of returning
the caller's pre-race copy, which is what the consoles need, since a browser
sends no `X-Device-Id`. Revoking withdraws the licence's outstanding invites,
and `_write_invite` overwrites one whose licence is revoked or gone, so
mint → revoke → re-mint to the same address delivers. Three emulator tests
cover the three races (one redeemer, one invite, one device); seat
claim/revoke/checkout/release run under real transactions that fail closed,
after a read-then-`WriteBatch` was found able to push a pool past `maxSeats`.

*Moving to another device is a clear, not a revoke.* One primitive,
`clear_device_lock(license_id, uid, actor=…)`, behind four routes: the IT seat
patch, a staff seat clear (`PATCH /v1/admin/licenses/{id}/seats/{uid}/device`),
`clearDeviceLock` on the staff licence patch, and `POST /v1/licenses/unbind`
for the holder. Emptying the lock is the whole change, since the next device to
sign in takes it. `_restore_holder_mode` re-stamps the mode as part of the
clear: a device change is normally preceded by the holder *trying* the new
phone, which demotes the account in place, and `revalidate_device_lock` returns
early for a demo account — without it the clear would leave them on demo
holding a live licence. It is guarded (not a revoked licence, not a revoked or
disabled seat, not an account that has moved on) and runs outside the
transaction. The holder's own path alone waits on
`SELF_DEVICE_CHANGE_COOLDOWN_DAYS` (30) against a `deviceChangedAt` only it
writes, because a second factor proves who is asking and not how often; staff
and IT never read or write it, so support always works. Both halves of a change
are audited, and operators can read the history per licence. **Restore has an
order**: sign in, let one authed request bind the lock, then restore — file
content is device-attested, so restoring first fails as unlicensed on a phone
the user has legitimately just moved to.

*A revoke reaches three places on three clocks, and IT sees only one.*
`seatsUsed` moves inside the revoke transaction; the holder's user document
just after it (`_drop_user_to_demo_if_licensed`, outside the transaction and
unretried); the holder's *device* only at its next `/v1/config` fetch. So the
institution console can report intent and nothing more.
`GET /v1/admin/licenses/{id}/reconcile` is the second number — a plain `ADMIN`
read, one user lookup per seat, sorting each into `active` (with
`never_claimed` for a roster place nobody took up), `revokedConfirmed` or
`revokedStillRunning`, each with a reason. Confirmation takes **two**
conditions, because the demotion already runs at revoke time and a bucket keyed
on stored mode alone would read clean almost always: the record has caught up
**and** `lastSeenAt > revokedAt`. `still_licensed` is a fault, repaired by
revoking again (idempotent); `no_checkin_since_revoke` is waited out. It reads
**stored** mode, never `effective_mode`, under which a floating member between
leases — the ordinary state for most of a roster — would report a healthy pool
as mass revoke failure. Both revoke paths stamp `revokedAt` apart from
`updatedAt`, which a later staff device clear would otherwise reset.
`lastSeenAt` is throttled to an hour, which would have made the read
over-report unlanded revokes for that long; the revoke stamps
`seenCheckpointAt` on the holder in the write the demotion already makes, and
a `lastSeenAt` older than that checkpoint is stale whatever its age — so the
holder's next request confirms the revoke, at the cost of one un-throttled
write per revoked account.

*Three authz tiers, deliberately distinct.* Institution IT authenticates on
`current_user` + APPROVED + verified email in that licence's `adminEmails` — no
device attestation and not Semper `role=admin`, so IT self-service never needs
a phone. `ADMIN_STEPUP` accepts either a device attestation or an admin ID
token carrying a completed second factor from a sign-in newer than
`ADMIN_WEB_REAUTH_SECONDS` (15 min; whole-licence revoke uses the tighter
`ADMIN_WEB_REVOKE_REAUTH_SECONDS`). It is weaker than device binding — a
phished live MFA session inside the window can mint a licence — which is why it
is its own tier rather than folded into `DEVICE_ADMIN`, and why
`ADMIN_WEB_MFA_ENABLED=0` withdraws it. `USER_STEPUP` is the same shape with
`current_user` beneath it (one shared `_attested_or_mfa`), carrying
`/v1/licenses/unbind` and `GET /v1/sessions/{sid}/bundle`. Every route has a row
in `tests/test_route_authz_matrix.py` and a declaration in
`gateway/openapi.yaml`; `tests/test_gateway_parity.py` fails the build on a
missing one, because ESPv2 is an allowlist and an undeclared route is
unreachable in production with nothing in the logs — a trap that has bitten
twice.

[Note 2026-09-23: institution IT now also needs the browser second factor —
`institution_admin_stepup`,
[CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md) §20.4.]

*Four web pages, one front door.* `/login` reads `GET /v1/me` and
`GET /v1/institutions/licenses` and forwards: staff to `/console/operator`, an
address named on a live licence's `adminEmails` to `/console/institution`
(deep-linked when there is exactly one), everyone else to `/account`. That
listing is a route rather than a field on `/v1/me`, which promises no extra
Firestore read and is called on every app launch; it is one `array_contains` on
`adminEmails` with kind and status filtered in Python, so no composite index.
`/login` and `/account` are Hosting rewrites into `/console/`, which is why the
relaxed console CSP is restated for them — a header matches the request path,
not the rewrite target — and why every page carries a `<base href>`. Each page
loads one ES module from a file beside it: **inline scripts do not run**, since
the console CSP is `script-src 'self'` with no `'unsafe-inline'`.
`scripts/check_console.py` (CI job `console-pages`, unfiltered) is the consoles'
only gate — they have no compiler — and it is what found all four pages inlined.
TOTP enrolment shows the secret for manual entry, since posting it to a QR
service would hand away the factor protecting licence issuance; SMS is never
enabled, so `auth.js` carries no phone branch. An analysis leaves through
`GET /v1/sessions/{sid}/bundle`: one `ZIP_STORED` archive over every completed
artifact — not a proxy of the stored `Session.zip`, which would drop the
`extras` zip modern sessions also carry — streamed into an unseekable sink so
600 files cost flat memory, with every refusal resolved before the first byte,
since a started body has no status code left. `list_session_artifacts` is kept
apart from the projection feeding `/v1/me/export`, so a field added here can
never widen the GDPR export.

*On the phone.* `seatRequiredToStart` is a **parallel** predicate to the quota
gate — an institution member is licensed, so `isSessionLimitReached` is false
for them by definition and they would sail past every existing check. It gates
the Home **+** before the source menu opens, and both compute paths, guarded by
`wouldCreateNewSession()` so a run in flight never aborts; `SeatRequiredActivity`
is one button that asks again, because seats free themselves. Floating seats
renew in-process on `seatHeartbeatMinutes` and release on sign-out, and a
four-hour `LicenseConfigWorker` refreshes `/v1/config` so an idle phone learns a
remote revoke (FI-16: four hours is the worst case, and shortening the interval
is the wrong lever — it costs every device every day to reach one). Demo
accounts upload silently (see *Demo records* below) but never open the share
sheet; restore maps
`license_device_mismatch` to a bind-first message. Settings → Account shows the
licence **prefix**, which is what support asks for; the key itself never
reaches the device.

*Traps.* `SCHEMA_VERSION` is 2
(`002_rename_campus_to_institution.py`, the first migration to include
`licenses`). The wire said `campus` and `plan: demo|professional`; it now says
`institution` and `mode: demo|licensed`, and every skew fallback is temporary —
the `plan` mirror on `/v1/config`, the hidden `/v1/campus/*` aliases (declared
in the gateway, or ESPv2 rejects them), the old pref key on upgrade,
`kind="campus"` still accepted at mint. Retirement order:
CLOUD_ARCHITECTURE_GCP §20.5. Licences are still keyed by the sha256 of their
key; opaque ids wait for the change that needs them, a licence with no key at
all. `MAX_SESSIONS_PER_USER` is **deleted**, not merely unread: `mode` selects
between `DEMO_MAX_ANALYSES` (25) and `LICENSED_MAX_SESSIONS_PER_USER`, so **a
deployment still setting the old variable silently gets 25 instead of 4**.

*Demo records; only retrieval is licensed (2026-09-19 decision).* A demo
account's analyses are uploaded and stored — images and results — but demo has
no backup/restore *feature*. On the backend that means `POST /v1/sessions` and
the upload broker are open to every approved account and the
`cloudBackupEnabled` gate sits only on `GET /v1/files/{id}/content` and the
session bundle. The reason is the installed fleet: every pre-licensing account
resolves to demo on its first request after the deploy, and the old
`DicUploadWorker` retries a 403 from session creation forever, whereas the old
restore worker gives up on 403 once. So the old app keeps backing up and shows
a single "rejected" on restore. In the new app demo sees **nothing** of cloud:
`CloudSync.uploadsEnabled` ignores the Save-to-cloud toggle for demo, Home
paints no sync badge or row progress, Settings has no Cloud / Analyses-data
section and no Free-up or auto-free control, and `StorageBudget` refuses to
evict on demo because the copy could not come back. Minting an individual
licence for an address that already has an approved, verified account attaches
it at mint time (`_attach_to_existing_holder`) instead of leaving an invite
that `claim_pending_invite` would skip over the stamped demo key.
`deploy-backend.yml` now pins `DEMO_MAX_ANALYSES`,
`LICENSED_MAX_SESSIONS_PER_USER`, `ADMIN_WEB_MFA_ENABLED`, `APP_CHECK_MODE` and
`SELF_DEVICE_CHANGE_COOLDOWN_DAYS` with safe expression defaults and warns when
the retired `MAX_SESSIONS_PER_USER` is still on the service. Still open: one
sentence in the Privacy Policy / Terms saying demo analyses are uploaded, and
the Play Data-safety form — both listed in the readiness gate.

*Dashboards on `app.sempermechanics.com`; CORS (`feat/console-domain-cors`).*
The consoles are hard-bound to Firebase Hosting (`auth.js` imports the SDK and
`firebaseConfig` from the reserved `/__/firebase/` namespace; `/login` and
`/account` are Hosting rewrites), and `sempermechanics.com` is the Netlify
marketing site, so the dashboards get a **custom domain** on the auth Hosting
site rather than a copy: `app.sempermechanics.com`. The marketing site links
"Sign in" there and redirects `/login`, `/account` and `/terms/` — the last
fixes a live 404 that `backend/app/legal.py` and the Terms clickwrap were
already pointing at. The same host carries the app's auth continue links under
`/auth/` (`AUTH_HOST`), with the `firebaseapp.com` host kept as
`LEGACY_AUTH_HOST` for every installed build and for the password-reset action
URL (TD-29). Found on the way: the API had **no CORS at all** — no middleware,
no `allowCors` on the gateway — so a console `fetch` carrying `Authorization`
was preflighted and refused at the edge from any origin. `CONSOLE_ORIGINS`
(Cloud Run, pinned by the workflow) plus `x-google-endpoints … allowCors` with
the `__MANAGED_SERVICE__` placeholder in `gateway/openapi.yaml` (the API's
managed service name — not the gateway hostname, which ESPv2 ignores) close that;
`test_security_controls.py` pins the preflight. The gateway must be redeployed
from the new spec before the consoles are usable.

*Go-live is ops, not code.* Identity Platform has to be enabled with TOTP on and
SMS off, or the consoles sign in and every write fails `mfa_required`; the API
Gateway has to be redeployed, since the backend workflow does not touch it; then
[`scripts/deploy-console.sh`](../../scripts/deploy-console.sh), whose trap restores
the `__API_BASE_URL__` / `__API_ORIGIN__` placeholders that
`check_console.py` insists stay placeholders. Steps and failure symptoms are in
[firebase-hosting/public/console/README.md](../../firebase-hosting/public/console/README.md).

Docs: [docs/backend/CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md)
§20 (§20.12 for the two seat counts), [docs/app/WORKFLOWS.md](../app/WORKFLOWS.md) §9,
[docs/app/ARCHITECTURE.md](../app/ARCHITECTURE.md),
[docs/backend/AUTH_SETUP.md](../backend/AUTH_SETUP.md) §3.1,
[docs/OPERATING_MANUAL.md](../OPERATING_MANUAL.md) Appendix D, and
[firebase-hosting/public/console/README.md](../../firebase-hosting/public/console/README.md).

## 2026-09-19 — Architecture pass on the licensing branch

**An architecture pass on the same branch closed four things and deferred
three.** It read the app against the standard boundaries — hoisted UI state,
main-safe I/O, injected dependencies, attested network calls — and the findings
worth acting on were the ones that cost correctness rather than shape.

*App Check answers a question an ID token cannot.* `verify_id_token` attests the
account and `deps.verified_device` attests the device, but neither says which
*binary* is calling, and the Firebase Web API key that mints an ID token ships
inside the APK as an identifier rather than a secret — so a script holding a
valid sign-in could drive `POST /v1/licenses/checkout` and hoard a floating
pool. The phone now attaches `X-Firebase-AppCheck` from a host-scoped OkHttp
interceptor, and `current_user` demands one **only from a caller sending
`X-Device-Id`**: the app always sends it and a browser never does, so the four
consoles are exempt by construction rather than by a route list somebody has to
keep in step. The check runs *before* `get_or_create_user`, so a caller on its
way to being refused neither creates an account row nor moves a device lock, and
it answers `app_check_required`, not `not_approved` — the account may be
perfectly entitled. `APP_CHECK_MODE` is `off` (default) / `monitor` / `enforce`,
and a fourth value fails startup rather than quietly disabling the gate. Play
Integrity is the only provider installed: the debug provider needs a per-install
secret registered by hand, and the interceptor fails open, so a developer build
simply sends no header against `off` or `monitor`.

*429 and 503 are not the same answer, and `RetryOnTransient` does not treat them
alike.* 429 is retried unconditionally — the per-instance token bucket and the
gateway quota both reject *before* the handler runs, so nothing happened and a
repeat is not a second write. 503 carries no such promise, since ESPv2 emits it
on both sides of handing the request on, so it is retried for GET and for the
two POSTs that are idempotent by contract (`/v1/licenses/checkout`, whose repeat
*is* the heartbeat, and `/v1/licenses/release`). Session create and the upload
broker are deliberately absent: a duplicate there costs a Drive object. Three
attempts, `Retry-After` preferred over the backoff and clamped; the long game
stays WorkManager's, and this layer exists for the interactive calls that have
no second chance and used to surface a one-second throttle as a flat failure.

*Four session-index reads still ran from a tap*, in Home and Settings backup,
the session-limit recheck and the viewer's share path. Both backup sites keep
their write order rather than turning optimistic: the PENDING stamp lands before
`CloudSync.enqueueUpload`, or an upload that finishes first has its SYNCED stamp
overwritten by a late PENDING. The viewer warms its `sessionRecord` lazy in
`onCreate` instead of restructuring `ShareCenter` — `by lazy` is synchronized,
so a tap arriving mid-read waits on the read it would have done itself and never
on a second one. `SessionStore`'s blocking accessors carry `@WorkerThread` as
documentation only; see TD-24 for why that is half a pair. And the parameter
sweep moved from the Activity's `lifecycleScope` onto `viewModelScope`, emitting
progress and outcome as `SharedFlow`, so a rotation mid-sweep no longer throws
the run away.

[Note 2026-09-23: `StaticAnalysisActivity` declares full `configChanges`
(`app/src/main/AndroidManifest.xml`), so a rotation does not recreate the
wizard; the remaining risk is process death —
[ADR-005](../adr/ADR-005-wizard-process-death.md).]

*The three deferrals each name their blocker.* TD-24: `@MainThread` on ~27
Activities is the other half of the thread contract, and needs a lint run to
land against an empty baseline. TD-25: `AuthRepository` has no fake backend to
test against, because `IndicApi` is final with a private constructor — a seam
there admits only the real client, so it wants the interface extraction that
CONTRIBUTING defers to a DI PR of its own. TD-26: wizard and viewer UI state
still lives on the Activity rather than hoisted as `StateFlow`, on a 1.8k-line
native-solve screen with bit-exact `.dat` oracles; the part that was losing work
was the run, and the run is now on `viewModelScope`.

Docs: [docs/backend/AUTH_SETUP.md](../backend/AUTH_SETUP.md) §3.2,
[docs/app/ARCHITECTURE.md](../app/ARCHITECTURE.md),
[docs/backend/BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md).

## 2026-09-18 — Terms clickwrap (#109)

**Terms clickwrap (merged to `main` in #109, and into `feat/license-demo-pro`
on 2026-09-18).** The Terms of
Service were rewritten from the owner's side (IP and anti-reverse-engineering,
acceptable use, AS-IS warranty disclaimer, capped liability, indemnity, sole
discretion termination, Indian law with Chennai arbitration and a class waiver,
professional use only, not offered in the EU/UK) and the Privacy Policy brought
to a GDPR-grade standard without EU/UK-specific commitments (grievance officer,
consent-based product improvement, transfers, breach handling, full rights
list). Hosted pages regenerated. Acceptance is now a **clickwrap**:
`AccessRouter.intentFor` sends every sign-in through `TermsActivity` until the
device has accepted the version in force; the backend records it
(`POST /v1/me/terms`, 409 on a stale app) and a separate, pre-ticked-but-declinable, withdrawable
improvement consent (`PUT /v1/me/consents`, Settings → Your data). Details in
[docs/backend/AUTH_SETUP.md](../backend/AUTH_SETUP.md) §3a. Bracketed operator
fields in both documents still have to be filled before public distribution.

## 2026-09 — Backend lock regeneration in CI (#105)

**Backend lock regeneration is a CI workflow now, not a local chore**
([#105](https://github.com/sempermechanics/semperdic-app/pull/105)). Dependabot
bumps `backend/requirements.txt` and cannot produce the hashed
`requirements.lock`, so every backend bump used to land Tier 4 red — #103 left
`main` red for exactly that reason. `.github/workflows/backend-lock.yml`
compiles on Linux / Python 3.12, verifies on any PR touching either file
(attaching the regenerated lock as an artifact), and pushes on
`workflow_dispatch` against a chosen branch. See
[docs/ops/CI.md](CI.md) § Dependabot cheap path.

[Note 2026-09-23: the push uses the workflow's `GITHUB_TOKEN` (`git push` at
`.github/workflows/backend-lock.yml:127`), and a push made with `GITHUB_TOKEN`
does not start a new workflow run. A Dependabot PR fixed this way keeps its old
red checks until they are re-run by hand.]

## Undated (before #100) — camera removal, import and sweep fixes, security pass

Lint and detekt burn-down on this branch: empty baselines still; `:app:detekt`
and `:app:lintDebug` report zero findings. Settings / wizard settings content
inflates through `SettingsScrollContentView` /
`WizardStepSettingsContentView`. `OldTargetApi` stays disabled until a
deliberate targetSdk PR.

**The in-app camera recording feature has been removed.** Home **+** now opens
the import source chooser directly; there is one acquisition path. Deleted with
it: `ui/capture/` in full, the `CAMERA` permission and camera `<queries>`, the
two capture activities, and the measured **capture noise floor** end to end —
the stored `SessionRecord.captureFloor`, its viewer caption, its CSV preamble
line and its PDF cover section.

Two consequences worth knowing:

- **The CSV's rigid-body motion columns survived and became unconditional.**
  `shift_u_px, shift_v_px, shift_rot_deg` used to be gated on a session having a
  measured floor, which tied an export column to the camera by accident. The fit
  is read off the solved field itself (`RigidBodyFit`), so every session now
  carries the columns; a frame that admits no fit writes three empty ones.
- **`SubsetRecommender` is back on the paper's constant.** The measured
  `D(eta)` came from the capture burst and no import can supply it. Since
  `thresholdFor` clamps to `max(measured, NOISE_VARIANCE)`, recommendations can
  only loosen slightly, and only relative to runs this app captured itself.

Upgrade safety is pinned by `SessionStoreLegacyFloorTest`: `SessionStore`'s
parser is built with `ignoreUnknownKeys`, so an `index.json` written before the
removal — still carrying its `captureFloor` object — loads unchanged. No
migration.

`NoiseFloorPixels` / `NoiseFloorProbe` / `NoiseFloorStats` stay in
`ui/analysis/`: they take plain arrays or raw image bytes, so they judge an
imported frame as readily as a captured one.

`SpeckleScale` and `DicGoodPractice` came the same way and now have a caller.
`SubsetRecommender.recommend` measures the speckle diameter by autocorrelation on
the very patches it already reads for SSSIG — no extra decode — and returns the
median as `Result.speckleDiameterPx`. The wizard's first step reports it against
the iDICs 3-9 px band: a muted readout under the subset slider whenever it is
measurable, and a warning chip when the pattern is under- or over-resolved, or
when the recommended subset would not span three dots.

The two speckle chips answer different questions and neither substitutes for the
other. SSSIG is a *sum* of gradients over a subset, so it clears its threshold on
a pattern far too fine to resolve simply by growing the subset; speckle size is
what the guidance is actually written against, and what the user can fix at the
bench. The size measurement only reports — it does not steer the recommended
subset, which stays the SSSIG answer.


`RawRgba` closed the DNG-in-`RoiDrawActivity` gap:
one shared helper detects a `w*h*4` blob and samples straight into a
preview-sized bitmap, so the full-resolution allocation never happens.

Import measures a frame the way the engine will see it: `ExifOrientedSize`
applies the EXIF orientation tag to `BitmapFactory`'s bounds, because OpenCV's
`imdecode` rotates and `BitmapFactory` does not. Without it one portrait photo
picked as both reference and deformed frame reported a size mismatch against
itself (4080×3072 vs 3072×4080).

A sweep now runs its whole plan. `ConvergenceGate` is the batch path only:
a batch's consecutive solves are successive frames, so decorrelation means
every later frame is worse, but a sweep's are parameter combinations on one
frame pair, ordered smallest subset first — exactly the ones most likely to
under-converge. The gate was killing sweeps in their opening combinations.

The wizard's inline format warning names whichever formats in the set are not
lossless (`LossyFormatCheck`), reference included, instead of only saying
"JPEG".

**Security pass on this branch.** `_emails_conflict` fails closed, so a token
with no email cannot adopt a device-bound account, and adoption additionally
requires `email_verified`. First-sign-in profile creation uses `create()`
rather than `set()`, so a launch race cannot reset an approved profile to
PENDING. The device signature covers the query string when a request has one.
`AuthActivity` checks arriving auth links against `AUTH_HOST` before handing an
`oobCode` to Firebase — the activity is exported, so an explicit intent
bypasses the App Link filter. The ROI mask read and the RAW reference copy in
`StaticAnalysisActivity` moved off the main thread.

The Demo/Professional plan and license-key work that was mixed into this
working tree belongs to **`feat/license-demo-pro`** and was moved there: no
`plan`, entitlement flags, or license fields are part of the user account
definition on this branch.

`origin/main` includes PRs #85–#96 (FAQ error map), the Dependabot batch
#102–#104, and #93 (Tier 3 microbenchmark skip + hashed lock). Open follow-up:
wizard step/overlap + step-2/3 reorder
([#97](https://github.com/sempermechanics/semperdic-app/pull/97)).
Refresh with `gh pr list --state open` — anything named here will rot.

## 2026-08-08 onward — merged PRs #59–#104, viewer and wizard polish

**Merged since 2026-08-08:** lint extracts #59–#64 and #66; compile/quality #68;
wizard slots/coach #69; `DicBatchRunner` + `DicFieldIo` #70; hashed lock /
Gradle 9.7 / docs #71; faster Release CI #74; API-34 emulators #75; brand/UX
polish #77; device-bound accounts + share logos #78; viewer chrome + media
picker #79; share caption / PDF thread #80; viewer Tufte restyle #81; FAB Files
→ SAF + splash #82; lattice viewer Tufte #83; workflows/docs recapture #84;
launcher icon contrast #85; viewer field FAB + vertical colour rail #86;
media-picker grid seam #87; wizard Paste params row #88; wizard warning FAQs
#91; engine failure reason #92; viewer probe / rest-fit #94; lattice Y readout /
mismatch names / picker dim #95; Tier 3 microbenchmark skip #93; Dependabot
actions / backend / gradle #102–#104.

New analysis picks media in-sheet (Images gallery; **Files** dismisses the sheet
and opens SAF). The reference picker opens full height and dims the grid for
1 s behind a large centred hint ("Select the reference image"); deformed
multi-select stays immediate. Wizard warnings (JPEG, low speckle, speckle size,
frame-size
mismatch, ROI too small, empty/too-big sweep plan) and remaining actionable
errors (engine failure dialog **Why?** plus a lasting ⓘ on the status line,
import / video, viewer batch/OOM/scale, lattice hollow nodes) link to Troubleshooting
sections on the public site behind a leave-the-app confirm; canonical copy in
[docs/app/FAQ.md](../app/FAQ.md) with map in
[docs/app/FAQ_LINKS.md](../app/FAQ_LINKS.md). Frame-size copy names the
mismatched files. `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` are requested when
the gallery tab needs them. Summary GIFs bake `viewer_canvas` (night `#101518`)
into the cache filename. Summary overview and the Animations share target are
single-setting only; a parameter sweep has neither. Launcher adaptive background is day `#F4F9FC` / night
`#101518`; night inverts the S on splash (`windowSplashScreenAnimatedIcon`).

Viewer chrome is inset-aware glass: heatmap rest-fit contains the ROI or
accepted points between the top bar and the scrub bar (colour scale overlays
the right edge), field switcher as a top-left glass pill with the live field
checked in its popup, auto-hiding on a timer, restored by a centre double-tap
when faded. A short tap anywhere on the figure probes. On a still frame the
ⓘ sheet quotes true min/max/mean and a histogram of accepted values. The
looping summary GIF uses one colour scale from every frame's trimmed ends
(lowest min, highest max, possibly from different frames); its ⓘ sheet
quotes those same ends without mean or histogram. Settings' Analyses rows
carry three actions — **Download** (SAF destination first, then a worker),
**Restore** (only when local frames are gone) and **Delete**. The sweep
lattice's plot toggle is an **All / Node** pill defaulting to **All**; the scrub
readout shows x and y.

Docs for all of the above: [docs/app/WORKFLOWS.md](../app/WORKFLOWS.md) (the
manual test pass). Every workflow — app, background and backend — mapped to its
files, failure surfaces and tests: [docs/WORKFLOWS.md](../WORKFLOWS.md);
proposals coming out of that map: [docs/ops/FUTURE_IMPROVEMENTS.md](FUTURE_IMPROVEMENTS.md).

A single-setting run that solves zero points shows the same VSG-failure
wording as a sweep lattice node, not the generic "No data produced" dialog.

**Next architecture (grill before coding):** the `DicKeys` extras bag is now
packed in one place — `ViewerArgs` in `ui/viewer/`, which both
`SessionOpenHelper.intentFor` and `AnalysisNavHelper.openResults` construct,
with `ViewerArgsTest` asserting their key sets against each other so they
cannot drift apart again. The unpack half is still four readers parsing the
bundle themselves, and is the larger move: an Intent already in the back stack
has to keep working across an update. Session **commit** still sits on
`AnalysisViewModel`'s public field bag after the JNI loop leaves.
