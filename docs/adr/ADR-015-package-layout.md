# ADR-015: Feature subpackages, about 15 files each; pinned names stay put

**Status:** Accepted, built
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
  (`Java_com_indicvision_semper_SemperNativeLib_*` in the engine's
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

| Package | Holds |
|---|---|
| *(root)* | `SemperApp`, `SemperNativeLib` + `ProgressCallback` (pinned) |
| `field/` | `DicResult`, `DatDecoder`, `FieldHistogram` |
| `diagnostics/` | `Diagnostics`, `CrashReportingTree`, `EngineDebug`, `SemperAnalytics` (the old `analytics/` package) |
| `navigation/` | `AppIntents`, `DicKeys` |
| `data/` | The six workers only (pinned): `BackupDeleteWorker`, `DicBundleDownloadWorker`, `DicRestoreWorker`, `DicUploadWorker`, `LicenseConfigWorker`, `SessionMetadataWorker` |
| `data/session/` | Local sessions: `SessionStore` (with `SessionRecord`), `SessionPaths`, `SessionRepository`, `SessionHeadline`, `SessionQuotaGate`, `SessionZip`, `ZipDirectory`, `DatCodec`, `LocalArtifacts`, `SkippedNode`, `StorageBudget`, `CacheJanitor`, `SessionEverythingExporter` |
| `data/cloud/` | Backup and sync: `CloudSync`, `CloudBackupListing`, `CloudAccountExport`, `SessionDeletes`, `SessionMetadataSync`, `SessionUploadBundler`, `SessionUploadMetadata`, `UploadWorkOutcomes`, `UploadProgressSampler`, `UploadErrors`, `TransferLog`, `TransferNotifications`, `CorruptTransferException` |
| `data/cloud/restore/` | `CloudRestore`, `RestoreStart`, `RestoreDownloadOutcomes`, `DownloadProgress`, `UnrestorableBackupException` |
| `data/account/` | `AuthRepository`, `AccessStatus`, `DevAuth`, `DeviceEnv`, `DeviceKeyManager`, `LicenseEntitlements`, `LicenseErrors`, `SeatLease`, `SeatHeartbeat`, `LegalTerms`, `TotpMfa` |
| `data/prefs/` | `DicSettings`, `CoachPrefs`, `ParamClipboard`, `WizardDraft` |
| `data/net/` | Unchanged |
| `imaging/video/` | `VideoFrameExtractor`, `VideoFrameBatchWriter`, `VideoKeyframeHelper`, `HardwareVideoDecoder`, `AviCodecDecoder`, `AviVideoDecoder`, `ImageLuma` (all previously in `ui/analysis/`) |
| `ui/analysis/` | The three Activities only (pinned): `StaticAnalysisActivity`, `RoiDrawActivity`, `VsgLatticeActivity` |
| `ui/analysis/wizard/` | `AnalysisViewModel`, `WizardState`, `AnalysisWizardChrome`, `AnalysisWizardCoach`, `AnalysisWizardSlots`, `AnalysisNavHelper`, `AnalysisReadyGate`, `AnalysisCancelGate`, `AnalysisSettingsSheetHelper`, `WizardStepSettingsContentView`, `ReferencePreviewLoader`, `LossyFormatCheck` |
| `ui/analysis/run/` | `DicBatchRunner`, `DicFieldIo`, `BatchRunController`, `ComputeOverlayHelper`, `ConvergenceGate`, `EngineFailure`, `AnalysisRunCodes`, `RunSpec`, `RunSummaryText` |
| `ui/analysis/frames/` | `FrameImportHelper`, `FrameOrderAdapter`, `FrameOrderHelper`, `AnalysisFrameOrderMenuHelper`, `AnalysisDeformedBatchHelper`, `AnalysisVideoExtractHelper` |
| `ui/analysis/roi/` | `StudioOverlayView`, `StudioOverlayMaskEncoder`, `RoiViewport`, `RoiResolveHelper` |
| `ui/analysis/recommend/` | `SubsetRecommender`, `SpeckleScale`, `DicGoodPractice`, `StrainWindowText`, `NoiseFloorPixels`, `NoiseFloorProbe`, `NoiseFloorStats`, `ExifPatchMap` |
| `ui/analysis/sweep/` | `SweepSetupHelper`, `VsgStudy`, `VsgStudyRunner`, `VsgLatticeView`, `VsgPlotView`, `LineCutPreviewView` |
| `ui/viewer/` | The two Activities (pinned) and what they share: `ResultViewerActivity`, `SaveExportActivity`, `ResultViewerViewModel`, `SaveExportViewModel`, `ViewerArgs`, `ScrubFrameCache`, `ViewerFieldPills`, `ViewerSettingsSheet`, `CustomScalePrefill`, `HeatmapFit` |
| `ui/viewer/share/` | `ShareCenter`, `ShareExportBuilder`, `ShareExportJobs`, `ShareExportUi`, `SendToSheet`, `ViewerReportFactory` |
| `ui/viewer/summary/` | `SummaryAnimation`, `SummaryCaption`, `ViewerSummaryHelper` |
| `ui/viewer/inspect/` | `InspectOverlayView`, `ViewerInspectHelper`, `PointSpatialIndex`, `FieldHistogramView`, `TouchImageView` |
| `util/` | Adds `CallerCancellation` (previously in `data/`) |

`imaging/`, `report/`, `ui/home`, `ui/settings`, `ui/auth`, `ui/common`,
`ui/admin` and `ui/limit` are unchanged. The largest new packages are
`data/session/` and `data/cloud/` (13 files each). Two untouched packages
are over the target, `ui/common/` (20) and `data/net/` (16); they are left
for a later split.

**Tests mirror the main packages.** A test of a single moved class moves with
it into the same package, so a `@VisibleForTesting` or `internal` seam keeps
reading as same-package. Older suites that are grouped by journey
(`analysis/`, `cloud/`, `results/`, `session/`, `settings/`, `viewer/`) and
cover several classes keep their packages and import what they test.

**A new file** goes in the feature subpackage it belongs to. When a package
passes about 15 files, split it by feature in its own pure-move PR.

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

A leaves `data/` with just six workers, which reads oddly but states the
constraint. B's shims would sit in the code forever: nobody can prove that
no phone still has a job queued under an old name. Moves touch every import
block once, so open branches will conflict. Git's rename detection carries
edits to moved files across a merge, but a new file in an old package needs
imports by hand. `scripts/move_kotlin_packages.py` makes that repeatable.

## Consequences

- `scripts/move_kotlin_packages.py` and `scripts/package_moves_2026_10.json`
  record the move. The material_testing fork replays them after merging this
  (see [FORK_SYNC](../ops/FORK_SYNC.md)).
- `@Serializable` types that moved (`SessionRecord`, `SkippedNode`,
  `WizardState.Frames`) are plain classes, not polymorphic ones. Their JSON
  never carries a class name, so `index.json`, metadata and the wizard draft
  read the same before and after.
- Layout XML names moved custom views by FQCN. ViewBinding compiles against
  those names, so a missed reference fails the build, not the app.
- Kover's view-class filters (`com.indicvision.semper.ui.*Activity*` etc.)
  match nested packages too, so coverage is measured the same way.

## Action items

1. [x] Move the files and tests; rewrite the imports, layouts and docs.
2. [ ] Upgrade check on a device before release: install the previous APK,
   queue an upload, a restore and a delete, install this build over it, and
   confirm the queued work still runs.
3. [ ] material_testing: replay the mapping after the next sync and move its
   lab-only files in `data/` and `ui/viewer/` into matching subpackages.
