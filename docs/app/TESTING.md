# App test suite — workflow chunks

Tests are organized into chunks that mirror the user journey through the app.
Each chunk owns one layer; no duplicate assertions across chunks. Each workflow
in [../WORKFLOWS.md](../WORKFLOWS.md) names the tests that pin it, so the two
files answer opposite questions: "what covers this flow?" there, "what does this
chunk own?" here.

Manual and device QC (emulator lanes, the real phone, consoles, human gates)
is not here: it is a catalogue of scenarios in [`qa/`](../../qa/README.md), run
in lanes and reported by `scripts/qa/qa.py`
([ADR-034](../adr/ADR-034-qc-framework.md)). When a scenario there is covered
by a test in this suite, its `automation` field names the test.

## Chunk map

| Chunk | User journey | JVM tests (`app/src/test`) | Instrumented (`androidTest`) |
|-------|--------------|---------------------------|------------------------------|
| **auth** | Splash → Auth / Pending / Home, re-auth, password rules | `auth/AccessRouterTest`, `ReauthFlowTest`, `PasswordPolicyTest` | `auth/FirebaseAuthIntegrationTest` |
| **analysis** | Import → ROI → batch / parameter sweep; speckle and noise-floor suitability; the wizard across a process death | `analysis/AnalysisViewModelTest`, `WizardStateTest`, `SweepStudyTest`, `SubsetRecommenderTest`, `ConvergenceGateTest`, `DicGoodPracticeTest`, `SpeckleScaleTest`, `NoiseFloorProbeTest`, `NoiseFloorStatsTest`, `NoiseCorrelationTest`, `FrameOrderHelperTest`, `BitmapDecoderTest`, `ExifOrientedSizeTest`, `LossyFormatCheckTest`, `RawRgbaTest`, `SweepSetupControllerTest`, `BatchAnalysisLimitTest`, `ui/common/media/MediaPickerSheetTest`, `ui/analysis/roi/StudioOverlayViewTest`, `RoiDrawActivityTest`, `SweepLatticeViewTest`, `RoiViewportTest`, `SweepPlotViewTest` | `ui/analysis/wizard/WizardDraftRestoreTest`; `e2e/RoiEditorGestureTest` (ROI editor under real touches: pinch keeps the ROI's pixels, two-finger pan stops at the edge, double-tap 2× / fit, a stray tap keeps the ROI, a zoomed draw saves the pixels under the finger) |
| **session** | Session store durability, disk footprint, failure provenance; the Home list and its multi-select | `session/SessionStoreAtomicTest`, `LocalStorageFootprintTest`, `FailureProvenanceTest`, `ui/home/SessionListAdapterTest`, `SessionSelectionControllerTest`, `CloudBackupsCardTest` | — |
| **results** | `.dat` decode, CSV, heatmap, PDF, GIF | `results/DicResultCsvTest`, `AnalysisCsvSectionsTest`, `DicResultDecodeTest`, `VisualizationEngineTest`, `ReportBuilderTest`, `ReportBuilderMeanStdParityTest`, `GifEncoderTest`, `SummaryAnimationTest`, `PdfReportGeneratorTest` | `report/PdfReportDeviceTest` |
| **viewer** | Result viewer controls, frame cache bounds, the Intent contract its one builder (`ViewerArgs.of` a saved row) writes and all four readers parse | `viewer/FrameNumberEntryTest`, `ScrubFrameCacheTest`, `ViewerFieldPillsTest`, `ShareCenterTest`, `ui/viewer/ViewerArgsTest`, `TouchImageViewTest`, `SettingsUsedSheetTest`, `analysis/RunSpecTest` | `ui/viewer/ViewerEntryParityDeviceTest` |
| **cloud** | Upload, API, restore, quota, account deletion | `cloud/ApiDtosContractTest`, `ApiErrorMappingTest`, `UploadResumableTest`, `DicUploadWorkerOutcomesTest`, `RestoreAndImportSafetyTest`, `RestoreStartTest`, `CloudBackupListingTest`, `QuotaGateTest`, `AccountDeletionTest`, `SessionEverythingExporterTest`, `data/cloud/SessionUploadBundlerTest` | `data/cloud/SessionUploadBundlerDeviceTest` |
| **settings** | Settings sections, contacting support, account deletion | `settings/AnalysisEntriesTest`, `HelpSupportSectionTest`, `DeleteAccountReauthTest`, `AccountDeletionDeathTest`, `AppSettingsMigrateTest` | — |
| **analytics** | Consent-gated Firebase Analytics events | `diagnostics/SemperAnalyticsTest` | — |
| **upgrade** | Prefs / session index forward compatibility | `upgrade/UpgradeFrom1036Test`: v1.2-beta.4's index and preference files (fixtures derived from its source at 33dce87d by `src/test/resources/upgrade/1036/make_fixture.py`, not captured from a device) | `upgrade/PrefsUpgradeSmokeTest` |
| **e2e** | Wizard chrome smoke (Next + toolbar; Back / Compute / instruction GONE on step 1) | — | `AnalysisWizardSmokeTest` |
| **pipeline** | JNI + native runtime | — | `pipeline/EnginePipelineSmokeTest` |
| **benchmark** | Startup / screen / viewer-scrub Macrobenchmarks; hot-path microbenchmarks | — | `:app` androidTest `benchmark/HotPathMicroBenchmark`, `:benchmark` module (both in CI's `tier-benchmark`: label `benchmark` / workflow_dispatch / a PR touching a hot path) |

## Overlap rules

- **Host C++ tests** own algorithmic displacement accuracy.
- **Android JNI smoke** (`pipeline/`) owns runtime/bridge correctness —
  `System.loadLibrary`, OpenMP threading, JNI marshalling.
- **JVM tests** own Kotlin orchestration and data contracts. Do not add JVM
  tests that re-assert displacement accuracy.

## Wire contracts (`contracts/`)

The JSON the app and the backend exchange is pinned once, in the top-level
`contracts/` directory: one realistic body per request the app sends
(`*_request.json`) and per response it reads (`*_response.json`). Two suites
read the same files:

- `cloud/ApiDtosContractTest` (app): a request fixture decodes into its DTO
  and re-encodes unchanged, so the DTO sends exactly those keys; a response
  fixture decodes, and every field its DTO reads is a key the fixture has (a
  renamed field would otherwise decode to its default). Gradle gets the
  directory through the `semper.contracts.dir` system property and tracks it
  as a test input.
- `backend/tests/test_wire_contracts.py`: each request validates against the
  pydantic model its route takes, with no key the model would drop; each
  response is compared, keys and JSON types both ways, with what the real
  routes return when driven from device registration to a finished backup,
  and a licence's phones (ADR-033: Activate, the list, Deactivate) in a
  second scenario of their own.

A backend response that grows a field fails the backend test until the fixture
has it. A field removed or renamed on either side fails one of the two suites;
change the client first, since installed builds keep reading the old name. The
`app` and `backend` CI filters both include `contracts/**`.

The backend's whole request surface is pinned too, in
`backend/contracts/app-openapi.json` ([ADR-029](../adr/ADR-029-api-contract-snapshot.md)):
`backend/tests/test_openapi_snapshot.py` fails, printing the diff, when the app's
OpenAPI document changes. After a deliberate change, rewrite it with
`UPDATE_OPENAPI_SNAPSHOT=1 python -m pytest tests/test_openapi_snapshot.py` (from
`backend/`) and review the diff in the PR. No route declares a response model, so
responses are pinned only by the fixtures above.

## Shared fixtures

Reach for these before writing a local helper; each replaced several
hand-rolled copies (#315, #330).

| Fixture | File (under `app/src/test/java/com/sempermechanics/semper/`) | Use it for |
|---|---|---|
| `sessionRecord(...)` | `fixtures/SessionRecords.kt` | A `SessionRecord` with every required field defaulted (one frame, 100 × 100 px, subset 41, step 5, the whole image as ROI). Name only the fields the test cares about |
| `packDat`, `gridFrame`, `writeGridBatch` | `fixtures/DatFixtures.kt` | `.dat` bytes in the engine's layout, a synthetic grid frame, or a whole batch of them in a folder |
| `viewerArgs`, `viewerController`, `launchViewer` | `fixtures/ViewerFixture.kt` | A `ResultViewerActivity` under Robolectric on a `writeGridBatch` batch; keep the controller to recreate the viewer |
| `idleUntil(what, timeoutMs) { done }` | `fixtures/Robo.kt` | Waiting for work that runs on a background dispatcher and posts back to main: idles the main looper until `done` holds, and names `what` when it times out. Use it instead of `Thread.sleep` or a bare `idle()` |
| `CleanAppState` | `fixtures/CleanAppState.kt` | A JUnit rule: signed out, no saved sessions, before and after each test (token store, remote config, session index and folders). Before each test it also clears `AndroidViewModelFactory`'s singleton, which keeps the first test's application, so an `AndroidViewModel(app)` is given this test's application |
| `MockWebServerRule` | OkHttp's `mockwebserver3.junit4` | Starting and closing a `MockWebServer` per test; do not start one by hand |
| `WizardTestBed`, `FakeWizardHost` | `ui/analysis/WizardTestBed.kt` | The wizard's parts one at a time: its views in a plain themed Activity, a fresh `AnalysisViewModel`, and a host that counts what each part asks of it. Pass `resumed = false` for a part that registers a result launcher |

## Swapping a dispatcher

A class whose background work a test must hold or run inline takes the
dispatcher as a constructor parameter defaulting to `Dispatchers.IO` (the
wizard parts, e.g. `ReferenceImportController`; `MediaPickerSheet.show`); a
view model takes it the same way and is built through a `viewModelFactory`
(`SaveExportViewModel.factory`). An Activity or Fragment, which the system
constructs, keeps it in a per-instance `@VisibleForTesting internal var` that
the test sets on `controller.get()` before `setup()`
(`ResultViewerActivity.frameSetDispatcher`, `SaveExportActivity.copyDispatcher`).
Never a companion or top-level `var`: it is process-global, so a test that
fails before restoring it leaks its dispatcher into every later test in the JVM.

## Running by chunk

```bash
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.auth.*"
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.session.*"
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.analysis.*"
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.results.*"
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.cloud.*"
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.settings.*"
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.viewer.*"
```

`settings/HelpSupportSectionTest` drives the real `SettingsActivity` under
Robolectric — it is the first UI-level test of that screen, and the pattern to
copy for the other sections. `viewer/FrameNumberEntryTest` does the same for
`ResultViewerActivity`, writing synthetic `.dat` frames to a temp folder and
handing their path in on the intent.

`results/GifEncoderTest` reads its own output back with `javax.imageio` rather
than a decoder of ours: the encoder is written against the GIF89a spec by hand,
so the only claim worth making is that a third-party decoder agrees.

**Golden oracles.** The `.dat` and GIF outputs are pinned byte for byte against
committed files in `app/src/test/resources/oracles/`, compared by
`fixtures/Goldens.kt`:

| Golden | Test | Pins |
|---|---|---|
| `field_small.dat` | `results/DatFieldOracleTest` | A field written through `DicFieldIo` as the batch loop writes it: the 32-byte `x y u v exx eyy exy znssd` record, little-endian, valid points only — and that `decodeDatFile` reads it back |
| `summary_u.gif`, `summary_exx.gif` | `results/SummaryGifOracleTest` | A whole `SummaryAnimation` build (decode, fit widened to the moved points, deformed heatmap render, jet palette, LZW): the bytes the viewer shows and Share sends. Regenerated 2026-10-13 when the GIF moved to the deformed configuration (TD-139, [ADR-011](../adr/ADR-011-viewer-deformed-frame.md#as-built-td-139-2026-10-13)) |

Parity tests (`VisualizationEngineTest`, `ReportBuilderTest`) compare the code
against an older copy of itself, and the round trips (`DatCodecTest`,
`GifEncoderTest`) against itself; both stay green when a change moves both
sides together. The goldens do not. A change that is *meant* to move an output
regenerates them and the diff of the binary file is the review:

```bash
./gradlew :app:testDebugUnitTest -PupdateGoldens --tests "*Oracle*"
```

The batch loop that writes the `.dat` files, `runBatchAnalysisBody`, cannot run
on the JVM (no engine library). `pipeline/EnginePipelineSmokeTest.batchLoopWritesEveryFrameAndSavesTheSession`
runs it on the emulator: three shifted frames, each `.dat` must hold its own
translation within 0.25 px, and the run must save its Home row. CI runs it in
Tier 3, which an engine bump or JNI change now triggers.

`analysis/WizardStateTest` covers the wizard's process-death restore on the
JVM, `ui/analysis/wizard/WizardDraftRestoreTest` covers it through a real Parcel on a
device, and neither can kill the process. The kill is a scripted pass: take
the wizard to step 2, press Home, run `adb shell am kill com.sempermechanics.semper`
(if `pidof` still shows the process, `adb shell run-as com.sempermechanics.semper
kill -9 <pid>`), then reopen from Recents. Step, sliders, ROI and both slots
must come back. Run it once more with `run-as … rm -rf cache/temp_deformed`
before reopening: expect an empty step 1 and the "cleared while Semper was in
the background" snackbar ([ADR-005](../adr/ADR-005-wizard-process-death.md)).

`session/LocalStorageFootprintTest` pins the rule that only cloud-backed
analyses may have their local frames freed — it is the guard against a storage
optimisation quietly deleting the one copy of someone's data.
`cloud/RestoreAndImportSafetyTest` and `DicUploadWorkerOutcomesTest` cover the
other half: a cancelled import and a terminally failed upload must both leave
the session store in a state you can come back to.

## Performance benchmarks

Two suites, answering different questions. Neither runs in the normal gate — both
need a device. CI's `tier-benchmark` job runs them on the `benchmark` label, a
`run_benchmark` dispatch, or a PR that touches a hot path (the `hot_path` filter in
`ci.yml`).

**Micro (`:app` androidTest) — "did this operation get cheaper?"**
Measures median `timeNs` **and `allocationCount`** for the hot paths (`valueRanges`,
`buildReport`, `generateHeatmap`, `generateDeformedHeatmap`, GIF encode,
`computeFieldExtrema`, `decodeDatFile`, the spatial index). `generateDeformedHeatmap_oneFrame`
renders a frame moved by u = 0.02·x, v = 0.01·y px: the other cases' synthetic frame
drifts by up to ~300 px, which would warp most of the deformed map off the canvas. `allocationCount` is the honest memory signal: the workload is
fixed, so a change in allocations is caused by the code and nothing else.

```bash
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class com.sempermechanics.semper.benchmark.HotPathMicroBenchmark \
  -e androidx.benchmark.suppressErrors EMULATOR,DEBUGGABLE,LOW-BATTERY,UNLOCKED,ACTIVITY-MISSING,NOT-AOT-COMPILED \
  com.sempermechanics.semper.test/androidx.test.runner.AndroidJUnitRunner
```

Emulators on API 34 and 37 both raise `ACTIVITY-MISSING` and `NOT-AOT-COMPILED`
besides `DEBUGGABLE`; leave one out and every case fails at once. Pass the list with
`am instrument`, not `connectedDebugAndroidTest -P …suppressErrors=…`: through Gradle
it arrives cut at its first comma, so only `EMULATOR` is suppressed (seen on Linux CI,
TD-86). CI runs this command.

Results land in logcat (`adb logcat -d -s Benchmark:I`).

**Macro (`:benchmark`) — "what does the user feel?"**
`ViewerScrubBenchmark` seeds a synthetic session via the benchmark-variant-only
`BenchmarkSeedActivity` and scrubs frames, reporting frame timing, max heap and the
`Semper.viewer.decodeDat` trace section. The seeder also writes the `field_ranges.bin`
sidecar a real batch run leaves, so the viewer's colour-scale pass reads it as it does
on a phone; without it the benchmark measured the no-sidecar fallback instead (TD-87).
`scrub10Frames` and `scrub150Frames` open frames with no photo of their own, so each is
drawn on the reference. `scrub150FramesWithPhotos` passes the seeder `--ez framePhotos true`:
a separate session whose frames move moderately and each have a flat grey PNG in
`raw_deformed/`, so the viewer draws every frame over its photo with
`generateDeformedHeatmap` ([ADR-011](../adr/ADR-011-viewer-deformed-frame.md)). It is
report-only: `benchmark/gates.json` has no reference for it.

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest -PabiFilters=x86_64 \
  -P android.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,LOW-BATTERY,UNLOCKED
```

The `benchmark` build type is **R8-minified with release's rules** (resources are
not shrunk: the benchmarks find views by resource name), so its numbers are a
release build's. Add `-PabiFilters=x86_64` on an emulator: without it the build
compiles arm64-v8a too.

CI's `tier-benchmark` job passes the same `suppressErrors` (plus
`enabledRules=Macrobenchmark`) on an **API 34** emulator;
`benchmark/build.gradle.kts` sets the same suppress list so a local emulator run
matches CI. First it runs `scripts/launch_smoke.py` on the minified APK: a cold
start that fails on a crash, a process gone within 15 s, or an R8 symptom
(`ClassNotFoundException`, `NoSuchMethodError`, `SerializationException`, …) in an
error line of the app's logcat. It is the only place CI launches an R8 build, so a
PR that touches the keep rules or the build script (the `packaging` filter) runs
the job too. On a PR the Macrobenchmarks run as a dry run (one pass each,
`androidx.benchmark.dryRunMode.enable=true`), since their emulator numbers are not
gated; the `benchmark` label or a `run_benchmark` dispatch runs the full
iterations. Without a PR (a dispatch) the job then runs the micro suite with the
`am instrument` command above; on a PR the A/B's rounds run that suite instead,
and that is a gate (below). The numbers outside the A/B are not gated; the
`*-benchmarkData.json` upload as `macrobenchmark-results` and
`microbenchmark-results`.

Three things that will otherwise cost you an afternoon:

- **The shell cannot start a non-exported Activity** (API 34+). Macrobenchmark launches
  through the shell, so anything it drives must be exported — `app/src/benchmark/AndroidManifest.xml`
  exports the needed screens for the `benchmark` variant only, never for a shipped build.
- **`startActivityAndWait` may not work on an API 37 emulator.** It confirms a launch by
  parsing `dumpsys gfxinfo <pkg> framestats`, which came back empty for *every* activity
  on an earlier API 37 image, so `StartupBenchmark`/`ScreenBenchmark` failed with "Unable
  to confirm activity launch completion []". The Pixel_10_2 API 37 image runs them
  (2026-09-25); if yours does not, use a physical device or an older image.
  `ViewerScrubBenchmark` deliberately avoids that API.
- **The connected task installs over whatever is on the phone, then uninstalls it.**
  `:benchmark:connectedBenchmarkAndroidTest` installs the `benchmark` build over an
  existing `com.sempermechanics.semper` (same debug key), keeping its data, and uninstalls
  the app when it finishes, taking that data with it. Back up anything you need first.
  A signed-in session left over from a debug install also changes the launch route
  (Splash → Home rather than sign-in); before TD-90 that crashed both `StartupBenchmark`
  cases on a build with no `SEMPER_API_BASE_URL` (found on a Pixel 6 in material_testing).

Results land as `*-benchmarkData.json` under the module's
`build/outputs/connected_android_test_additional_output/`. A worked before/after
comparison is in [../perf/round2-main-vs-branch.md](../perf/round2-main-vs-branch.md).

The benchmarks drive `:app`'s `applicationId`: `benchmark/build.gradle.kts` reads it
from `:app`'s build into `BuildConfig.TARGET_PACKAGE` and the manifest's `<queries>`,
so the same sources run in material_testing under its own id. CI's micro step and
`scripts/startup_ab.py` read the same `applicationId` line of `app/build.gradle.kts`.

### CI microbenchmark A/B (TD-199)

An emulator's absolute times say nothing (its speed moves from runner to runner), so
on a PR `tier-benchmark` judges the hot paths by their change against the PR's base.
It builds the base (A) and the PR (B) as debug app + androidTest APKs, in the one
checkout, then `scripts/micro_ab.py` runs `HotPathMicroBenchmark` from each on the
same emulator in A B B A order, so drift hits both builds equally. Each build's
folder carries its `applicationId` in `app-id.txt`, so a PR that changes the id (as
ADR-019 did) runs each round under its own build's id. For each
benchmark it pools every `timeNs` run of a build (50 a round) and fails the job if
B's median is more than `microAbMargin` (15 %, `benchmark/gates.json`) over A's.
The log shows both medians, the change and both allocation counts; the rounds are
uploaded as `microbenchmark-ab`. A benchmark only one build has (the PR adds or
removes it) is reported and not compared. Method tracing is off for these runs
(`profiling.mode none`).

A PR that means to slow a hot path (a correctness fix that costs time) gets the
`perf-accepted` label: the comparison still runs and prints a warning, but passes.
Labels apply from the next push. To re-read a run, download `microbenchmark-ab` and
run `python scripts/micro_ab.py --analyse <folder>`.

### Real-device gates

[`benchmark/gates.json`](../../benchmark/gates.json) turns a phone's reference medians
into gates: a result fails when it is more than `margin` (30 %) over its reference.
After a run on a phone:

```bash
python scripts/ci_test_report.py --gates benchmark/gates.json \
  benchmark/build/outputs/connected_android_test_additional_output \
  app/build/outputs/connected_android_test_additional_output
```

It prints `GATE ok …` per reference, an `::error` per breach, and exits 1 if any gate
is over. Gates are keyed by the device the JSON records (`context.build.device`,
`g0q` for the Galaxy S22+, `oriole` for a Pixel 6); a device the file does not list, CI's
emulator included, is reported and never gated, and CI does not pass `--gates`. Without
`--gates` the script only reports and always exits 0.

**The benchmark phone is the Galaxy S22+ (`g0q`, SM-S906U1, API 36)** since 2026-10-27.
Its references are from a fresh install of `7cf205af` (signed out), on AC, and every
test held thermal status 0: cold and warm start, Settings and wizard cold start,
Settings scroll, and the two viewer scrubs without frame photos
(`scrub150FramesWithPhotos` stays report-only). The run, how it was driven, and the
micro numbers are in [../perf/samsung-g0q-baseline.md](../perf/samsung-g0q-baseline.md).
**The Pixel 6 (`oriole`)** keeps its 2026-10-05 references for the five tests that
stayed at status 0 there; its startup cold and warm start and wizard cold start never
got one. material_testing's were taken on its own app and are not copied. Microbenchmark times have no reference here: debuggable
and not AOT-compiled, they are relative numbers, gated only against the PR's base in
CI (below).

**The phone's state ([ADR-008](../adr/ADR-008-startup-gates-phone-state.md)).**
A startup time moves 30–40 % with heat and the charger (material_testing's TD-135),
so a result is gated only in the state the references assume. `DeviceStateRule` (a
`@get:Rule` in `StartupBenchmark`, `ScreenBenchmark`, `StartupHeadroomBenchmark` and
`ViewerScrubBenchmark`) writes `com.sempermechanics.semper.benchmark-deviceState.json`
next to the results: per test, the thermal status, battery temperature and level,
charger, free memory and swap, at its start and end. A result whose test ran above
`state.maxThermalStatus` (0) or off the charger (`state.requirePlugged`) prints
`GATE not gated …` with the reason and does not count as a breach. A result with no
state file (an APK from before the rule) is gated as before and says so. The three
cold-start cases run `StartupBenchmark.COLD_START_ITERATIONS` = 15 iterations, not 5:
one run's starts spread 404–478 ms, so a 5-start median moved with one or two slow ones.

To add a phone, run both suites on the charger, at thermal status 0, with known app
data, and add its codename under `devices` with those medians and the state in its
`label`. Run one macro class per `am instrument` call and pull its
`*benchmarkData.json` and `*deviceState.json` before the next, since each call
overwrites both ([../perf/samsung-g0q-baseline.md](../perf/samsung-g0q-baseline.md)
has the loop).

**When a startup gate trips**, compare with the reference build on the same phone
before calling it a regression:

```bash
python scripts/startup_ab.py --a ref/app-benchmark.apk --b new/app-benchmark.apk \
  --bench benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk --out ab-run
```

It runs the three cold starts in A B B A A B B A order, pools each build's runs, and
exits 1 if the candidate's median is more than `abMargin` (10 %) over the reference
build's. It backs up the installed app first (`--package`, default `:app`'s
`applicationId`) and reinstalls it at the end, uses `am instrument` (never the
connected task), wakes the screen every 10 s, and stops without touching the phone if
it is locked or another instrumentation is running. `--analyse ab-run` re-reads a
finished run.

### Coverage floors

Three suites are gated on coverage, each two points under what it measured:

| Suite | Measured (2026-10-08) | Floor | Where |
|---|---|---|---|
| App JVM (Kover, lines) | 82.33 % (2026-10-28) | 80 | `app/build.gradle.kts` `kover.verify` |
| App JVM (Kover, branches) | 63.55 % (2026-10-28) | 61 | same |
| Backend (pytest-cov, lines) | 95.47 % | 93 | `.github/actions/backend-gate/action.yml` |
| Backend + Firestore emulator tier | 95.54 % | 93 | `ci.yml` tier 4, `--cov-append` |
| Console JS (`scripts/console_coverage.mjs`, lines merged by file) | 98.30 % | 96 | `ci.yml` `console-pages` |

**Every PR that adds tests re-measures and raises the floors it moved** to
measured − 2 (rounded down), in the same PR, with the date in the comment
beside the number. Never lower a floor to turn a build green; the one
exception is a PR that changes *what* is measured (an exclude removed), which
resets the floor and says why.

Measuring:

```bash
./gradlew :app:testDebugUnitTest :app:koverXmlReport   # totals: app/build/reports/kover/report.xml
cd backend && pytest tests/ -q --cov=app               # TOTAL line
NODE_V8_COVERAGE=/tmp/console-cov node --test "firebase-hosting/tests/*.test.mjs"
node scripts/console_coverage.mjs /tmp/console-cov     # a row per file, then the total
```

The console figure comes from `scripts/console_coverage.mjs`, not Node's
`--experimental-test-coverage`. The harness opens each page as `page.js?load=N`,
and Node keeps every URL apart: a file's row showed one copy, and the total fell
as tests opened pages more often (55 % for code that is 98 % covered). The script
reads the raw V8 coverage, merges a file's copies, takes each line from the
innermost range that holds it, and counts every `.js` under `public/` except
`vendor/`, loaded or not. `console/chrome.js` shows 0 %: pages load it from its own
`<script>` tag, and no test does.

Kover leaves out only the generated bindings (`app/build.gradle.kts`
`kover.reports.filters`). Activities, Adapters and Dialogs were excluded until
2026-10-08; Robolectric runs most of them, so they now count. That reset the app
floors from 73 / 58 to 72 / 54: on the old, narrower set the same suite measured
76.84 / 60.80 %.

Device tests wait on conditions, not on the clock: `awaitCondition(what) { done }`
and `awaitDrawnFrame()` (before a screenshot) in `androidTest/.../fixtures/DeviceWaits.kt`.
The only sleeps left are the touch pacing in `RoiEditorGestureTest`, where the
gesture detectors read the event timing.

### Known coverage gaps

Worth knowing before you assume something is protected:

- Robolectric's `PdfDocument` has no native document: `startPage` throws
  "document is closed". Anything that draws a PDF page (`PdfReportGenerator`,
  the bundler's reports, the viewer's PDF and ZIP exports) is checked only by
  the device tests above. JVM tests stop at the progress and error contract.

- The strain plot's own gestures — scrub, pinch, pan, double-tap, and the
  fraction it reports to the scrub slider (NaN once the scrub clears) — are
  covered by `ui/analysis/sweep/SweepPlotViewTest`. The lattice screen around it — the
  slider itself, double-tap-to-copy and the composed **Save graph** PNG — has
  **no automated coverage**; it is exercised only by the manual pass in
  [WORKFLOWS.md](WORKFLOWS.md) §7.
- The Storage section and the diagnostics consent toggle have no UI test;
  `settings/HelpSupportSectionTest` is the Robolectric pattern to copy if you add
  one.

Emulator (all instrumented):
```bash
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64
```

Macrobenchmark / Baseline Profile (`:benchmark` module — not part of default CI):
```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -P android.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,LOW-BATTERY,UNLOCKED
```
CI runs this only with the `benchmark` PR label or workflow_dispatch
`run_benchmark`. `app/src/main/baseline-prof.txt` holds only comments, and
`profileinstaller` ships the AndroidX libraries' own profile rules. A profile of
the app's own startup path has nothing to gain: on a Pixel 6, `Full` compilation
starts no faster than `None` ([perf/startup.md](../perf/startup.md), which has the
`StartupHeadroomBenchmark` steps). Measure that again before generating one.

## Source checks (no Gradle)

Rules that read the sources rather than run them are Python checks, stdlib
only, run in CI's `legal-pages` job on every event ([CI.md](../ops/CI.md)).
Their tests are `unittest` modules in `scripts/tests`, run in the same job:

```bash
python scripts/check_kotlin_file_size.py                   # ADR-015's 500-line file budget (TD-292)
python scripts/check_activity_scope_work.py                # ADR-016 rule 1 on screens' lifecycleScope (TD-291)
python scripts/check_swallowed_cancellation.py            # ADR-018: cancellation rethrown around suspend calls (TD-171)
python -m unittest discover -s scripts/tests -p "test_*.py"
python scripts/check_engine_pin.py                         # its own always-on job, engine-pin (TE2-006)
```

`check_kotlin_file_size.py` does not count the fused hot-loop functions
([ADR-015](../adr/ADR-015-package-layout.md#amendments)); an exception goes in
`scripts/kotlin_file_size_allowlist.txt` with its ceiling and a TD/ADR pointer,
and an entry that is no longer needed fails the check.

`check_activity_scope_work.py` reads `ui/` for a `lifecycleScope` launch (or a
lambda handed to a function that launches it, like the old `runAuth`) whose
block reaches a `data/net` / `data/cloud` client, an `AuthRepository` network
call or an enqueue, directly or through a function of the same file. Its tests
copy the Kotlin fixtures in `scripts/tests/fixtures/activity_scope/` into a
temporary tree: Home's restore on `lifecycleScope` fails, the same restore on
`viewModelScope` passes.

`check_swallowed_cancellation.py` stands in for detekt's
`SuspendFunSwallowedCancellation`, which needs type resolution detekt 1.23.8
cannot do here (TD-171). It reads every `app/src/main` file for a
`catch (e: Exception)` (or `Throwable`, `RuntimeException`,
`IllegalStateException`) or a `runCatching { }` whose block calls a suspend
function, known by name: one the main sources declare `suspend`, a
`suspend`-typed parameter or property, or a kotlinx.coroutines / androidx
suspend call. It passes a `CancellationException` clause ahead of the broad
one, a catch that rethrows or calls `rethrowIfCallerCancelled()`, a
`runCatching` chained to `getOrThrow()`, and anything under `NonCancellable`;
an exception goes in `scripts/swallowed_cancellation_allowlist.txt` with a
TD/ADR pointer. A name that is also a plain `fun` counts only in a suspend
context, so a library suspend call it does not list is missed. Its fixtures are
in `scripts/tests/fixtures/cancellation/`.

## What not to test here

- Algorithm accuracy → the engine's own suite, which lives in the `engine/`
  submodule and runs in the engine repo's CI (this repo's `engine-host-sanitized`
  job reruns its host suite under ASan+UBSan at the pin)
  ([docs/engine/TESTING.md](../engine/TESTING.md))
- Backend API → backend pytest (`backend/tests/`)
- Real Firebase Auth → `auth/FirebaseAuthIntegrationTest`: email/password
  sign-in, an ID token and its refresh against the app's Firebase project, then
  sign-out. Tier 3 passes the CI test account from the repo secrets
  `FIREBASE_TEST_EMAIL` / `FIREBASE_TEST_PASSWORD` (TD-200). Without them (a local
  run, a fork's or Dependabot's PR) the cases skip (JUnit `assumeTrue`); locally,
  pass both with `-Pandroid.testInstrumentationRunnerArguments.FIREBASE_TEST_EMAIL=…`.
  The password must have no comma: Gradle cuts an argument there (TD-86).
  The account and both secrets come from `scripts/create_ci_firebase_account.sh`
  (gcloud as an owner of `indicvision-dic-app-auth`, gh as a repo admin); `--rotate`
  gives the account a new password and updates the secret.
  For local runs use a separate account, not CI's: an owner of the auth project
  runs `bash scripts/create_local_firebase_test_account.sh` (Git Bash on Windows;
  the `bash.exe` on PATH is WSL's). It makes `test-local-instrumented@sempermechanics.com`,
  a Firebase sign-in only (no users doc or licence, so the backend refuses it),
  and writes both arguments to `~/.gradle/gradle.properties`, outside the repo,
  so every later `connectedDebugAndroidTest` signs in without flags. The password
  is never printed; running it again gives the account a new password.
