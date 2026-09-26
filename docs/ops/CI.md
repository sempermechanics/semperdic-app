# CI — path-filtered tiers (PR and push)

Defined in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml).

**`main` is the only integration branch.** Pull requests target `main`. Pushes
to `main` run the full matrix. Feature-branch PRs run always-on gates plus
path-filtered Tier 1 / Tier 4; expensive Tier 3 / Tier 5 run on push to `main`
or when a PR carries an `e2e` / `release` / `full-ci` label.
Benchmarks (macro startup and the `HotPathMicroBenchmark` micro suite) run only
with the `benchmark` label or `workflow_dispatch` → `run_benchmark`.

There is **no weekly/scheduled full matrix** — use those labels or
**Actions → CI → Run workflow** (`workflow_dispatch`, `full_ci` defaults to
**false**) for an on-demand full run.

Secrets, vars, and GitHub Environments are mapped in
[ENVIRONMENTS.md](ENVIRONMENTS.md).

```
secret-scan ─────────────────────────────┐
legal-pages ─────────────────────────────┤
console-pages ───────────────────────────┤
changes ──┬──> tier1-app-fast ───────────┤
          ├──> tier3-emulator-e2e (*) ───┤
          ├──> tier4-backend ────────────┤
          └──> tier5-signed-release (*) ─┤
                                         └──> ci-ok

(*) On a PR, Tier 3/5 need an `e2e` / `release` / `full-ci` label (or a
    workflow_dispatch with full_ci). Push to main always schedules them.
    Skipped Tier 1 no longer cascade-skips JNI/release jobs.
```

| Job | Proves | Path-filtered? | Typical (warm / cold) |
|-----|--------|---|-----------------------|
| `secret-scan` | gitleaks (see below) | No — always runs | ~1–2 min |
| `legal-pages` | `scripts/render_legal_pages.py --check`: the published pages still match `docs/legal/` | No — always runs | seconds |
| `console-pages` | `scripts/check_console.py`: the consoles' wiring, CSP, deploy placeholders and gateway paths — their only gate, since they have no compiler — plus `node --test`: the DOM-free `console/util.js`, `auth.js` (sign-in, second factor, step-up, `api`, revoke gate), `router.js`, and the operator, account and institution pages, against a fake Firebase SDK loaded through a `module.register` hook and a DOM parsed from the real pages (`firebase-hosting/tests/harness.mjs`) | No — always runs | seconds |
| `changes` | Resolves path filters + PR/main/Dependabot mode into tier flags | — | seconds |
| `tier1-app-fast` | spotless, detekt, lint, JVM unit tests, `compileReleaseKotlin`, Kover coverage log + `koverVerify` floor | `app` (PR); always on `main` push | ~5–8 / ~10 min |
| `tier3-emulator-e2e` | x86_64 emulator: JNI smoke + `AnalysisWizardSmokeTest`. Excludes `com.indicvision.semper.benchmark` on debug (those need the `benchmark` job). | main push / labels | ~20–40 / ~60–90 min |
| `tier4-backend` | ruff, shell-script parse, pip-audit, hashed-lock verification, pytest at `--cov-fail-under=75`, Firestore emulator suite | `backend` (PR); always on `main` push | ~5–10 min |
| `tier5-signed-release` | R8 + signed `assembleRelease` arm64, `.so` presence, signature verify, R8 mapping artifact | main push / labels | ~15–40 / up to ~90 min |
| `tier-benchmark` | One API 34 emulator, two suites: Macrobenchmark cold/warm startup (`:benchmark`, `suppressErrors=EMULATOR,LOW-BATTERY,UNLOCKED`), then `:app`'s `HotPathMicroBenchmark` on the debug build, installed by Gradle and run with `adb shell am instrument` (`suppressErrors` adds `DEBUGGABLE`, `ACTIVITY-MISSING`, `NOT-AOT-COMPILED`; through Gradle the list arrives cut at its first comma, TD-86; the step fails unless `am instrument` prints `OK (`, and the results are pulled from the device). Emulator **smoke**: no numeric thresholds; both upload their `*-benchmarkData.json` (`macrobenchmark-results`, `microbenchmark-results`). `scripts/ci_test_report.py` prints failing tests and each metric's min / median / max (`BENCH …` lines) into the log, as Tier 3 does for its failures. | `benchmark` label / `run_benchmark` dispatch only | ~30–60 min |
| `ci-ok` | Single required status check — every job above passed or was skipped | — | seconds |

**There is no tier 2 here any more.** Host C++ builds, the DICe comparisons and
the ASan/UBSan and TSan suites moved to `sempermechanics/semper-dic-engine` along with
the engine sources. This repo only proves the pinned submodule still *links*:
arm64 in tier 5, x86_64 on the emulator in tier 3. `ci-ok` prints a reminder of
where the engine suites live.

## Secret scan (two paths)

`secret-scan` always runs and gates `ci-ok`. How it runs depends on the branch:

| Event | How | Scope |
|-------|-----|-------|
| Human PR / push / dispatch | `gitleaks/gitleaks-action` | Full git history (`fetch-depth: 0`) |
| Dependabot PR (`head_ref` starts with `dependabot/`) | gitleaks **CLI** (no org license / no PR-commits API) | Only `${{ pull_request.base.sha }}..HEAD` |

Key off **`github.head_ref`**, not `actor`: a human rebase of a Dependabot branch
still needs the CLI path because Dependabot PRs do not receive normal Actions
secrets.

- Org / repo secret **`GITLEAKS_LICENSE`** is required for the action path.
- Optional: add the same value under **Settings → Secrets → Dependabot** if you
  later want the action on Dependabot PRs too. Until then the CLI path is enough.

Local repro for a human PR (full history):

```bash
gitleaks detect --config .gitleaks.toml
```

Local repro matching Dependabot (PR range only):

```bash
gitleaks detect --config .gitleaks.toml --log-opts="<base-sha>..HEAD"
```

## Path filters

| Output | Paths | Jobs |
|--------|-------|------|
| `app` | `app/**` except `app/src/main/cpp/**`; `gradle/**`, `*.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `settings.gradle.kts` | tier 1 |
| `native_core` | `native` (the gitlink itself) and `.gitmodules` | — see note |
| `native_jni` | `native`, `.gitmodules`, `app/src/main/cpp/**`, `SemperNativeLib.kt` | tiers 3 + 5 on main / labels |
| `backend` | `backend/**`, `firestore.rules`, `firebase-hosting/**`, `scripts/deploy-firestore.sh`, and the client's three wire-contract files | tier 4 |
| `ci_workflow` | `.github/workflows/ci.yml` | sets `app` + `backend` so a workflow-only PR is not gates-only |
| `full_ci` | `full-ci` label, or `workflow_dispatch` with `full_ci: true`, or **push to `main`** | all |

Because the engine is a submodule, `native_core` and `native_jni` match the
**gitlink** `native` rather than a source tree — bumping the pinned engine
commit is what triggers them. `native_core` no longer has a job of its own.

Two further filters widen `app` rather than gating a job directly:
`app/src/androidTest/**` and the packaging files (`app/build.gradle.kts`,
`app/proguard-rules.pro`, `gradle/libs.versions.toml`).

### PR vs push to `main`

| Diff class | T1 | T3 | T4 | T5 |
|------------|----|----|----|-----|
| app only (PR) | run | —† | — | —† |
| backend only (PR) | — | — | run | — |
| engine pin / JNI (PR) | — | —† | — | —† |
| push to `main` | run | run | run | run |
| `full-ci` label / dispatch `full_ci` | run | run | run | run |

† Also with the `e2e`, `release`, or `full-ci` label on the PR.

Editing `.github/workflows/ci.yml` on a PR runs Tier 1 and Tier 4 (the
`ci_workflow` filter). Empty path outputs otherwise fail **closed** to gates-only
(`secret-scan` + `legal-pages` + `console-pages` + `ci-ok`), not a full matrix. Rules-only or
Hosting-only PRs match `backend` so they cannot skip Tier 4.

### Dependabot cheap path

When `github.head_ref` starts with `dependabot/`:

| Ecosystem (from branch name) | Tiers |
|------------------------------|-------|
| `dependabot/pip/…` | gates + Tier 4 |
| `dependabot/gradle/…` | gates + Tier 1 |
| `dependabot/github_actions/…` | gates only — deploy workflows are **not** exercised; read WIF / Cloud Run action inputs by hand before landing |
| `dependabot/docker/…` | gates + Tier 4. Tier 4 runs on the runner's Python 3.12, not the image, so read the base-image change by hand too. `dependabot.yml` ignores Python minor/major bumps: only digest and patch updates arrive while the lock is compiled on 3.12 (TD-68) |

Never Tier 3 / Tier 5 for Dependabot. Path filters use an explicit
`base: pull_request.base.sha` so Dependabot's 403 on the PR Files API does not
fail open into a full matrix.

**A `dependabot/pip/…` PR always lands Tier 4 red, and that is expected.**
Dependabot bumps `backend/requirements.txt` and cannot regenerate the hashed
`requirements.lock`, so tier 4's *Verify the hashed lock* step reports a version
diff. Fix it before merging by running
[`Backend lock`](../../.github/workflows/backend-lock.yml) against the Dependabot
branch — it compiles on Linux / Python 3.12 and pushes the lock to that PR.
That push uses `GITHUB_TOKEN`, which starts no CI run, so re-run the PR's checks
afterwards (the status stays on the old commit until you do). See
[RELEASING.md](RELEASING.md) § Bumping backend dependencies.

`secret-scan`, `legal-pages` and `console-pages` are absent from the tables on
purpose: they carry no path filter and run on every event.

Skipped jobs count as success for `ci-ok`.

## Required check

Set **`CI OK`** (`ci-ok`) as the single required status check in a branch
ruleset / protection on `main`. It gates on all tiers and treats skipped jobs as
passing. Free private orgs may block classic branch protection — see
[ENVIRONMENTS.md](ENVIRONMENTS.md).

## On-demand full matrix

- PR label: `full-ci` (or `e2e` / `release` for Tier 3 / 5 only)
- Or: Actions tab → **CI** → **Run workflow** (tick `full_ci`)

## Reproducing a failure locally

```bash
# Full local gate (mirrors tiers 1 + 5, no emulator)
./gradlew ciReleaseGate

# Individual tiers
./gradlew :app:testDebugUnitTest spotlessCheck :app:detekt :app:lintDebug   # tier 1
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.notPackage=com.indicvision.semper.benchmark  # tier 3
cd backend && pip install -r requirements-test.txt && pytest tests/ -v      # tier 4

# The two always-on gates
python scripts/render_legal_pages.py --check                                # legal-pages
python scripts/check_console.py                                             # console-pages
node --test "firebase-hosting/tests/*.test.mjs"                             # console-pages
gitleaks detect --config .gitleaks.toml                                     # secret-scan (human)
```

The engine's own suites are not reproducible from this repo — run them in
`sempermechanics/semper-dic-engine`, or from the submodule as described in
[docs/engine/TESTING.md](../engine/TESTING.md).

## Manual release

A separate `workflow_dispatch` workflow ([release.yml](../../.github/workflows/release.yml))
builds a signed release APK and publishes it as a GitHub Release. Jobs only run
when the workflow is dispatched from **`main`**. See [RELEASING.md](RELEASING.md).

Release builds **require** `INDIC_API_BASE_URL` (repo or `release` environment
variable — see [ENVIRONMENTS.md](ENVIRONMENTS.md)) and pass
`-PrequireCloudApi=true` so an empty URL cannot silently ship with cloud sync
disabled. Local `assembleRelease` without that flag still allows offline
inspection builds. Release builds also pass `-PuploadCrashlyticsMapping=true`,
so the R8 mapping reaches Crashlytics; local builds never upload it.

## Backend deploy

[`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml) is
`workflow_dispatch` with separate **staging** and **production** GitHub
Environments. A **production** deploy runs only when dispatched from `main`
(the `deploy` job is skipped otherwise); staging may deploy a branch. It:

1. Runs the shared [`backend-gate`](../../.github/actions/backend-gate/action.yml),
   the same check Release and CI tier 4 run. It installs the hashed lock
   the image uses, runs `pip-audit`, ruff over `app/ tests/ scripts/ ../scripts/`,
   and pytest at the 75 % coverage floor.
2. Deploys from `backend/` tagging the new revision
   `cand-<run_id>-<run_attempt>`.
   - **Existing service:** `no_traffic: true` — the previous revision keeps
     serving while the candidate is smoked.
   - **First create:** Cloud Run rejects `--no-traffic` on create, so the
     workflow sets `no_traffic: false` for that one deploy. The candidate is
     still smoked before you treat it as proven; prefer creating staging once,
     then always updating.
3. Records the revision image digest.
4. Smokes `GET /readyz` on the **tagged candidate URL**, authenticated with an ID
   token whose audience is the service URL — both environments run
   `--no-allow-unauthenticated` (staging too: the opposite flag binds
   `allUsers`), so an unauthenticated probe would only prove the
   gateway rejects it. The deploy SA needs `run.invoker` on each service.
5. Promotes the candidate to 100% traffic once the smoke passes (when
   `no_traffic` was used), with `--to-latest` after checking the latest ready
   revision is the candidate, and removes every `cand-*` tag in the same call.
6. Tags images by digest for the registry cleanup policy: `serving` on the
   promoted revision's image, `rollback-prev` on the image of the revision it
   replaced. A failure here is an error annotation, not a rollback; re-pin by
   hand ([BACKEND_SETUP_GCP.md A7](../backend/BACKEND_SETUP_GCP.md#a7-storage-hygiene)).

7. The `gateway` job then moves that environment's API Gateway (`semper-gw`, or
   `semper-gw-staging` for staging) onto a config
   rendered from `backend/gateway/openapi.yaml`
   ([ADR-006](../adr/ADR-006-gateway-deploy-job.md)). Input `gateway_mode`
   defaults to `dry-run`, which prints the diff against the live config.
   `apply` creates the config, switches the gateway, checks `/v1/config` → 401
   and the consoles' preflight → 200 with the allowed origin, and switches back
   if either check fails.

On an update deploy, traffic never reaches an unproven revision, so a failed
smoke needs no rollback. The revision suffix includes the **run attempt** as
well as the run id, so re-running a failed job cannot collide with the revision
name the first attempt created.

Required secrets / vars (repo-level on Free private orgs is fine — Environment
*names* still select staging vs production in the workflow):
`GCP_WIF_PROVIDER`, `GCP_DEPLOY_SA`; vars `FIREBASE_PROJECT_ID`,
`SHARED_DRIVE_ID`, `SERVICE_ACCOUNT_EMAIL`, `AUTO_APPROVE_HD`, `ADMIN_EMAILS`,
`SUPPORT_EMAIL`, `NOTIFY_FROM`, async provisioning `TASKS_QUEUE`,
`TASKS_LOCATION`, `TASKS_TARGET_BASE_URL` and `TASKS_INVOKER_SA`.

`REQUIRE_ATTESTED_UPLOADS` is no longer passed (retired 2026-09-26, TD-45):
`/uploads` is always device-attested. The "Describe live env" step warns while
a service still carries it; remove it after promote. See
[BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md).

API Gateway OpenAPI uses the placeholders `__CLOUD_RUN_URL__`,
`__FIREBASE_PROJECT_ID__` and `__MANAGED_SERVICE__` — substitute all three at config-create time (see
`backend/gateway/openapi.yaml`). The substituted output goes to
`backend/gateway/openapi.generated.yaml`, which is **gitignored**: never commit a
live hostname or project id.

## Data-protection workflows

Two more scheduled workflows guard Firestore, both documented in
[FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md):

| Workflow | When | What it does |
|---|---|---|
| [`firestore-backup.yml`](../../.github/workflows/firestore-backup.yml) | Daily, 02:17 UTC | Exports Firestore to a GCS bucket, writing a `manifest.json` beside it. Refuses to run unless PITR is on |
| [`firestore-restore-drill.yml`](../../.github/workflows/firestore-restore-drill.yml) | Monthly, 03:40 UTC on the 1st, plus on demand | Imports the latest export into a throwaway project, verifies it against the manifest, then purges it. Runs in its own `restore-drill` GitHub environment |

Create the **`restore-drill`** environment before relying on the schedule — the
`production-backup` environment is not a substitute. See
[ENVIRONMENTS.md](ENVIRONMENTS.md).

## Caching

| Cache | Key pattern | Purpose |
|-------|-------------|---------|
| `app/.cxx` | `cxx-arm64-<hash>` (tier 5 + Release), `cxx-x86_64-<hash>` (tier 3) | ABI-specific CMake/ninja tree |
| `ccache` | `ccache-arm64-<hash>`, `ccache-x86_64-<hash>` | Compiled object cache, per ABI, keyed on native sources (not commit SHA) so Kotlin-only runs exact-hit |
| `~/.gradle` | managed by `setup-gradle`; `org.gradle.caching=true` | Dependency + task output cache |
| pip | managed by `setup-python` in `backend-gate`, keyed on `backend/requirements.lock` + `requirements-test.txt` | Backend dependencies |

### Shared steps

Local composite actions under `.github/actions/` hold the steps more than
one job runs:

| Action | Used by | What |
|--------|---------|------|
| `setup-android-build` | every app job | JDK 17 + Gradle; cache written from `main` only |
| `setup-native-ci` | tier 3, tier 5, Release, benchmark | `.cxx` / ccache restore, OpenCV sparse checkout, the `CCACHE_*` env |
| `enable-kvm` | tier 3, benchmark | Emulator acceleration |
| `check-arm64-so` | tier 5, Release | The release APK carries `libsemper_core.so` |
| `backend-gate` | tier 4, Release, Deploy | Lock install, audit, ruff, pytest + floor |
| `prune-cache` | tier 3, tier 5, Release | Drops superseded cache entries |

### Keeping under the 10 GB limit

A repository gets 10 GB of Actions cache. Each caching job prunes its own prefix
via [`.github/actions/prune-cache`](../../.github/actions/prune-cache/action.yml).
[`cache-cleanup.yml`](../../.github/workflows/cache-cleanup.yml) also drops a
PR's caches on close and runs a **weekly** sweep (plus on-demand) to delete
caches of deleted branches / closed PRs and trim LRU until usage is under 8 GB.

```bash
gh cache list --limit 100 --sort size_in_bytes --order desc
```

## Things that surprise people

**Only arm64-v8a is shipped.** Every phone from 2022 on is 64-bit ARM. CI builds
exactly what users get. Build for an emulator locally with `-PabiFilters=x86_64`.

**Wrapper validation is off in NDK jobs and in the Release workflow.** OpenCV's
repo bundles ancient `gradle-wrapper.jar` files that fail checksum validation,
and those jobs check out submodules recursively. Our own wrapper is validated by
tier 1 when tier 1 runs.

**A new push cancels a PR's older run, never `main`'s.** CI's concurrency
group is per branch, and `cancel-in-progress` is true only for `pull_request`
events. On `main` a started run finishes its full matrix. GitHub keeps one
pending run per group, so several merges while one run is in flight queue
only the newest; the ones in between get no run of their own. Before this,
merges cancelled each other: on 2026-09-26 the `main` runs of #268, #270 and
#271 were all cancelled mid-matrix, so #271's app-and-console change never had a
full run of its own (TD-97, as in material_testing#67).

**Three jobs run on every single event.** `secret-scan`, `legal-pages` and
`console-pages` carry no path filter, so a documentation-only PR still runs
them — and can still be blocked by them, which is the point.

**Runners are pinned to `ubuntu-24.04`, not `ubuntu-latest`.** GitHub moves
`ubuntu-latest` to Ubuntu 26 from 2026-10-19. The emulator, NDK / OpenCV and
Firestore-emulator jobs are sensitive to the image, so moving to 26 is its own
PR, run with `full_ci=true`, rather than a silent label change.

**Emulator steps pass `-no-metrics`.** Without an explicit metrics choice the
Android emulator prints an "ACTION REQUIRED" banner, which its own notes say a
future release turns into a blocking prompt. Keep the flag when editing
`emulator-options`.

**A green backend tier means more than pytest.** Tier 4 also audits dependencies
with pip-audit, proves `requirements.lock` resolves under `--require-hashes` on
Python 3.12, and checks that **versions** of every direct dep in
`requirements.txt` match the lock (not just package names). Deploy's `test` job
installs the lock the same way the image does.
