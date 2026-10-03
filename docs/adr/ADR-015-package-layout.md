# ADR-015: Feature subpackages, about 15 files each; pinned names stay put

**Status:** Accepted, built. Amended 2026-10-03 for the layout after the quality
program's second move (#331): see [Amendments](#amendments)
**Date:** 2026-10-01
**Deciders:** app owner

## Context

By 2026-10 the app's Kotlin sources had grown into two flat packages. `data/`
held 53 files: the WorkManager workers, the session store, cloud upload and
restore, auth and licensing, and preferences. `ui/analysis/` held 55: the
wizard, the batch runner, frame import, the ROI studio, subset recommendation,
the VSG sweep, and the video decoders. Nine more files sat in the root
package. With no structure below the package, finding the owner of a change
meant reading file names, and nothing showed which files belong together.

Kotlin has no package-private visibility. Moving a file between packages
therefore changes only name resolution, never access. Some names cannot move,
though, because something outside the compiler records them:

- **WorkManager** stores each queued job's worker class by fully qualified
  name in its database. A renamed worker leaves every job queued before an
  upgrade pointing at a class that no longer exists. Those jobs fail with
  `ClassNotFoundException`, and uploads, restores and deletes are lost.
- **JNI** binds `SemperNativeLib`'s natives by symbol name
  (`Java_com_sempermechanics_semper_SemperNativeLib_*` in the engine's
  `adapters/android/jni/SemperJNI.cpp`). The engine calls
  `ProgressCallback.onProgressUpdate` through `GetMethodID`, and the R8 keep
  rules in `app/proguard-rules.pro` name both classes.
- **Activities and the Application** are named in `AndroidManifest.xml`
  (`AuthActivity` carries the App Links), the benchmark build type's
  manifest, and the `benchmark/` module's string constants.
  `TermsActivity` also hands the next Activity on as a class name in an
  Intent extra.

## Decision

Split by feature into subpackages of about 15 files or fewer. Leave every
pinned name where it is.

The table is the layout after the program's second move (#331, 2026-10-03).
File counts are main sources only; the first move's table is in this file's history.

| Package | Files | Holds |
|---|---|---|
| *(root)* | 2 | `SemperApp`, `SemperNativeLib` + `ProgressCallback` (pinned) |
| `field/` | 12 | The decoded field (`DicResult`, `DatDecoder`, `FieldHistogram`) and the value types every layer shares: `Roi` (+ `RoiCodecs`, `RoiRects`), `ImageSize`, `DicParams`, `FrameParams`, `RunStop`, `ValueRange`, `FieldStats` |
| `diagnostics/` | 4 | `Diagnostics`, `CrashReportingTree`, `EngineDebug`, `SemperAnalytics` (the old `analytics/` package) |
| `navigation/` | 2 | `AppIntents`, `DicKeys` |
| `data/` | 11 | The six workers (pinned): `BackupDeleteWorker`, `DicBundleDownloadWorker`, `DicRestoreWorker`, `DicUploadWorker`, `LicenseConfigWorker`, `SessionMetadataWorker`. Beside `DicUploadWorker`, the steps it was split into: `UploadStaging`, `UploadSessionPlanner`, `UploadRun`, `UploadFailures`, `UploadTuning` |
| `data/session/` | 17 | Local sessions: `SessionStore`, `SessionRecord` (+ `SessionRecordFields`), `SessionPaths`, `SessionLayout`, `SessionNaming`, `SessionRepository`, `SessionHeadline`, `SessionQuotaGate`, `SessionZip`, `ZipDirectory`, `DatCodec`, `LocalArtifacts`, `SkippedNode`, `StorageBudget`, `CacheJanitor`, `SessionEverythingExporter` |
| `data/cloud/` | 18 | Backup and sync: `CloudSync`, `CloudErase`, `CloudReconcile`, `CloudBackupListing`, `CloudAccountExport`, `SessionDeletes`, `SessionMetadataSync`, `SessionMetadataDoc`, `SessionUploadBundler`, `SessionUploadMetadata`, `UploadWorkOutcomes`, `UploadProgressSampler`, `UploadErrors`, `TransferWork`, `WorkTags`, `TransferLog`, `TransferNotifications`, `CorruptTransferException` |
| `data/cloud/restore/` | 10 | `CloudRestore`, `RestoreBundleFetcher`, `RestoreUnpacker`, `RestoreZipVerifier`, `RestoreStart` (+ `RestoreFailureLedger`), `RestoreDownloadOutcomes`, `DownloadFailure`, `DownloadProgress`, `SafDestination`, `UnrestorableBackupException` |
| `data/account/` | 15 | `AuthRepository`, `AuthLinks`, `AccessStatus`, `AccessStatusResolver`, `FirebaseOp`, `ReauthCredentials`, `DevAuth`, `DeviceEnv`, `DeviceKeyManager`, `LicenseEntitlements`, `LicenseErrors`, `SeatLease`, `SeatHeartbeat`, `LegalTerms`, `TotpMfa` |
| `data/prefs/` | 6 | `DicSettings`, `CoachPrefs`, `ParamClipboard`, `WizardDraft`, `PrefKey`, `PrefFiles` |
| `data/net/` | 23 | The backend client: `SemperApi` (+ `SemperApiCalls`, `SemperApiClients`, `SemperApiHttp`, `SemperApiSigning`, `Paging`, `ApiHost`), `CloudApi`, `Authed`, `HttpFailure`, `HttpStatus`, `ApiDtos`, `ApiErrors`, `ApiExceptions`, the interceptors (`RetryOnTransient`, `AppCheckHeader`, `AppIdHeader`, `ClientNonce`), `AppRemoteConfig`, `ArtifactRoles`, `SingleFlight`, `TokenProvider`, `TokenStore` |
| `data/net/drive/` | 5 | Drive's resumable transfers: `DriveTransfer`, `DriveUploader`, `DriveDownloader`, `DriveDownload`, `DriveUpload` |
| `imaging/` | 9 | Decoders and encoders (`BitmapDecoder`, `ImageEncoder`, `AviReader`, `AviLuma`, `MjpegHuffman`, `LumaRange`, …) |
| `imaging/video/` | 7 | `VideoFrameExtractor`, `FrameSink` (was `VideoFrameBatchWriter`), `VideoKeyframeHelper`, `HardwareVideoDecoder`, `AviCodecDecoder`, `AviVideoDecoder`, `ImageLuma` |
| `report/` | 22 | PDF, CSV, GIF and heatmaps; `VisualizationEngine` is a facade over `HeatmapColorScale`, `HeatmapRenderer` and `DeformedHeatmap` |
| `ui/analysis/` | 3 | The three Activities only (pinned): `StaticAnalysisActivity`, `RoiDrawActivity`, `VsgLatticeActivity` |
| `ui/analysis/wizard/` | 22 | `AnalysisViewModel` (+ `AnalysisModels`, `RunChannels`, `SweepRunner`, `WizardDraftBinding`), `WizardState`, `WizardStep`, the host interface `AnalysisWizardHost`, chrome / slots / coach, nav, ready and cancel gates, the settings sheet, `WizardParamFields` (+ `ParamSliders`), `AnalysisLeaveController`, `WizardStepSettingsContentView` |
| `ui/analysis/run/` | 16 | `DicBatchRunner` (+ `BatchRun`, `UnsavedRerun`, `RunRecordSave`), `DicFieldIo`, `SemperEngine`, `BatchRunController`, `RunChrome`, `ComputeOverlayController`, `ConvergenceGate`, `EngineFailure`, `RunSpec`, `RunSummaryText`, `RunStatusLine`, `WizardRunLauncher`, `WizardRunOutcomes` |
| `ui/analysis/frames/` | 12 | Frame and reference import (`ReferenceImportController`, `FrameImportController`, `FrameImportHelper`, `WizardMediaPickers`), ordering (`FrameOrderController`, `FrameOrderAdapter`, `FrameOrderHelper`, `AnalysisFrameOrderMenuHelper`), `DeformedFrame`, the deformed-batch and video-extract controllers, `VideoSamplingSheet` |
| `ui/analysis/roi/` | 7 | `StudioOverlayView` (+ `StudioOverlayGeometry`, `StudioOverlayViewport`), `StudioOverlayMaskEncoder`, `RoiViewport`, `RoiResolveHelper`, `RoiStudioLauncher` |
| `ui/analysis/recommend/` | 9 | `SubsetRecommender`, `SubsetRecommendationController`, `SpeckleScale`, `DicGoodPractice`, `StrainWindowText`, `NoiseFloorPixels`, `NoiseFloorProbe`, `NoiseFloorStats`, `ExifPatchMap` |
| `ui/analysis/sweep/` | 17 | Setup (`SweepSetupController`, `SweepRangeFields`, `SweepFramePicker`, `SweepRanges`), `VsgStudy` / `VsgStudyRunner`, the lattice (`VsgLatticeView`, `LatticeControls`, `LatticeProfiles`, `LatticeGraphExport`), the plot (`VsgPlotView`, `VsgPlotAxes`, `VsgPlotViewport`, `VsgPlotPalette`, `PlotStyle`), `LineCutPreviewView`, `SweepPointConversions` |
| `ui/viewer/` | 19 | `ResultViewerActivity` and its controllers (`ViewerFrameLoader`, `ViewerScaleController`, `ViewerImageLoader`, `FrameJumpController`, `ViewerShareController`, `ViewerChromeController`, `ViewerCaptions`, `FieldPopup`), `SaveExportActivity`, both ViewModels, `ViewerArgs`, `ScrubFrameCache`, `ViewerFieldPills`, `ViewerSettingsSheet`, `ColorScaleBar`, `CustomScalePrefill`, `HeatmapFit` |
| `ui/viewer/share/` | 11 | `ShareCenter`, `ShareKind`, `ShareExportBuilder` (+ `FieldImageExport`, `BundleExport`, `DataExport`), `ShareExportJobs`, `ShareExportUi`, `SendToSheet`, `ViewerReportFactory`, `ViewerReportSource` |
| `ui/viewer/summary/` | 3 | `SummaryAnimation`, `SummaryCaption`, `ViewerSummaryController` |
| `ui/viewer/inspect/` | 5 | `InspectOverlayView`, `ViewerInspectController`, `PointSpatialIndex`, `FieldHistogramView`, `TouchImageView` |
| `ui/common/` | 13 | Small helpers with no better home: `SerialJob`, `ConflatedRefresh`, `Busy`, `Insets`, `ImeReveal`, `Keyboard`, `ToggleGroups`, `Motion`, `Dp`, `ViewportMath`, `SettingsSectionHeader`, `CoachMarkController`, `ByteSize` |
| `ui/common/dialog/` | 8 | `Dialogs`, `Feedback`, `CrispToast`, `WarnChip`, `Sheet`, `FaqRedirect`, `DeleteChoiceDialog`, `DeterminateProgressDialog` |
| `ui/common/auth/` | 6 | `AuthRoute`, `SignOutRun`, `SignOutConfirm`, `SupportMail`, `SupportMailContext`, `ExternalLinks` |
| `ui/common/media/` | 6 | `MediaPickerSheet`, `MediaGridAdapter`, `MediaSourceChooser`, `MediaStoreBrowser`, `ThumbnailLoader`, `SquareFrameLayout` |
| `ui/common/transfer/` | 4 | `TransferWorkObserver`, `TransferBannerController`, `DeleteFeedback`, `RestoreFailureNotice` |
| `ui/home/`, `ui/settings/`, `ui/auth/`, `ui/admin/`, `ui/limit/` | 11, 15, 11, 1, 2 | One screen each, plus the parts each Activity was split into (`Home*`, `Settings*Section`, `Auth*`) |
| `util/` | 12 | `AtomicFiles`, `Streams`, `Zips`, `Digests`, `Mime`, `CallerCancellation`, `SuspendRunCatching`, … |

Eight packages are still over the ~15-file guide: `data/net/` (23),
`report/` (22), `ui/analysis/wizard/` (22), `ui/viewer/` (19),
`data/cloud/` (18), `data/session/` (17), `ui/analysis/sweep/` (17) and
`ui/analysis/run/` (16). They are left for later feature splits; none of them
mixes features the way the flat packages did.

**Tests mirror the main packages.** A test of a single moved class moves with
it into the same package, so a `@VisibleForTesting` or `internal` seam keeps
reading as same-package. Older suites that are grouped by journey
(`analysis/`, `cloud/`, `results/`, `session/`, `settings/`, `viewer/`) and
cover several classes keep their packages and import what they test.

**A new file** goes in the feature subpackage it belongs to. When a package
passes about 15 files, split it by feature in its own pure-move PR.

**File size: about 500 lines.** A file that grows past about 500 lines is
split. Fused hot loops are exempt: the function that holds one keeps its body
whole however long it is, and only the code around it moves out. They are
(CONTEXT.md § Invariants):

| Hot loop | Function | Lines (2026-10-03) |
|---|---|---|
| The batch JNI loop | `DicBatchRunner.runBatchAnalysisBody` (`ui/analysis/run/DicBatchRunner.kt:51`) | 354 |
| The ReportBuilder fusion pass | `ReportBuilder.buildReport` (`report/ReportBuilder.kt:133`) | 180 |
| The heatmap pixel loops | `HeatmapRenderer.generateHeatmapIndices` (`report/HeatmapRenderer.kt:59`), `DeformedHeatmap.generateDeformedHeatmapIndices` (`report/DeformedHeatmap.kt:40`) | 119, 78 |
| GIF LZW | `GifEncoder`'s `compress` (`report/GifEncoder.kt:184`) | |
| `.dat` decode | `DatDecoder.decodeInto` (`field/DatDecoder.kt:32`), behind `DicResult.decodeDatFile` | |
| Viewer look-ahead | `ViewerFrameLoader.prefetchAround` (`ui/viewer/ViewerFrameLoader.kt:148`) | |
| Inspect index | `PointSpatialIndex.build` (`ui/viewer/inspect/PointSpatialIndex.kt:50`) | |

After #331 no main-source file is over 500 lines; the largest is
`ui/analysis/recommend/SubsetRecommender.kt` at 497.

**The split rule.** A split is a pure move plus the least visibility change
that compiles (`private` → `internal`). The new file goes in the same package
as the file it came from, unless a feature subpackage for it already exists,
as `data/net/drive/` does for Drive code. This is why `data/` holds the upload
worker's steps next to the worker, and the wizard's parts started in
`ui/analysis/` before #331 moved them into its subpackages. A later move of the
new files is its own pure-move commit.

## Options considered

### A: Feature subpackages, pinned names in place (chosen)

Each split moves files and changes only `package` and `import` lines, so it
reviews as renames (`git diff -M --stat`). Workers, JNI classes and
Activities keep their names, so nothing persisted or bound by name changes.

### B: Also move the workers and Activities, with compatibility shims

Moving a worker needs a forwarding class under the old name for at least one
release, plus a WorkManager `WorkerFactory` that maps old names to new.
Moving an Activity breaks pinned shortcuts and App Link verification, and
needs `activity-alias` entries. That is a lot of machinery for the benefit
of seeing `DicUploadWorker` next to `CloudSync`.

### C: Leave the packages flat

This costs nothing now. But both packages keep growing, and the fork adds
its lab files to the same flat directories.

## Trade-off analysis

A leaves `data/` with only the workers (six files at first; 11 once #327
put the backup's steps beside `DicUploadWorker`), which reads oddly but
states the constraint. B's shims would sit in the code forever: nobody can prove that
no phone still has a job queued under an old name. Moves touch every import
block once, so open branches will conflict. Git's rename detection carries
edits to moved files across a merge, but a new file in an old package needs
imports by hand. `scripts/move_kotlin_packages.py` makes that repeatable.

## Consequences

- `scripts/move_kotlin_packages.py` and `scripts/package_moves_2026_10.json`
  record the move; `scripts/package_moves_2026_10_wave5.json` records the
  second one (#331). The material_testing fork replays both, in that order,
  after merging this (see [FORK_SYNC](../ops/FORK_SYNC.md)). A mapping moves
  files and rewrites imports only: an API a PR renamed or deleted alongside
  its moves still needs fixing by hand in the fork, and FORK_SYNC lists them.
- **A PR that mixes moves with fixes merges with a merge commit, never a
  squash.** Its pure-move commits (a rename plus `package`/`import` lines,
  each checked by `git diff -M`) are what the fork can review and replay; a
  squash folds them into the fixes and loses that.
- `@Serializable` types that moved (`SessionRecord`, `SkippedNode`,
  `WizardState.Frames`) are plain classes, not polymorphic ones. Their JSON
  never carries a class name, so `index.json`, metadata and the wizard draft
  read the same before and after.
- Layout XML names moved custom views by FQCN. ViewBinding compiles against
  those names, so a missed reference fails the build, not the app.
- Kover's view-class filters (`com.sempermechanics.semper.ui.*Activity*` etc.)
  match nested packages too, so a move alone does not change what coverage
  measures. Splitting an Activity does: its controllers are no longer
  `*Activity*` classes, so their lines join the measured set
  (`app/build.gradle.kts:371-387`).

## Action items

1. [x] Move the files and tests; rewrite the imports, layouts and docs.
2. [ ] Upgrade check on a device before release: install the previous APK,
   queue an upload, a restore and a delete, install this build over it, and
   confirm the queued work still runs.
3. [ ] material_testing: replay both mappings after the next sync and move its
   lab-only files in `data/` and `ui/viewer/` into matching subpackages.
4. [x] Second move (#331): `ui/common/` into `dialog/`, `auth/`, `media/`
   and `transfer/`; Drive code into `data/net/drive/`; the wizard's parts out
   of the `ui/analysis/` root into its subpackages.

## Amendments

**2026-10-03, after the quality program (#317–#331).**

- **The layout table is current, not as first built.** The PRs that split
  the large files (#323–#330) added files in the same package as the file they
  came from, and #331 then moved them into feature subpackages. The first
  table said `data/` held only the six workers, `data/net/` was unchanged and
  `ui/common/` (20) and `data/net/` (16) were left for later; all three are
  out of date.
- **`data/` holds more than the workers.** `DicUploadWorker.doWork` became
  four named steps (#327), and the split rule kept them beside the worker.
  Moving them to `data/cloud/` is allowed (they are not pinned), but not done.
- **Added: the file-size target and the split rule** (Decision), which those
  PRs followed but this record did not state.
- **Added: the merge-commit rule** (Consequences).

**2026-10-03, naming scheme.** The table uses the names after the naming
cleanup (the rules are in CONTRIBUTING's "Code style"): the stateful
`*Helper` classes are `*Controller`s, the `*Ext.kt` files are named for
their content, and `DriveDownload` has its own file. Renames are not moves,
so no mapping replays them; [FORK_SYNC](../ops/FORK_SYNC.md) lists each one.
