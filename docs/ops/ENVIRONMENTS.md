# GitHub Environments, secrets, and vars

Maintainer map of which GitHub Environment (or repo-level secret/var) each
workflow expects. Companion to [CI.md](CI.md) and [RELEASING.md](RELEASING.md).

## Free private org constraint

This repo's org is on the GitHub **Free** plan. The repository itself reports
**public** (GitHub REST API, checked 2026-10-18), so its Releases and Actions logs are
public too; what follows was written for a private repo and may now be loosened.
Environment *shells* exist and workflows can still select them
(`environment: staging`), but:

- **Required reviewers** work on a public repository: `production` requires the
  product owner's approval for every deploy (set 2026-10-22, owner decision;
  admins may still bypass). No other environment has protection rules.
- **Public artifacts.** On a public repository any signed-in GitHub user can
  download a workflow artifact while it is kept. So nothing secret goes in one;
  the R8 mappings (`release-mapping*`) are kept 14 days (release) and 3 days (CI
  Tier 5), [RELEASING.md](RELEASING.md).
- Prefer storing **secrets and variables at the repository level** when
  Environment-scoped secrets are empty or cannot be set. Workflows read
  `secrets.*` / `vars.*` the same way either way.
- Classic branch protection sometimes returns 403; use a **ruleset** on `main`
  when the plan allows, otherwise treat green `CI OK` + PR-only merges as
  process.

Do not invent Environment-only secrets that the plan cannot enforce — document
the real placement.

## Environments in this repo

| Environment | Used by | Purpose |
|-------------|---------|---------|
| `staging` | [`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml) | Cloud Run staging deploy + candidate smoke |
| `production` | same | Cloud Run production deploy + candidate smoke |
| `release` | [`release.yml`](../../.github/workflows/release.yml) | Signed APK + GitHub Release publish |
| `production-backup` | [`firestore-backup.yml`](../../.github/workflows/firestore-backup.yml) | Daily Firestore export |
| `restore-drill` | [`firestore-restore-drill.yml`](../../.github/workflows/firestore-restore-drill.yml) | Monthly / on-demand restore verify |

**`restore-drill` has its five variables** (set by `scripts/setup-restore-drill.sh` on 2026-10-09; the first drill passed 2026-10-10). Backup and restore credentials must
not share an environment: the drill identity can write to a throwaway project
and must never be able to import over production. See
[FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md).

## Secrets and variables by workflow

### CI ([`ci.yml`](../../.github/workflows/ci.yml))

| Name | Kind | Notes |
|------|------|-------|
| `GITLEAKS_LICENSE` | secret | Required for human PRs / pushes (gitleaks-action). Optional Dependabot copy under Dependabot secrets |
| `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD` | secrets | Optional for Tier 5 signature verify; unsigned notice if absent |

### Release ([`release.yml`](../../.github/workflows/release.yml))

| Name | Kind | Notes |
|------|------|-------|
| `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD` | secrets | Required to ship a signed APK |
| `SEMPER_API_BASE_URL` | var | HTTPS API Gateway (preferred) or Cloud Run URL; required with `-PrequireCloudApi=true` |
| `SEMPER_API_CERT_PINS` | var | Required for a release: the four GTS root pins, comma-separated ([RELEASING.md § Certificate pins](RELEASING.md#certificate-pins-semper_api_cert_pins)); a secret of the same name also works. Empty fails the release (`check_release_apk.py --require-pins`); CI tier 5 only warns |
| `SEMPER_ENTITLEMENT_PUBKEYS` | var | Entitlement-signature public key(s) baked into the release build; once set, `check_release_apk.py --require-entitlement-pins` fails a release that compiled none |
| `SEMPER_REQUIRE_ENTITLEMENT_PINS` | var | Optional; `true` once TD-215 is live keeps that requirement on even if the pubkeys variable is cleared ([RELEASING.md](RELEASING.md) step 5) |
| `SEMPER_RELEASE_CERT_SHA256` | var | Optional upstream (defaults to the certificate of every Semper release, `FF:72:…:5C:9A`); the release must be signed with it. Set it only for a deliberate key rotation; the Semper Classroom fork (material_testing) sets its own |

Dispatch **from `main` only** — jobs no-op on other refs.

### Resource names

Services and queues are named `semper-*`. Service-account emails, project IDs and
the gateway hostname cannot be renamed in place, so these keep their original
names:

| Resource | Name | Notes |
|----------|------|-------|
| Cloud Run (production) | `semper-api` | `https://semper-api-u5luo3i5ma-el.a.run.app`, private |
| Cloud Run (staging) | `semper-api-staging` | `https://semper-api-staging-u5luo3i5ma-el.a.run.app`, private |
| Cloud Tasks queues | `semper-provision`, `semper-provision-staging` | One per environment (asia-south1) |
| API Gateway | `semper-gw`, `semper-gw-staging` | APIs `semper-api`, `semper-api-staging` (asia-northeast1) |
| Gateway host (production) | `semper-gw-86wx7pp1.an.gateway.dev` | Baked into app builds — never replace the gateway |
| Runtime SA | `indic-api@indicvision-dic-app.iam.gserviceaccount.com` | Runs both services; Tasks OIDC identity |
| Gateway SA | `indic-gw@indicvision-dic-app.iam.gserviceaccount.com` | Gateway → Cloud Run invoker |
| Deploy SA | `indic-deployer@indicvision-dic-app.iam.gserviceaccount.com` | GitHub Actions (WIF) |
| Backup SA | `indic-backup@indicvision-dic-app.iam.gserviceaccount.com` | Firestore export workflow |
| Unused SA | `dic-app-support@indicvision-dic-app.iam.gserviceaccount.com` | No bindings in use |
| Projects | `indicvision-dic-app`, `indicvision-dic-app-auth` | Backend; Firebase Auth / Hosting |

Each service needs `run.invoker` for the gateway, runtime (Tasks) and deploy SAs —
never `allUsers`. `TASKS_QUEUE` and `TASKS_TARGET_BASE_URL` are **environment**
variables (GitHub environments `production` / `staging`): a repository-level value
once pointed staging's tasks at the production service and queue.

### Backend deploy ([`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml))

| Name | Kind | Notes |
|------|------|-------|
| `GCP_WIF_PROVIDER`, `GCP_DEPLOY_SA` | secrets | Workload Identity Federation |
| `FIREBASE_PROJECT_ID`, `SHARED_DRIVE_ID`, `SERVICE_ACCOUNT_EMAIL` | vars | Runtime identity |
| `AUTO_APPROVE_HD`, `ADMIN_EMAILS`, `SUPPORT_EMAIL`, `NOTIFY_FROM`, `NOTIFY_SECURITY_FROM` | vars | Access / mail. `NOTIFY_SECURITY_FROM` (a verified Resend sender) turns on the holder's new-device mail (TD-295); `deploy-backend.yml` passes it like `NOTIFY_FROM` (TD-309), and unset (empty) it keeps that mail off. `ADMIN_EMAILS` holds several addresses **space-separated**, like `CONSOLE_ORIGINS`: `deploy-backend.yml` passes it through an `env_vars` block that splits pairs on commas. |
| `TASKS_QUEUE`, `TASKS_TARGET_BASE_URL` | environment vars | Per environment (`production`: `semper-provision` + the `semper-api` URL; `staging`: `semper-provision-staging` + the `semper-api-staging` URL). Empty → inline provisioning |
| `TASKS_LOCATION`, `TASKS_INVOKER_SA` | vars | Shared: `asia-south1`, the runtime SA |
| `REQUIRE_ATTESTED_UPLOADS` | var | **Unused** since 2026-09-26 (TD-45): the backend no longer reads it and `deploy-backend.yml` no longer passes it. Keep it at `1` while a redeploy of an older commit is plausible (that workflow still pins it), then delete it |
| `DEMO_MAX_ANALYSES`, `LICENSED_MAX_SESSIONS_PER_USER` | vars | Cloud caps by `mode`. Unset → `25` / `999` (expression defaults in the workflow). Set `DEMO_MAX_ANALYSES` from the pre-deploy Firestore survey — every pre-licensing account is demo |
| `ADMIN_WEB_MFA_ENABLED`, `APP_CHECK_MODE`, `IMPLICIT_ACTIVATION` | vars | Unset → `1` / `off` / `legacy`. `SELF_DEVICE_CHANGE_COOLDOWN_DAYS` is no longer read (ADR-033); the deploy merges env, so it stays on the service until removed with `--remove-env-vars`. `APP_CHECK_MODE` must stay `off` while a build without App Check is installed; `enforce` 403s it |
| `MIN_INSTANCES` | var | Warm Cloud Run instances. Unset → `0` in both environments (since 2026-09-26: one idle warm instance was the whole bill and over the ₹500/month budget, [perf/backend-cost.md](../perf/backend-cost.md)). The first call after about 15 minutes idle waits for a cold start, measured at p50 3.9 s and p95 6.0 s. Set `1` to keep one instance warm, at roughly ₹800–1,150 ($10–14) a month. Tagged `cand-*` revisions do not carry traffic, but prune them so an old tag cannot hold an instance |
| `CHECK_REVOKED_ON_STEP_UP` | var | Unset → `false`. `true` makes step-up calls check the ID token's revocation (`verify_id_token(check_revoked=True)`); the runtime SA then needs `roles/firebaseauth.admin` on the Auth project (or a role with `firebaseauth.users.update` and `.delete`, which revoke and delete also need; `check_gcp_posture.sh` item 3.4-iam) |
| `ENTITLEMENT_KID`, `ENTITLEMENT_OFFLINE_GRACE_DAYS` | vars | Unset → `ent-1` / `14`. Key id stamped on signed entitlements, and the offline grace they carry |
| `ENTITLEMENT_SIGNING_SECRET` | var | Name of the Secret Manager secret holding the entitlement signing key (e.g. `entitlement-signing-key`). Set → the deploy mounts it as `ENTITLEMENT_SIGNING_KEY=<name>:latest`, if the secret exists; unset or missing → the deploy goes ahead without it, with a notice or warning. The deploy SA needs `roles/secretmanager.viewer` to see it, the runtime SA `roles/secretmanager.secretAccessor` on it |
| `MIN_SUPPORTED_VERSION_CODE`, `RECOMMENDED_VERSION_CODE` | vars | Unset → `0` / `0` (no gating). Semper's release gate: `/v1/config` sends them to Semper builds only, and the app's update gate compares its own versionCode |
| `MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM`, `RECOMMENDED_VERSION_CODE_SEMPER_CLASSROOM` | vars | Unset → the `_MATERIAL_TESTING` variable of the same pair, then `0` / `0`. The same gate for Semper Classroom builds (and Material Testing builds, the same app before [ADR-030](../adr/ADR-030-semper-classroom-app-identity.md)), which number their versionCodes from their own release counter (`settings.release_gate(app)`, `backend/app/config.py:308-335`; `deploy-backend.yml` passes both names) |
| `MIN_SUPPORTED_VERSION_CODE_MATERIAL_TESTING`, `RECOMMENDED_VERSION_CODE_MATERIAL_TESTING` | vars | The pre-rename names of the pair above, read only where the `_SEMPER_CLASSROOM` one is unset. Move their values to the new names, then delete them (TD-330) |
| `KILL_SWITCHES` | var | Unset → empty. Feature names to switch off (`backup`, `share`, `restore`, `upload`), **space-separated** (the deploy action splits on commas); unknown names are ignored and logged. Published on `/v1/config` and, since 2026-10-18, **enforced by the backend**: `upload` / `backup` refuse `POST /v1/sessions` and `POST /v1/files/{id}/complete`, `restore` refuses file content and the bundle, with 503 `feature_paused` and `Retry-After: 300` (`deps.unless_paused`, `backend/app/deps.py:301-336`). `share` has no route and is published only |
| `TEST_ACCOUNT_DOMAINS` | var | Unset → empty, and staff test accounts are **off**: every `POST /v1/admin/test-accounts` answers 403 `test_accounts_disabled` ([ADR-027](../adr/ADR-027-test-accounts.md)). The address domains a QC or CI test account may be created on, e.g. `qa.sempermechanics.com`: a domain the company controls and no customer uses, matched exactly (no subdomains). Several are **space-separated** (the deploy action splits on commas; commas and `;` also parse). `deploy-backend.yml` passes `${{ vars.TEST_ACCOUNT_DOMAINS }}`, and the backend reads it at start (`backend/app/config.py`, `Settings.TEST_ACCOUNT_DOMAINS`) |
| `CONSOLE_ORIGINS` | var | Browser origins the API answers CORS for. Unset → `https://app.sempermechanics.com https://indicvision-dic-app-auth.firebaseapp.com`. Space-separated, never commas (the deploy action splits `env_vars` on them). Add a Hosting preview channel here while testing a console change |

`AUTH_ADMIN_CALLS_PER_SECOND` and `AUTH_ADMIN_BURST` (the per-instance Firebase Auth
Admin budget, defaults 10 and 20) are **not** passed by `deploy-backend.yml`: the
service runs on the defaults unless set on it by hand
([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md), optional variables).

**The `service` input belongs to the `environment` (since 2026-11-01).** Both
environments authenticate with the same repo-level `GCP_WIF_PROVIDER` /
`GCP_DEPLOY_SA`, and only `production` is gated (main only, the owner's review).
So `environment=staging` with `service=semper-api` used to deploy any branch to
production, unreviewed and with staging's environment variables. Both jobs now run
`scripts/check_deploy_target.sh` before any credential is minted: with `staging`
only a `*-staging` name (empty = `semper-api-staging`), with `production` never
one (empty = `semper-api`). The gateway job checks the service, `GATEWAY_ID` and
`GATEWAY_API` the same way, so a variable cannot point a staging run at
`semper-gw`. This is a workflow rule; GCP itself would still accept a staging
run's identity on the production service (a workflow edited on a branch could
skip the check). Owner-side hardening that would make it a GCP rule: a WIF
attribute condition per environment (`assertion.environment`), or a separate
production deploy SA held as a `production` environment secret, with the
staging SA granted on `semper-api-staging` only (TD-366).

**Workflow inputs.** Besides `environment`, `project`, `region`, `service` and
`gateway_mode`, `release_train` (since 2026-10-18, TD-262) names the app release
train the deploy ships with. It is set as the Cloud Run label `release-train` on the
service and the new revision: lowercased, dots and other characters turned into
`-`, at most 63 characters (`v1.2-beta.4` becomes `v1-2-beta-4`). Empty, it is the
newest `v*` tag reachable from the deployed commit, or `untagged`; the deploy job
checks out the full history for that (`fetch-depth: 0`). Find the revision a
release was tested against with
`gcloud run revisions list --service semper-api --region asia-south1 --filter=metadata.labels.release-train=v1-2-beta-4`.

**Deploy order** when a release adds a Firestore index, a gateway path or a console
change: indexes (`scripts/deploy-firestore.sh indexes`, wait for the build), then
the backend (this workflow, `gateway_mode: dry-run`), then the gateway (`apply`),
then Hosting (`scripts/deploy-console.sh` from a clean `main`; it refuses another
branch or a dirty tree and runs the console and legal checks first), then monitoring
(`scripts/apply-monitoring.sh`). A backend ahead of its index fails the query at
runtime (`FAILED_PRECONDITION`); a console ahead of its gateway path gets 404s.

### Storage hygiene

Artifact Registry `cloud-run-source-deploy` (asia-south1) and the bucket
`run-sources-indicvision-dic-app-asia-south1` fill up with every source deploy. Each image
is about 80 MB. Both delete what is more than 15 days old; the registry never deletes an
image tagged `serving` (moved to the serving image by each deploy) or `latest`, any with a
tag starting `rollback` (`rollback-prev`, moved by each deploy to the image it replaced,
and hand-pinned ones, kept until the tag is removed), or any of a package's five newest
versions. The policy files are in
[`backend/deploy/`](../../backend/deploy/), and the one-time apply commands are in
[BACKEND_SETUP_GCP.md A7](../backend/BACKEND_SETUP_GCP.md#a7-storage-hygiene). Applied on
`indicvision-dic-app` on 2026-09-26, with the registry policy enforcing. The Firestore
backup bucket is not covered: it has its own retention.

### Firestore backup / restore drill

See [FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md) for
WIF + bucket + drill project variables (`GCP_WORKLOAD_IDENTITY_PROVIDER`,
`FIRESTORE_BACKUP_BUCKET`, `FIRESTORE_RESTORE_DRILL_*`, etc.).

**`production-backup` must define these Environment (or repo) variables** or the
daily export fails at auth with an empty `workload_identity_provider`:

| Variable | Purpose |
|----------|---------|
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | WIF provider resource name |
| `FIRESTORE_BACKUP_SERVICE_ACCOUNT` | Backup export SA email |
| `GCP_PROJECT` | Production GCP project id |
| `FIRESTORE_BACKUP_BUCKET` | Destination bucket name **without** `gs://` (`firestore-export.sh` adds the prefix) |

### Deploy Backend pitfall

The `project` workflow input is the **GCP project id**
(e.g. `indicvision-dic-app`), not the Cloud Run service name (`semper-api` /
`semper-api-staging`). A swapped value used to look like a “first deploy” and
then fail on smoke URL lookup with `PERMISSION_DENIED` on a project named
after the service.

## Default branch and `main` hygiene

- **Default branch:** `main` (integration + Dependabot target). Feature work
  lands via PR into `main`; do not use `damodar` as an integration branch.
  If GitHub still shows `damodar` as default, a repo admin must switch
  **Settings → General → Default branch → `main`** (the Actions token cannot
  change it). Until that flip, Dependabot may still open against `damodar`
  even though `dependabot.yml` sets `target-branch: main` after merge.
- **PR-only onto `main`:** no direct pushes when a ruleset can enforce it;
  emergency hotfix must be followed by a recording PR.
- **No force-push / no branch deletion** on `main`.
- **Required check:** `CI OK`.
- **`main` stays releasable:** if full CI on push fails, fix or `git revert`
  the same day — do not pile features on a red tip.
- **Label-driven heavy CI on PRs:** `e2e` / `release` / `full-ci`.
- **Batch releases** from `main` via `release.yml` when a coherent set is ready.
- **Docs change with behavior:** auth, quotas, deploy env vars, and CI modes
  update the matching doc in the same PR.

### Ruleset checklist (when the org plan allows)

- Default branch = `main`
- Require pull request before merging
- Require status checks: `CI OK`
- Require conversation resolution (if available)
- Block force-push and branch deletion on `main`
- Optional: required review count ≥ 1, dismiss stale approvals on new commits
