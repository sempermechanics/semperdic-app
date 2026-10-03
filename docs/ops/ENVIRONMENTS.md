# GitHub Environments, secrets, and vars

Maintainer map of which GitHub Environment (or repo-level secret/var) each
workflow expects. Companion to [CI.md](CI.md) and [RELEASING.md](RELEASING.md).

## Free private org constraint

This repo lives on a **Free** private GitHub org plan. Environment *shells*
exist and workflows can still select them (`environment: staging`), but:

- **Required reviewers / deployment protection** on Environments may be limited
  or unavailable.
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

**Create `restore-drill` if it is missing.** Backup and restore credentials must
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
| `AUTO_APPROVE_HD`, `ADMIN_EMAILS`, `SUPPORT_EMAIL`, `NOTIFY_FROM` | vars | Access / mail. `ADMIN_EMAILS` holds several addresses **space-separated**, like `CONSOLE_ORIGINS`: `deploy-backend.yml` passes it through an `env_vars` block that splits pairs on commas. |
| `TASKS_QUEUE`, `TASKS_TARGET_BASE_URL` | environment vars | Per environment (`production`: `semper-provision` + the `semper-api` URL; `staging`: `semper-provision-staging` + the `semper-api-staging` URL). Empty → inline provisioning |
| `TASKS_LOCATION`, `TASKS_INVOKER_SA` | vars | Shared: `asia-south1`, the runtime SA |
| `REQUIRE_ATTESTED_UPLOADS` | var | **Unused** since 2026-09-26 (TD-45): the backend no longer reads it and `deploy-backend.yml` no longer passes it. Keep it at `1` while a redeploy of an older commit is plausible (that workflow still pins it), then delete it |
| `DEMO_MAX_ANALYSES`, `LICENSED_MAX_SESSIONS_PER_USER` | vars | Cloud caps by `mode`. Unset → `25` / `999` (expression defaults in the workflow). Set `DEMO_MAX_ANALYSES` from the pre-deploy Firestore survey — every pre-licensing account is demo |
| `ADMIN_WEB_MFA_ENABLED`, `APP_CHECK_MODE`, `SELF_DEVICE_CHANGE_COOLDOWN_DAYS` | vars | Unset → `1` / `off` / `30`. `APP_CHECK_MODE` must stay `off` while a build without App Check is installed; `enforce` 403s it |
| `MIN_INSTANCES` | var | Warm Cloud Run instances. Unset → `0` in both environments (since 2026-09-26: one idle warm instance was the whole bill and over the ₹500/month budget, [perf/backend-cost.md](../perf/backend-cost.md)). The first call after about 15 minutes idle waits for a cold start, measured at p50 3.9 s and p95 6.0 s. Set `1` to keep one instance warm, at roughly ₹800–1,150 ($10–14) a month. Tagged `cand-*` revisions do not carry traffic, but prune them so an old tag cannot hold an instance |
| `CONSOLE_ORIGINS` | var | Browser origins the API answers CORS for. Unset → `https://app.sempermechanics.com https://indicvision-dic-app-auth.firebaseapp.com`. Space-separated, never commas (the deploy action splits `env_vars` on them). Add a Hosting preview channel here while testing a console change |

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
