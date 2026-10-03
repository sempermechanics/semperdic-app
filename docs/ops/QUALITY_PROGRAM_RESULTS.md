# Quality program: results (2026-10-03)

The 2026-10 quality program set out to fix the bugs a whole-app review found,
then make the Android app easier to read and change: a package layout,
shared helpers in place of copies, ViewBinding in place of `findViewById`, and
no main source file over about 500 lines. This page compares the result with
the [baseline](QUALITY_BASELINE_2026-10-01.md) taken before any of it
(`main` @ `ff0cfc3d`, 2026-10-01).

The "after" numbers are measured on the second package move's branch (#331)
at `d81aa1f1`, which carries every code PR of the program, with the same
script: `python scripts/quality_metrics.py <checkout> <out_prefix>`. Coverage
was measured one commit earlier, at `2bb72a45`; the commit between them
changed one sweep function and its test. Its counts are
approximate (regex and brace counting), so compare its runs with each other
only. As of 2026-10-03 only #310 and #315 are merged; the rest are open, in
the order of [§8](#8-the-prs-and-their-merge-order).

**Headlines**

- Main source files over 500 lines: **20 → 0**. The largest file went from
  `StaticAnalysisActivity.kt` at 1,834 lines to `SubsetRecommender.kt` at 497.
- The largest package went from **54 files to 23**.
- `findViewById` calls: **416 → 46**; `Toast.makeText`: **65 → 2** (one call,
  inside `Feedback.toast`, and one KDoc mention);
  `lateinit var`: **183 → 56**; `!!`: **4 → 0**.
- Duplicated code (6-line blocks): **2.51 % → 1.33 %** of normalised lines.
- Functions over 100 lines: **15 → 4**. Three of the four hold *fused hot
  loops*: the performance-critical loops (the JNI batch loop, the heatmap
  pixel loops, the report's fusion pass, GIF compression, `.dat` decoding)
  whose body stays whole in one function, so a split cannot add calls or
  allocations per iteration ([ADR-015](../adr/ADR-015-package-layout.md)). The
  fourth is `ResultViewerActivity.onCreate` (121 lines).
- Files with `@file:Suppress`: **80 → 58**.
- Unit tests (`@Test`): **1,125 → 1,861**; JVM line coverage **57.8 % → 75.1 %**
  (the denominator changed; see [§1.7](#17-coverage)).
- **About 60 bugs fixed**, 11 of them ranked high before work began; almost
  every one has a regression test.
- **18 items deferred**, each a [TECH_DEBT](TECH_DEBT.md) row (TD-158 to TD-175).

The decisions the program made are ADR-015 (as amended) to ADR-018:
[package layout](../adr/ADR-015-package-layout.md),
[work that outlives the Activity](../adr/ADR-016-work-that-outlives-the-activity.md),
[ViewBinding and the ui/common kit](../adr/ADR-017-viewbinding-and-ui-kit.md),
[the error convention](../adr/ADR-018-error-convention.md).

## 1. Metrics side by side

### 1.1 Size

| Metric | Before | After | Change |
|---|---:|---:|---|
| Main Kotlin files | 223 | 374 | +151: the splits made more, smaller files |
| Main Kotlin lines | 43,037 | 49,854 | +16 %: each new file adds a package line, imports and a KDoc header |
| Normalised code lines (no blanks, comments or imports) | 23,271 | 23,739 | +2 % |
| Mean lines per file | 193.0 | 133.3 | −31 % |
| Files over 300 lines | 39 | 38 | −1 |
| Files over 500 lines | 20 | 0 | −20 |
| …of them outside the fused-loop files | 18 | 0 | −18 |
| Largest file | `StaticAnalysisActivity.kt`, 1,834 | `SubsetRecommender.kt`, 497 | −73 % |
| Largest package (files) | 54 (`ui/analysis`) | 23 (`data/net`) | −57 % |
| Functions | 1,843 | 2,430 | +587 |
| Functions over 60 lines | 47 | 14 | −33 |
| Functions over 100 lines | 15 | 4 | −11 |
| Functions with 7 or more parameters | 37 | 42 | +5 |
| Duplicated 6-line blocks | 83 | 40 | −52 % |
| Duplicated lines | 584 | 315 | −46 % |
| Duplication | 2.51 % | 1.33 % | −1.18 points |

The longer total comes from file overhead, not new logic: normalised code grew
by 468 lines while the program added value types, helpers and typed outcomes.
Functions with 7 or more parameters rose by 5 overall, though the worst ones
shrank: `bakeAnnotationsToCanvas` went from 14 parameters to 8, and
`buildSessionRecord` from 17 to 4 (its inputs grouped as `RunInput` and
`RunOutcome`).

**Largest files**

| Before | Lines | After | Lines |
|---|---:|---|---:|
| `ui/analysis/StaticAnalysisActivity.kt` | 1,834 | `ui/analysis/recommend/SubsetRecommender.kt` | 497 |
| `ui/viewer/ResultViewerActivity.kt` | 1,442 | `ui/analysis/roi/StudioOverlayView.kt` | 495 |
| `data/DicUploadWorker.kt` | 1,021 | `ui/viewer/ResultViewerActivity.kt` | 494 |
| `data/CloudRestore.kt` | 912 | `ui/home/HomeActivity.kt` | 492 |
| `ui/analysis/AnalysisViewModel.kt` | 902 | `data/net/SemperApi.kt` | 490 |
| `ui/analysis/VsgPlotView.kt` | 842 | `ui/analysis/sweep/VsgPlotView.kt` | 490 |
| `ui/analysis/SweepSetupHelper.kt` | 820 | `ui/analysis/VsgLatticeActivity.kt` | 487 |
| `ui/settings/SettingsActivity.kt` | 817 | `data/cloud/SessionMetadataDoc.kt` | 474 |
| `ui/home/HomeActivity.kt` | 802 | `ui/analysis/run/DicBatchRunner.kt` | 467 |
| `ui/viewer/ShareCenter.kt` | 781 | `data/account/AuthRepository.kt` | 466 |

**Longest functions**

| Before | Lines | After | Lines |
|---|---:|---|---:|
| `DicUploadWorker.doWork` | 513 | `DicBatchRunner.runBatchAnalysisBody` (the one JNI batch loop, kept whole) | 354 |
| `runBatchAnalysisBody` | 385 | `ReportBuilder.buildReport` (the fusion pass, kept whole) | 180 |
| `StaticAnalysisActivity.onCreate` | 306 | `ResultViewerActivity.onCreate` | 121 |
| `DriveTransfer.downloadFile` | 216 | `HeatmapRenderer.generateHeatmapIndices` (pixel loop, kept whole) | 119 |
| `ResultViewerActivity.onCreate` | 188 | `ReportAnnotations.bakeAnnotationsToCanvas` | 94 |
| `RoiDrawActivity.onCreate` | 175 | `DeformedHeatmap.generateDeformedHeatmapIndices` (pixel loop, kept whole) | 78 |
| `SessionUploadBundler.stageCsvAndBundles` | 165 | `VsgStudyRunner.run` | 77 |
| `ReportBuilder.bakeAnnotationsToCanvas` | 153 | `SubsetRecommender.recommend` | 74 |

### 1.2 Android idioms

| Metric | Before | After | Change |
|---|---:|---:|---|
| `findViewById` | 416 | 46 | −89 %: ViewBinding ([ADR-017](../adr/ADR-017-viewbinding-and-ui-kit.md)) |
| `lateinit var` | 183 | 56 | −69 % |
| `Toast.makeText` | 65 | 2 | −97 %: `Feedback.toast`; the 2 are its one call and a KDoc mention |
| `CrispToast.show` | 13 | 14 | +1 |
| `AlertDialog.Builder` / `MaterialAlertDialogBuilder` | 31 | 15 | −52 %: `Dialogs.info` / `confirm` |
| Generic `catch (Exception/Throwable)` | 59 | 46 | −13 |
| `CancellationException` mentions | 31 | 53 | +22: cancellation is now rethrown ([ADR-018](../adr/ADR-018-error-convention.md)) |
| `Dispatchers.IO` | 97 | 76 | −21 |
| `!!` | 4 | 0 | −4 |
| `Pair<` / `Triple<` types | 91 | 89 | −2 |
| `Timber.e` / `Timber.w` | 185 | 178 | −7 |
| `String.format(Locale` | 32 | 30 | −2 |

`Pair` and `Triple` types barely moved. The program replaced only the ones
that carried a meaning across APIs: image sizes, value ranges, baked heatmaps
and Drive upload results became `ImageSize`, `ValueRange`, `BakedHeatmap` and
`DriveUpload`.

### 1.3 Suppressions

| detekt rule (`@Suppress`, file-wide / inline) | Before | After |
|---|---:|---:|
| `MagicNumber` | 56 / 3 | 25 / 1 |
| `ReturnCount` | 29 / 35 | 20 / 23 |
| `TooManyFunctions` | 27 / 11 | 19 / 15 |
| `LongParameterList` | 20 / 25 | 12 / 24 |
| `CyclomaticComplexMethod` | 19 / 4 | 6 / 2 |
| `LongMethod` | 17 / 3 | 5 / 1 |
| `TooGenericExceptionCaught` | 14 / 34 | 9 / 29 |
| `NestedBlockDepth` | 9 / 2 | 6 / 2 |
| `ComplexCondition` | 6 / 0 | 3 / 1 |
| `LargeClass` | 5 / 1 | 0 / 0 |
| **Files with `@file:Suppress`** | **80** | **58** |

Suppressions were checked by stripping them and re-running detekt or lint;
46 that no longer fired were removed in #320 alone. Lint reports no warnings
after #331 (the branch before it had 6), and both baselines are still empty.

### 1.4 Layouts

| Metric | Before | After |
|---|---:|---:|
| Layout XML files | 36 | 37 |
| Layout XML lines | 6,283 | 6,171 |

The new file is `settings_section_header.xml`, which seven `<include>`s use in
place of copied header XML.

### 1.5 Tests

| Source set | Files | Lines | `@Test` | `Thread.sleep` | `@Config(sdk` |
|---|---|---|---|---|---|
| Unit, before | 142 | 20,301 | 1,125 | 5 | 72 |
| Unit, after | 271 | 36,825 | 1,861 | 4 | 0 |
| Instrumented, before | 17 | 3,199 | 65 | 13 | 0 |
| Instrumented, after | 18 | 3,249 | 65 | 13 | 0 |

The 72 `@Config(sdk = [34])` lines became one `robolectric.properties` (#310,
#315). Shared fixtures replaced hand-rolled records, `.dat` writers, server
setup and waits ([TESTING.md § Shared fixtures](../app/TESTING.md#shared-fixtures)).
The instrumented suite was not reworked; its 13 sleeps remain.

### 1.6 Files per package

| Package | Before | After |
|---|---:|---:|
| `ui/analysis` (root) | 54 | 3 (the Activities) |
| `ui/analysis/wizard` · `run` · `sweep` · `frames` · `recommend` · `roi` | — | 22 · 16 · 17 · 12 · 9 · 7 |
| `data` (root) | 50 | 11 (the six Workers and the backup's steps) |
| `data/session` · `cloud` · `cloud/restore` · `account` · `prefs` | — | 17 · 18 · 10 · 15 · 6 |
| `data/net` | 16 | 23 |
| `data/net/drive` | — | 4 |
| `ui/viewer` | 20 | 19 |
| `ui/viewer/share` · `summary` · `inspect` | — | 11 · 3 · 5 |
| `ui/common` | 18 | 12 |
| `ui/common/dialog` · `auth` · `media` · `transfer` | — | 8 · 6 · 6 · 4 |
| `report` | 12 | 22 |
| `ui/settings` · `ui/home` · `ui/auth` | 10 · 6 · 8 | 15 · 11 · 11 |
| `field` · `imaging` · `imaging/video` | — · 9 · — | 12 · 9 · 7 |
| `util` · `navigation` · `diagnostics` | 6 · 1 · — | 12 · 2 · 4 |
| `(root)` · `analytics` | 9 · 1 | 2 · — |

Eight packages are still over ADR-015's guide of about 15 files: `data/net`
(23), `report` (22), `ui/analysis/wizard` (22), `ui/viewer` (19), `data/cloud`
(18), `data/session` (17), `ui/analysis/sweep` (17) and `ui/analysis/run` (16).
ADR-015 leaves them for later feature splits; none mixes features the way the
flat packages did.

### 1.7 Coverage

| | Before | After |
|---|---:|---:|
| JVM line coverage (`./gradlew :app:koverLog`) | 57.8 % | 75.1 % |
| Floor (`koverVerify`) | 49 % | 49 % |

Same command, same filters, but not the same denominator. Kover leaves out
view classes by name (`ui.*Activity*`, `*Adapter*`, `*Fragment*`, `*Dialog*`
and generated bindings; `app/build.gradle.kts:371-387`). The six largest
Activities lost about 3,500 lines between them, most of it to controllers,
sections and helpers, which are measured, and most of those parts got tests
of their own. So part of the rise is code that became measurable and was
then tested, not only new tests on code that was already counted. The floor was not raised; that is a
build-script change for a later PR.

## 2. The package tree

Before (`main` @ `ff0cfc3d`; files per package):

```
com.sempermechanics.semper/          9   SemperApp, SemperNativeLib, DicResult, DicKeys, …
├── analytics/                   1
├── data/                       50   workers, session store, cloud sync, restore, auth, prefs, …
│   └── net/                    16   SemperApi, DriveTransfer, interceptors, tokens
├── imaging/                     9
├── navigation/                  1
├── report/                     12
├── ui/admin/                    1
├── ui/analysis/                54   the wizard, the run, ROI, sweep, video, recommendations
├── ui/auth/                     8
├── ui/common/                  18
├── ui/home/                     6
├── ui/limit/                    2
├── ui/settings/                10
├── ui/viewer/                  20
└── util/                        6
```

After (the #331 branch):

```
com.sempermechanics.semper/          2   SemperApp, SemperNativeLib (JNI names; never move)
├── data/                       11   the six Workers + the backup's steps
│   ├── account/                15
│   ├── cloud/                  18
│   │   └── restore/            10
│   ├── net/                    23
│   │   └── drive/               4
│   ├── prefs/                   6
│   └── session/                17
├── diagnostics/                 4
├── field/                      12   DicResult, DatDecoder and the value types (Roi, ImageSize, RunStop, …)
├── imaging/                     9
│   └── video/                   7
├── navigation/                  2
├── report/                     22
├── ui/admin/                    1
├── ui/analysis/                 3   the three Activities
│   ├── frames/                 12
│   ├── recommend/               9
│   ├── roi/                     7
│   ├── run/                    16
│   ├── sweep/                  17
│   └── wizard/                 22
├── ui/auth/                    11
├── ui/common/                  12
│   ├── auth/                    6
│   ├── dialog/                  8
│   ├── media/                   6
│   └── transfer/                4
├── ui/home/                    11
├── ui/limit/                    2
├── ui/settings/                15
├── ui/viewer/                  19
│   ├── inspect/                 5
│   ├── share/                  11
│   └── summary/                 3
└── util/                       12
```

What each package holds: [ARCHITECTURE.md § Package map](../app/ARCHITECTURE.md#package-map).
The moves were made by `scripts/move_kotlin_packages.py` from two mapping
files, `scripts/package_moves_2026_10.json` (#317) and
`scripts/package_moves_2026_10_wave5.json` (#331); material_testing replays
both ([FORK_SYNC.md](FORK_SYNC.md)). No Worker, Activity, `SemperNativeLib`,
persisted key or file format changed name.

## 3. Bugs fixed

Severity, on this page's scale: **High**, it loses or corrupts user data,
leaves work stuck for good, writes outside its folder, or crashes; **Medium**,
the user is told the wrong thing, work is wasted, or a resource leaks;
**Low**, logging hygiene or main-thread work that can stutter. The `H` numbers
are the eleven findings the review ranked high before work began. The tests
named are the ones the PR added or extended for that fix; most PRs checked that
they fail on the old code. A few small fixes (a released decoder, freed
bitmaps, work moved off Main) came without a test of their own.

### 3.1 Restore, auth and account deletion (#311)

| Severity | Bug | Regression test |
|---|---|---|
| High | A backup entry named like a sibling folder (`../<id>X/f`) passed a string-prefix check and could be written outside the session folder | `RestoreDestForTest` |
| High | Backups no retry could restore (no completed files, no metadata, no bundle, metadata that is not JSON) were retried forever | `RestoreDownloadOutcomesTest`, `RestoreWorkersTest`, `CloudRestorePipelineTest` |
| High | H7: rotating during account deletion could skip the phone wipe and sign-out after the cloud erase | `AccountDeletionTest` |
| Medium | `metadata.json`'s sha256 was not checked, and a stale partial copy could be resumed | `CloudRestorePipelineTest` |
| Medium | `DatCodec` trusted header sizes and leaked zlib state on a failure | `DatCodecCorruptInputTest` |
| Medium | Ten `AuthRepository` catches, the cloud erase and the restore listing reported a cancelled call as a failure | `AuthCancellationTest`, `EraseCancellationTest` |

### 3.2 Upload (#312)

| Severity | Bug | Regression test |
|---|---|---|
| High | H2: an outage (5xx, 429, a bare 404, 401) during a resume was read as "session gone": the half-uploaded session was dropped, its quota slot orphaned, and the retry got the same unusable session back | `DicUploadWorkerTest`, `UploadErrorsTest` |
| High | H3: a cancelled Drive upload kept sending chunks to the end of the file | `UploadResumableTest` |
| High | H4: the "already complete" probe could skip the server's checksum check | `UploadResumableTest` |
| Medium | Completion errors were classified by status alone, so any 409 was blamed on the analysis size | `UploadErrorsTest` |
| Medium | A truncated staged `metadata.json` was declared as is; it is now written atomically and rewritten if short | `DicUploadWorkerTest` |
| Medium | A full quota in the background tried to start an Activity from the worker | `DicUploadWorkerTest` |
| Medium | Cloud PDFs dropped engine stats slots 16–18 | `SessionUploadBundlerTest` |
| Low | A response whose nonce check failed was left open; cancellation in the token provider was swallowed | `TokenAndNonceCancellationTest` |
| Low | Upload logs carried file names and paths | `SessionUploadBundlerTest` |

### 3.3 Wizard and video import (#313)

| Severity | Bug | Regression test |
|---|---|---|
| High | H1: Cancel on a video import reported "too few frames" instead of ending the import | `VideoFrameExtractorCancelTest` |
| High | H5/H6: a new reference kept the old session and a stale custom ROI and mask, at a different size or the same size | `RoiResolveHelperTest`, `AnalysisViewModelTest` |
| High | H11: an AVI that states a frame rate of 0 would not import | `AviReaderTest` |
| Medium | A partial re-run opened the viewer without its reference, stop reason and image size | `UnsavedRerunTest` |
| Medium | The subset recommendation sampled an EXIF-rotated reference away from where the ROI is | `SubsetRecommenderExifTest`, `ExifPatchMapTest` |
| Medium | A RAW reference showed no thumbnail after a process-death restore | `WizardRestorePreviewTest` |
| Medium | Moved frames were re-pointed on the native thread instead of Main | `AnalysisViewModelTest` |
| Medium | The `MediaExtractor` leaked when the hardware decoder could not start | none in the PR |
| Low | The ROI editor read the reference and wrote the mask on Main; the wizard draft blocked Main on its lock; the sweep-frame preview decoded on Main | `RoiDrawActivityTest`, `WizardStateTest`, `WizardDraftRestoreTest` |

### 3.4 Viewer and report (#314)

| Severity | Bug | Regression test |
|---|---|---|
| High | H8: two summary GIF builds of the same file could race | `SummaryAnimationBuildTest` |
| High | H9: an export running in the background was lost on rotation | `ShareExportJobsTest`, `ShareCenterTest` |
| Medium | Custom colour scales were lost on rotation | `ViewerRotationTest` |
| Medium | Save to Files could open two pickers or copy twice across a rotation | `SaveExportActivityTest` |
| Medium | The report colour bar's 6-stop gradient was off by up to 83/255 from the heatmap's colours | `ReportColorBarTest` |
| Medium | The PDF generator missed `finishCurrentPage()` on a mid-page error and formatted telemetry in the device locale | `PdfReportGeneratorTest` |
| Medium | Lattice line cuts were keyed by list position, not frame index | `SweepFrameProfilesTest` |
| Medium | Share banner ids could collide; report and share bitmaps leaked when a render threw; the report base could be scaled up | `ShareCenterTest` |
| Low | Opening the viewer read the disk on Main | `ViewerRotationTest`, `FrameNumberEntryTest` |

### 3.5 Home, settings and auth (#316)

| Severity | Bug | Regression test |
|---|---|---|
| High | H10: Settings blocked Main on WorkManager `.get()` | `BusyTransfersTest` |
| Medium | Account deletion's dialog and outcome did not survive a rotation (window leak, outcome lost) | `AccountDeletionRotationTest` |
| Medium | A forced analysis-limit stop was cleared by the next quota refresh, and the limit screen could open repeatedly | `QuotaGateTest` |
| Medium | Overlapping list refreshes could land out of order | `ConflatedRefreshTest` |
| Medium | A late thumbnail decode could land on a recycled row | `SessionListThumbnailTest` |
| Medium | Splash, sign-in and Admin treated cancellation as a failure; sign-outs ran on the screen and could be cut short | `SplashRouteGuardTest`, `SignOutRunTest`, `AuthRouteTest`, `TermsGateTest` |
| Low | "Are the frames on this phone?" and the gallery query ran on Main | `AnalysisEntriesTest`, `SessionListAdapterTest`, `MediaPickerSheetTest` |

### 3.6 Found while splitting (#324–#331)

| Severity | Bug | PR | Regression test |
|---|---|---|---|
| High | An expired Drive upload link (404, 410 or 499 on the probe) was retried forever, and a 5xx on the probe restarted the upload at byte 0 | #324, #327 | `UploadResumableTest`, `DicUploadWorkerTest` |
| High | Session-file and pending-upload listings read only the first page of 1,000 | #324 | `PagingTest` |
| High | An offline backup lookup crashed Home | #327 | `CloudSyncFailureMappingTest` |
| High | Sweeps backed up before skip codes were stored failed to restore, and the worker retried forever | #326 | `CloudRestorePipelineTest`, `RestoreWorkersTest`, `SkippedNodeTest` |
| High | Restore picked the reference by file name, so a frame named `reference.png` could become the reference | #326 | `CloudRestorePipelineTest` |
| High | A closing wizard could delete the next wizard's draft | #330 | `WizardStateTest` |
| Medium | A corrupt merged `.dat` in Save to Files failed generically or fell back to the phone's copy | #326 | `DatCodecCorruptInputTest`, `RestoreWorkersTest` |
| Medium | A failed Save to Files left its archive in the cache for a day | #326 | `CloudRestorePipelineTest` |
| Medium | The cloud erase reported every failure as a bare `false` | #327 | `AccountDeletionTest` |
| Medium | A failed account deletion said "nothing was deleted" after the cloud erase had succeeded | #329 | `AccountDeletionStagesTest` |
| Medium | Sign-out from a screen the user had closed never reached sign-in | #329 | `SignOutRunTest` |
| Medium | Home opened the limit screen for any backup that failed without a reason | #329 | `TransferReactionsTest` |
| Medium | Deleting a downloaded analysis in Settings could bring its row back | #329 | `AnalysisDataAdapterTest` |
| Medium | Every failed save was reported as the session limit; an unreadable index is now "Analysis not saved" | #330 | `RunRecordSaveTest`, `BatchRunControllerTest`, `AfterSaveTest` |
| Medium | A cancelled run was shown as a strain-window failure | #330 | `EngineFailureTest` |
| Medium | A sweep whose save was refused said nothing; it now opens the limit screen or "Analysis not saved", as a batch run does | #331 | `SweepSessionSaveTest`, `BatchRunControllerTest` |
| Medium | A blank frame name targeted the `raw_deformed` folder itself | #330, #331 | `OriginalNameTest`, `LocalStorageFootprintTest`, `SweepFramePickerTest` |
| Medium | The report leaked earlier fields' bitmaps when a later field threw | #328 | `ReportBuilderBitmapOwnershipTest` |
| Medium | The viewer's colour bar did not match the heatmap's colours | #328 | `ViewerColorScaleBarTest` |
| Low | An unknown engine code was shown raw in its label | #331 | `EngineFailureShortReasonTest` |
| Low | A resumed download logged a local path | #324 | `DriveTransferDownloadTest` |
| Low | The backups-card refresh could run twice at once | #329 | `CloudBackupsCardTest` |

## 4. Abstractions introduced

The foundation PRs (#318–#321) only added code; the later PRs moved callers
over one area at a time. The counts are the sites each one replaced.

| Abstraction | Where | Replaced | PR, then adopted in |
|---|---|---|---|
| `ImageSize` | `field/ImageSize.kt` | width/height pairs and extras in about 15 places | #318; #325, #328, #330 |
| `Roi` (`forSolve`, `clampTo`, `orFullFrame`, codecs) | `field/Roi.kt`, `RoiCodecs.kt` | about 9 ROI shapes (ints, `Rect`, LTRB, arrays, extras, JSON); `RoiResolveHelper.resolve` | #318; #325, #330, #331 |
| `RunStop` | `field/RunStop.kt` | the stop and error constants of `AnalysisRunCodes`, `EngineFailure`, `VsgStudyRunner`, `AnalysisViewModel` | #318; #325, #330 |
| `DicParams`, `FrameParams` | `field/` | 13+ subset / step / strain-window carriers | #318; #328, #330 |
| `ValueRange`, `FieldStats`, `BakedHeatmap`, `DriveUpload` | `field/`, `report/`, `data/net/drive/` | `Pair` ranges, `[max,min,mean]` arrays, a `Triple`, a Drive `Pair` | #318; #328 |
| `SweepRanges`, `DeformedFrame` | `ui/analysis/sweep/`, `ui/analysis/frames/` | a 7-axis `IntArray`; four parallel frame lists | #318; #325, #330 |
| `AtomicFiles.writeVia` | `util/AtomicWrites.kt` | 7 hand-written temp-then-promote writes | #321; #323, #326, #327, #328 |
| `Zips`, `Streams` | `util/` | copy and CRC loops (12 sites in restore and session alone) | #321; #326, #328 |
| `PrefKey`, `PrefFiles` | `data/prefs/` | key constants and `getSharedPreferences` in 12 pref files (names and keys unchanged) | #321; #323, #326, #327 |
| `SessionLayout`, `StagingLayout` | `data/session/SessionLayout.kt` | hand-built session and staging paths (about 16 in upload alone) | #321; #326, #327 |
| `WorkTags`, `oneTimeWork`, `enqueueUnique`, `TransferWork` | `data/cloud/` | 5 work-request builders and the WorkInfo readers | #321; #323, #326, #327 |
| `Authed`, `HttpFailure` | `data/net/` | token-plus-try/catch blocks (7 sites in sync, more in seat lease and restore) | #321; #323, #326, #327 |
| `SessionMetadataDoc` | `data/cloud/` | `CloudRestore.recordFrom` and about 110 lines of org.json in the upload metadata | #321; #326, #327 |
| `ReportSource`, `SemperEngine.solve` | `report/`, `ui/analysis/run/` | duplicate report-parameter builders; the two single-shot JNI callers | #321; #325, #327, #328 |
| `Feedback.toast` | `ui/common/dialog/` | 65 `Toast.makeText` call sites (66 counted in #319's survey) | #319; #325, #328, #329, #330 |
| `Dialogs.info` / `confirm`, `bindInfo` | `ui/common/dialog/` | 6 info, 12 confirm and 15 ⓘ-button sites | #319; #325, #329, #330 |
| `WarnChip`, `Sheet` / `inflateSheet` | `ui/common/dialog/` | 12 warning-chip sites; 5 bottom-sheet setups and 8 rows | #319; #325, #328, #330 |
| `SerialJob`, `ConflatedRefresh` | `ui/common/` | 11 cancel-then-relaunch and 8 cancel-only sites; overlapping refreshes | #319, #316; #325, #328, #329, #330 |
| `setBusy`, keyboard helpers | `ui/common/` | 6 spinner patterns; 7 + 6 + 4 + 9 keyboard and toggle sites | #319; #329, #330 |
| `ThumbnailLoader` | `ui/common/media/` | 3 thumbnail loaders | #319; #329, #330 |
| `PlotStyle`, `ViewportMath` | `ui/analysis/sweep/`, `ui/common/` | 8 paints; 3 pan / zoom clamps | #319; #325, #328 |
| `ExternalLinks`, `SupportMail`, `ByteSize` | `ui/common/` | 3, 3 and 10 sites | #319; #329 |
| `settings_section_header` | `res/layout/` | 7 copied section headers | #319; #329 |
| `TransferWorkObserver` | `ui/common/transfer/` | Home's and Settings' hand-written WorkInfo observers | #329 |
| `ApiAnswer`, `ApiHostInterceptor`, `Paging` | `data/net/` | copied header pairs and hand-built exceptions in `SemperApi`; the host check in 3 interceptors | #324 |
| `ShareKind`, `Mime` | `ui/viewer/share/`, `util/` | share-kind and MIME strings | #328 |
| `AnalysisWizardHost`, `WizardStep`, `RunChrome` | `ui/analysis/wizard/`, `run/` | callback fan-outs; `Int` page numbers | #330, #331 |
| `UpsertResult`, `saveRunRecord`, `afterSave` | `data/session/`, `ui/analysis/run/` | a `Boolean` save result read as "limit reached" | #326, #330, #331 |
| Constants and dead code | across `app/` | literals that already had a constant (17 files), 7 unused `DicKeys`, 33 orphaned strings, 46 stale suppressions | #320 |

ViewBinding itself ([ADR-017](../adr/ADR-017-viewbinding-and-ui-kit.md)) was
switched on in #310 and adopted screen by screen in #325, #328, #329 and #330.

## 5. Large files split

Lines are before (`main` @ `ff0cfc3d`) and after (the #331 branch). A
fused hot loop always moved whole with its function.

| File | Before | After | Split into | PR |
|---|---:|---:|---|---|
| `StaticAnalysisActivity` | 1,834 | 434 | `ReferenceImportController`, `FrameImportController`, `FrameOrderController`, `VideoSamplingSheet`, `WizardMediaPickers`, `RoiStudioLauncher`, `SubsetRecommendationController`, `WizardParamFields` + `ParamSliders`, `WizardRunLauncher`, `WizardRunOutcomes`, `RunStatusLine`, `AnalysisLeaveController` | #330 |
| `ResultViewerActivity` | 1,442 | 494 | `ViewerFrameLoader`, `ViewerScaleController`, `ViewerImageLoader`, `FrameJumpController`, `ViewerShareController`, `ViewerChromeController`, `ViewerCaptions`, `FieldPopup`; field metrics into `ResultViewerViewModel` | #328 |
| `DicUploadWorker` | 1,021 | 248 | `UploadStaging`, `UploadSessionPlanner`, `UploadRun`, `UploadFailures`, `UploadTuning` | #327 |
| `CloudRestore` | 912 | 367 | `RestoreBundleFetcher`, `RestoreUnpacker`, `RestoreZipVerifier`, `DownloadFailure` | #326 |
| `AnalysisViewModel` | 902 | 438 | `AnalysisModels`, `SweepRunner`, `RunChannels`, `WizardDraftBinding` | #330 |
| `VsgPlotView` | 842 | 490 | `VsgPlotViewport`, `VsgPlotAxes`, `VsgPlotPalette` | #325 |
| `SweepSetupHelper` | 820 | 327 | `SweepRangeFields`, `SweepFramePicker` | #325 |
| `SettingsActivity` | 817 | 420 | `SettingsCloudSection`, `SettingsAnalysesSection`, `SettingsFooterSection` | #329 |
| `HomeActivity` | 802 | 492 | `HomeQuotaCard`, `FirstRunPrompts`, `HomeFabLayout`, `HomeTransferWatch`, `BackupBadgeActions` | #329 |
| `ShareCenter` | 781 | 207 | `ShareExportJobs`, `ShareExportBuilder`, `ShareExportUi` (#314); the builder then into `FieldImageExport`, `BundleExport`, `DataExport` (#328) | #314, #328 |
| `SemperApi` | 775 | 490 | `SemperApiSigning`, `SemperApiCalls`, `SemperApiClients`, `Paging`, `ApiHost` | #324 |
| `VsgLatticeActivity` | 775 | 487 | `LatticeProfiles`, `LatticeGraphExport`, `LatticeControls` | #325 |
| `AuthRepository` | 721 | 466 | `AuthLinks`, `AccessStatusResolver`, `FirebaseOp`, `ReauthCredentials` | #323 |
| `StudioOverlayView` | 721 | 495 | `StudioOverlayGeometry`, `StudioOverlayViewport` | #325 |
| `VisualizationEngine` | 656 | 181 | `HeatmapColorScale`, `HeatmapRenderer`, `DeformedHeatmap` | #328 |
| `AuthActivity` | 608 | 402 | `AuthTotpUi`, `AuthPasswordReset` | #329 |
| `DriveTransfer` | 519 | 74 | `DriveUploader`, `DriveDownloader` | #324 |
| `SessionStore` | 518 | 330 | `SessionRecord.kt` | #326 |
| `ReportBuilder` | 514 | 346 | `ReportFieldExtrema`, `ReportAnnotations`, `ReportColorBar` | #328 |
| `CloudSync` | 511 | 389 | `CloudErase`, `CloudReconcile` | #327 |
| `SessionZip` | 465 | 353 | helpers moved to `Zips` / `Streams` | #326 |
| `DicBatchRunner` | 455 | 467 | `BatchRun`, `UnsavedRerun`; the batch loop stays whole and grew with the fixes | #330 |

## 6. Deferred

Each item is a row in [TECH_DEBT.md](TECH_DEBT.md#new-deferred-by-the-2026-10-quality-program),
with its evidence and priority. "Found in" names the PR whose work or review
found the item; "the program's review" means it came from checking the PRs
against each other while this page and the TECH_DEBT rows were written, not
from any one PR.

| Row | What was left | Next step | Found in |
|---|---|---|---|
| TD-158 | After a 308, the next upload chunk starts at the end of what was sent, not where Drive says it stopped | Read `Range` on every 308 and rewind | #324 |
| TD-159 | The gateway spec declares no `page_token` on the uploads and files listings | A gateway deploy, when the user decides | #324 |
| TD-160 | A base URL with a port turns off the backend-only headers and the certificate pins | Compare host and port, or strip the port | #324's review |
| TD-161 | A redirect to another host keeps the backend's headers | Strip them when the host changes, or stop following redirects | #324's review |
| TD-162 | The ROI studio's readout can be 1 px off the ROI it saves | Show the size of the ROI that is saved | #324's review |
| TD-163 | A frame named exactly `Reference.png` collides with the reference in the upload archive | A format decision | #326 |
| TD-164 | Skipped nodes from old sweep backups read as strain-window failures | A nullable skip code, with a model and `metadata.json` change | #326 |
| TD-165 | Account deletion is lost if the process dies between the cloud erase and the phone wipe | Persist the stage; finish from a startup hook | #329 |
| TD-166 | A failed backup WorkManager still keeps is announced again by each new Home | A backup failure ledger | #329 |
| TD-167 | Two quota rules disagree on whether another analysis may start | A product decision, then one function | #329 |
| TD-168 | A wizard run that ends while the wizard is in the background loses its outcome | Replay or hold the outcome ([ADR-016](../adr/ADR-016-work-that-outlives-the-activity.md)) | #330 |
| TD-169 | Two strain-window defaults: 15 px for readers, 21 px in the wizard | Decide; changing it changes what old records show | #318, #328, #330 |
| TD-170 | Three ways to swap a dispatcher in tests | Constructor parameters; retire the mutable globals | the program's review |
| TD-171 | detekt does not check cancellation handling | Enable the rule with type resolution in CI | the program's review |
| TD-172 | The noise-floor files (871 lines) have no production caller | Wire them into import, or delete them | the program's review |
| TD-173 | The rest of `RunSpec`: the batch loop still reads its inputs from the view model | Freeze the inputs into `BatchRun` at Compute | the program's review |
| TD-174 | The periodic licence refresh is scheduled on some config paths only | One enqueue wherever the config is recorded | the program's review |
| TD-175 | No benchmark covers the deformed-frame heatmap | A micro case and a scrub run with photos | #328's review |

Also left on purpose, not as rows: the viewer's file-safe export name keeps
its 60-character cap rather than `SessionNaming`'s 40, because changing it
would rename existing exports (#328); `ListAdapter` was not adopted for the
session list and media grid, whose callers read the list right after `submit`
(#329). The Kover floor stays at 49 % (§1.7). Open program rows that nothing
in the program fixed were re-pointed to their new files rather than closed.

## 7. Benchmarks

All numbers are from one x86_64 emulator (`Semper_Smoke` AVD), so they are
only good for comparing builds with each other. The Pixel 6 runs of both
suites are still to do.

**ViewerScrubBenchmark** (macro, medians, one run per build, 2026-10-02). The
builds are the baseline (`ff0cfc3d`); `e4b40b96`, which adds the bug fixes, the
package move, the foundations and the net, restore, account and
analysis-parts work; and `eb95c5c5`, which adds the upload, wizard,
viewer/report and home/settings/auth work.

| Benchmark | Metric | Baseline | + first half | + second half |
|---|---|---:|---:|---:|
| 10 frames | `.dat` decodes | 4 | 4 | 4 |
| 10 frames | decode time, ms | 8.50 | 7.53 | 8.98 |
| 10 frames | frame CPU p50 / p90, ms | 24.1 / 43.9 | 21.6 / 29.5 | 22.1 / 43.6 |
| 10 frames | max heap, KB | 20,122 | 20,091 | 20,075 |
| 150 frames | `.dat` decodes | 7 | 7 | 7 |
| 150 frames | decode time, ms | 21.46 | 21.32 | 13.95 |
| 150 frames | frame CPU p50 / p90, ms | 24.8 / 44.5 | 21.0 / 44.8 | 25.3 / 29.5 |
| 150 frames | max heap, KB | 22,590 | 22,611 | 23,719 |
| 150 frames | anonymous RSS max, KB | 91,120 | 91,204 | 93,244 |

The decode counts are identical, so the scrub cache still decodes the same
frames. Frame times move both ways between runs, within emulator noise. The
150-frame heap is about 1.1 MB (5 %) higher after the second half, plausibly
the viewer's controller objects and the field-metrics cache that now lives in
the ViewModel; recheck it on the Pixel 6.

**HotPathMicroBenchmark** (micro), before and after the viewer and report
split (#328):

| Benchmark | Before, ms | After, ms | Allocations before / after |
|---|---:|---:|---|
| `buildReport_oneFrame` | 164.1 | 202.6 | 1,743 / 1,746 |
| `computeFieldExtrema_oneFrame` | 1.37 | 1.48 | 2.0 / 2.1 |
| `decodeDatFile_oneFrame` | 3.60 | 2.69 | 36.3 / 36.4 |
| `generateHeatmap_oneFrame` | 14.2 | 13.2 | 22.3 / 20.3 |
| `gifEncode_10frames` | 18.1 | 17.7 | 32 / 32 |
| `gifEncode_150frames` | 76.1 | 62.2 | 316 / 316 |
| `pointSpatialIndexBuild_oneFrame` | 140.1 | 83.5 | 286,744 / 286,743 |
| `profileAlong_threeComponents` | 3.00 | 2.39 | 1,492 / 1,492 |
| `valueRanges_oneFrame` | 6.86 | 5.28 | 30.2 / 30.2 |
| `valueRanges_150frames` | 1,152 | 793 | 4,528 / 4,527 |

Allocations are unchanged, which is the check that matters for a split that
must not touch a loop body. The faster "after" times come from machine load
(other builds ran during the "before" run), not from a speed-up. The
`buildReport` row was run three more times, alternating builds: medians 176
and 181 ms, inside the ±15 % spread between runs, and a mechanical diff
shows the loop bodies are identical.

The `.dat` and GIF oracle tests (byte-exact golden-output tests: they compare
the bytes the code writes with stored reference files) were left unedited by
the splits and pass on the #331 branch.

## 8. The PRs and their merge order

Merged: [#310](https://github.com/sempermechanics/semperdic-app/pull/310)
(test dependencies, ViewBinding on, the baseline) and
[#315](https://github.com/sempermechanics/semperdic-app/pull/315) (shared
test fixtures).

Open, in merge order: #311 → #312, #316 → #313 → #314 → #317 → #318–#321 →
#322 → #323–#326 → #327–#330 → #331 → this docs PR. A PR that mixes moves
with fixes merges with a merge commit, never a squash
([ADR-015](../adr/ADR-015-package-layout.md)). Each later PR was built on an
integration of the ones before it, so retarget it to `main` once those land.

| PR | What |
|---|---|
| [#311](https://github.com/sempermechanics/semperdic-app/pull/311) | Restore, cancellation-safe auth, account deletion (§3.1) |
| [#312](https://github.com/sempermechanics/semperdic-app/pull/312) | Upload integrity and cancellation (§3.2) |
| [#316](https://github.com/sempermechanics/semperdic-app/pull/316) | Home, settings and auth correctness (§3.5) |
| [#313](https://github.com/sempermechanics/semperdic-app/pull/313) | Wizard input state and video import (§3.3) |
| [#314](https://github.com/sempermechanics/semperdic-app/pull/314) | Viewer and report lifecycle (§3.4) |
| [#317](https://github.com/sempermechanics/semperdic-app/pull/317) | The first package move: `data/` and `ui/analysis/` into subpackages |
| [#318](https://github.com/sempermechanics/semperdic-app/pull/318) | Value types: ROI, image size, DIC parameters, run stops |
| [#319](https://github.com/sempermechanics/semperdic-app/pull/319) | The `ui/common` kit: toasts, dialogs, jobs, thumbnails, viewport math |
| [#320](https://github.com/sempermechanics/semperdic-app/pull/320) | Shared constants, dead code, stale suppressions |
| [#321](https://github.com/sempermechanics/semperdic-app/pull/321) | Data-layer primitives: atomic writes, zips, prefs, layouts, work, auth, metadata doc |
| [#322](https://github.com/sempermechanics/semperdic-app/pull/322) | Docs: fused hot loops stay whole; their files may be split |
| [#323](https://github.com/sempermechanics/semperdic-app/pull/323) | Account and prefs: typed pref keys, one seat call, `AuthRepository` split |
| [#324](https://github.com/sempermechanics/semperdic-app/pull/324) | Net: request helpers, paging and expired-link fixes, `SemperApi` / `DriveTransfer` split |
| [#325](https://github.com/sempermechanics/semperdic-app/pull/325) | Analysis parts: ViewBinding, ROI and plot kit, four files split |
| [#326](https://github.com/sempermechanics/semperdic-app/pull/326) | Restore and session: metadata reader, three restore fixes, `CloudRestore` / `SessionStore` split |
| [#327](https://github.com/sempermechanics/semperdic-app/pull/327) | Upload and sync: the four-step upload worker, three fixes, `CloudSync` split |
| [#328](https://github.com/sempermechanics/semperdic-app/pull/328) | Viewer and report: controllers, colour-bar and bitmap-leak fixes |
| [#329](https://github.com/sempermechanics/semperdic-app/pull/329) | Home, settings and auth: ViewBinding, `TransferWorkObserver`, three fixes, splits |
| [#330](https://github.com/sempermechanics/semperdic-app/pull/330) | The wizard: ViewBinding, value types, one host interface, four fixes, splits |
| [#331](https://github.com/sempermechanics/semperdic-app/pull/331) | The second package move, cross-area fixes, the suppression sweep |

Still owed before release: the emulator passes listed in each PR's test plan,
the Pixel 6 benchmark runs, and ADR-015's upgrade check (queued work from the
previous APK must still run after installing this one).
