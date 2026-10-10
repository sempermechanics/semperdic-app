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

Engine pipeline (`engine/docs/ARCHITECTURE.md`): AKAZE seeds → Delaunay → RGDIC → ICGN → VSG → `.dat`.

## Layout

```
app/          Android UI (Kotlin). Gradle builds ../engine/CMakeLists.txt;
              app/src/main/cpp/ holds only a redirect CMakeLists.txt
engine/       Pinned submodule: sempermechanics/semper-dic-engine (solver, tests,
              docs, and the JNI adapter in engine/adapters/android/)
backend/      FastAPI on Cloud Run — routers in backend/app/routers/, Firestore
              access in the backend/app/repo/ package (ADR-021)
firebase-hosting/  Auth continue URLs, asset links, generated legal pages
```

Bump the engine by changing the `engine` gitlink. Its host / sanitizer / DICe suites run
in the engine repo; this CI only proves the pin **links** (emulator x86_64, release arm64).

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
[ADR-008](docs/adr/ADR-008-startup-gates-phone-state.md)) list the Pixel 6 for five metrics; its startup cold and warm start and wizard cold start are owed (TD-155); the engine floor (≥ 4557 solves/s,
[PERF_BASELINE_bd44af0.md](docs/engine/PERF_BASELINE_bd44af0.md)) is a manual reference.
Keep `-O3 -ffast-math` / OpenMP / LTO on release.

## Current state (2026-10-10)

- **Deployed.** Cloud Run `semper-api` behind API Gateway `semper-gw`, and staging
  `semper-api-staging` behind `semper-gw-staging`, from `029a12c4` (2026-10-09; redeployed
  2026-10-10 from `bd1246c3` with no backend change). Live since 2026-10-09: the erasure
  check `GET /v1/me/erasure` (#400), JSON log lines with the caller's `appId`, the licence
  prefix only while licensed, one status per refusal (#409). Licensing
  ([ADR-007](docs/adr/ADR-007-licence-lifecycle.md)), device binding per app
  ([ADR-010](docs/adr/ADR-010-device-binding-per-app.md)) and sessions tagged by app
  ([ADR-014](docs/adr/ADR-014-session-app-tag.md)) are live; consoles on
  `app.sempermechanics.com` ([§20](docs/backend/CLOUD_ARCHITECTURE_GCP.md)). What went out
  when: [CHANGELOG](docs/ops/CHANGELOG.md). Project IDs keep `indic-*`
  ([ENVIRONMENTS.md](docs/ops/ENVIRONMENTS.md)).
- **Backups are restorable.** The first Firestore restore drill passed 2026-10-10 (RTO
  111 s) and runs monthly ([FIRESTORE_DATA_PROTECTION.md](docs/backend/FIRESTORE_DATA_PROTECTION.md)).
- **App id `com.sempermechanics.semper`** ([ADR-019](docs/adr/ADR-019-sempermechanics-app-id.md),
  #333/#334, 2026-10-05; Pixel 6 checked). material_testing moves to
  `com.sempermechanics.materialtesting` when it merges this code
  ([FORK_SYNC](docs/ops/FORK_SYNC.md)).
- **App release `v1.2-beta.3`** (beta, versionCode 35, from `ae05bb87`). Everything below
  is on `main` and not in a release yet:
  - the quality program (#310–#332; [QUALITY_PROGRAM_RESULTS.md](docs/ops/QUALITY_PROGRAM_RESULTS.md));
  - security and data-loss fixes (#393–#400: Drive resume by `Range`, the API client's
    host check, an interrupted account deletion finished at the next start and checked
    without re-creating the account, a wizard outcome kept for the screen);
  - the UI pass (#385–#396) and the minimal pass (#402–#419);
  - tech-debt fixes (#404–#411, #425): the licence row, licence refresh and PDF progress
    text, the ROI readout, one name per sweep frame, one session-quota rule, a failed
    backup told once.
- **material_testing shares this history** and merges this `main` (last at `3154ebc`,
  material_testing#132, 2026-10-08); shared code and backend changes land here first
  ([FORK_SYNC.md](docs/ops/FORK_SYNC.md)).
- **CI.** Tiers follow the diff, coverage floors rise with every test PR
  ([TESTING.md](docs/app/TESTING.md#coverage-floors)), and `main` requires `CI OK`.
  Dependabot opens monthly PRs for major versions only.
- **Owed.** The public release (website / Play) with the emulator and Pixel checks
  each PR's test plan lists, ADR-015's queued-work upgrade check and the manual checks
  of the material_testing ports #298–#302; a licensed-account smoke of share, PDF and Delete
  everywhere; a device check of the transfer notifications; Material Testing beside
  Semper on one phone, and its App Check; AVI import on a phone
  ([WORKFLOWS.md](docs/app/WORKFLOWS.md) §5.1a); the privacy-policy values (TD-36);
  unchecked rows in [PRODUCTION_READINESS_GATE.md](docs/ops/PRODUCTION_READINESS_GATE.md).
- **Look it up; this list rots.** `gh pr list --state open`, [CHANGELOG.md](docs/ops/CHANGELOG.md), [FUTURE_IMPROVEMENTS.md](docs/ops/FUTURE_IMPROVEMENTS.md).

## Traps

- An undeclared route 404s in production with nothing in the logs: ESPv2 is an allowlist. `test_gateway_parity.py` checks the spec — [§20.9](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- A query that needs a composite index fails `FAILED_PRECONDITION` at runtime, not deploy (`test_firestore_query_indexes.py` catches one missing from the file; declare a new query's shape there): run `scripts/deploy-firestore.sh indexes` (Git Bash, Node >= 20) and wait for the build before the backend that queries it (the staff licence list needs three) — [§5](docs/backend/CLOUD_ARCHITECTURE_GCP.md). TTL policies live in that file's `fieldOverrides`; never deploy it with `--force`, which deletes any the file omits.
- `MAX_SESSIONS_PER_USER` is deleted; a deployment still setting it silently gets `DEMO_MAX_ANALYSES` (25) — [§7](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- List env vars (`CONSOLE_ORIGINS`, `ADMIN_EMAILS`) are space-separated; the deploy action splits on commas — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- `GET /v1/sessions` lists the asking app's sessions (`X-App-Id`; an untagged session is Semper's), and `/files` answers only that app; a browser or export that needs the whole account asks `?app=all` or uses `iter_all_user_sessions` — [ADR-014](docs/adr/ADR-014-session-app-tag.md).
- Restore on a new device has an order: sign in, let one authed request bind the lock, then restore — [§20.10](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Licence terms are mirrored onto users, so editing a licence reaches nobody without `update_license`'s fan-out — [§20.6](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Demo uploads silently; only retrieval is gated. Gating `POST /v1/sessions` would loop old builds on 403 — [§20.3](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
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
- After Compute, read the run's `RunSpec` / `RunResult` (`spec`, `settings`), never the wizard's sliders or ROI vars: they stay editable and drift — [ADR-004](docs/adr/ADR-004-runspec.md).
- Since engine 0.2.3 two runs of one build give bit-identical `.dat` whatever the thread count (TD-65), so a `.dat` hash can prove "engine unchanged" again and any run-to-run difference is a defect — `EnginePipelineSmokeTest.repeatSolveIsBitIdentical`, `engine/tests/integration/test_full_field_determinism.cpp`.
- `ConvergenceGate` is batch-only; a sweep runs its whole plan, smallest subset first — [ConvergenceGate.kt](app/src/main/java/com/sempermechanics/semper/ui/analysis/run/ConvergenceGate.kt).
