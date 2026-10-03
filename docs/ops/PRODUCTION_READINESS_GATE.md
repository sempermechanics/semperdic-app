# Production readiness — completion gate

Re-score of the production readiness rubric after Phases 1–6 repo work and the
Step 4 pilot rollout. Integration branch is **`main`**. This is an **ops gate**,
not a substitute for counsel or console verification. Do not treat
PARTIAL/UNKNOWN as PASS.

Secrets / Environments reality: [ENVIRONMENTS.md](ENVIRONMENTS.md).

## Score summary (post-remediation, repository + pilot evidence)

| Band | Count (approx.) | Notes |
|------|-----------------|-------|
| PASS / improved to PASS | Security headers, deny-all rules (checked in), validation, erasure without silent 2k cap, readiness, structured logs, async notify, authz tests, pagination/export, deploy template, legal drafts, candidate smoke deploy, attested uploads pin, live assetlinks + legal Hosting | Repo + pilot |
| PARTIAL | Distributed rate limit (gateway YAML + in-process), backups/PITR, Crashlytics, WorkManager, GitHub Environment *reviewers* on Free plan | Need console / plan upgrade |
| UNKNOWN | Firebase API-key restrictions, App Check, Auth abuse limits, vendor retention, cookie inventory, branch ruleset if plan blocks it | External — see below |
| FAIL remaining | None intended in security/data-integrity **repo** scope; remaining gaps are console / process | |

Strict binary PASS against all applicable external controls is **not** claimed.

## Launch blockers (must clear or risk-accept)

### Security / data integrity

- [x] Deploy deny-all `firestore.rules` (`./scripts/deploy-firestore.sh rules`) and confirm
      client SDK cannot read/write. Deployed to `indicvision-dic-app` 2026-09-24;
      anonymous REST reads of `users`, `licenses`, `sessions` and a create all
      answer `403 PERMISSION_DENIED` ("Missing or insufficient permissions").
      The Auth project has no Firestore database.
- [ ] Confirm Firebase API key restrictions + App Check posture.
- [ ] Confirm Auth abuse / enumeration protections in Firebase console.
- [x] Deploy API Gateway with `openapi.yaml` quotas (`__CLOUD_RUN_URL__`
      substituted) — pilot gateway is live; re-confirm quotas in console.
- [ ] Confirm Cloud Run ingress, SA roles, Shared Drive Manager rights. Ingress
      checked 2026-09-24: `all` on `semper-api`, as intended
      ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) "Leave Cloud Run ingress at its
      default"); an anonymous `GET /v1/config` on the `run.app` URL of production and
      staging answers `403 Forbidden`. SA roles reviewed 2026-09-24: `run.invoker`
      on `semper-api` is `indic-gw`, `indic-api`, `indic-deployer` and the owner's
      user (no `allUsers`); `indic-api` holds only `datastore.user`,
      `cloudtasks.enqueuer`, `logging.logWriter`; `indic-backup` only
      `datastore.importExportAdmin` + `datastore.viewer`; the default compute SA
      has no `roles/editor`. Open: `indic-deployer`'s project-wide
      `storage.admin` and `iam.serviceAccountUser` (TD-71), and Shared Drive
      Manager rights for `indic-api`.
- [ ] Run and record one **Firestore restore drill** (TD-156: it has never run). The drill is automated
      (`.github/workflows/firestore-restore-drill.yml`) but needs a
      **`restore-drill` GitHub Environment** (separate from `production-backup`)
      plus one recorded RTO
      ([FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md)).
- [x] Confirm PITR / scheduled export job actually scheduled in GCP. The export
      script already refuses to run without PITR, and now also fails if the
      `challenges.expireAt` TTL policy is not ACTIVE. Checked 2026-09-24:
      `pointInTimeRecoveryEnablement` is `POINT_IN_TIME_RECOVERY_ENABLED`, the
      `challenges.expireAt` TTL is `ACTIVE`, and the daily `firestore-backup.yml`
      (`17 2 * * *`) succeeded on its scheduled runs of 2026-09-22, -23 and -24.
- [x] Cloud Tasks queue and IAM for async session provisioning
      ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) §A6) — pilot
      `indic-provision`, renamed `semper-provision` / `semper-provision-staging` in #146 (2026-09-23), + `TASKS_*` vars. Keep vars set on redeploy.

### Authorization / quality

- [x] CI green including backend authz suite (`test_http_authz.py`), plus a route
      inventory (`test_route_authz_matrix.py`) that fails when any route ships
      without a written-down auth tier, and cross-user negative tests for every
      protected route.
- [x] Firestore emulator tier runs in CI (`FIRESTORE_EMULATOR_HOST` is set in the
      Tier 4 job). It found three real defects the in-memory fake could not
      express — see "Contention fixes" below.
- [x] Coverage gated at 75% (`--cov-fail-under`), currently 78%.
- [x] Dependency audit (`pip-audit`) and hashed lock verification in CI.
- [x] **Dependency-outage readiness test**: `test_health.py` asserts `/healthz`
      stays 200 while both dependencies raise, and `/readyz` returns 503 with the
      stable code. `test_verify_degradation.py` asserts a Drive outage during
      `?verify=true` deletes nothing.

### Deployment

- [x] GitHub Environments `staging`, `production`, `release`, `production-backup`
      exist. On Free private orgs, **secrets/vars are often repo-level** (Environment
      protection_rules may be empty). Still open: required reviewers when the plan
      allows; create **`restore-drill`**. See [ENVIRONMENTS.md](ENVIRONMENTS.md).
- [ ] Branch ruleset / protection with required `CI OK` (may be unavailable on
      Free — enforce by process until then).
- [x] Staging candidate smoke before traffic shift
      (`deploy-backend.yml`: tagged revision + ID-token `/readyz`). Record a
      deliberate rollback drill when convenient.
- [x] `SEMPER_API_BASE_URL` available to release builds; signed release shipped
      with `-PrequireCloudApi=true` (e.g. `v0.1.0-beta.1`).
- [x] `/uploads` always device-attested: the `REQUIRE_ATTESTED_UPLOADS`
      window was retired 2026-09-26 (TD-45), so there is no flag left to
      clear on deploy.

### Compliance

- [x] [PRIVACY_POLICY.md](../legal/PRIVACY_POLICY.md) and
      [TERMS_OF_SERVICE.md](../legal/TERMS_OF_SERVICE.md) are **rendered** into
      `firebase-hosting/public/{privacy,terms}/` by
      `scripts/render_legal_pages.py`; CI (`legal-pages`) fails on drift. Hosting
      has been deployed so public URLs are live.
- [x] Crash reporting and analytics are **opt-in**: disabled in the manifest,
      a first-run notice, and a Settings toggle that also deletes queued reports
      on withdrawal. Named funnel events (sign-in, analysis, cloud transfer,
      export, feedback) fire only when diagnostics are enabled.
- [x] In-app **Send feedback** (Settings → Help & support) plus release
      follow-up process in [RELEASING.md](RELEASING.md) (milestone triage + reply
      when fixed).
- [x] Cloud account export (`GET /v1/me/export`) is reachable from the app —
      Settings → Your data → "Download my cloud account data".
- [x] Release signing cert listed in
      `firebase-hosting/public/.well-known/assetlinks.json` (debug + release).
- [ ] Counsel review of the Terms (India law, Chennai arbitration, liability
      cap, indemnity, class waiver) and the Privacy Policy bases before public
      distribution. Drafted as a risk exercise, not legal advice.
- [ ] Fill the bracketed operator fields in both legal documents and
      regenerate the pages: `[OPERATOR LEGAL NAME]`, `[REGISTERED ADDRESS]`,
      `[GRIEVANCE OFFICER NAME]` / `[EMAIL]` (DPDP Act 2023 requires one),
      `[GCP REGION …]`. `render_legal_pages.py --check` does not catch these.
      **They are live** on the hosted pages as literal text (TD-36, checked
      2026-09-23): `PRIVACY_POLICY.md:5` (name, address), `:7` (Grievance
      Officer name, email), `:18` (name), `:153` (region);
      `TERMS_OF_SERVICE.md:6` (name, address), `:438` (name), `:439`
      (address) — rendered into `firebase-hosting/public/privacy/index.html`
      and `terms/index.html`. Values come from the operator; nobody else
      should fill them.
- [x] Clickwrap: Terms acceptance is an affirmative in-app act recorded
      server-side (`users/{uid}.termsAccepted`) with a version, and the
      product-improvement consent is a separate, pre-ticked but declinable,
      withdrawable option
      ([AUTH_SETUP.md](../backend/AUTH_SETUP.md) §3a).
- [ ] Record cookie inventory per [COOKIE_CONSENT.md](../legal/COOKIE_CONSENT.md);
      no banner without non-essential cookies.
- [ ] Confirm Crashlytics / Cloud Logging / Resend retention in vendor consoles
      and replace the UNKNOWN entries in the policy's retention table.

### Legal rollout (branch `feat/legal-terms-clickwrap`)

Pending before the rewritten Terms and the clickwrap gate go to real users.
Order matters: the backend and gateway must serve the new routes before an app
build that calls them ships, or every sign-in ends at an unrecordable gate.

- [ ] Appoint the **Grievance Officer** (DPDP Act 2023) and fill the bracketed
      fields in [TERMS_OF_SERVICE.md](../legal/TERMS_OF_SERVICE.md) and
      [PRIVACY_POLICY.md](../legal/PRIVACY_POLICY.md); regenerate and **deploy
      Hosting** so `/terms/` and `/privacy/` show the new text before the app
      links to it.
- [x] Deploy the backend **and** redeploy API Gateway from the updated
      `backend/gateway/openapi.yaml` (`POST /v1/me/terms`, `PUT /v1/me/consents`);
      confirm with a curl that both reach Cloud Run through the gateway. Checked
      2026-09-24 on config `v202609241122-44`: both answer an anonymous call with
      `401 "Jwt is missing"` (the route exists and wants a token), where an
      undefined path answers `404 "not defined by this API"`. A signed-in call
      reaching Cloud Run is the Terms gate in the device pass below.
- [ ] Manual device pass of the gate: fresh install → password sign-up → Terms
      screen before Pending/Home → Agree writes `users/{uid}.termsAccepted`;
      Google sign-in and email link show the same screen; Decline and Back sign
      out; Settings → Your data toggle flips `improvementConsent` and
      `GET /v1/me/export` shows both records; bumping `TERMS_VERSION` on a dev
      backend re-gates an approved user on next launch.
- [ ] Every **existing** user is re-gated once on their first launch after the
      rollout (no `termsAccepted` on file). Tell pilot users beforehand; nobody
      is locked out — PENDING accounts can accept too.
- [ ] The Privacy Policy §2.8 promises a **separate, pseudonymised improvement
      dataset**, deletion from it within 30 days of withdrawal, and a 36-month
      cap on raw content. No tooling exists for that yet. Until it does, do not
      copy any synced content into an improvement dataset, consent or not — the
      toggle records the choice; it does not license a process that is not built.
- [ ] Update the **Google Play Data safety** form: analysis content may be used
      for app improvement (optional, user-controlled), and account data now
      includes terms-acceptance / consent records.
- [ ] Console: `source: console` is reserved for consent changes made without
      `X-Device-Id`; the web console has no consent UI yet. Either add one or
      note that withdrawal is app- or support-mailbox-only (the policy says both).

### Licensing rollout (PR #100 → `feat/licensing-go-live`)

Everything pending, deferred or delayed for taking licensing live. The hard
constraint: **installed builds that predate licensing (v1.1-beta.5, v1.2-beta.0)
must keep working** — every pre-existing account resolves to demo on the first
request after the deploy, so demo must be able to do what those builds do.
Product decision: *demo analyses are recorded (images and results are uploaded
and stored) but demo has no backup/restore feature.* Recording is open;
retrieval (`/content`, bundle) is licensed. Ops steps in order:

**Before the deploy**

- [x] Full CI green on the #100 tip (run `35433845913` after the E741 fix);
      `gh pr merge 100 --merge`.
- [x] `feat/licensing-go-live` merged to `main` (#110, `08e959d`, 2026-09-19)
      **before** any backend deploy from `main` — it removes the `403` on
      `POST /v1/sessions` that would make every old build's upload worker
      retry forever.
- [x] `feat/console-domain-cors` merged to `main` before the deploy (#112,
      then #113 for the space-separated `CONSOLE_ORIGINS`): CORS
      (`CONSOLE_ORIGINS` + gateway `allowCors`), the `/auth/*` rewrites, and
      the app's second continue host. Without it the dashboards cannot call
      the API from any origin.
- [x] Create the six repository variables `deploy-backend.yml` now pins:
      `DEMO_MAX_ANALYSES`, `LICENSED_MAX_SESSIONS_PER_USER`,
      `ADMIN_WEB_MFA_ENABLED` (`1`), `APP_CHECK_MODE` (`off`),
      `SELF_DEVICE_CHANGE_COOLDOWN_DAYS` (`30`), `CONSOLE_ORIGINS`
      (`https://app.sempermechanics.com,https://indicvision-dic-app-auth.firebaseapp.com`).
      Unset resolves to those defaults, but set them so the value is a
      decision, not an accident.
- [x] Choose `DEMO_MAX_ANALYSES` from a read-only Firestore survey (**25**): it must be
      ≥ max(live `MAX_SESSIONS_PER_USER`, the largest per-user `maxSessions`
      override, the largest per-uid session count) or an existing user 409s on
      the next upload. Confirm no user document already carries `mode` /
      `licenseId`.
- [x] `gcloud run services describe indic-api --format=yaml > indic-api-before.yaml`
      (rollback revision `indic-api-31896308319-1`);
      note the serving revision (rollback target) and confirm
      `REQUIRE_ATTESTED_UPLOADS=1`, no `DEV_INSECURE_AUTH`.
- [x] PITR on and a fresh export (`gh workflow run firestore-backup.yml`); note
      the path. Firestore is never rolled back by the deploy.

**Staging** (same project → same Firestore; migration 002 is a no-op on
pre-licensing documents)

- [x] `deploy-backend.yml` with `environment=staging`; `migrate_schema.py`
      dry-run (expect 0 changes) then `--apply`; ledger row written.
- [x] `GET /v1/config` with an ID token → `mode: demo`, `plan: demo`,
      `cloudBackupEnabled: false`, `maxSessions: <DEMO_MAX_ANALYSES>`.
- [x] `OPTIONS /v1/me` with `Origin: https://app.sempermechanics.com` and
      `Access-Control-Request-Method: GET` against the staging Cloud Run URL →
      200 with `access-control-allow-origin` echoed. Through the staging
      gateway it was **405** until `x-google-endpoints.name` was the API's
      managed service name (#114, #115) — ESPv2 ignores `allowCors` on any
      other name.
- [x] Old-APK pass (debug build from `bcc467a`, `SEMPER_API_BASE_URL` = staging):
      sign in, back up one analysis (201 + upload completes), **restore fails
      once with "rejected" and does not loop**, delete, export. Two things
      the pass taught: the old build shows the raw `detail`, so the refusal
      now carries a sentence (`feature_not_licensed: Restore isn't available
      in demo mode.`, #116) and the same for a refused bundle download in the
      current build (#117); and **on-device export stays ungated on every
      installed pre-licensing build** — it never touched the backend, and
      the backend has no forced-update gate. Accepted: share gating arrives
      with the next app release, not the deploy.
- [ ] Mint an individual licence against that account (the operator's own
      test account, from the operator desk once the consoles are up) → response carries
      `claimedByUid` → `/v1/config` flips to `mode: licensed` → restore
      succeeds. Mint against an address with no account → invite retained.
      **Attempted 2026-09-22 and it answered 500** — `_write_license` returned
      the `SERVER_TIMESTAMP` sentinel it had just written, which the response
      cannot serialise, so both attempts created a licence and reported
      failure (#131). Two time-limited keys from that are on the desk:
      `SEMP-EQUZ` (redeemed) and the unused duplicate `SEMP-5MZP`. Revoke the
      duplicate, then re-issue perpetual and uncapped once #131 is deployed.

**Production**

- [x] `deploy-backend.yml` with `environment=production` (candidate → `/readyz`
      → promote; auto-rollback on failure). Read the "Describe live env"
      warning: after promote, `--remove-env-vars MAX_SESSIONS_PER_USER,PRO_MAX_SESSIONS_PER_USER`
      **and then `update-traffic`** — the deploy pins traffic to the
      candidate revision, so the env change lands in a revision serving 0 %
      until moved (2026-09-21: `indic-api-00066-x2r`, image `d6b1b64`).
- [x] Gateway: new `api-config` from the substituted spec — all three
      placeholders, `__MANAGED_SERVICE__` included — `gateways update`,
      `PREV_CFG` recorded ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md)
      "Redeploying the gateway"). Unauthenticated `/v1/config` → **401**, not
      404; the preflight above → **200** through the gateway. Done
      2026-09-21 on `semper-gw` (`v202609211150`, rollback `v202608081145`).
      The legacy `indic-gw` gateway and its `indic-api` gateway API were
      deleted 2026-09-23 (last client call 2026-09-10).
- [x] Verify with the **installed, unmodified** old app: sign-in, new backup,
      delete, export succeed; restore shows one refusal. (Prod log 2026-09-21:
      `POST /v1/sessions` 200, uploads complete, `DELETE /v1/sessions/…` 200,
      one `403 feature_not_licensed` from restore.)
- [ ] Verify with a new-app build: Terms gate, one account receives a demo key
      (`licenses/` gains a `createdByUid: system` document), Home shows no
      sync badge and Settings shows no Cloud/Analyses-data section on demo.
- [x] Custom domain `app.sempermechanics.com` on the `indicvision-dic-app-auth`
      Hosting site: TXT verification + A records in **Netlify DNS**, certificate
      issued, `https://app.sempermechanics.com/.well-known/assetlinks.json` 200.
- [x] Repository made public (2026-09-22) so Actions minutes stop being
      billed; the engine submodule already was. Nothing else changed — 0
      repository secrets are exposed by it, the 19 Actions variables hold no
      credential, and deploys authenticate by Workload Identity Federation.
- [x] Consoles: Identity Platform + TOTP enabled (TOTP is the only factor;
      `mfa.providerConfigs[0].totpProviderConfig.adjacentIntervals: 5`,
      `enabledProviders` empty so no SMS) and `app.sempermechanics.com` an
      authorised domain
      ([console README](../../firebase-hosting/public/console/README.md)); `firebase deploy --only
      firestore:indexes` (production had **no** composite index before; the
      file held seven entries Firestore refuses, #118); `./scripts/deploy-console.sh`
      (`/login`, `/account`, `/auth/finishSignIn`, `/terms/`,
      `assetlinks.json` all 200 on the custom domain; preflight from that
      origin through `semper-gw` → 200 with the origin echoed).
- [x] Hand-check `/login` as staff: TOTP enrolment (QR, #129) and the
      operator desk listing licences — the CORS proof from a real browser.
      What the first staff sign-in cost, all merged: `<base>` href, theme,
      CSP origins, `init.json`, one SDK origin, no-cache (#120–#128); the
      role gate, so a non-operator sees where they *can* go rather than a
      mint form that refuses (#130); and the re-authentication loop that made
      revoke unusable — firebase-auth resolves a redirect's second-factor
      challenge against `auth.redirectUser`, never making it current, so the
      fresh `auth_time` was invisible and every revoke bounced back to Google
      (#132, which also resumes the revoke on the return leg).
- [x] Deploy #131 (mint 500) to production: `deploy-backend.yml`
      `environment=production project=indicvision-dic-app region=asia-south1`.
      The workflow pins env from the repository variables, so this is also the
      first deploy to carry `ADMIN_EMAILS` for both operators — space-separated
      since #134, because the deploy action splits `env_vars` pairs on commas.
      **Done 2026-09-23:** serving `indic-api-35821056144-1` (`7593692`); env,
      `/readyz` and the gateway's 401 checked, no errors since. Rollback:
      `update-traffic --to-revisions=indic-api-00067-mbp=100`.
- [x] Deploy the #138 console (`scripts/deploy-console.sh`): the desk reports
      every revoke and mint outcome, and asks before a second live licence for
      the same address. **Done 2026-09-23** from `402c59d`: `config.js` carries
      the gateway host, `operator.js` matches `main`, `/login`, `/account`,
      `/auth/finishSignIn`, `/terms/` and `assetlinks.json` 200, preflight from
      the custom domain through `semper-gw` 200.
- [x] Revoke `SEMP-5MZP` from the desk (the 500-retry duplicate beside `SEMP-EQUZ`).
      **Done 2026-09-23** by the operator on the deployed #138 desk.
- [x] Hand-check `/login` as an ordinary account holder (`/account` only).
      **Done 2026-09-23**: `/login` read `/v1/me` and
      `/v1/institutions/licenses`, found neither staff role nor institution,
      and sent the account to `/account`, which showed **demo**; every
      preflight and call 200 through the production gateway.
- [x] Marketing site (`IndicVision/semper-website`, Netlify): "Sign in" in the
      nav, `/dashboard/` page, `_redirects` for `/login`, `/account`,
      `/terms/*` → `app.sempermechanics.com`. `curl -sI https://sempermechanics.com/terms/`
      → 301 → 200 (was 404, and `legal.py` links it; live 2026-09-22).
- [x] A 429 on a device-signed route leaves the nonce unspent (#154): the
      bucket is a route dependency resolved before `verified_device`, and the
      429 sends `Retry-After`. **Done 2026-09-24**: production
      `semper-api-35957034833-1` from `2fdb44c`; a four-analysis erase from the
      Pixel 6 got one 429, and the app's unchanged resend 2 s later returned
      200 (was 401 `nonce_invalid_or_replayed`, leaving analyses in the cloud).
- [ ] 24 h log watch: `feature_not_licensed` only from restore/bundle by demo
      accounts (never from `POST /v1/sessions`); `app_check_required` **= 0**;
      `session_quota_exceeded`; `license_device_mismatch`; `mfa_required`;
      5xx rate; Cloud Tasks backlog.

**Deferred / risk-accepted for launch**

- [ ] **Legal wording for demo recording.** Terms §7.2 licenses "syncing or
      uploading Your Content" generically, but the Privacy Policy reads as
      user-elected sync ("metrics you sync", "the cloud path is optional").
      On demo the upload is automatic with no toggle. Add one sentence to
      Privacy §2.3 and Terms §6.1 stating demo analyses are uploaded and
      stored and cannot be restored on the Demo plan; regenerate with
      `scripts/render_legal_pages.py`; deploy Hosting. **Before the next app
      release**, together with the bracketed operator fields above.
- [ ] Google Play **Data safety** form: demo analysis content (images,
      results) is uploaded to the operator's cloud.
- [ ] Floating institution pools: not minted at launch; the lease routes ship
      dormant.
- [ ] `APP_CHECK_MODE` stays `off`. Move to `monitor` only once an App Check
      build is the fleet; **never** `enforce` while a pre-App-Check build is
      installed (every request from it would 403).
- [x] App Check APIs (`firebaseappcheck`, `playintegrity`) enabled on
      `indicvision-dic-app-auth` and the Android app registered with Play
      Integrity (2026-09-23); Firebase Auth enforcement stays `UNENFORCED`.
- [ ] Play developer account → link the project in Play Console → App
      integrity, and add the Play app-signing SHA-256 to the Firebase Android
      app ([AUTH_SETUP.md §3.2](../backend/AUTH_SETUP.md)). Until then no build
      can get a token and `monitor` would count 100 % missing.
- [ ] §20.5 skew fallbacks 6–9 (the app's `config.plan` / old `plan` pref
      reads, the `plan` mirror in `/v1/config` and licence summaries, and
      `normalize_mode` / `normalize_kind` reading old stored values) stay until
      adoption of a `mode`-reading build is high enough; retire in that order.
      Shims 1–5 (ops/IT wire aliases, `PRO_MAX_SESSIONS_PER_USER`, the
      unattested `/uploads` read) were retired 2026-09-26.
- [x] Gateway deploy job (TD-27): `deploy-backend.yml`'s `gateway` job; first
      `apply` 2026-09-24 (run 35992296245, config `v202609241122-44`).
- [ ] Per-user `maxSessions` override ignored in demo (TD-28): lifting one demo
      account's cap means attaching a licence.
- [ ] Old-app restore UX: a pre-licensing build shows a generic "rejected"
      message on restore; there is no way to tell it "licence required".
      Accepted — it fails once and stops.
- [ ] Password-reset custom action URL stays
      `https://indicvision-dic-app-auth.firebaseapp.com/finishReset` (TD-29):
      builds before `AUTH_HOST = app.sempermechanics.com` intercept only that
      host. Switch it, and drop the legacy filters, once Play vitals show no
      such build installed.

**Latency and failure visibility (`perf/backend-latency-and-failures`)**

- [x] Grant the runtime SA `iam.serviceAccountUser` **on itself only**, so
      Cloud Tasks accepts its OIDC tasks. Without it every enqueue 403s
      (`iam.serviceAccounts.actAs`) and large sessions provision inline:
      `gcloud iam service-accounts add-iam-policy-binding indic-api@indicvision-dic-app.iam.gserviceaccount.com --member=serviceAccount:indic-api@indicvision-dic-app.iam.gserviceaccount.com --role=roles/iam.serviceAccountUser --project indicvision-dic-app`.
      Granted 2026-09-23 (verified with `get-iam-policy`). Check still owed:
      the next backup's session reaches `UPLOADING` through the queue, and no
      `provision_enqueue_failed` events once this branch is deployed.
- [x] Give `indic-deployer@` `run.invoker` on `indic-api-staging` before the
      next staging deploy: the workflow now deploys staging private
      (`--no-allow-unauthenticated`), so the candidate smoke needs it.
      Granted 2026-09-23; staging invokers are now `indic-gw@`,
      `indic-deployer@` and `domain:indicvision.com`.
- [x] Deploy to production (min-instances 1, one worker, client nonces,
      `/readyz` no longer on the gateway — redeploy the gateway config from
      `backend/gateway/openapi.yaml`). Deployed 2026-09-23: `c0c0ce3` as
      `indic-api-35835537292-1` (run `35835537292`, minScale 1), gateway
      `semper-gw` on `v202609230812` (the diff against `v202609211150` was
      the `/readyz` block only). Checked: `/readyz` through the gateway → 404,
      unauthenticated `/v1/config` → 401, the console preflight → 200 with
      ACAO, invokers unchanged. Superseded the same day by the rename
      (#146): production is `semper-api-35844549945-1` (`d518179`) behind
      `v202609230845`; `indic-api`, its revisions and the older gateway
      configs are deleted. Rollback: `gcloud run deploy semper-api --image
      …/cloud-run-source-deploy/semper-api:rollback-c0c0ce3` (the
      `c0c0ce3` image, kept under the new package). [Note 2026-09-26:
      `c0c0ce3` predates ADR-007 and one licence per person; the rollback
      target is now `semper-api:rollback-prev`, which each deploy moves.]
- [ ] A signed call from a new build carries a `t1.` nonce and no
      `POST /v1/challenge` precedes it (next app release).
- [x] A session over `INLINE_PROVISION_MAX_FILES` (8) files reaches
      `UPLOADING` through `semper-provision` with no `provision_enqueue_failed`
      event (proves the Tasks grant). A phone backup cannot prove it: a bundle
      is 3 files and provisions inline. Two Pixel 6 backups on 2026-09-23 did
      that — `POST /v1/sessions` 6.06 s / 4.05 s, provisioning 5.37 s / 3.43 s.
      **Proven 2026-09-23** with the threshold set to `0` for the test, so the
      3-file Pixel 6 bundle took the queue path, then removed:
      staging (`semper-provision-staging`) and production (`semper-provision`,
      revision `semper-api-00006-gq5`) each logged `session_provision_queued`,
      a `Google-Cloud-Tasks` `POST /v1/tasks/provision-session` 200 and
      `session_provisioned` (production `latencyMs` 2752, `folderMs` 1653);
      `POST /v1/sessions` returned in 1.20 s and all three files completed.
- [x] Inline provisioning after the folder change (check-and-create run
      concurrently, no name search for the new session folder): a phone backup's
      `session_provisioned` shows `folderMs` and `latencyMs` well under the
      3.43 s above. Pixel 6 on 2026-09-23 against #149: `latencyMs` 2401,
      `folderMs` 1233, `POST /v1/sessions` 3.12 s (from 3.43 s / 4.05 s);
      the rest is opening the three resumable uploads, already in parallel.
- [x] Prune the tagged `cand-*` revisions so none holds a warm instance.
      Every deploy re-created one, so the promote now removes them
      (`chore/prune-cand-tags`, TD-32).
- [x] The first staging deploy after `chore/prune-cand-tags` promotes with
      `--to-latest` and leaves no `cand-*` tag (`gcloud run services describe
      semper-api-staging --format='value(spec.traffic)'`); then production.
      2026-09-23 (#151, `b65ff66`): `semper-api-staging-35856393597-1` and
      `semper-api-35856726929-1`, both `latestRevision` 100 %, no tags.

## Contention fixes found by the emulator tier

Wiring the Firestore emulator into CI immediately falsified three assumptions the
in-memory fake could not test (it applies transactional writes immediately, with
no isolation and no retries):

| Defect | Symptom in production | Fix |
|---|---|---|
| `bump_session_progress` used a read-modify-write transaction on one hot document | Concurrent completions exhausted the client's 5 retries → `Aborted: Transaction lock timeout` → **500 on the last files of an otherwise successful upload** | Atomic `firestore.Increment`, no transaction |
| `consume_nonce` raised when it lost the transaction race | Replay attempts returned 500 instead of 401 | Fail closed: contention → deny |
| `complete_file` raised when it lost the race | A client retry could 500 instead of resolving idempotently | Re-read and answer `already` / `""` |

## External UNKNOWN register (do not invent PASS)

| Control | Evidence |
|---------|----------|
| Firebase MCP / active project | Confirm in console |
| GitHub Environments | Shells exist (`staging`, `production`, `release`, `production-backup`); `restore-drill` may still need creating; reviewers often empty on Free |
| Secrets / vars | Prefer **repo-level** on Free private orgs; Environment names still select workflow targets |
| Branch protection / ruleset | Confirm on `main`; 403 / unavailable → process-only until plan allows |

## Risk acceptance

Any remaining PARTIAL/UNKNOWN item at launch needs an explicit owner, expiry
date, and compensating control written below:

| Item | Owner | Accept until | Compensating control |
|------|-------|--------------|----------------------|
| _tbd_ | | | |

## Sign-off

| Role | Name | Date | Result |
|------|------|------|--------|
| Engineering | | | |
| Security / ops | | | |
| Product / legal | | | |
