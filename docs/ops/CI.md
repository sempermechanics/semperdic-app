# CI — path-filtered tiers (PR and push)

Defined in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml).

**`main` is the only integration branch.** Pull requests target `main`. Every
event runs the always-on gates; the tiers follow the diff:

- Tier 1 (app), Tier 4 (backend) and the Firestore rules tests run when their
  paths change, and on every push to `main`.
- Tier 3 (emulator) and Tier 5 (arm64 release, R8) run when the diff can break
  them — an engine bump or JNI change runs both, a packaging change (keep rules,
  `app/build.gradle.kts`, the version catalog) runs Tier 5 — on a PR and on a
  push to `main` alike. The `e2e` / `release` / `full-ci` labels force both.
- The whole matrix runs **weekly** (Monday 04:17 UTC, `schedule`), with the
  `full-ci` label, or from **Actions → CI → Run workflow** with `full_ci`.

Benchmarks (macro startup and the `HotPathMicroBenchmark` micro suite) run only
with the `benchmark` label or `workflow_dispatch` → `run_benchmark`.

Secrets, vars, and GitHub Environments are mapped in
[ENVIRONMENTS.md](ENVIRONMENTS.md).

```
secret-scan ───────────────────────────────────────────────────┐
engine-pin ────────────────────────────────────────────────────┤
legal-pages ───────────────────────────────────────────────────┤
console-pages ─────────────────────────────────────────────────┤
console-a11y ──────────────────────────────────────────────────┤
console-lighthouse ────────────────────────────────────────────┤
changes ──┬──> tier1-static ──┬────────────────────────────────┤
          │                   ├──> tier3-emulator-e2e (*) ─────┤
          │                   ├──> tier5-signed-release (*) ───┤
          │                   └──> tier-benchmark (*) ─────────┤
          ├──> tier1-app-fast ─────────────────────────────────┤
          ├──> tier4-backend ──────────────────────────────────┤
          ├──> firestore-rules ────────────────────────────────┤
          ├──> android-deps-osv ───────────────────────────────┤
          ├──> semgrep ────────────────────────────────────────┤
          └──> engine-host-sanitized (†) ──────────────────────┤
                                                               └──> ci-ok

(*) Tier 3/5 follow the `emulator` / `release_build` flags: engine, JNI and
    packaging paths, the labels, the weekly run; the benchmark job its own
    (`benchmark_ci`). They wait for Tier 1's static checks (spotless, detekt,
    about two minutes), not for its unit tests: a skipped Tier 1 (an
    engine-only bump) does not skip them; failed static checks do.
(†) Whenever Tier 3 runs, and on a ci.yml change.
```

| Job | Proves | Path-filtered? | Typical (warm / cold) |
|-----|--------|---|-----------------------|
| `secret-scan` | gitleaks (see below) | No — always runs | ~1–2 min |
| `engine-pin` | `scripts/check_engine_pin.py`: the `engine` gitlink points at a `v*` tag of the engine repository and, when it changed, descends from the previous pin (`HEAD^1`); the PR label `engine-downgrade` allows a backwards or sideways move (read at run time: re-run the job after adding it) (TE2-006); and the engine repo's latest completed `ci.yml` run on the pinned commit has a green `Host tests` job, the unsanitized Release run where the numeric goldens compare (`gh api`, read-only; TE2-102) | No — always runs | seconds |
| `legal-pages` | `scripts/render_legal_pages.py --check`: the published pages still match `docs/legal/`; `scripts/check_doc_paths.py`: every path a doc names exists; `scripts/check_framework_coverage.py`: every readiness-framework item (`docs/ops/READINESS_FRAMEWORK_2026-10.md`) has a row in the gate's cross-check, every open row an owner that exists in TECH_DEBT or FUTURE_IMPROVEMENTS, and every status count written in that section matches its table (TE6-012); `scripts/check_kotlin_file_size.py`: no main Kotlin file over ADR-015's 500-line budget, fused hot loops not counted, the exceptions and their ceilings in `scripts/kotlin_file_size_allowlist.txt` (TD-292); `scripts/check_activity_scope_work.py`: no network call or WorkManager enqueue launched on a screen's `lifecycleScope` (ADR-016 rule 1), the triaged exceptions in `scripts/activity_scope_allowlist.txt` (TD-291); `scripts/check_swallowed_cancellation.py`: no broad catch or `runCatching` around a suspend call in `app/src/main` that swallows `CancellationException` (ADR-018), the triaged exceptions in `scripts/swallowed_cancellation_allowlist.txt` (TD-171); then the stdlib `unittest` suites in `scripts/tests` that test these source checks | No — always runs | seconds |
| `console-pages` | `scripts/check_console.py`: the consoles' wiring, CSP, deploy placeholders and gateway paths — their only gate, since they have no compiler — plus `node --test`: the DOM-free `console/util.js`, `auth.js` (sign-in, second factor, step-up, `api`, revoke gate), `router.js`, and the operator, account and institution pages, against a fake Firebase SDK loaded through a `module.register` hook and a DOM parsed from the real pages (`firebase-hosting/tests/harness.mjs`), with line coverage of the console's JS gated at 96 % by `scripts/console_coverage.mjs` (merged by file from the raw V8 coverage; every `.js` under `public/` but `vendor/`). Its check 13 fails when a code a console route names in `backend/app/routers/` has no words on the page that calls it (readiness AE2-006). Then `scripts/qa/qa.py check --base HEAD^1` over the QC catalogue in `qa/` ([ADR-034](../adr/ADR-034-qc-framework.md)): scenario ids unique, every issued id still present (and every id present at `HEAD^1`, so the checkout fetches depth 2), every account, device, build, lane and human gate a table names exists, no lane uses a device marked `use = false`, and every automation reference not marked `planned:` points at a real test file; then the tool's own `unittest` suite in `scripts/qa/tests` ([qa/README.md](../../qa/README.md)). | No — always runs | seconds |
| `console-a11y` | The console pages in Chromium (`firebase-hosting/tests/a11y`, `npm run test:a11y`: `@playwright/test` and `@axe-core/playwright`, pinned in `firebase-hosting/package-lock.json`) against a stub API and the fake Firebase SDK, no network: zero serious or critical axe violations (WCAG 2.1 A/AA) and no sideways page scroll at 360, 768 and 1280 px on the console pages, the hosted pages (landing pages, 404, privacy and terms) and the console's dialog, list and error states, a confirmation card named, Escape closing it and focus returned: 54 checks, 2 of them `test.fixme` for AE2-103 (the privacy page at 360 px, held for counsel) (readiness AE2-001, AE2-111). The Playwright report uploads as `console-a11y-report` on a failure | No — always runs | not timed yet (its first CI run is the readiness PR) |
| `console-lighthouse` | Lighthouse 13.5.0 (pinned in `firebase-hosting/package-lock.json`, `npm run lighthouse`: `firebase-hosting/tests/lighthouse/run.mjs`) on each console page and the hosted pages around them, served and stubbed as `console-a11y` serves them (no network), in Playwright's Chromium at phone and desktop size: the Accessibility and Best Practices scores are each at least 95 (readiness AE2-110). Node 22.19 or later. The JSON reports upload as `console-lighthouse-report` on every run | No — always runs | ~4 min |
| `changes` | Resolves path filters + PR/main/Dependabot mode into tier flags | — | seconds |
| `tier1-static` | spotless and detekt, in about two minutes; the heavy tiers wait for this and not for `tier1-app-fast`'s ~8 min of compile, unit tests and lint, which run beside them (and still fail `ci-ok`) | `app` (PR); always on `main` push | ~2 min |
| `tier1-app-fast` | lint, JVM unit tests (one worker: two made the TD-312 exit happen every time on the material_testing fork; `-PunitTestForks` sets it, and each worker writes its own `gc-<pid>.log`), `compileReleaseKotlin`, the androidTest and `:benchmark` sources compile, Kover coverage log + `koverVerify` floor. Failing tests are printed by `scripts/ci_test_report.py`, and a test JVM that exits with no failing test by `scripts/ci_test_crash_report.py` (its crash file, heap dump, GC log, last classes and output, TD-312); JUnit XML, the Kover XML/HTML and `app/build/test-crash/` upload as `app-unit-tests-and-coverage` | `app` (PR); always on `main` push | ~5–8 / ~10 min |
| `tier3-emulator-e2e` | x86_64 emulator: the whole androidTest suite except `com.sempermechanics.semper.benchmark` (those need the `benchmark` job) — `EnginePipelineSmokeTest` through the JNI, `AnalysisWizardSmokeTest`, the device tests, and `FirebaseAuthIntegrationTest` signing the CI test account in to the app's Firebase project (secrets `FIREBASE_TEST_EMAIL` / `FIREBASE_TEST_PASSWORD`; it skips without them). Its report step runs `scripts/ci_test_report.py --require-tests`, so a run that leaves no test case (an APK that failed to install while Gradle still ended `BUILD SUCCESSFUL`, TD-349) fails the job (since 2026-11-01) | `emulator`: engine / JNI / native-CI actions, labels, weekly | ~20–40 / ~60–90 min |
| `tier4-backend` | ruff, shell-script parse, shellcheck of the two GCP ops scripts, pip-audit, hashed-lock verification, pytest at `--cov-fail-under=93`, then the Firestore emulator suite appended to the same coverage and the total gated at 93. pytest also holds the console gate's own tests (`test_check_console.py`), the index check (`test_firestore_query_indexes.py`) and the wire contracts (`test_wire_contracts.py`, over `contracts/`), the release APK gate's parsers (`test_script_check_release_apk.py`), the framework coverage check (`test_script_check_framework_coverage.py`), and `check_gcp_posture.sh` / `apply-monitoring.sh` run against a stub `gcloud` and `curl` (`test_script_ops_gcp.py`; never a real project); the OpenAPI breaking-change check (`test_openapi_breaking.py`: the committed snapshot against `origin/main`'s, which the job fetches at depth 1 first, so a removed path, method or response code, or a removed, newly required or narrowed request field fails unless its line is in `backend/contracts/app-openapi.breaking.txt`; it skips when the ref is absent, TE3-105); and the Schemathesis run (`test_schemathesis.py`, schemathesis 4.31.1 from `requirements-test.txt`: the 68 operations of the app's own schema, derandomized so every run sends the same examples, ~25–50 s, TD-319's fuzzing) | `backend` (PR); always on `main` push | ~5–10 min |
| `firestore-rules` | `firestore-rules-tests/`: `firestore.rules` on the Firestore emulator (`firebase emulators:exec`, project `demo-rules`) with `@firebase/rules-unit-testing` under `node --test`. For each collection and subcollection `backend/app` uses (`collections.mjs`: `users`, `auth_links`, `devices`, `challenges`, `sessions`, `files`, `licenses`, `licenses/{id}/seats`, `licenseInvites`, `deleted_licenses`, `deleted_licenses/{id}/deleted_seats`, `audit_logs`), signed out, as the owner, as another user and with admin-looking claims, every get, list, owner query, collectionGroup query and write is `assertFails`. `collections.test.mjs` greps `backend/app` for `.collection(…)` / `.collection_group(…)` names and fails when one is missing from the list, so a new collection cannot ship untested. Pinned `firebase-tools`, `firebase` and `@firebase/rules-unit-testing` from `package-lock.json` (`npm ci`, npm cache); the emulator's rule coverage report uploads as `firestore-rules-coverage` | `rules` (PR); always on `main` push | ~1–2 min |
| `tier5-signed-release` | R8 + signed `assembleRelease` arm64, `.so` presence, signature verify, `scripts/check_release_apk.py` (the APK is not debuggable or testOnly, carries no debug source-set component and no `DebugAppCheckProviderFactory` (by name in the dex or renamed in R8's mapping), permits no cleartext, has `app/build.gradle.kts`'s `minSdk` and `targetSdk` (at least 36, Play's floor), ships 16 KB-aligned `lib/<abi>/*.so` files (every LOAD segment, and a stored `.so`'s zip offset), keeps the consent defaults (Crashlytics, Analytics and ad-ID collection off in `<application>` meta-data, no `AD_ID` or `ACCESS_ADSERVICES_*` permission), and R8's mapping renames app classes (obfuscation, not an identity map); it warns when no certificate pin is compiled in, which is always the case here, and when no entitlement key is, which is the case until TD-215; the Release workflow makes the latter a failure once `SEMPER_ENTITLEMENT_PUBKEYS` is set), R8 mapping artifact (kept 3 days: a public repository's artifacts are readable by any signed-in user) | `release_build`: engine / JNI / native-CI actions / packaging, labels, weekly | ~15–40 / up to ~90 min |
| `engine-host-sanitized` | The engine's host suite (`engine/tests`, `dic_tests`: math, DICe comparisons, full-field contracts) built with system `libopencv-dev`, Release, `-DDIC_SANITIZER=address,undefined`, at the commit this app pins: the engine CI's asan-ubsan configuration, rerun here (TE2-004). No Gradle, no NDK. TSan and the C SDK smoke stay in the engine repo; the JNI adapter is not built here | `engine_host`: whenever `emulator` is set, and on a `ci_workflow` change | ~10–30 min |
| `tier-benchmark` | Builds the R8-minified `benchmark` app and the Macrobenchmark APK (x86_64, ccache) before the emulator boots. One API 34 emulator: first `scripts/launch_smoke.py` cold-starts the minified app and fails on a crash, a process gone within 15 s, or an R8 symptom in an error line of its logcat (`launch-smoke` artifact), the only place CI launches an R8 build; then Macrobenchmark cold/warm startup, a dry run on a PR (one pass each, `dryRunMode`) and the full iterations with the `benchmark` label or a dispatch (`:benchmark`, `suppressErrors=EMULATOR,LOW-BATTERY,UNLOCKED`), after uninstalling the smoke's install (Gradle packages and signs the app again, and its install over the smoke's failed while the task still passed, TD-349); the step fails unless `scripts/ci_test_report.py --require-tests` finds a test case and no failing one in `benchmark/build/outputs/androidTest-results`; then, without a PR (a dispatch), `:app`'s `HotPathMicroBenchmark` on the debug build, installed by Gradle and run with `adb shell am instrument` (`suppressErrors` adds `DEBUGGABLE`, `ACTIVITY-MISSING`, `NOT-AOT-COMPILED`; through Gradle the list arrives cut at its first comma, TD-86; the step fails unless `am instrument` prints `OK (`, and the results are pulled from the device). Emulator **smoke**: no numeric thresholds; both upload their `*-benchmarkData.json` (`macrobenchmark-results`, `microbenchmark-results`). On a PR the micro suite runs only as the gate: the step before the emulator builds the PR's base and the PR (debug app + androidTest APKs, base first in the one checkout), and `scripts/micro_ab.py` runs each on the emulator in A B B A order and fails if a benchmark's pooled median `timeNs` is more than `microAbMargin` (`benchmark/gates.json`) over the base's (TD-199); the `perf-accepted` label makes it warn instead, and the rounds upload as `microbenchmark-ab`. `scripts/ci_test_report.py` prints failing tests and each metric's min / median / max (`BENCH …` lines) into the log, as Tier 3 does for its failures. CI does not pass `--gates`: the real-device gates (`benchmark/gates.json`, checked only in the phone state `DeviceStateRule` records, [ADR-008](../adr/ADR-008-startup-gates-phone-state.md), [TESTING.md](../app/TESTING.md)) apply to a phone run only. The job reads the app id from `app/build.gradle.kts` (`APP_ID`) for the micro run's `am instrument` and pulls, so it runs unchanged in the Semper Classroom fork (material_testing). The A/B writes each build's own id beside its APKs (`app-id.txt`), so a PR that changes the app id (ADR-030) runs its base under the old one; until 2026-10-26 round A failed on such a PR. | `benchmark` label / `run_benchmark` dispatch / a PR matching `hot_path` (the micro suite's sources, the benchmark, `scripts/micro_ab.py`, `benchmark/gates.json`, `scripts/launch_smoke.py`) or `packaging` | ~25–35 min on a PR; ~45–75 with the full Macrobenchmarks |
| `android-deps-osv` | Known vulnerabilities in the release APK's dependency graph (readiness TE5-105). Gradle writes `app/gradle.lockfile` for `:app`'s `releaseRuntimeClasspath` through the init script in `.github/actions/android-deps-osv/` (the build locks nothing itself, so no lockfile is committed), and OSV-Scanner 2.7.0, its download checked against a pinned SHA-256, scans it. An advisory is passed only through `.github/actions/android-deps-osv/osv-scanner.toml`, with a reason and an `ignoreUntil` after which the job fails again; none is passed today (the Guava advisories found on 2026-10-28 were fixed by a constraint to 33.7.2-android) | `app` (PR); always on `main` push and weekly, which catches an advisory against an unchanged graph | not timed yet (first run is the readiness PR) |
| `semgrep` | SAST (readiness TE5-105): semgrep 1.180.0 in its image pinned by digest, the registry packs `p/python`, `p/javascript` and `p/kotlin` over `backend/app`, `firebase-hosting/public` and `app/src/main/java`, `--error`, metrics off. No rule is excluded: a false positive carries a `nosemgrep: <rule id>` comment with its reason on the line it flags. The packs are fetched at run time and are not versioned, so a new rule can fail a PR that did not touch what it flags | `sast` (PR); always on `main` push and weekly | ~1 min locally |
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

| Filter | Paths | Sets |
|--------|-------|------|
| `app` | `app/**`, `benchmark/**`, `gradle/**`, `*.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `settings.gradle.kts`, `contracts/**` | `app` (tier 1) |
| `android_test` | `app/src/androidTest/**` | `app` (tier 1 compiles it) |
| `packaging` | `app/build.gradle.kts`, `app/proguard-rules.pro`, `gradle/libs.versions.toml` | `app` + `release_build` (tier 5 runs R8) |
| `native_jni` | `engine`, `.gitmodules`, `SemperNativeLib.kt` | `emulator` + `release_build` (tiers 3 + 5) |
| `native_ci` | `.github/actions/{setup-native-ci,enable-kvm,check-arm64-so,checkout-submodules}/**` | `emulator` + `release_build` |
| `backend` | `backend/**`, `firestore.rules`, `firebase-hosting/firebase.json`, `scripts/deploy-firestore.sh`, `.github/workflows/deploy-backend.yml`, `docs/legal/**`, `scripts/*.py`, `scripts/tests/**` (the scripts' test fixtures), `scripts/check_gcp_posture.sh`, `scripts/apply-monitoring.sh`, `ops/monitoring/**`, `contracts/**`, and the client's three wire-contract files | `backend` (tier 4) |
| `rules` | `firestore.rules`, `firestore-rules-tests/**`, `backend/app/**` | `rules` (Firestore rules tests) |
| `sast` | `backend/app/**`, `firebase-hosting/public/**`, `app/src/main/java/**` | `sast` (semgrep) |
| `ci_workflow` | `.github/workflows/ci.yml`, `.github/actions/**`, `scripts/ci_test_report.py` | `app` + `backend` + `rules` + `sast`, so a workflow-only PR is not gates-only |

The `backend` filter lists every file outside `backend/` that a backend test
reads (`test_security_controls`, `test_terms_and_consent`), plus the ops
scripts, whose only lint is Tier 4's ruff. The console pages under
`firebase-hosting/` are not in it: `console-pages` checks them on every event.

Because the engine is a submodule, `native_jni` matches the **gitlink**
`engine` rather than a source tree — bumping the pinned engine commit is what
triggers it. The JNI adapter is part of the engine
(`engine/adapters/android/`; Gradle builds `engine/CMakeLists.txt`), so a JNI
change arrives as a pin bump too.

The filter step runs with `predicate-quantifier: 'some-with-excludes'`: a file
matches a filter if any of its patterns includes it and no `!` pattern excludes
it. No filter has a `!` pattern since `app/src/main/cpp/` (a dead redirect)
was deleted; the setting stays so the next one holds. Under the default
(`some`), `app`'s old `!app/src/main/cpp/**` matched every file outside
`cpp/`, so `app` was true on every PR and a docs-only PR ran Tier 1 (TD-96).

### What runs for each diff

| Diff class | T1 | T3 | T4 | Rules | T5 |
|------------|----|----|----|-------|-----|
| app Kotlin / resources | run | —† | — | — | —† |
| androidTest only | run (compiles it) | —† | — | — | —† |
| keep rules / app build script / version catalog | run | —† | — | — | run |
| engine pin (JNI adapter included) / `SemperNativeLib.kt` | — (‡) | run | — | — | run |
| `backend/app/**`, `firestore.rules` | — | — | run | run | — |
| `firestore-rules-tests/**` | — | — | — | run | — |
| other backend, Terms, ops scripts | — | — | run | — | — |
| `contracts/**` (shared wire bodies) | run | —† | run | — | —† |
| console pages only | — | — | — | — | — |
| docs only | — | — | — | — | — |
| push to `main` | run | as the diff | run | run | as the diff |
| weekly schedule / `full-ci` label / dispatch `full_ci` | run | run | run | run | run |

† Also with the `e2e`, `release`, or `full-ci` label on the PR.
‡ `SemperNativeLib.kt` is also under `app/**`, so a change to it runs Tier 1 too.

A push to `main` filters against `github.event.before` (the previous `main`
head), so a merge commit is judged by its whole PR. One with no base (a zero
`before`) runs the full matrix. Empty path outputs otherwise fail **closed** to
gates-only (`secret-scan` + `engine-pin` + `legal-pages` + `console-pages` + `console-a11y` + `console-lighthouse` + `ci-ok`), not a
full matrix.

### Dependabot cheap path

Version updates run monthly, one grouped PR per ecosystem. For pip, Gradle and
Actions only major releases (X.0.0) arrive; minor and patch bumps are ignored
(`dependabot.yml`). That rule does not filter Dependabot **security** updates, which
are a repository setting (Settings → Code security). Docker keeps digest and patch
updates for the base image's OS fixes.

When `github.head_ref` starts with `dependabot/` **and** `github.actor` is `dependabot[bot]` (since 2026-11-01). The branch name alone is the PR author's choice: any PR on a `dependabot/github_actions/…` branch used to get gates only with `CI OK` green. Any other actor, a human pushing to a Dependabot branch too, takes path mode. Gitleaks above still keys off the branch name, for the reason given there:

| Ecosystem (from branch name) | Tiers |
|------------------------------|-------|
| `dependabot/pip/…` | gates + Tier 4 |
| `dependabot/npm_and_yarn/…` | gates + Firestore rules (monthly, major-only npm entry for `/firestore-rules-tests` in `dependabot.yml`) |
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

`secret-scan`, `engine-pin`, `legal-pages`, `console-pages`, `console-a11y` and `console-lighthouse` are absent from
the tables on purpose: they carry no path filter and run on every event.

Skipped jobs count as success for `ci-ok`.

## Required check

**`CI OK`** (`ci-ok`) is the single required status check on `main`: repository
ruleset `main` (id 24720948, set 2026-10-08) requires it on the default branch and
blocks force-push and deletion, with no bypass actors. It gates on all tiers and
treats skipped jobs as passing. Every change reaches `main` through a PR whose
`CI OK` passed; a direct push is refused. That includes the Backend lock workflow:
dispatch it on the PR branch (a Dependabot branch, its purpose), not on `main`.

## Full matrix

- Weekly: `schedule`, Monday 04:17 UTC. It catches what paths cannot: a new
  emulator image, an SDK or runner change, a test that went flaky.
- PR label: `full-ci` (or `e2e` / `release` for Tier 3 + 5 without the rest)
- Or: Actions tab → **CI** → **Run workflow** (tick `full_ci`)

## Reproducing a failure locally

```bash
# Full local gate (mirrors tiers 1 + 5, no emulator)
./gradlew ciReleaseGate

# Individual tiers
./gradlew :app:testDebugUnitTest spotlessCheck :app:detekt :app:lintDebug   # tier 1
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.notPackage=com.sempermechanics.semper.benchmark  # tier 3
cd backend && pip install -r requirements-test.txt && pytest tests/ -v      # tier 4
cd firestore-rules-tests && npm ci && \
  npx firebase emulators:exec --only firestore --project demo-rules --config firebase.json "node --test"  # rules (Java 21)

# The two always-on gates
python scripts/check_engine_pin.py                                          # engine-pin
python scripts/render_legal_pages.py --check                                # legal-pages
python scripts/check_doc_paths.py                                           # legal-pages
python scripts/check_framework_coverage.py                                  # legal-pages
python scripts/check_kotlin_file_size.py                                    # legal-pages
python scripts/check_activity_scope_work.py                                 # legal-pages
python scripts/check_swallowed_cancellation.py                             # legal-pages
python -m unittest discover -s scripts/tests -p "test_*.py"               # legal-pages
python scripts/check_console.py                                             # console-pages
node --test "firebase-hosting/tests/*.test.mjs"                             # console-pages
python scripts/qa/qa.py check --base HEAD^1                                 # console-pages
python -m unittest discover -s scripts/qa/tests -p "test_*.py"            # console-pages
cd firebase-hosting && npm ci && npx playwright install chromium && npm run test:a11y  # console-a11y
cd firebase-hosting && npm ci && npx playwright install chromium && npm run lighthouse  # console-lighthouse
gitleaks detect --config .gitleaks.toml                                     # secret-scan (human)
semgrep scan --error --metrics=off --config p/python --config p/javascript --config p/kotlin \
  backend/app firebase-hosting/public app/src/main/java                     # semgrep
./gradlew --no-configuration-cache \
  --init-script .github/actions/android-deps-osv/lock-all-configurations.init.gradle \
  :app:dependencies --configuration releaseRuntimeClasspath --write-locks
osv-scanner scan source --config .github/actions/android-deps-osv/osv-scanner.toml \
  -L app/gradle.lockfile                                                    # android-deps-osv
```

The engine's own suites are not reproducible from this repo — run them in
`sempermechanics/semper-dic-engine`, or from the submodule as described in
[docs/engine/TESTING.md](../engine/TESTING.md).

## Manual release

A separate `workflow_dispatch` workflow ([release.yml](../../.github/workflows/release.yml))
builds a signed release APK and publishes it as a GitHub Release. Jobs only run
when the workflow is dispatched from **`main`**, and nothing is signed until
`verify-ci` (`scripts/check_release_ci.py`) has found every job of a full-matrix
CI run on that commit passed. See [RELEASING.md](RELEASING.md).

Release builds **require** `SEMPER_API_BASE_URL` (repo or `release` environment
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
   and pytest at the 93 % coverage floor.
2. Resolves the release train (TD-262): the `release_train` input, or, when it
   is empty, the newest `v*` tag reachable from the deployed commit (`git
   describe`; the job checks out full history, `fetch-depth: 0`), else
   `untagged`; lowercased, other characters turned into `-`, at most 63. Deploys
   from `backend/` tagging the new revision `cand-<run_id>-<run_attempt>` and
   labelling the service and the revision `release-train=<train>`.
   - **Existing service:** `no_traffic: true` — the previous revision keeps
     serving while the candidate is smoked.
   - **First create:** Cloud Run rejects `--no-traffic` on create, so the
     workflow sets `no_traffic: false` for that one deploy. The candidate is
     still smoked before you treat it as proven; prefer creating staging once,
     then always updating.
3. Smokes `GET /readyz` on the **tagged candidate URL**, authenticated with an ID
   token whose audience is the service URL — both environments run
   `--no-allow-unauthenticated` (staging too: the opposite flag binds
   `allUsers`), so an unauthenticated probe would only prove the
   gateway rejects it. The deploy SA needs `run.invoker` on each service. Then
   `GET /v1/config` on the candidate with `X-Forwarded-Authorization: smoke`
   must be the app's own JSON 401 `missing_bearer`: the routers and auth stack
   loaded (no token is verified and no audit row written; Cloud Run's own 401
   is HTML, a missing route 404).
4. Promotes the candidate to 100% traffic once the smoke passes (when
   `no_traffic` was used), with `--to-latest` after checking the latest ready
   revision is the candidate, and removes every `cand-*` tag in the same call.
   Then **checks the live service** on its own URL (`post_promote_health`): the
   same `/readyz` 200 and `/v1/config` 401 probes, three tries 10 s apart. A
   failure there runs "Rollback traffic on post-promote failure", which routes
   100 % back to the previous revision. Dispatch input `rollback_drill`
   (staging only, ignored for production) fails this check on purpose, for the
   revision-rollback drill (TD-220).
5. Records the serving revision's image digest and its `release-train` label in
   the job summary. A label that does not match is a warning carrying the
   `gcloud run services update … --update-labels` command that sets it, not a
   rollback: this step runs after the promote.
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
`TASKS_LOCATION`, `TASKS_TARGET_BASE_URL` and `TASKS_INVOKER_SA`; optional
`NOTIFY_SECURITY_FROM` (the new-device mail; unset keeps it off),
`CHECK_REVOKED_ON_STEP_UP`, `ENTITLEMENT_KID`, `ENTITLEMENT_OFFLINE_GRACE_DAYS`,
`MIN_SUPPORTED_VERSION_CODE`, `RECOMMENDED_VERSION_CODE`, `KILL_SWITCHES`
(space-separated) and `ENTITLEMENT_SIGNING_SECRET` (a Secret Manager secret name,
mounted as `ENTITLEMENT_SIGNING_KEY` only when it exists), all in
[ENVIRONMENTS.md](ENVIRONMENTS.md).

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
| npm | managed by `setup-node` in `firestore-rules`, keyed on `firestore-rules-tests/package-lock.json` | Rules-test emulator and SDK |

### Shared steps

Local composite actions under `.github/actions/` hold the steps more than
one job runs:

| Action | Used by | What |
|--------|---------|------|
| `setup-android-build` | every app job | JDK 17 + Gradle; cache written from `main` only |
| `checkout-submodules` | tier 3, tier 5, Release, benchmark, `engine-host-sanitized` | `engine`, Eigen and OpenCV one commit deep after a plain `actions/checkout`; Eigen's git directory restored from the `submodule-eigen-<pin>` cache; each fetch retried with backoff (`retry.sh`) |
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

**Eigen comes from GitHub, not gitlab.com.** The engine's `.gitmodules` points
`third_party/eigen` at `gitlab.com/libeigen/eigen`, which refused clones under load
on 2026-10-26 and failed `engine-host-sanitized` at checkout. `ci.yml` and
`release.yml` set a workflow-level `url.<…>.insteadOf` via `GIT_CONFIG_COUNT` /
`GIT_CONFIG_KEY_0` / `GIT_CONFIG_VALUE_0`. Every git command in every job, including
`actions/checkout`'s recursive submodule clones, then fetches Eigen from
`github.com/eigen-mirror/eigen`. The engine's gitlink still pins the commit (Eigen
5.0.1, `bc3b3987`), so the source is identical; a mirror without it fails the
checkout. Local clones still use gitlab.com. The engine-side fix is TD-348.

**A cached Eigen needs neither host.** The jobs that check out submodules use
[`checkout-submodules`](../../.github/actions/checkout-submodules/action.yml) after a plain
`actions/checkout` (ported from material_testing#152): Eigen's git directory (~10 MB) is
cached by its pinned commit (`submodule-eigen-<pin>`), so a run that restores it fetches
Eigen from neither gitlab.com nor the mirror, and every fetch still needed (`engine`,
OpenCV, an uncached Eigen) is retried for about four minutes. The cache comes from a run
that fetched Eigen: a PR's own earlier run, or `main`'s for every PR. The first run on a
new Eigen pin, before either, still needs the mirror. The micro A/B's base and head
checkouts retry their submodule updates with a copy of the same `retry.sh`.

**A new push cancels a PR's older run, never `main`'s.** CI's concurrency
group is per branch, and `cancel-in-progress` is true only for `pull_request`
events. On `main` a started run finishes its full matrix. GitHub keeps one
pending run per group, so several merges while one run is in flight queue
only the newest; the ones in between get no run of their own. Before this,
merges cancelled each other: on 2026-09-26 the `main` runs of #268, #270 and
#271 were all cancelled mid-matrix, so #271's app-and-console change never had a
full run of its own (TD-97, as in material_testing#67).

**Six jobs run on every single event.** `secret-scan`, `engine-pin`,
`legal-pages`, `console-pages`, `console-a11y` and `console-lighthouse` carry no path filter, so a documentation-only PR still runs
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
