# Semper — agent context

Read this before changing code. Commands: [CONTRIBUTING.md](CONTRIBUTING.md); screen maps:
[ARCHITECTURE.md](docs/app/ARCHITECTURE.md); DIC primer: [docs/README.md](docs/README.md).

## Product

Semper is an Android app that measures **how a surface deforms** from photographs.
A specimen is painted with random speckle; one **reference** image and one or more
**deformed** frames go through an on-device C++ engine. Output is a full-field
**displacement** (U, V, ~1/100 px) and **strain** (Exx, Eyy, Exy) as interactive
heatmaps, PDF, CSV, and PNG.

Analysis is **offline**. Cloud (Firebase Auth → Cloud Run → Firestore → Drive) is
optional identity, metadata, and blob sync. Bytes never transit Cloud Run; the
phone PUTs to a Drive resumable URI. No JSON service-account keys.

## Domain terms

Use these words. Do not invent synonyms.

| Term | Meaning |
|------|---------|
| subset | Odd-width pixel window tracked around its center (default ~41 px) |
| step | Grid spacing between tracked points, px |
| ZNSSD | Match score; 0 = perfect, ≤ 0.15 accepted, < 0 failed-point sentinel |
| ICGN | Iterative Gauss-Newton sub-pixel solver |
| VSG | Strain window: least-squares plane fit over the points within (window − 1) / 2 steps. The window is entered in data points (odd, 3–31); VSG = `(window − 1) × step + 1` px is what the engine and sessions get (`SweepStudy.vsgFor`) |
| `.dat` | Binary field: 8 floats/point (`x y u v exx eyy exy znssd`), 32 bytes |
| session | One saved analysis on disk (and optionally in the cloud) |

Engine pipeline (`engine/docs/ARCHITECTURE.md`): anchor-lattice seeds (AKAZE + RANSAC homography + ICGN, engine 0.6.0) → Delaunay → RGDIC → ICGN → VSG → `.dat`.

## Layout

```
app/          Android UI (Kotlin). Gradle builds ../engine/CMakeLists.txt
              (app/build.gradle.kts externalNativeBuild); no app/src/main/cpp/
engine/       Pinned submodule: sempermechanics/semper-dic-engine (solver, tests,
              docs, and the JNI adapter in engine/adapters/android/)
backend/      FastAPI on Cloud Run — routers in backend/app/routers/, Firestore
              access in the backend/app/repo/ package (ADR-021)
firebase-hosting/  Auth continue URLs, asset links, generated legal pages
```

Bump the engine by changing the `engine` gitlink, to a `v*` tag and never backwards
(`engine-pin`). Its host / sanitizer / DICe suites run in the engine repo; this CI proves
the pin **links** (emulator x86_64, release arm64) and reruns the host suite under
ASan+UBSan at the pin (`engine-host-sanitized`).

## Runtime

```
Splash → Auth / Pending / Home → StaticAnalysisActivity (wizard) → ResultViewerActivity
Home → open session → ResultViewerActivity | VsgLatticeActivity
```

Access routing is `AccessRouter` + `AccessStatus`. Intent extras are `IntentKeys`.
Session dirs: `SessionStore` + `SessionPaths` (`raw_deformed/`, `frame_%04d.dat`).

Backend: `backend/app/main.py` (app, middleware, lifespan), `routers/` (`/v1/*` by
prefix), `session_provision.py` (`provision_session` / `purge_session`).

Kotlin helpers are plain `object` / small classes; no Hilt/Dagger. Activity Result
launchers are registered before the Activity starts: as a property, or by a part built
there (`WizardMediaPickers`, `RoiStudioLauncher`, `ViewerShareController`). Work that must
outlive the screen follows [ADR-016](docs/adr/ADR-016-work-that-outlives-the-activity.md);
views go through ViewBinding and the `ui/common` kit ([ADR-017](docs/adr/ADR-017-viewbinding-and-ui-kit.md));
failures are typed outcomes ([ADR-018](docs/adr/ADR-018-error-convention.md)). Cloud logic under test takes a defaulted
`api: CloudApi` / `tokens: TokenSource`; tests pass `FakeCloudApi` ([ADR-002](docs/adr/ADR-002-cloudapi-seam.md)).
The wizard (`StaticAnalysisActivity`, ViewStub steps) has full `configChanges`: rotation
does not recreate it, process death does (see Traps). Home **+** opens `MediaPickerSheet` (shared with the wizard dropzones). Uploads,
restores, bundle downloads and backup deletes are WorkManager, shown by the
non-modal `TransferBannerController` strip.

## Invariants

- **Bit-exact fields.** Do not change `.dat` packing, ZNSSD threshold, or DatCodec
  oracles unless the engine contract major-bumps. GIF bytes are pinned 0-delta.
  The golden files that hold both are `app/src/test/resources/oracles/`
  (`results/DatFieldOracleTest`, `results/SummaryGifOracleTest`); regenerating
  one (`-PupdateGoldens`) is a reviewed decision, never a fix for a red build.
- **JNI buffer is bounded.** Allocate to the ROI grid; a point count over capacity
  is an engine failure, never a read past the buffer.
- **Hot loops stay fused.** VisualizationEngine pixel loops, GifEncoder LZW, the
  ReportBuilder fusion pass, `DicResult.decodeDatFile`, `prefetchAround` and
  `PointSpatialIndex.build` each keep their loop body whole in one function. The
  files around them may be split; a split proves itself with the `.dat` / GIF
  oracles and no regression in `HotPathMicroBenchmark` / `ViewerScrubBenchmark`.
- **Upload staging is repeatable.** `DicUploadWorker.doWork` may be broken into
  named steps, but the staged bytes must be identical across attempts: Drive's
  resumable URI and the reconcile check the declared size and sha256.
- **Scrub cache** is byte-bounded and filled by **one** serialized worker.
- **Whole-batch** summary / spatial index start on demand, never on viewer open.
- **Batch progress** is a buffered `SharedFlow` (`DROP_OLDEST`), not a `StateFlow`.
- **Cancel sweep** abandons the sweep (check between combinations).
- **Drive unknown ≠ deleted.** Drop local metadata only when the backend confirms
  a blob is missing.
- **Storage reclaim** frees local frames of **backed-up** sessions only.
- **Analytics and crash reporting share one consent flag** (`AppSettings.diagnosticsEnabled`).
  Events stay PII-free — buckets and enums only, never images, results, session ids
  or specimen names.
- **Release** builds require HTTPS `SEMPER_API_BASE_URL`. Debug emulator boots
  local-only unless `SEMPER_DEV_AUTH_BYPASS=false`.
- **Legal pages** are generated: edit `docs/legal/`, run `scripts/render_legal_pages.py`,
  never hand-edit `firebase-hosting/public/{privacy,terms}/`.

## Quality gates

Baselines, `targetSdk`, Kover and the backend lock: see CLAUDE.md. `OldTargetApi` stays
disabled until the `targetSdk` bump. Settings / wizard XML stay under `TooManyViews` via
`SettingsScrollContentView` / `WizardStepSettingsContentView`. Macrobenchmark CI is smoke,
no thresholds; the hot-path microbenchmarks gate a PR A/B against its base on the CI
emulator (`scripts/micro_ab.py`, [TESTING.md](docs/app/TESTING.md)); the phone-run gates (`benchmark/gates.json`,
[ADR-008](docs/adr/ADR-008-startup-gates-phone-state.md)) gate the benchmark phone, a Galaxy S22+ (`g0q`), on eight metrics from 2026-10-27 (the Pixel 6 keeps five); the engine floor (≥ 4557 solves/s,
[PERF_BASELINE_bd44af0.md](docs/engine/PERF_BASELINE_bd44af0.md)) is a manual reference.
Keep `-O3 -ffast-math` / OpenMP / LTO on release.

## Current state (2026-11-02)

- **Review fixes merged, not deployed** (2026-11-02): #521–#534, #536. Owed: backend deploy (#521
  #523–#525 #527–#529 #532), Hosting (#527; #534 changed `/privacy`: `LEGAL_PAGES_FROM=b940cc29`, below),
  app release. The recycle bin ([ADR-035](docs/adr/ADR-035-recycle-bin.md), #529 #533 #534) must not reach
  production before counsel's Privacy text (TD-277): a prod backend deploy from `main` ships it; staging is
  fine; its TTL and Scheduler are owner steps in [DEPLOY_CHECKLIST_2026-10.md](docs/ops/DEPLOY_CHECKLIST_2026-10.md).
- **Console list tools open** (#540): filters, sorting and slimmer rows on the desk's licences and test
  accounts, the rosters, institution phones and saved analyses; Hosting deploy after merge.
- **Deployed.** Backend from `main` at `12a89d31` (2026-10-30; everything through #515, ADR-033
  included): staging `semper-api-staging-41004261956-1`, production `semper-api-41007741365-1`;
  gateways `v202610300446-120` (staging), `v202610300526-121` (production). Both run
  `IMPLICIT_ACTIVATION=legacy` without `SELF_DEVICE_CHANGE_COOLDOWN_DAYS` (staging's
  `-00055-j6q` is that removal). Hosting (consoles, account page) from the same commit.
  The split (`split_licences_per_app.py --apply`) ran: 3 licences split, 0 conflicts
  ([CHANGELOG](docs/ops/CHANGELOG.md)). Project IDs keep `indic-*` ([ENVIRONMENTS.md](docs/ops/ENVIRONMENTS.md)).
- **Legal pages held:** #474's text (Privacy §2.3, Terms §6.1, retention values) waits on counsel
  (TD-277). Deploy Hosting from a worktree with `/privacy` and `/terms` taken from `b940cc29`
  ([checklist](docs/ops/DEPLOY_CHECKLIST_2026-10.md) §4) and compare the live pages after; a
  plain-`main` deploy published them on 2026-10-26 and again on 2026-10-30, each time redeployed
  the same day; live matches `b940cc29` since.
- **Readiness runs** 2026-10-24 (#489) and 2026-10-28 (#503, verdict **No-go** on six P1s that need
  the Admin or counsel, [report](docs/ops/readiness/20261028/REPORT.md) §4) are merged and now deployed.
- **Licensing rework** ([ADR-033](docs/adr/ADR-033-licence-per-app-explicit-activation.md)): merged
  #506–#515 and deployed (above); `retire_stale_devices.py` found nothing to retire. Left: the
  Semper release with Activate; the Semper Classroom release (its sync merged); then 30 days of
  `event=activation`, `minSupportedVersionCode`, `IMPLICIT_ACTIVATION=off` and the cleanup (TD-363).
  Nothing of it has been tried on an emulator or against staging.
- **Admin actions** are one table, [gate § Admin actions](docs/ops/PRODUCTION_READINESS_GATE.md#admin-actions-external):
  monitoring `--apply` (no notification channel yet), the posture FAILs (17 on 2026-10-24, the same 17 on 2026-10-28),
  TD-215, the checklist's §6; the readiness report's §4 adds that run's one-off actions.
- **App** `com.sempermechanics.semper` ([ADR-019](docs/adr/ADR-019-sempermechanics-app-id.md)).
  Latest release `v1.2-beta.4` (versionCode 1036, `33dce87d`, 2026-10-17, engine 0.4.2). On
  `main` since: **minSdk 30**, version gate, EMM config, AAB ([ADR-024](docs/adr/ADR-024-account-anchored-entitlements.md));
  #453, #457–#462, #464, #469 (TD-298); engine `f072da0` v0.6.1 (#470); tamper-evident records
  ([ADR-028](docs/adr/ADR-028-tamper-evident-records.md), #471, review findings in its comments);
  AD_ID removed (#475, TD-232).
- **Release blockers:** `SEMPER_API_CERT_PINS` unset (every release fails `--require-pins` until it is; readiness A7); TD-229 (Play account; TD-226, TD-230, TD-231 follow it), TD-232 (two
  owner decisions, then submit), TD-219's device checks, counsel on the Terms (TD-277).
- **Semper Classroom** (`semper_classroom`, formerly `material_testing`; [ADR-030](docs/adr/ADR-030-semper-classroom-app-identity.md),
  old names aliased until TD-330) merges this `main` (last `12a89d31`, ADR-033, semper_classroom#159,
  2026-10-31; [FORK_SYNC.md](docs/ops/FORK_SYNC.md)). Then its release, before `IMPLICIT_ACTIVATION=off`,
  and `rename_classroom_app.py`.
- **CI.** Tiers follow the diff, floors rise with every test PR ([TESTING.md](docs/app/TESTING.md#coverage-floors)),
  `main` requires `CI OK`, and Tier 3 signs in to live staging, so a staging backend behind
  `main` fails it. Always-on: `engine-pin`, `console-a11y`, `console-lighthouse`; `android-deps-osv`
  on app diffs, `semgrep`; `engine-host-sanitized` follows Tier 3. The OpenAPI snapshot is
  rewritten with `UPDATE_OPENAPI_SNAPSHOT=1` ([ADR-029](docs/adr/ADR-029-api-contract-snapshot.md)).
- **Owed.** A device smoke of the next release ([RELEASING.md](docs/ops/RELEASING.md) step 5);
  TD-212 at the 0.6.1 pin (on the Galaxy S22+); ADR-015's queued-work upgrade check;
  [WORKFLOWS.md](docs/app/WORKFLOWS.md) §5.1a on a device; both apps on one phone; TD-36.
- **Look it up; this list rots.** `gh pr list --state open`, [CHANGELOG.md](docs/ops/CHANGELOG.md), [FUTURE_IMPROVEMENTS.md](docs/ops/FUTURE_IMPROVEMENTS.md).

## Traps

- An undeclared route 404s in production with nothing in the logs: ESPv2 is an allowlist. `test_gateway_parity.py` checks the spec — [§20.9](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- A query that needs a composite index fails `FAILED_PRECONDITION` at runtime, not deploy (`test_firestore_query_indexes.py` catches one missing from the file; declare a new query's shape there): run `scripts/deploy-firestore.sh indexes` (Git Bash, Node >= 20) and wait for the build before the backend that queries it (the staff licence list needs three) — [§5](docs/backend/CLOUD_ARCHITECTURE_GCP.md). TTL policies live in that file's `fieldOverrides`; never deploy it with `--force`, which deletes any the file omits.
- `MAX_SESSIONS_PER_USER` is deleted; a deployment still setting it silently gets `DEMO_MAX_ANALYSES` (25) — [§7](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- List env vars (`CONSOLE_ORIGINS`, `ADMIN_EMAILS`) are space-separated; the deploy action splits on commas — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- `GET /v1/sessions` lists the asking app's sessions (`X-App-Id`; an untagged session is Semper's), and `/files` answers only that app; a browser or export that needs the whole account asks `?app=all` or uses `iter_all_user_sessions` — [ADR-014](docs/adr/ADR-014-session-app-tag.md).
- A licence with `app` licenses only the phones activated on it (`licenses/{id}/activations`); other phones are Demo per request. This app sends that header (`AppIdHeader`), so a new phone of this build is Demo until Activate (Your phones, `ui/phones/`); builds without it auto-activate only while `IMPLICIT_ACTIVATION=legacy` — [§20.16](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Restore on a new device has an order: sign in, then Activate (a licence that names its app) or let one authed request bind the lock (one from before the split), then restore — [§20.10](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Licence terms are mirrored onto users, so editing a licence reaches nobody without `update_license`'s fan-out — [§20.6](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Demo uploads silently; only retrieval is licence-gated. Gating `POST /v1/sessions` on the licence would loop old builds on 403; a kill switch refuses it with 503 `feature_paused`, which every build retries — [§20.3](docs/backend/CLOUD_ARCHITECTURE_GCP.md), [§20.14](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Share has no backend route: the cached `/v1/config` licence is its only gate. A signed token can narrow it, never widen it, and none is issued until TD-215, so today the TLS-delivered cache alone decides, and a modified APK can share (accepted, TD-274). Anything that touches the cloud is checked by the server — [ADR-024](docs/adr/ADR-024-account-anchored-entitlements.md).
- Console CSP is `script-src 'self'` with no `'unsafe-inline'`: inline scripts never run — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- `check_console.py` requires `__API_BASE_URL__` / `__API_ORIGIN__` to stay placeholders; deploy through `scripts/deploy-console.sh` — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- `SCHEMA_VERSION` is 2 and every `campus` / `plan` skew fallback is temporary; retire in the stated order — [§20.5](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Backup stamps PENDING before `CloudSync.enqueueUpload`; reversing it lets a late PENDING overwrite SYNCED — [§8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- A 429 that consumes the nonce makes the app's retry a 401 replay: keep a signed route's bucket in `dependencies=[deps.rate_limited(...)]`, never in the handler; unsigned routes call `rate_limit.enforce` — `test_rate_limit_before_nonce.py`.
- Activities are `@MainThread` at class level, so a private helper that runs on `Dispatchers.IO` needs `@WorkerThread` (or `@AnyThread`) or lint fails (lint's `WrongThread`; closed as TD-24).
- `SessionStore`'s parser uses `ignoreUnknownKeys` so old `index.json` fields load; keep it — [SessionStoreLegacyFloorTest](app/src/test/java/com/sempermechanics/semper/data/session/SessionStoreLegacyFloorTest.kt).
- `SubsetRecommender` runs on the paper's `NOISE_VARIANCE`; no import supplies a measured floor — [SubsetRecommender.kt](app/src/main/java/com/sempermechanics/semper/ui/analysis/recommend/SubsetRecommender.kt).
- Viewer screens read `ViewerArgs.from(intent, …)`, never `intent.get…Extra(IntentKeys…)`; a new viewer field goes in `ViewerArgs`, its default and its `SessionRecord` mapping — [ADR-003](docs/adr/ADR-003-viewerargs-read-side.md).
- A new wizard input must survive a kill: scalars go in `WizardState`'s Bundle, bytes and lists in `WizardDraft`; and `cacheDir/temp_deformed` is only safe from the janitor while the draft is live — [ADR-005](docs/adr/ADR-005-wizard-process-death.md).
- After Compute, read the run's `RunSpec` (`BatchAnalysisOutcome.spec`) and the saved row its outcome carries (`.record`, opened through `ViewerArgs.of`), never the wizard's sliders or ROI vars: they stay editable and drift — [ADR-004](docs/adr/ADR-004-runspec.md).
- Since engine 0.2.3 two runs of one build on one device give bit-identical `.dat` (TD-65), so a `.dat` hash can prove "engine unchanged" on that device and any run-to-run difference there is a defect. Not across thread counts: the engine pins its threads to the core count and says invariance across them is no contract (`engine/tests/integration/test_full_field_contracts.cpp:437-441`); no test varies it — `EnginePipelineSmokeTest.repeatSolveIsBitIdentical`, `engine/tests/integration/test_full_field_determinism.cpp`.
- `ConvergenceGate` is batch-only; a sweep runs its whole plan, smallest subset first — [ConvergenceGate.kt](app/src/main/java/com/sempermechanics/semper/ui/analysis/run/ConvergenceGate.kt).
