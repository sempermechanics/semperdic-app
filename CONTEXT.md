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
| VSG | Strain window: least-squares plane fit over the points within (window − 1) / 2 steps. The window is entered in data points (odd, 3–31); VSG = `(window − 1) × step + 1` px is what the engine and sessions get (`VsgStudy.vsgFor`) |
| `.dat` | Binary field: 8 floats/point (`x y u v exx eyy exy znssd`), 32 bytes |
| session | One saved analysis on disk (and optionally in the cloud) |

Engine pipeline (`native/docs/ARCHITECTURE.md`): AKAZE seeds → Delaunay → RGDIC → ICGN → VSG → `.dat`.

## Layout

```
app/          Android UI (Kotlin). Gradle builds ../native/CMakeLists.txt;
              app/src/main/cpp/ holds only a redirect CMakeLists.txt
native/       Pinned submodule: sempermechanics/semper-dic-engine (solver, tests,
              docs, and the JNI adapter in native/adapters/android/)
backend/      FastAPI on Cloud Run — routers in backend/app/routers/, Firestore
              access in backend/app/repo/ behind the firestore_repo facade
firebase-hosting/  Auth continue URLs, asset links, generated legal pages
```

Bump the engine by changing the `native` gitlink. Its host / sanitizer / DICe suites run
in the engine repo; this CI only proves the pin **links** (emulator x86_64, release arm64).

## Runtime

```
Splash → Auth / Pending / Home → StaticAnalysisActivity (wizard) → ResultViewerActivity
Home → open session → ResultViewerActivity | VsgLatticeActivity
```

Access routing is `AccessRouter` + `AccessStatus`. Intent extras are `DicKeys`.
Session dirs: `SessionStore` + `SessionPaths` (`raw_deformed/`, `frame_%04d.dat`).

Backend: `backend/app/main.py` (app, middleware, lifespan), `routers/` (`/v1/*` by
prefix), `session_provision.py` (`provision_session` / `purge_session`).

Kotlin helpers are plain `object` / small classes; no Hilt/Dagger. Keep `lifecycleScope`
and Activity Result launchers on the Activity. Cloud logic under test takes a defaulted
`api: CloudApi` / `tokens: TokenSource`; tests pass `FakeCloudApi` ([ADR-002](docs/adr/ADR-002-cloudapi-seam.md)).
The wizard (`StaticAnalysisActivity`, ViewStub steps) has full `configChanges`: rotation
does not recreate it, process death does (see Traps). Home **+** opens `MediaPickerSheet` (shared with the wizard dropzones). Uploads,
restores, bundle downloads and backup deletes are WorkManager, shown by the
non-modal `TransferBannerController` strip.

## Invariants

- **Bit-exact fields.** Do not change `.dat` packing, ZNSSD threshold, or DatCodec
  oracles unless the engine contract major-bumps. GIF bytes are pinned 0-delta.
- **JNI buffer is bounded.** Allocate to the ROI grid; a point count over capacity
  is an engine failure, never a read past the buffer.
- **Do not split** VisualizationEngine loops, GifEncoder LZW, ReportBuilder fusion,
  `DicResult.decodeDatFile`, `DicUploadWorker.doWork`, `prefetchAround` /
  `ScrubFrameCache`, `PointSpatialIndex.build`.
- **Scrub cache** is byte-bounded and filled by **one** serialized worker.
- **Whole-batch** summary / spatial index start on demand, never on viewer open.
- **Batch progress** is a buffered `SharedFlow` (`DROP_OLDEST`), not a `StateFlow`.
- **Cancel sweep** abandons the sweep (check between combinations).
- **Drive unknown ≠ deleted.** Drop local metadata only when the backend confirms
  a blob is missing.
- **Storage reclaim** frees local frames of **backed-up** sessions only.
- **Analytics and crash reporting share one consent flag** (`DicSettings.diagnosticsEnabled`).
  Events stay PII-free — buckets and enums only, never images, results, session ids
  or specimen names.
- **Release** builds require HTTPS `INDIC_API_BASE_URL`. Debug emulator boots
  local-only unless `INDIC_DEV_AUTH_BYPASS=false`.
- **Legal pages** are generated: edit `docs/legal/`, run `scripts/render_legal_pages.py`,
  never hand-edit `firebase-hosting/public/{privacy,terms}/`.

## Quality gates

Baselines, `targetSdk`, Kover and the backend lock: see CLAUDE.md. `OldTargetApi` stays
disabled until the `targetSdk` bump. Settings / wizard XML stay under `TooManyViews` via
`SettingsScrollContentView` / `WizardStepSettingsContentView`. Macrobenchmark CI is smoke,
no thresholds ([TESTING.md](docs/app/TESTING.md)); the engine floor (≥ 4557 solves/s,
[PERF_BASELINE_bd44af0.md](docs/engine/PERF_BASELINE_bd44af0.md)) is a manual reference.
Keep `-O3 -ffast-math` / OpenMP / LTO on release.

## Current state (2026-09-26)

- **Deployed.** Cloud Run `semper-api` (`semper-api-00029-z72`: image of `semper-api-36237900008-1`,
  from `10809473`, with `REQUIRE_ATTESTED_UPLOADS` removed by hand; scales to zero) behind API Gateway
  `semper-gw` (config `v202609261112-75`, deployed by CI, ADR-006);
  staging `semper-api-staging` behind `semper-gw-staging` (CI since #258); project IDs keep `indic-*` ([ENVIRONMENTS.md](docs/ops/ENVIRONMENTS.md)).
  Licensing is live, consoles on `app.sempermechanics.com` ([§20](docs/backend/CLOUD_ARCHITECTURE_GCP.md));
  #240 (cost), the licence desk (backend and console), the device-change fixes (#248, #249,
  #255, #261, #264), pinned serving/rollback images (#263), the staff phone release for
  Demo accounts (#266), compat shims 1–5 retired (#267, TD-45) and the account page's kept
  load error (#271, TD-136) went out 2026-09-26 ([CHANGELOG](docs/ops/CHANGELOG.md)).
- **App release `v1.2-beta.2`** (beta, private GitHub Release, from `fab33cb`): the
  burn-down's app half, #180's strain window in data points, engine `v0.2.2`, #182 (TD-66).
- **Merged, awaiting release:** #189, four fixes ported from material_testing
  (`SubsetRecommender` reads speckle inside the ROI from textured patches only,
  `VsgPlotView` y gutter, `TouchImageView` zoom across a resize, `AviReader` µs slack);
  #197, `settingsScroll` skips the licence-only settings headers and
  `scripts/ci_test_report.py` puts failing device tests and benchmark numbers in the CI log;
  #203 (TD-81), Compute comes back after a single run fails outright, and a first frame
  that kept no points says why (the strain window with the run's VSG and step, nothing
  correlated, or an unreadable frame).
- **Measured optimisation (2026-09-25, all six passes done).** Passes 1–2 (#191, #206) take
  an app open from 12 to 3 requests on the Pixel 6 and ship with the next app build; Pass 4
  (#202) is deployed; Passes 3, 5 and 6 measured nothing worth changing
  ([perf/request-volume.md](docs/perf/request-volume.md), [CHANGELOG](docs/ops/CHANGELOG.md)).
- **Benchmarks in CI.** `HotPathMicroBenchmark` runs (#200, TD-86: debug-only permission,
  `am instrument`); the scrub seeder writes the ranges sidecar (#208, TD-87: 150-frame heap
  161 → 21 MB); #214 reuses one frame buffer, #217 (TD-88) saves the sidecar after a full
  decode. #229/#230 (TD-90): no API URL now reads as offline instead of crashing on open.
- **Wrong-information audit, app half (merged, awaiting release):** #211 (enforce a known
  licensed ceiling), #212 (PDF page cover image and name, mixed bulk-delete prompt, sweep
  export header, per-node sweep reasons, restored skip count), #218 (licence countdown, backup
  status, local quota count, restored stop reasons), #219 (PDF and share extremes), #220 (run
  counts, stale Home rows), #223 (counts, captions, report names, progress), #225 (frame names
  past a skipped frame, Home headline, partial-run dialog). No audit TECH_DEBT rows remain.
- **Bulk delete.** Backend half deployed (#226, [CHANGELOG](docs/ops/CHANGELOG.md)); app half
  merged, awaiting release (#227: one `SessionDeletes` queue, Delete everywhere, cloud link cleared).
  Pixel 6, 10 rows, Delete everywhere: 10 DELETEs, all 200, in ~17 s (was 61 in 100 s).
  **Restore (#234, merged, awaiting release):** Home says Restore, restores a multi-selection,
  shares `RestoreStart` with Settings, and announces a failed restore once (`RestoreFailureLedger`).
  Pixel 6, 3 at once: 13 requests, 0 × 429, ~17 s, so restores stay parallel. **Home cloud
  backups (#235, merged, awaiting release):** a card offers backups this phone has no row for (`CloudBackupListing`).
- **Lock taken by a refused phone (#264, deployed):** the Pixel 6 account demoted on
  2026-09-26 still needs one **New device** to get its licence back (not verified here).
- **Licence desk:** #236 (fast list, one-row refresh), #237 (one licence per person), #238
  (edit/upgrade/convert) and #239 (delete with a 30-day restore): backend, gateway, indexes,
  TTLs and console deployed 2026-09-26 ([ADR-007](docs/adr/ADR-007-licence-lifecycle.md)),
  with #251's step-up and #257's edit fixes. Production has no duplicate holders (checked 2026-09-26).
- **material_testing shares this history** (it merged `643462c`, material_testing#22):
  sync with a plain `git merge`. Lab features stay there; only general fixes come here.
  It also ships under Semper's app id (TD-133), so its lab build replaces Semper on a phone.
- **"Upload pending" with no upload coming (#254, merged, awaiting release):** a build with no
  backend saves analyses as not backed up, and each reconcile queues rows still PENDING again.
  Pixel 6, 2026-09-26: its 8 waiting analyses backed up on their own after sign-in.
- **`v1.2-beta.2` shows "1 / 1 analyses used"** after a user's first analysis, whatever
  the cap: its plural's "one" form is a hard-coded "1 / 1", so a licensed account (cap 999)
  looks capped at 1. TD-82's fix (`b9c218da`) is on `main`, so the next release carries it.
  The backend floor for a licensed cap (#211) is deployed; the app half ships with that release.
- **Owed.** A device smoke of `v1.2-beta.2` and its public release (website / Play); AVI import has
  run only on emulators ([WORKFLOWS.md](docs/app/WORKFLOWS.md) §5.1a); unchecked rows in [PRODUCTION_READINESS_GATE.md](docs/ops/PRODUCTION_READINESS_GATE.md).
- **Look it up; this list rots.** `gh pr list --state open`, [CHANGELOG.md](docs/ops/CHANGELOG.md), [FUTURE_IMPROVEMENTS.md](docs/ops/FUTURE_IMPROVEMENTS.md).

## Traps

- An undeclared route 404s in production with nothing in the logs: ESPv2 is an allowlist. `test_gateway_parity.py` checks the spec — [§20.9](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- A query that needs a composite index fails `FAILED_PRECONDITION` at runtime, not deploy: run `scripts/deploy-firestore.sh indexes` (Git Bash, Node >= 20) and wait for the build before the backend that queries it (the staff licence list needs three) — [§5](docs/backend/CLOUD_ARCHITECTURE_GCP.md). TTL policies live in that file's `fieldOverrides`; never deploy it with `--force`, which deletes any the file omits.
- `MAX_SESSIONS_PER_USER` is deleted; a deployment still setting it silently gets `DEMO_MAX_ANALYSES` (25) — [§7](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- List env vars (`CONSOLE_ORIGINS`, `ADMIN_EMAILS`) are space-separated; the deploy action splits on commas — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Restore on a new device has an order: sign in, let one authed request bind the lock, then restore — [§20.10](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Licence terms are mirrored onto users, so editing a licence reaches nobody without `update_license`'s fan-out — [§20.6](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Demo uploads silently; only retrieval is gated. Gating `POST /v1/sessions` would loop old builds on 403 — [§20.3](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Console CSP is `script-src 'self'` with no `'unsafe-inline'`: inline scripts never run — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- `check_console.py` requires `__API_BASE_URL__` / `__API_ORIGIN__` to stay placeholders; deploy through `scripts/deploy-console.sh` — [§20.8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- `SCHEMA_VERSION` is 2 and every `campus` / `plan` skew fallback is temporary; retire in the stated order — [§20.5](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- Backup stamps PENDING before `CloudSync.enqueueUpload`; reversing it lets a late PENDING overwrite SYNCED — [§8](docs/backend/CLOUD_ARCHITECTURE_GCP.md).
- A 429 that consumes the nonce makes the app's retry a 401 replay: keep a signed route's bucket in `dependencies=[deps.rate_limited(...)]`, never in the handler; unsigned routes call `rate_limit.enforce` — `test_rate_limit_before_nonce.py`.
- Activities are `@MainThread` at class level, so a private helper that runs on `Dispatchers.IO` needs `@WorkerThread` (or `@AnyThread`) or lint fails — TD-24 in [TECH_DEBT.md](docs/ops/TECH_DEBT.md).
- `SessionStore`'s parser uses `ignoreUnknownKeys` so old `index.json` fields load; keep it — [SessionStoreLegacyFloorTest](app/src/test/java/com/indicvision/semper/data/SessionStoreLegacyFloorTest.kt).
- `SubsetRecommender` runs on the paper's `NOISE_VARIANCE`; no import supplies a measured floor — [SubsetRecommender.kt](app/src/main/java/com/indicvision/semper/ui/analysis/SubsetRecommender.kt).
- Viewer screens read `ViewerArgs.from(intent, …)`, never `intent.get…Extra(DicKeys…)`; a new viewer field goes in `ViewerArgs`, its default and its `SessionRecord` mapping — [ADR-003](docs/adr/ADR-003-viewerargs-read-side.md).
- A new wizard input must survive a kill: scalars go in `WizardState`'s Bundle, bytes and lists in `WizardDraft`; and `cacheDir/temp_deformed` is only safe from the janitor while the draft is live — [ADR-005](docs/adr/ADR-005-wizard-process-death.md).
- After Compute, read the run's `RunSpec` / `RunResult` (`spec`, `settings`), never the wizard's sliders or ROI vars: they stay editable and drift — [ADR-004](docs/adr/ADR-004-runspec.md).
- Two runs of one build on the emulator do not give bit-identical `.dat` (TD-65), so a hash match cannot prove "engine unchanged"; digest the JNI inputs instead — [ADR-004 As built](docs/adr/ADR-004-runspec.md#as-built-2026-09-23).
- `ConvergenceGate` is batch-only; a sweep runs its whole plan, smallest subset first — [ConvergenceGate.kt](app/src/main/java/com/indicvision/semper/ui/analysis/ConvergenceGate.kt).
