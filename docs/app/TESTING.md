# App test suite — workflow chunks

Tests are organized into chunks that mirror the user journey through the app.
Each chunk owns one layer; no duplicate assertions across chunks. Each workflow
in [../WORKFLOWS.md](../WORKFLOWS.md) names the tests that pin it, so the two
files answer opposite questions: "what covers this flow?" there, "what does this
chunk own?" here.

## Chunk map

| Chunk | User journey | JVM tests (`app/src/test`) | Instrumented (`androidTest`) |
|-------|--------------|---------------------------|------------------------------|
| **auth** | Splash → Auth / Pending / Home, re-auth, password rules | `auth/AccessRouterTest`, `ReauthFlowTest`, `PasswordPolicyTest` | `auth/FirebaseAuthIntegrationTest` |
| **analysis** | Import → ROI → batch / parameter sweep; speckle and noise-floor suitability; the wizard across a process death | `analysis/AnalysisViewModelTest`, `WizardStateTest`, `VsgStudyTest`, `SubsetRecommenderTest`, `ConvergenceGateTest`, `DicGoodPracticeTest`, `SpeckleScaleTest`, `NoiseFloorProbeTest`, `NoiseFloorStatsTest`, `NoiseCorrelationTest`, `FrameOrderHelperTest`, `BitmapDecodeTest`, `ExifOrientedSizeTest`, `LossyFormatCheckTest`, `RawRgbaTest`, `SweepSetupHelperTest`, `DicBatchRunnerLimitTest`, `ui/common/media/MediaPickerSheetTest`, `ui/analysis/roi/StudioOverlayViewTest`, `RoiDrawActivityTest`, `VsgLatticeViewTest`, `RoiViewportTest`, `VsgPlotViewTest` | `ui/analysis/wizard/WizardDraftRestoreTest`; `e2e/RoiEditorGestureTest` (ROI editor under real touches: pinch keeps the ROI's pixels, two-finger pan stops at the edge, double-tap 2× / fit, a stray tap keeps the ROI, a zoomed draw saves the pixels under the finger) |
| **session** | Session store durability, disk footprint, failure provenance; the Home list and its multi-select | `session/SessionStoreAtomicTest`, `LocalStorageFootprintTest`, `FailureProvenanceTest`, `ui/home/SessionListAdapterTest`, `SessionSelectionControllerTest`, `CloudBackupsCardTest` | — |
| **results** | `.dat` decode, CSV, heatmap, PDF, GIF | `results/DicResultCsvTest`, `AnalysisCsvSectionsTest`, `DicResultDecodeTest`, `VisualizationEngineTest`, `ReportBuilderTest`, `ReportBuilderMeanStdParityTest`, `GifEncoderTest`, `SummaryAnimationTest`, `PdfReportGeneratorTest` | `report/PdfReportDeviceTest` |
| **viewer** | Result viewer controls, frame cache bounds, the Intent contract both entry points write and all four readers parse | `viewer/FrameNumberEntryTest`, `ScrubFrameCacheTest`, `ViewerFieldPillsTest`, `ShareCenterTest`, `ui/viewer/ViewerArgsTest`, `TouchImageViewTest`, `ViewerSettingsSheetTest`, `analysis/RunSpecTest` | `ui/viewer/ViewerEntryParityDeviceTest` |
| **cloud** | Upload, API, restore, quota, account deletion | `cloud/ApiDtosContractTest`, `ApiErrorMappingTest`, `UploadResumableTest`, `DicUploadWorkerOutcomesTest`, `RestoreAndImportSafetyTest`, `RestoreStartTest`, `CloudBackupListingTest`, `QuotaGateTest`, `AccountDeletionTest`, `SessionEverythingExporterTest`, `data/cloud/SessionUploadBundlerTest` | `data/cloud/SessionUploadBundlerDeviceTest` |
| **settings** | Settings sections, contacting support, account deletion | `settings/AnalysisEntriesTest`, `HelpSupportSectionTest`, `DeleteAccountReauthTest`, `DicSettingsMigrateTest` | — |
| **analytics** | Consent-gated Firebase Analytics events | `diagnostics/SemperAnalyticsTest` | — |
| **upgrade** | Prefs / session index forward compatibility | (covered in settings + session) | `upgrade/PrefsUpgradeSmokeTest` |
| **e2e** | Wizard chrome smoke (Next + toolbar; Back / Compute / instruction GONE on step 1) | — | `AnalysisWizardSmokeTest` |
| **pipeline** | JNI + native runtime | — | `pipeline/EnginePipelineSmokeTest` |
| **benchmark** | Startup / screen / viewer-scrub Macrobenchmarks; hot-path microbenchmarks | — | `:app` androidTest `benchmark/HotPathMicroBenchmark`, `:benchmark` module (both in CI's `tier-benchmark`: label `benchmark` / workflow_dispatch) |

## Overlap rules

- **Host C++ tests** own algorithmic displacement accuracy.
- **Android JNI smoke** (`pipeline/`) owns runtime/bridge correctness —
  `System.loadLibrary`, OpenMP threading, JNI marshalling.
- **JVM tests** own Kotlin orchestration and data contracts. Do not add JVM
  tests that re-assert displacement accuracy.

## Shared fixtures

Reach for these before writing a local helper; each replaced several
hand-rolled copies (#315, #330).

| Fixture | File (under `app/src/test/java/com/indicvision/semper/`) | Use it for |
|---|---|---|
| `sessionRecord(...)` | `fixtures/SessionRecords.kt` | A `SessionRecord` with every required field defaulted (one frame, 100 × 100 px, subset 41, step 5, the whole image as ROI). Name only the fields the test cares about |
| `packDat`, `gridFrame`, `writeGridBatch` | `fixtures/DatFixtures.kt` | `.dat` bytes in the engine's layout, a synthetic grid frame, or a whole batch of them in a folder |
| `viewerArgs`, `viewerController`, `launchViewer` | `fixtures/ViewerFixture.kt` | A `ResultViewerActivity` under Robolectric on a `writeGridBatch` batch; keep the controller to recreate the viewer |
| `idleUntil(what, timeoutMs) { done }` | `fixtures/Robo.kt` | Waiting for work that runs on a background dispatcher and posts back to main: idles the main looper until `done` holds, and names `what` when it times out. Use it instead of `Thread.sleep` or a bare `idle()` |
| `CleanAppState` | `fixtures/CleanAppState.kt` | A JUnit rule: signed out, no saved sessions, before and after each test (token store, remote config, session index and folders) |
| `MockWebServerRule` | OkHttp's `mockwebserver3.junit4` | Starting and closing a `MockWebServer` per test; do not start one by hand |
| `WizardTestBed`, `FakeWizardHost` | `ui/analysis/WizardTestBed.kt` | The wizard's parts one at a time: its views in a plain themed Activity, a fresh `AnalysisViewModel`, and a host that counts what each part asks of it. Pass `resumed = false` for a part that registers a result launcher |

## Running by chunk

```bash
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.auth.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.session.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.analysis.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.results.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.cloud.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.settings.*"
./gradlew :app:testDebugUnitTest --tests "com.indicvision.semper.viewer.*"
```

`settings/HelpSupportSectionTest` drives the real `SettingsActivity` under
Robolectric — it is the first UI-level test of that screen, and the pattern to
copy for the other sections. `viewer/FrameNumberEntryTest` does the same for
`ResultViewerActivity`, writing synthetic `.dat` frames to a temp folder and
handing their path in on the intent.

`results/GifEncoderTest` reads its own output back with `javax.imageio` rather
than a decoder of ours: the encoder is written against the GIF89a spec by hand,
so the only claim worth making is that a third-party decoder agrees.

`analysis/WizardStateTest` covers the wizard's process-death restore on the
JVM, `ui/analysis/wizard/WizardDraftRestoreTest` covers it through a real Parcel on a
device, and neither can kill the process. The kill is a scripted pass: take
the wizard to step 2, press Home, run `adb shell am kill com.indicvision.semper`
(if `pidof` still shows the process, `adb shell run-as com.indicvision.semper
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
need a device, and they are gated in CI behind the `benchmark` label.

**Micro (`:app` androidTest) — "did this operation get cheaper?"**
Measures median `timeNs` **and `allocationCount`** for the hot paths (`valueRanges`,
`buildReport`, `generateHeatmap`, GIF encode, `computeFieldExtrema`, `decodeDatFile`,
the spatial index). `allocationCount` is the honest memory signal: the workload is
fixed, so a change in allocations is caused by the code and nothing else.

```bash
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class com.indicvision.semper.benchmark.HotPathMicroBenchmark \
  -e androidx.benchmark.suppressErrors EMULATOR,DEBUGGABLE,LOW-BATTERY,UNLOCKED,ACTIVITY-MISSING,NOT-AOT-COMPILED \
  com.indicvision.semper.test/androidx.test.runner.AndroidJUnitRunner
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

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -P android.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,LOW-BATTERY,UNLOCKED
```

CI's `tier-benchmark` job passes the same `suppressErrors` (plus
`enabledRules=Macrobenchmark`) on an **API 34** emulator;
`benchmark/build.gradle.kts` sets the same suppress list so a local emulator run
matches CI. On the same emulator the job then runs the micro suite with the
`am instrument` command above. Numbers are smoke, not
a regression gate; both suites' `*-benchmarkData.json` are uploaded, as
`macrobenchmark-results` and `microbenchmark-results`.

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
  existing `com.indicvision.semper` (same debug key), keeping its data, and uninstalls
  the app when it finishes, taking that data with it. Back up anything you need first.
  A signed-in session left over from a debug install also changes the launch route
  (Splash → Home rather than sign-in); before TD-90 that crashed both `StartupBenchmark`
  cases on a build with no `INDIC_API_BASE_URL` (found on a Pixel 6 in material_testing).

Results land as `*-benchmarkData.json` under the module's
`build/outputs/connected_android_test_additional_output/`. A worked before/after
comparison is in [../perf/round2-main-vs-branch.md](../perf/round2-main-vs-branch.md).

The benchmarks drive `:app`'s `applicationId`: `benchmark/build.gradle.kts` reads it
from `:app`'s build into `BuildConfig.TARGET_PACKAGE` and the manifest's `<queries>`,
so the same sources run in material_testing under its own id. CI's micro step and
`scripts/startup_ab.py` read the same `applicationId` line of `app/build.gradle.kts`.

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
`oriole` for a Pixel 6); a device the file does not list, CI's emulator included, is
reported and never gated, and CI does not pass `--gates`. Without `--gates` the script
only reports and always exits 0. **No device is listed yet:** Semper's Pixel 6
references are owed (TD-155). material_testing's were taken on its own app and are not
copied. Microbenchmark times are not gated: debuggable and not AOT-compiled, they are
relative numbers only.

**The phone's state ([ADR-008](../adr/ADR-008-startup-gates-phone-state.md)).**
A startup time moves 30–40 % with heat and the charger (material_testing's TD-135),
so a result is gated only in the state the references assume. `DeviceStateRule` (a
`@get:Rule` in `StartupBenchmark`, `ScreenBenchmark`, `StartupHeadroomBenchmark` and
`ViewerScrubBenchmark`) writes `com.indicvision.semper.benchmark-deviceState.json`
next to the results: per test, the thermal status, battery temperature and level,
charger, free memory and swap, at its start and end. A result whose test ran above
`state.maxThermalStatus` (0) or off the charger (`state.requirePlugged`) prints
`GATE not gated …` with the reason and does not count as a breach. A result with no
state file (an APK from before the rule) is gated as before and says so. The three
cold-start cases run `StartupBenchmark.COLD_START_ITERATIONS` = 15 iterations, not 5:
one run's starts spread 404–478 ms, so a 5-start median moved with one or two slow ones.

To add a phone, run both suites on the charger, at thermal status 0, with known app
data, and add its codename under `devices` with those medians and the state in its
`label`.

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

### Known coverage gaps

Worth knowing before you assume something is protected:

- Robolectric's `PdfDocument` has no native document: `startPage` throws
  "document is closed". Anything that draws a PDF page (`PdfReportGenerator`,
  the bundler's reports, the viewer's PDF and ZIP exports) is checked only by
  the device tests above. JVM tests stop at the progress and error contract.

- The strain plot's own gestures — scrub, pinch, pan, double-tap, and the
  fraction it reports to the scrub slider (NaN once the scrub clears) — are
  covered by `ui/analysis/sweep/VsgPlotViewTest`. The lattice screen around it — the
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

## What not to test here

- Algorithm accuracy → the engine's own suite, which lives in the `native/`
  submodule and runs in the engine repo's CI, not here
  ([docs/engine/TESTING.md](../engine/TESTING.md))
- Backend API → backend pytest (`backend/tests/`)
- Real Firebase Auth → `auth/FirebaseAuthIntegrationTest`. These self-skip
  (JUnit `assumeTrue`) unless `FIREBASE_TEST_EMAIL` / `FIREBASE_TEST_PASSWORD`
  are passed as instrumentation args. CI does not supply them, so they are
  skipped there today — to run them, provide the args locally or wire the
  secrets into the emulator job.
