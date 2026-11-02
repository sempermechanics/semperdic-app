# Deploy checklist: readiness change (2026-10)

What has to happen, in order, to put the readiness change live: #448, #449, #454
and #455, merged to `main` on 2026-10-19 (`main` @ `2de605c`). Deployed 2026-10-22 from
`b940cc29`, with #457 and test accounts (#460); the boxes below are ticked for that
deploy, and what it did is in [CHANGELOG.md](CHANGELOG.md). §5 and most of §6 are still open.

Everything deploys **from semperdic-app only**. Never deploy Hosting from
material_testing: any Hosting deploy replaces the whole site.

## Before the next deploy (readiness 2026-10-28)

The 2026-10-28 readiness run's backend (TE3-102 domain lock, ADDENDUM-4 name) adds
two Admin checks before its production deploy; neither changes anything.

- [ ] **IT-added addresses off the licence's domain (read-only).** From that deploy,
      institution IT may add only an address at the licence's `domainLock`; any other
      is refused with 422 `member_off_domain`, and a licence with no `domainLock`
      lets IT add nobody (`backend/app/repo/institution_admin.py:117-120`). Semper
      staff are not limited. Before the deploy, list for each `licenses` document
      with `kind` `institution` (Firestore console, read-only): its `domainLock`
      (flag an empty one), every seat in `licenses/{id}/seats` and every pending
      `licenseInvites` entry with that `licenseId` whose `email` is at another
      domain. Existing seats and invites stay; IT just cannot add such an address
      again, so tell the institution, or have staff seat the collaborator, or set
      the right `domainLock`. The audit log (`audit_logs`, `licenseId`) says who
      added each one.
- [ ] **Mail sender names say "semper" (ADDENDUM-4).** The display name of
      `NOTIFY_FROM` and `NOTIFY_SECURITY_FROM` is whatever the variable holds, not
      code. On 2026-10-28 `NOTIFY_FROM` was `onboarding@resend.dev` with no display
      name and `NOTIFY_SECURITY_FROM` was unset (`gh variable list`); whenever a
      display name is set, write it lower-case, e.g. `semper <noreply@…>` and
      `semper security <security@…>` (an Admin variable change, applied by the
      next deploy).

## 0. Prerequisites (once)

- [x] **Google credentials** for whoever runs steps 1, 4 and 5: Firestore index
      admin and Firebase Hosting admin on `indicvision-dic-app`, plus
      `roles/monitoring.editor` and `roles/logging.configWriter`.
- [x] **Revocation (TD-214)** (acceptance owed), optional for the first deploy. Grant
      `gcloud projects add-iam-policy-binding indicvision-dic-app-auth --member=serviceAccount:indic-api@indicvision-dic-app.iam.gserviceaccount.com --role=roles/firebaseauth.admin`,
      then set the repo variable `CHECK_REVOKED_ON_STEP_UP=true`. Not before the
      grant: without it the check fails closed and refuses every step-up.
      (2026-10-22: the grant is in place and the variable is set, for production
      from its next deploy; staging has it deployed. 2026-10-23: live on
      `semper-api-39900623312-1`. Owed: the acceptance check, TD-214.)
- [ ] **Offline entitlement (TD-215)**, optional for the first deploy. Follow
      [BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) A8 in its order:
      create the P-256 key in Secret Manager; grant the runtime SA
      `secretAccessor` on it and the deploy SA `secretmanager.viewer`; only then
      set `ENTITLEMENT_SIGNING_SECRET`, `ENTITLEMENT_KID` and
      `SEMPER_ENTITLEMENT_PUBKEYS`. The deploy checks the runtime SA's grant
      (`scripts/check_secret_access.sh`) and stops with the command to run when it
      is missing. Without a key the backend behaves as today and issues no token.
- [ ] **New-device mail (TD-295, TD-309)**, optional, and only once #457 is merged.
      Verify a sender in Resend (for example `semper security <security@yourdomain.com>`),
      then set the repo variable `NOTIFY_SECURITY_FROM` (the workflow passes it since
      `a02ef2f`, `deploy-backend.yml:340`) and redeploy. Unset, that mail is off and the
      access-request mail is unaffected ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md)).

- [x] **Test accounts ([ADR-027](../adr/ADR-027-test-accounts.md))**, optional, and
      only once the branch carrying them is merged. Creating, resetting and ending
      one calls Firebase Auth admin, so it needs the same
      `roles/firebaseauth.admin` grant on `indicvision-dic-app-auth` as the
      revocation item above (without it each call answers 503 `internal_error`).
      Then set the repo variable `TEST_ACCOUNT_DOMAINS` to the domain(s) staff may
      use, space-separated, for example `qa.sempermechanics.com`: a domain the
      company controls and no customer uses. `deploy-backend.yml` already passes
      it in `env_vars` (`TEST_ACCOUNT_DOMAINS=${{ vars.TEST_ACCOUNT_DOMAINS }}`);
      unset, it is empty and every create answers 403 `test_accounts_disabled`.

## 1. Firestore indexes (TD-216, TD-271)

- [x] `PROJECT=indicvision-dic-app scripts/deploy-firestore.sh indexes`. Never
      with `--force`: it deletes any TTL policy the file omits. (2026-10-22: it stopped
      on a 409 before creating anything, TD-316; the missing indexes went in by gcloud
      and the REST API, as the [CHANGELOG](CHANGELOG.md) entry shows.)
- [x] Wait for READY: the composite `audit_logs (licenseId ASC, ts DESC)`, and
      the `seats.uid` / `deleted_seats.uid` collection-group overrides
      (`gcloud firestore indexes fields describe uid --collection-group=seats`).

## 2. Backend: staging, then production

Actions → `deploy-backend.yml` (`.github/workflows/deploy-backend.yml`).

- [x] **Staging:** `environment=staging`, `project=indicvision-dic-app`,
      `region=asia-south1`, `gateway_mode=dry-run`. Read the gateway diff: it
      adds `/v1/me/claim`, `/v1/admin/licenses/search` and the roster and audit
      paths.
- [ ] **Staging smoke:** `/readyz` answers 200; an anonymous `POST /v1/me/claim`
      answers **401, not 404**; `GET /v1/config` carries `killSwitches`,
      `minSupportedVersionCode` and, if the key was set, `entitlement`. (2026-10-22: the
      first two held, the claim after the gateway apply; `/v1/config` needs a signed-in
      caller and was not read.)
- [x] **Staging gateway:** run again with `gateway_mode=apply`.
- [x] **Production:** `environment=production`, first `gateway_mode=dry-run`,
      then `apply`. The candidate revision is smoke-tested on `/readyz` before
      traffic moves, and rolls back on failure.
- [x] **Licence fan-out task (TD-294, [ADR-026](../adr/ADR-026-licence-fanout-marker.md))**,
      once #457 is merged. A staff revoke or terms edit schedules a Cloud Task on
      `TASKS_QUEUE`, so check that the environment's `TASKS_QUEUE` and
      `TASKS_TARGET_BASE_URL` are set (provisioning already uses them); unset, no task
      is scheduled and the action relies on its inline run alone. The callback
      `POST /v1/tasks/license-fanout` is reached by Cloud Tasks straight on Cloud Run
      with the invoker SA's OIDC token, like `/v1/tasks/provision-session`, and is
      kept off the gateway (`backend/tests/test_gateway_parity.py:45`): the gateway
      diff must not add it. On staging, a revoke of a test licence leaves a
      `license-fanout-<id>` task that runs about 6 minutes later and finds nothing
      pending.
- [x] **Test-account expiry job (ADR-027)**, with the test-accounts item in §0. A
      Cloud Scheduler job per environment calls the backend daily; like the Cloud
      Tasks callbacks it goes straight to Cloud Run with the invoker SA's OIDC token,
      and `POST /v1/tasks/test-account-expiry` is kept off the gateway
      (`backend/tests/test_gateway_parity.py`): the gateway diff must not add it.
      With the environment's `TASKS_TARGET_BASE_URL` (the Cloud Run URL) and
      `TASKS_INVOKER_SA`, and `cloudscheduler.googleapis.com` enabled:
      `gcloud scheduler jobs create http semper-test-account-expiry --project indicvision-dic-app --location asia-south1 --schedule "17 3 * * *" --time-zone Etc/UTC --http-method POST --uri "$TASKS_TARGET_BASE_URL/v1/tasks/test-account-expiry" --oidc-service-account-email "$TASKS_INVOKER_SA" --oidc-token-audience "$TASKS_TARGET_BASE_URL"`
      (staging: its own job name and URL). Whoever creates it needs
      `iam.serviceAccounts.actAs` on the invoker SA. Check with
      `gcloud scheduler jobs run semper-test-account-expiry --location asia-south1`:
      the request log shows 200 and the answer `{"expiredTestAccounts": 0, ...}`.
      Without the job, accounts past their date are not erased; their licence has
      still lapsed, and the staff list shows them `expired`.
- [x] For the first deploy, leave unset (or 0): `MIN_SUPPORTED_VERSION_CODE`,
      `RECOMMENDED_VERSION_CODE`, their `_MATERIAL_TESTING` pair, and
      `KILL_SWITCHES`.

## 3. Gateway checks (after `apply`)

- [x] An anonymous call to each new path answers 401: `/v1/me/claim`,
      `/v1/admin/licenses/search`,
      `/v1/institutions/licenses/{id}/seats/import`, `…/export`, `…/summary`,
      and `…/audit`. (2026-10-22: all of them on staging; on production the GETs
      `licenses/search`, `test-accounts` and `…/summary`.)
- [ ] `GET …/audit` answers 200 for a licence administrator.
- [x] With test accounts: an anonymous `GET /v1/admin/test-accounts` and
      `POST /v1/admin/test-accounts` answer 401, and
      `POST /v1/tasks/test-account-expiry` through the gateway answers 404. (Staging;
      production's `GET` answers 401.)

## 4. Hosting: consoles, account page, aligned Terms

- [x] From a **clean `main` checkout**:
      `API_BASE_URL=<INDIC_API_BASE_URL repo variable> LEGAL_PAGES_FROM=b940cc29 ./scripts/deploy-console.sh`.
      Never a bare `firebase deploy`. From Windows PowerShell, set the variables there
      (`$env:API_BASE_URL = gh variable get INDIC_API_BASE_URL`;
      `$env:LEGAL_PAGES_FROM = 'b940cc29'`) and run the script through Git Bash;
      PowerShell 5.1 drops quotes nested inside `bash -c "…"`.
      The deploy publishes the **legal pages** too (`/privacy`, `/terms`). While
      `main` holds legal text counsel has not cleared (TD-277; #474 since 2026-10-22),
      `LEGAL_PAGES_FROM=b940cc29` (the last cleared commit) ships those two
      `index.html` files as that commit holds them and restores main's copies after;
      it skips `render_legal_pages.py --check`, which runs in every other deploy.
      Compare the live pages with `b940cc29` afterwards. Since 2026-11-01 the script
      refuses a branch other than `main`, a changed tracked file, or an untracked or
      ignored file under `firebase-hosting/public` (`DEPLOY_CONSOLE_ALLOW_UNCLEAN=1`
      overrides, with a warning), refuses an `API_BASE_URL` that is not an `https://`
      origin (a trailing `/` is stripped), and runs `scripts/check_console.py` first.
      `DRY_RUN=1` runs all of that and stops before `firebase deploy`. Before, the pin
      meant a worktree with hand-placed files, which no check could tell from a stray
      edit.
- [x] The script runs `scripts/check_live_console.py`; it must pass (no `__`
      placeholder left live).
- [ ] By hand: `/account` shows the licence facts and, for a demo account, the
      "Redeem a claim code" card; `/terms` shows 6.6 "No purchases in the App";
      the operator desk's search works.

## 5. Monitoring (TD-217)

- [x] Dry run:
      `scripts/apply-monitoring.sh --project indicvision-dic-app --notification-channel <id> --staging-service semper-api-staging`.
      Done 2026-10-22 with a placeholder channel: 24 files render as valid JSON with
      no unfilled placeholder; it would create 9 log metrics, the `/readyz` uptime
      check, the service and its 99.5 % SLO, 8 alert policies and 4 dashboards. The
      project has **none** of these today, and **no notification channel** in either
      project, so `--apply` first needs a channel created
      (`gcloud beta monitoring channels create`, email to the on-call address).
- [ ] The same with `--apply`: log metrics, the three uptime checks (`/readyz`,
      and since TE6-003 the gateway's `/healthz` and `/v1/config`), SLO, the alert
      policies (13 since TE6-002 / TE6-003: App Check refusals, revocation-check
      failures, device-lock mismatches, licensing refusals and the gateway path
      were added), four dashboards; then the `run.invoker` grant for the
      Monitoring service agent that the script prints under "By hand, once".
- [x] `scripts/check_gcp_posture.sh` against both projects, with
      `--backup-bucket <FIRESTORE_BACKUP_BUCKET>`; record the output in
      [PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md). Done 2026-10-22
      (18 FAIL; recorded there; 17 once 3.4 went live 2026-10-23, not re-run). On
      Windows it runs under Git Bash with `python` or `python3`, whichever is a real
      Python 3.8+ (TE6-017).

## 6. After the deploy

- [ ] **Staff and IT phones re-register with a second factor** (ADR-010
      amendment, device trust). A device registered before this backend has no
      `stepUpEligible`, so its signed staff or IT calls answer `403
      mfa_required`. On each such phone: sign out, sign in completing the TOTP
      second factor (that registers the device again, stamped). Holders are not
      affected; the browser consoles are not affected.

- [ ] 24 h log watch: `feature_not_licensed` only from restore and bundle;
      `app_check_required` = 0; the rate of `session_quota_exceeded` and
      `device_quota_exceeded`; `feature_paused` only if a kill switch was set;
      5xx rate; Cloud Tasks backlog. Once #457 is in: `license_fanout_enqueue_failed`
      = 0, and each `license_fanout_finished_late` is a revoke or edit whose call died
      part-way and the task finished (TD-294).
- [ ] One erasure of a test account: its `ACCOUNT_DELETE` row says
      `seatScanComplete: true` (TD-271).
- [ ] With test accounts, on staging: create a QC account (`days: 1`), sign it in
      on two phones or emulators (both licensed, no new-device mail), check its
      upload lands under `Research Storage/_test/{uid}`, then end it from the
      console; the audit trail has `test_account_created` and `test_account_ended`,
      and its Firebase user is gone.
      2026-10-22, all but the end: `qc-emulator@qa.sempermechanics.com` (7 days)
      on an Android 14 and an Android 12 emulator, both `DEVICE_REGISTER` with
      `testAccount: true` and no new-device mail; one backup
      (`SESSION_CREATE` `testAccount: true`, three `UPLOAD_COMPLETE`), which
      `session_provision.py:102` sends under `_test/` (the Drive folder itself not
      listed). End it from the console, then check the audit rows and the Auth user.
- [x] **Audit retention, backfill and lock (TD-237, TD-246).** Retention is decided:
      **365 days** (owner, 2026-10-22; `AUDIT_RETENTION_DAYS`, default 365), and the
      one-time backfill is approved (owner, 2026-10-22). In this order, by the
      commands in [FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md),
      "The audit trail is append-only":
      1. Deploy the backend that writes `expireAt` on each new audit row (§2), and
         leave `AUDIT_RETENTION_DAYS` unset.
      2. The backfill, which stamps `licenseId` and `expireAt` (= `ts` + 365 days)
         on older rows: `python backend/scripts/stamp_audit_licence_ids.py --project indicvision-dic-app`
         (dry run; read both counts), then the same with `--apply --decision
         "TD-246: backfill approved by <owner>, 2026-10-22"`. A rerun reports 0 to
         stamp. **Done 2026-10-23** (dry run, then `--apply`; the rerun: 6145 rows, all with
         `expireAt`, 0 to stamp).
      3. The `audit-trail` log bucket at `--retention-days=365`, the sink on
         `jsonPayload.event="audit_row"`, then lock the bucket (irreversible).
         Check: a staff licence edit's row reaches the bucket, and lowering its
         retention is refused. Needs `roles/logging.configWriter` (§0).
         **Done 2026-10-23**: bucket `audit-trail` in `asia-south1`, sink on the filter
         `resource.type="cloud_run_revision" AND jsonPayload.event="audit_row"`; a staff
         licence edit (08:27:53 UTC) reached it, the bucket is locked, and
         `--retention-days=1` answered `FAILED_PRECONDITION`. The sink routed nothing in
         its first minutes: two rows (08:24:46, 08:25:49) are only in `_Default` and Firestore.
      4. The Firestore TTL:
         `gcloud firestore fields ttls update expireAt --collection-group=audit_logs --enable-ttl --project=indicvision-dic-app`;
         `ttls list --collection-group=audit_logs` shows it `ACTIVE`. Rows already
         older than a year go within about a day. The policy is declared in
         `backend/firestore.indexes.json`, so an indexes deploy before this step
         would create it early: run none until step 2 is done.
         `PROJECT=indicvision-dic-app scripts/deploy-firestore.sh indexes` can
         also do this step: it now runs a pinned `firebase-tools@15.33.0`, which
         matches the six live composites and the five live overrides (TD-316,
         fixed in file), so it writes only this policy. After the `gcloud`
         command, run the script once anyway: it should print no "not present"
         line and write nothing, which closes TD-316. Never with `--force`.
         **Done 2026-10-23** by the script after step 2: no "not present" line, deploy
         complete, the policy `CREATING` (TD-316 closed).
- [ ] Move the merged-and-deployed entries from [CONTEXT.md](../../CONTEXT.md)
      "Current state" into [CHANGELOG.md](CHANGELOG.md), in both repos.

## 7. App releases (separate from the backend)

- [ ] Release builds from `main`: Semper, and Material Testing after its sync
      (material_testing#139). Both now require **Android 11 (minSdk 30)**.
      Steps: [RELEASING.md](RELEASING.md).
- [ ] Set `SEMPER_ENTITLEMENT_PUBKEYS` before the release build if offline tokens
      should be enforced. Set `SEMPER_API_CERT_PINS` (the four GTS root pins,
      [RELEASING.md § Certificate pins](RELEASING.md#certificate-pins-semper_api_cert_pins)):
      the release fails without it (TD-318).
- [ ] Device smoke (TD-219): installs on Android 11 and is refused on Android 10;
      Refresh licence; a demo account sees locked features; the device-limit
      screen.
- [ ] Only after those builds are out, raise `MIN_SUPPORTED_VERSION_CODE` if
      older builds must update; drill it on staging first (TD-220). It reaches
      only builds released after `v1.2-beta.4` (versionCode 1036): that build and
      every earlier one have no version gate and no client kill switch, so for
      them the levers are the server-side `KILL_SWITCHES` (503 `feature_paused`)
      and nothing else ([RELEASING.md](RELEASING.md), Rollback levers).

## Still external (not deploy steps)

Every Admin action, deploy-related or not, is one table:
[PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md#admin-actions-external)
(A1–A14, each with its TD row, the command or console path, and when it is done).
§5 and §6 here are its rows A1 and A7.
