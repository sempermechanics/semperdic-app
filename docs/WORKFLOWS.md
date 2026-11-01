# Workflow index — every flow, and the files behind it

One map for the whole product: the app's screens, the work that keeps running
after you leave a screen, and every backend request. The engine is deliberately
absent — it lives in the `engine/` submodule and has its own docs
([engine/ARCHITECTURE.md](engine/ARCHITECTURE.md),
[engine/ENGINE_APP_CONTRACT.md](engine/ENGINE_APP_CONTRACT.md)).

**What this file is for: backtracking.** Given a symptom — a wrong number, a
message nobody can find in the source, a backup that failed silently — it names
the entry point, the files in the chain, what got written, where the failure
surfaces and which test already pins the behaviour. Start at
[§E Backtracking](#e-backtracking) if you have a symptom; read a section
top-to-bottom if you are learning an area.

| You want to… | Go to |
|---|---|
| Walk the UI as a user, or run a manual test pass | [app/WORKFLOWS.md](app/WORKFLOWS.md) |
| Know which file to edit for a screen | [app/ARCHITECTURE.md](app/ARCHITECTURE.md) |
| Run or extend the automated suite | [app/TESTING.md](app/TESTING.md) |
| Understand the cloud design | [backend/CLOUD_ARCHITECTURE_GCP.md](backend/CLOUD_ARCHITECTURE_GCP.md) |
| See what we plan to change about all this | [ops/FUTURE_IMPROVEMENTS.md](ops/FUTURE_IMPROVEMENTS.md) |

## Ids

Ids are stable — quote them in commits, issues and code comments.

| Prefix | Kind | Numbering |
|---|---|---|
| `A0`–`A9` | App, user-facing | Matches §0–§9 of [app/WORKFLOWS.md](app/WORKFLOWS.md) |
| `B1`–`B15` | App, background / data | This file only |
| `C1`–`C20` | Backend request flows | This file only |
| `D1`–`D7` | Ops / CI | This file only |

Paths are relative to the repo root. App classes are shown short — every one is
under `app/src/main/java/com/sempermechanics/semper/`. Backend modules are under
`backend/`.

## Two rules this map exists to protect

**Accuracy.** Numbers on screen must be the numbers the engine produced, for the
settings the ⓘ sheet claims. Every workflow that copies, packs or re-reads field
data (`A5`, `A8`, `B1`, `B2`, `B3`) names where the bytes are written and which
oracle test pins them.

**Privacy.** Analysis is on-device; the cloud is optional. Nothing leaves the
phone except through `B1`/`B7`/`B8`, and telemetry only through `B10`, which is
off until consent. The workflows marked 🔒 touch identity, consent or erasure —
change them deliberately, and re-read
[legal/PRIVACY_POLICY.md](legal/PRIVACY_POLICY.md) when you do.

---

## A. App — user-facing workflows

Twelve activities, no NavHost and no Fragments; sub-flows are wizard pages,
bottom sheets and dialogs inside one Activity. The tick-box walkthrough of each
of these is [app/WORKFLOWS.md](app/WORKFLOWS.md).

### A0 App launch 🔒

```
SplashActivity ─ session restore ─┬─ no session ─────────→ A1 Login
                                  ├─ cached APPROVED ────→ A3 Home, re-checked in the background
                                  ├─ PENDING ────────────→ A2 Pending approval
                                  ├─ APPROVED / offline ─→ A3 Home
                                  └─ [debug] dev bypass ─→ A3 Home, cloud off
```

| Field | Value |
|---|---|
| Entry | `ui/auth/SplashActivity` |
| Chain | `data/account/AuthRepository` → `data/net/SemperApi.me` (with `getConfig` in parallel, fetched again once if its `mode` disagrees with `me.license.mode`, as when the invite claim landed between the two) → `ui/auth/AccessRouter` (+ `data/account/AccessStatus`), `data/account/DevAuth` for the emulator bypass. A device approved and bound last time opens Home without waiting; `ui/auth/StatusRecheck` runs the same check behind it and moves the user only on PENDING or a refused sign-in (`AuthRepository.AccessLostException`), never on a timeout or 5xx |
| Writes | `data/net/AccountCache` cached uid / email / status / role |
| Fails as | Routing error passed on as `IntentKeys.ROUTING_ERROR` with the process's `ROUTING_TOKEN` (`AuthRoute.putMessage`), shown by A1 as a red pill; a message without the token (another app's start) is ignored |
| Tests | `auth/AccessRouterTest`, `auth/StatusRecheckTest` |

The quota check is **not** here — it runs in `HomeActivity.onCreate` (A3), which
is why a capped account still lands on Home first.

### A1 Login 🔒

One screen, four credential paths plus two deep links.

```
AuthActivity ─┬─ Google SSO ............ ui/auth/GoogleSignInHelper
              ├─ email + password ...... data/account/AuthRepository
              ├─ create account ........ + ui/auth/PasswordPolicy
              ├─ email sign-in link .... /finishSignIn App Link
              └─ forgot password ....... /finishReset App Link → in-app reset form
```

| Field | Value |
|---|---|
| Entry | `ui/auth/AuthActivity` |
| Chain | `data/account/AuthRepository` (Firebase Auth) → `data/net/SemperApi.me` / `registerDevice` → `ui/auth/AccessRouter` |
| Writes | Firebase session; `AccountCache` identity; device registration flag |
| Fails as | `ui/common/dialog/CrispToast` pill; policy failures inline from `PasswordPolicy` |
| Tests | `auth/PasswordPolicyTest`, `auth/ReauthFlowTest`, `auth/FirebaseAuthIntegrationTest` (instrumented) |

Email verification is enforced for password accounts only; Google and email-link
users arrive verified. The App Links depend on Digital Asset Links being
verified at the Firebase host — see [backend/AUTH_SETUP.md](backend/AUTH_SETUP.md).

### A2 Pending approval 🔒

| Field | Value |
|---|---|
| Entry | `ui/auth/PendingApprovalActivity` |
| Chain | `data/account/AuthRepository` + `data/account/DeviceKeys` (device id shown) → `SemperApi.me` on **Check status** |
| Server side | The PENDING account was mailed to support at creation by `C1` → `backend/app/notify.py` |
| Note | It does not poll, despite the KDoc; only the button checks |

### A3 Home

```
HomeActivity ─┬─ beta notice + diagnostics prompt (first run) ......... B10
              ├─ session list ...... ui/home/SessionListAdapter → ui/home/SessionOpenHelper → A7 / A8
              ├─ selection mode .... ui/home/SessionSelectionController → B4 / B12
              ├─ pull to refresh ... data/cloud/CloudSync.reconcile(deep = true) ....... B7
              ├─ row state icons ... WorkInfo from B1 / B2
              ├─ quota chip ........ data/net/AccountCache + AppRemoteConfig → A9 (shown from 80% of the cap)
              └─ FAB ............... ui/common/media/MediaPickerSheet (A3a) → A5
```

| Field | Value |
|---|---|
| Entry | `ui/home/HomeActivity` |
| Chain | `data/session/SessionStore.list` (local index) ⋈ `data/cloud/CloudSync.reconcile` (cloud) |
| Writes | Session index (rename, delete, sync state), coach-mark flags in `data/prefs/CoachPrefs` |
| Fails as | Message pill + a "why + retry" dialog on the row's cloud icon, fed by the worker's `IntentKeys.UPLOAD_FAIL_REASON` / `DicRestoreWorker.KEY_ERROR` |
| Tests | `session/SessionStoreAtomicTest`, `cloud/QuotaGateTest` |

### A3a New-analysis media picker

| Field | Value |
|---|---|
| Entry | `ui/common/media/MediaPickerSheet` (shared by the Home FAB and both wizard dropzones) |
| Chain | `ui/common/media/MediaStoreBrowser` + `MediaGridAdapter` for the Images tab; `ui/common/media/MediaSourceChooser` for the Files (SAF) tab |
| Permissions | `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`, asked only when the Images tab needs them |
| Tests | `ui/common/media/MediaStoreBrowserTest`, `ui/common/media/MediaPickerSheetTest` |

### A4 Settings 🔒

Seven collapsible sections; each is its own file, and only restore / download /
delete stay on the Activity.

| Section | File | Workflow |
|---|---|---|
| Account (+ admin) | `ui/settings/SettingsAccountSection` | → A4.1 |
| Cloud backup | `ui/settings/SettingsCloudSection` | B1 |
| Analyses data management | `ui/settings/SettingsAnalysesSection` + `AnalysisEntries` / `AnalysisDataAdapter`; open, restore, download and delete on `SettingsActivity` | B2, B3, B4 |
| Storage | `ui/settings/SettingsStorageSection` | B5, B6 |
| Your data 🔒 | `ui/settings/SettingsYourDataSection` | B8, B9, B10 |
| Analysis preferences | `ui/settings/SettingsPreferencesSection` | `data/prefs/AppSettings` + `data/net/AppRemoteConfig` |
| Help & support | `ui/settings/SettingsHelpSupportSection` | mailto / hosted pages |

Long jobs are non-modal: `ui/common/transfer/TransferBannerController` carries restores,
bundle downloads and both exports. Each page shows its status, the percent to one
decimal and, once `EtaEstimator` has an answer, "About N s left"; a restore or download
reads "4.2 of 12.0 MB · 1.1 MB/s" from its worker's progress Data (`TransferBytes`).
Terminal restore failures are observed here as well as on Home.

Tests: `settings/AnalysisEntriesTest`, `settings/HelpSupportSectionTest`,
`settings/DeleteAccountReauthTest`, `settings/AppSettingsMigrateTest`,
`settings/TransferBannerControllerTest`.

### A4.1 Admin `[admin]` 🔒

`ui/admin/AdminActivity` → `SemperApi.listUsers` / `setUserStatus` → `C14`.
Visible only when `AccountCache.isAdmin` (backend-reported role).

### A5 Analysis wizard

One Activity, three pages (`WizardStep`); pages 2 and 3 inflate from ViewStubs.
`StaticAnalysisActivity.goToStep(WizardStep)` hands the page change to
`AnalysisWizardChrome.applyStep`; the steps' parts live in `ui/analysis/frames`,
`roi`, `recommend`, `run`, `sweep` and `wizard`.

| Id | Step | Entry | Chain |
|---|---|---|---|
| A5.1 | Load frames | `StaticAnalysisActivity` + `AnalysisWizardSlots` / `AnalysisWizardCoach` | `ui/common/media/MediaPickerSheet` → `ui/analysis/frames/FrameImportHelper` → `imaging/BitmapDecoder`; ordering via `AnalysisFrameOrderMenuHelper` + `FrameOrderHelper` / `FrameOrderAdapter` |
| A5.1a | Video source | same | `AnalysisVideoExtractController` → `VideoFrameExtractor` |
| A5.2 | Confirm settings | same | `AnalysisSettingsSheetController`, `SubsetRecommender` (SSSIG seed), `data/prefs/ParamClipboard` (Paste params), ROI card → A6, `AnalysisReadyGate` |
| A5.3 | Sweep summary `[sweep]` | same | `SweepSetupController` + `SweepStudy` (plan) + `LineCutPreviewView` |
| A5.4 | Running | `BatchRunController` + `ComputeOverlayController` | `RunChannels.launchBatchAnalysis` → `runBatchAnalysis` → `BatchAnalysis.runBatchAnalysisBody` → `DicFieldIo` → JNI `SemperNativeLib.computeFullFieldDirect`; sweeps go `RunChannels.launchSweep` → `SweepAnalysis.runSweep` → `SweepStudyRunner` |
| A5.5 | Terminal states | `EngineFailure` + `ui/common/dialog/FaqRedirect` | `field/RunStop`, `ConvergenceGate`, `AnalysisCancel` |

| Field | Value |
|---|---|
| Writes | `<sessionDir>/run_staging/frame_%04d.dat` while it runs, moved up to `<sessionDir>/frame_%04d.dat` (`data/session/SessionPaths`) with `field_ranges.bin` and the signed `record_manifest.json` (`data/integrity/RecordManifestWriter`, ADR-028) by `RunSession.commitRecord` when it ends any way but a cancel, `raw_deformed/`, reference copy and the index row via `data/session/SessionRepository.buildSessionRecord` → `ui/analysis/run/RunRecordSave.saveRunRecord` → `data/session/SessionStore.save` |
| Then | Once the row is saved, `saveRunRecord` calls `data/cloud/CloudSync.enqueueUpload` → B1 when cloud backup is on. `afterSave` reads the save's `UpsertOutcome` for both, a sweep's through `SweepAnalysis.finishSolvedSweep` → `persistSweepSession`: a full quota ends the run at the session limit and opens the session-limit screen; an index that could not be read or written shows **Couldn't save results**. Either way a sweep's lattice does not open, and the sweep records `analysis_failed` with reason `session_limit` or `index_unavailable` (`SweepAnalysis.sweepEndEvent`) |
| Fails as | `EngineFailure.reasonRes` dialog with **Why?** → FAQ; stop reason persisted on the record (`stopCode`, `plannedFrameCount`) so it survives a restart, and in the backup's `metadata.json` `metrics` so it survives a restore. A run never writes over a saved analysis: once its row is saved the wizard lets go of the session (`RunChannels.applyRunOutcome`), and `openRunSession` reuses a working id only while no row names it. A cancel keeps nothing, for a batch run or a sweep: the staged results are deleted, the frames the run moved go back to the import, and a new session's folder is deleted (`SessionStore.deleteUnsavedDir`) (`CancelledRun.discardCancelledBatch`, `SweepAnalysis.endSweep` → `RunChannels.discardRun`) |
| Then, on results | The results open and the wizard finishes (`BatchRunController.openResults` → `finish()`, from the saved row through `ViewerArgs.of`), so their Back lands on Home and the draft is discarded (owner decision, 2026-10-15) |
| Re-run inputs | A saved run also keeps `<sessionDir>/rerun/` (`ui/analysis/rerun/RerunKeeper`: `inputs.json`, the original reference bytes, the mask, and frames the loop never moved); Home's **Re-run** (`SessionSelectionController.rerunSelected` → `HomeActivity.startRerun`, the FAB's seat and quota gates) opens the wizard with `IntentKeys.RERUN_SESSION_ID`, and `RerunImportController` copies them into the import cache as a new analysis. A row without `rerun/` but with its frames (older, or restored) has its reference picked again first (`ui/home/RerunReferencePicker`, size checked against the record; `RerunLoader.loadWithReference`) ([ADR-022](adr/ADR-022-rerun-as-copy.md)) |
| Signals | Timber; `android.os.Trace` sections; `diagnostics/SemperAnalytics` analysis started / completed / failed (consent-gated, buckets only) |
| Tests | `analysis/SweepStudyTest`, `analysis/SubsetRecommenderTest`, `analysis/ConvergenceGateTest`, `session/FailureProvenanceTest`, `results/DicResultDecodeTest`, `EngineFailureTest`, `AnalysisViewModelTest`, instrumented `pipeline/EnginePipelineSmokeTest` |

**Accuracy invariants** (do not "clean up"): the JNI output buffer is sized to
the ROI grid and the returned point count is checked against that capacity
before read-back; `computeFullFieldDirect` stays inside the one batch loop;
progress is a buffered `SharedFlow`, not a `StateFlow`.

### A6 ROI editor

| Field | Value |
|---|---|
| Entry | `ui/analysis/RoiDrawActivity` (started for result by A5.2) |
| Chain | `ui/analysis/roi/StudioOverlayView` (draw / hit-test / mask) → `StudioOverlayMaskEncoder`; resolved back by `ui/analysis/roi/RoiResolveHelper` |
| Writes | Mask file at `IntentKeys.MASK_FILE_PATH`; ROI rect in `IntentKeys.ROI_*` |
| Tests | `ui/analysis/roi/StudioOverlayViewTest`, `StudioOverlayGeometryTest`, `StudioOverlayViewportTest`, `RoiResolveHelperTest`, `ui/analysis/RoiDrawActivityTest` |

Circle / ellipse / freeform are implemented in `StudioOverlayView` but not
exposed by `activity_roi_draw.xml` — see §11 of [app/WORKFLOWS.md](app/WORKFLOWS.md).

### A7 Parameter-sweep lattice `[sweep]`

| Field | Value |
|---|---|
| Entry | `ui/analysis/VsgLatticeActivity` (a sweep opens here, not in the viewer) |
| Chain | `SweepLatticeView` (nodes) + `SweepPlotView` (line-cut plot) + `SweepStudy` (plan maths); node open → A8 |
| Reads | `IntentKeys.SWEEP_*` extras packed by `AnalysisNavHelper.openResults` or `ui/home/SessionOpenHelper.intentFor` |
| Writes | `data/prefs/ParamClipboard` on a parameter-chip copy |
| Fails as | Hollow node → `EngineFailure.shortReasonRes` + FAQ |
| Tests | `analysis/SweepStudyTest`, `EngineFailureTest` |

### A8 Result viewer

| Field | Value |
|---|---|
| Entry | `ui/viewer/ResultViewerActivity` (+ `ResultViewerViewModel`) |
| Reads | `.dat` frames via `DicResult.decodeDatFile` (memory-mapped) and `data/session/DatCodec` |
| Renders | `report/VisualizationEngine` heatmaps, `ui/viewer/HeatmapFit` rest-fit, `TouchImageView` zoom/pan, `ViewerFieldPills`, `ScrubFrameCache` look-ahead; single-setting only: `ViewerSummaryController` + `SummaryAnimation` + `report/GifEncoder` |
| Probe | `ViewerInspectController` + `PointSpatialIndex` (built lazily on first tap) + `InspectOverlayView` |
| Details | `SettingsUsedSheet` (ⓘ). On a frame: true extrema plus a Scott-binned histogram of accepted values (`FieldHistogramView`). On the summary: min of every frame's colour-bar min and max of every frame's colour-bar max, matching the GIF; no mean, no histogram |
| Exports | `ShareCenter` → `ViewerReportFactory` / `report/ReportBuilder` / `PdfReportGenerator` / `AnalysisCsvWriter` / `data/session/SessionEverythingExporter` → `SendToSheet` → `SaveExportActivity` (SAF) |
| Fails as | Snackbar + **Why?** FAQ (`no_batch_data`, OOM, scale) |
| Tests | `results/*` (decode, histogram, CSV, heatmap, PDF, GIF, summary), `viewer/ScrubFrameCacheTest`, `viewer/FrameNumberEntryTest`, `HeatmapFitTest`, `ViewerFieldPillsTest` |

**Memory invariants**: the scrub cache is byte-bounded and filled by one
serialized worker; whole-batch passes (summary scan, spatial index) start on
demand, never on open; report compositing is capped at
`VisualizationEngine.REPORT_MAX_EDGE`.

### A9 Session limit

`ui/limit/SessionLimitActivity` → `CloudSync.reconcile(deep = true)` +
`SessionQuota.blocked` (the one quota rule, [app/WORKFLOWS.md §9](app/WORKFLOWS.md#9-session-limit))
→ back to A3 when the cap clears. Reached from Home cold
start, the FAB, the quota chip, a pre-run check (`AnalysisNavHelper.ensureSessionQuota`)
or a quota rejection during B1. Not a paywall: the way past it is an email.

The pre-run check (and the seat check, `ensureSeat`) applies to every run:
a saved analysis is never written over, so every run that saves makes a new
Home row (owner decision, 2026-10-15; `AnalysisNavHelper.ensureCanStart`, then
`RunChannels.admitRun` on the native thread). Home's Re-run applies the same
gates before it opens the wizard.

---

## B. App — background and data workflows

Nothing here has a screen of its own. These are the flows that keep the user's
data correct while they are elsewhere, and the ones a bug report usually means
when it says "it just didn't work". Four are WorkManager jobs and survive
leaving the app; the rest run on `lifecycleScope` or at process start.

### B1 Back up an analysis to the cloud 🔒

```
CloudSync.enqueueUpload → DicUploadWorker.doWork → backUp
    ├─ stage  : UploadStaging → SessionUploadBundler → ReportBuilder / VisualizationEngine / GifEncoder
    │            → SessionZip (+ ZipDirectory) → Session.zip + .sha256 in upload_staging/
    ├─ declare: UploadSessionPlanner.ensureSession → SessionUploadMetadata
    │            → SemperApi.createSession ......................................... C5
    ├─ resume : UploadSessionPlanner → SemperApi.listSessionUploads (poll while PROVISIONING) C6
    ├─ bytes  : uploadFiles → DriveUploader → Drive resumable URI (never through Cloud Run)
    ├─ finish : SemperApi.completeFile (size + md5 verified server-side) ....... C7
    └─ failure: every refusal and exception is mapped by UploadFailures
```

| Field | Value |
|---|---|
| Triggered by | `saveRunRecord` after a run, the Home cloud icon retry, Settings **Back up now**, turning **Save to cloud** on, and each reconcile that lists the cloud, for rows still PENDING (`CloudSync.reconcile`) |
| Decisions | `data/cloud/UploadWorkOutcomes` — HTTP → retry/fail, staging reuse, verified `Session.zip`, incomplete staging (`classifyIncompleteStaging`: retry while the reference/`.dat` inputs exist, the row was saved < 15 min ago, or they have been missing < 10 min by the `<sessionDir>/upload_inputs_missing_since` marker; else terminal `inputs_missing`); resuming a cloud session: `Resume.of` (`data/UploadSessionPlanner.kt`), and a staging that is not the one the session declared releases it first (`UploadSessionPlanner.releaseForRestage`) |
| Writes | `<sessionDir>/upload_staging/`, sync state + `cloudSessionId` on the index row; `StorageBudget.enforce` runs at the end |
| Fails as | Terminal: `IntentKeys.UPLOAD_FAIL_REASON` in the worker output → Home pill + cloud icon dialog. Retryable: `Result.retry()` with a Timber `Upload RETRY` line |
| Signals | `data/cloud/TransferNotifications` foreground notification (bar, bytes, rate, percent, time left); `data/cloud/TransferResultNotifications` "… is backed up" / failure reason with Retry (`TransferRetryReceiver`); `IntentKeys.UPLOAD_PHASE` / `UPLOAD_PERCENT` progress, plus `TRANSFER_BYTES_*` while bytes go to Drive; `SemperAnalytics` cloud_upload_* buckets; the backend's `X-Request-Id` appended by `UploadWorkOutcomes.withRef` |
| Tests | `cloud/UploadResumableTest`, `cloud/DicUploadWorkerOutcomesTest`, `cloud/UploadChunkSizingTest`, `cloud/BackupSplitTest`, `cloud/SessionZipTest`, `cloud/WaitingUploadsTest` |

`doWork` may be broken into named steps, but the resume contract depends on
the staged bytes being **identical** across attempts (the declared size and sha256 are what Drive's
resumable URI and `C7` reconcile against).

### B2 Restore an analysis from the cloud 🔒

| Field | Value |
|---|---|
| Entry | `CloudRestore.enqueueRestore` (Home row tap, or Settings **Restore**) → `data/DicRestoreWorker` |
| Chain | `CloudRestore.restore` → `SemperApi.listSessionFiles` (C9) → `RestoreBundleFetcher` → `SemperApi.downloadFile` / `downloadRange` → `DriveDownloader` → `RestoreUnpacker` (`SessionZip` entries) → `SessionStore.save` |
| Writes | A fresh `<sessionDir>` with `.dat` frames + `raw_deformed/`; `*.part` files while in flight |
| Fails as | `DicRestoreWorker.KEY_ERROR` (carries the `ApiException` message, and with it the backend `ref:` id) → pill on Home **and** Settings |
| Retry rules | `data/cloud/restore/RestoreDownloadOutcomes` — 5xx/timeout Range-resumes, `ZipException`/corrupt transfer is terminal |
| Tests | `cloud/RestoreAndImportSafetyTest`, `cloud/RestoreDownloadOutcomesTest`, `cloud/DriveTransferDownloadTest`, `cloud/DownloadWindowSizingTest` |

### B3 Download a session bundle to a file

`CloudRestore.enqueueBundleDownload` (Settings **Download**, SAF destination
chosen **first**) → `data/DicBundleDownloadWorker` → the cloud `Session.zip`, or
a locally packed one via `SessionZip` when the cloud copy is unavailable. A
failed download deletes the empty destination rather than leaving a 0-byte file.

### B4 Erase a cloud backup 🔒

`ui/home/SessionSelectionController` or `SettingsActivity` → `data/cloud/SessionDeletes.enqueue`
(one unique `session-delete` chain, so a second confirm queues behind the first) →
5-second undo → `data/BackupDeleteWorker` → one analysis at a time through
`CloudSync.eraseCloudBackup` / `eraseEverywhere` → `SemperApi.deleteSession` (C11).
A 429 waits 5 s and retries that analysis (up to six tries) instead of reporting it
as still in the cloud; a cloud delete clears the row's `cloudSessionId`, so a later
delete of the phone copy sends nothing. `ui/common/transfer/DeleteFeedback` shows Undo,
"Deleting x of y…" and the outcome, with **Try again** for what is left. Settings
re-lists with `CloudRestore.listCompleted`, which is uncached, so the list stops
offering what no longer exists.

### B5 Reclaim local space

`data/session/StorageBudget` — `enforce` at `SemperApp.onCreate` (before any screen),
`freeAllBackedUpOnIo` / `enforceOnIo` from `ui/settings/SettingsStorageSection`.
It frees local frames of **backed-up** analyses only; a row whose frames were
freed becomes "Only in cloud", not "data gone".
Pinned by `session/LocalStorageFootprintTest`.

### B6 Clear the cache

`data/session/CacheJanitor` from `SettingsStorageSection` — regenerable files only
(previews, GIF cache, staging leftovers). Also provides the size measurement the
Storage section shows.

### B7 Reconcile with the cloud 🔒

`CloudSync.reconcile(deep = …)` from Home pull-to-refresh and the limit screen →
`SemperApi.listSessions` (paged, `verify=true` on a deep refresh) → C8. Repairs
sync state, re-enqueues B1 for local-only rows, refreshes the quota in
`AccountCache` / `AppRemoteConfig`. A SYNCED row missing from this app's list
is repaired only after one `?app=all` listing shows no other app's backup of
it either (`CloudReconcile.list`, ADR-014, TD-153); if that listing fails, no
row is repaired that pass.

**A Drive outage must not look like deleted data**: local metadata is dropped
only when the backend *confirms* a blob is missing (`C8` returns `MISSING`, not
`UNKNOWN`).

### B8 Export data 🔒

| Export | Entry | Chain |
|---|---|---|
| Everything on this phone | Settings **Export my data** | `data/session/SessionEverythingExporter` → `SessionZip` / `ZipDirectory` → `SendToSheet` |
| Everything in the cloud | Settings **Download cloud data** | `SemperApi.exportAccount` (streamed) → C12 → `SendToSheet` |
| Viewer exports | A8 Share | `ui/viewer/share/ShareCenter` → `report/*` → `SendToSheet` |

The two Settings exports run behind `ui/common/transfer/TransferBannerController`, not a
modal dialog. A viewer export starts in `ui/common/dialog/DeterminateProgressDialog`
(status, percent to one decimal, time left; every kind but the one-photo share reports
frame by frame or field by field, `ui/viewer/share/ExportProgress`) and moves to the same
banner when the dialog is dismissed.
Tests: `cloud/SessionEverythingExporterTest`, `results/*`.

### B9 Delete the account 🔒

`ui/settings/SettingsYourDataSection` → re-authentication via `ui/common/auth/AuthRoute`
(password, Google or email link) → `CloudSync.deleteAccount` → `SemperApi.deleteAccount`
(C13) → **only on success** the local wipe (`SessionStore`, `AccountCache`,
`AppSettings`) and Firebase `delete()`. Backend first, deliberately: a local wipe
on a failed server call would strand the cloud copy.
Tests: `cloud/AccountDeletionTest`, `settings/DeleteAccountReauthTest`.

### B10 Diagnostics consent 🔒

One flag, two consumers:

```
AppSettings.diagnosticsEnabled ─┬─ Diagnostics.apply/setEnabled → Crashlytics + CrashReportingTree
                                ├─ diagnostics/SemperAnalytics.event (dropped, not queued, when off)
                                └─ Diagnostics.applyAccountType → account_type (cleared when off)
```

`account_type` is `test` or `ci` on a QC or CI test account (`/v1/config`'s
`testAccount`, kept by `AppRemoteConfig.testAccount`), as a Firebase Analytics
user property and a Crashlytics custom key. It is re-applied whenever the
consent or the cached config changes, and cleared without consent, on a real
account and at sign-out.

Asked once on first run (after the beta notice, defaulting to **off**), mirrored
by the Settings toggle (**Send crash reports and usage data**). Events carry
buckets and enums only — never images, results, session ids, specimen names or
paths. The label, its subtitle, the first-run prompt and
[legal/PRIVACY_POLICY.md](legal/PRIVACY_POLICY.md) §2.4 all name both halves;
keep them in step if the event set changes.
Tests: `diagnostics/SemperAnalyticsTest`, `diagnostics/AccountTypeTest`.

### B11 Device identity and attestation 🔒

| Field | Value |
|---|---|
| Key | `data/account/DeviceKeys` — EC P-256 in the AndroidKeyStore, private key never leaves it |
| Register | `SemperApi.registerDevice` → C2 (409 = this account or device is bound elsewhere) |
| Per call | `SemperApiSigning`: a client nonce (`ClientNonce`), or `fetchChallenge` → C3 once the server refused one; `signedHeaders` signs `(nonce ‖ METHOD ‖ path) ‖ SHA-256(body)` → verified by `C4` |
| Recover | A `401 bad_signature` (another app with this signing key registered the same device id, TD-209) → `DeviceKeyRecovery` registers again (C2, same id, a key overwrite) and the call is sent once more: `SemperApiCalls.signed`, and `signedDownload` for restores. At most one registration per call and one a minute per process; the second refusal is the failure |
| Tokens | `data/net/TokenProvider` / `AccountCache` — Firebase ID tokens are held in memory, never persisted |

### B12 Local session index

`data/session/SessionStore` (atomic index write, `synchronized`, corruption flag) +
`data/SessionRecord` + `data/session/SessionQuota` + `data/session/SessionPaths` (the one
place that knows `frame_%04d.dat` and `raw_deformed/`).
Tests: `session/SessionStoreAtomicTest`, `upgrade/PrefsUpgradeSmokeTest`.

### B13 Remote product limits

`data/net/AppRemoteConfig` caches `GET /v1/config` (C-config): max sessions, max
files per session, max frames. The wizard's frame cap and the quota chip read it,
falling back to compile-time defaults when it has never been fetched.

### B14 Parameter clipboard

`data/prefs/ParamClipboard` — one subset/step/VSG (px) triple, copied from the A7
parameter chip and pasted in A5.2. In memory only.

### B15 Transfer progress surfaces

One notification channel (`semper_transfers`). `data/cloud/TransferNotifications`
builds the running upload, restore and download notifications: a spinning bar until the
worker reports, then the bar, "4.2 of 12.0 MB · 1.1 MB/s" and "34.6% · About 35 s left"
(`TransferMeter`; an upload still staging says "Preparing the backup"). The upload
updates it from `UploadProgressSampler`; the two downloads at most once a second
(`TransferProgressUpdates`). `data/cloud/TransferResultNotifications` posts the outcome:
"steel_00 is backed up / restored / saved", or "… was not …" with the reason and, for a
backup or a restore, a Retry that `TransferRetryReceiver` turns into the same unique
work again. A failure without a reason (the upload's quota stop) and a retry post
nothing. Outcomes need the notification permission, which the app declares but never
asks for, so from Android 13 on they show only once the user allows notifications.
On Home each row has its state icon, and while a transfer runs a progress line and bar
fed by the same byte counts (`TransferWorkObserver.RowProgress`, "Backing up · 4.2 of
12 MB"); the `TransferBannerController` strip in Settings and the viewer. Tests:
`data/cloud/TransferNotificationTextTest`, `data/cloud/TransferRetryReceiverTest`,
`data/cloud/TransferMeterTest`.

---

## C. Backend — request workflows

FastAPI on Cloud Run, in front of Firestore (index + metadata) and a Google
Drive shared drive (bytes). **File bytes never transit Cloud Run on the way up**
— the phone PUTs straight to a Drive resumable URI; only downloads are proxied,
because Drive has no anonymous signed read.

Handlers stay plain `def` (Firestore and Drive calls are blocking, so Starlette
runs them in its threadpool). Every route is in `backend/app/routers/`; shared
pieces are `deps.py` (auth), `step_up.py` (the step-up tiers), `services/` (multi-step
flows a route calls), `repo/` (all Firestore access, one module per aggregate),
`drive.py` (all Drive access), `errors.py` (the `detail` codes),
`validation.py`, `rate_limit.py`, `audit.py`, `observability.py`.

### Cross-cutting

| Id | Concern | File | What it does |
|---|---|---|---|
| C17 | Access log | `main.py` `EdgeMiddleware` (one ASGI middleware) | One JSON line per request: `requestId`, `opClass`, `routeTemplate`, status, latency, outcome, uid/deviceId when known, `appId` (the `X-App-Id` when it is a listed id, else `none` / `unknown`; TD-176). Stamps **`X-Request-Id`** on the response — the app now echoes it into failure reasons (B1/B2), so a user's screenshot joins to this line |
| C17a | Route classes | `observability.classify_route` | `health` / `attest` / `login` / `config` / `account` / `backup` / `sync` / `restore` / `admin`; ids in paths collapse to `{id}` so nothing identifying lands in `routeTemplate` |
| C18 | Audit trail 🔒 | `audit.record` | Append-only `audit_logs`: AUTH_DENIED, DEVICE_*, SESSION_CREATE/DELETE, UPLOAD_COMPLETE, FILE_DOWNLOAD, DATA_EXPORT, ACCOUNT_DELETE, ADMIN_*. Best-effort — an audit write never fails the request |
| C19 | Access-request mail 🔒 | `notify.access_request` | On first PENDING user, mails support via Resend on a daemon worker with retry + per-uid idempotency. Off (silently) without `RESEND_API_KEY` |
| C20 | Rate limits | `rate_limit.py` | Per-instance token buckets per uid (`export`, `erase` (account), `session_erase` (one analysis), `download`, `session`, `session_verify`, `challenge`, `device_register`, `file_complete`, `listing`, `admin`, `health`). The durable cross-instance limits are the gateway quotas in `backend/gateway/openapi.yaml` |
| C20a | Security headers | `main.py` `EdgeMiddleware` | nosniff, `frame-ancestors 'none'`, no-referrer, Permissions-Policy, HSTS behind HTTPS; on every answer, a hidden 500 included |
| C20b | Body cap | `main.py` `MAX_BODY_BYTES` (4 MiB) | A larger body is refused `413 request_too_large`: unread when its length is declared, counted as it arrives when not |
| C20b | Startup checks | `main.py` `_startup_checks` | Refuses to start on Cloud Run without the required env; refuses `DEV_INSECURE_AUTH` unless explicitly acknowledged. Interactive docs are served locally only |

### Authentication pipeline 🔒

| Id | Workflow | Entry | Chain |
|---|---|---|---|
| C1 | Identify the caller | `deps.current_user` | `google_auth.verify_id_token` → `repo.get_or_create_user` (auto-approve rules, device binding, `DeviceInUseError` → 409) → 403 unless APPROVED. First PENDING user triggers C19 |
| C2 | Register a device | `POST /v1/devices/register` (`routers/devices.py`) | One account per device and one device per account: a different bound device is `device_conflict`, a device owned by another uid is `device_in_use` (audited), and a phone staff released from this test account is `device_released` (audited, TD-314); all checked inside the registration's transaction (`repo.register_device`). The device records `stepUpEligible`: whether the registering token completed a second factor, which staff and IT step-up require of a phone (ADR-010 amendment). Re-registering the same id heals the stored public key (`DEVICE_REBIND`; no device retired, no lock moved). A retired `com.indicvision.*` `X-App-Id` is `410 app_retired` (audited), since it shares the device id of its successor |
| C3 | Mint a nonce | `POST /v1/challenge` | `repo.issue_nonce(uid, deviceId)` — single-use, bound to the pair |
| C1t | Token only | `deps.token_uid` | `google_auth.verify_id_token` (plus App Check when a device is named) and nothing else: no profile read or created. Only `GET /v1/me/erasure`, which must not bring back an erased account |
| C4 | Verify a device-signed call | `deps.verified_device` | ACTIVE device → `consume_nonce` (replay = 401) → ECDSA P-256 over `(nonce ‖ METHOD ‖ path) ‖ SHA-256(body)` → `bad_signature` audited on failure |
| C14 | Admin | `routers/admin.py` | Listing and **device-history** need only an admin ID token; **approve / revoke / config-patch / mint additionally require a step-up** — a device attestation, or a second factor plus a recent sign-in for the staff console — so a stolen ID token alone cannot change access. Whole-licence revoke uses a tighter freshness window. All audited |

### Account and identity routes

| Id | Route | Auth | Notes |
|---|---|---|---|
| C-me | `GET /v1/me` | C1 | uid, email, role, access_status. 409 = device conflict |
| C-config | `GET /v1/config` | C1 | Resolved limits (per-user override → fleet default) — feeds B13 |
| C12 | `GET /v1/me/export` 🔒 | C4 | GDPR Art. 20. **Streamed** — profile, devices, then each session with its file manifest, `"complete": true` written last so a truncated transfer is detectable. `Cache-Control: no-store`. Audited as DATA_EXPORT |
| C13e | `GET /v1/me/erasure` | C1t | Whether this phone's device record is gone or someone else's, the app's check after an erase that never answered (`CloudErase.probeErasedAccount`). Rate-limited in the handler on the erase bucket |
| C13 | `DELETE /v1/me` 🔒 | C4 | GDPR erasure. Drive subtree **first**: every folder named after the account (the stored user-folder pointer and every `user/{uid}` the name walk finds: a concurrent first upload or a retried create can leave two), then each session's stored folder still there; Firestore second — never the other way round. A Drive failure, or a Shared Drive the service account can no longer see, stops it before Firestore with `502 drive_write_failed`. Per-phase timings in the audit detail. Only the audit trail survives |

### Session and file routes

| Id | Route | Auth | Notes |
|---|---|---|---|
| C5 | `POST /v1/sessions` | C4 | Idempotent on `localSessionId` while in flight. Quota + file-count check → **reserve the session doc before any Drive I/O** → batch-write file docs → enqueue provisioning (C15) or provision inline with rollback. The session is tagged with the asking app (`app`, ADR-014). An optional `name` (≤ 200 chars) is the analysis's name, which the list shows; an older app sends none (TD-208) |
| C15 | Provisioning | `session_provision.provision_session` | Opens one Drive resumable URI per unprovisioned file, bounded fan-out. Idempotent: only files with no `uploadUrl` are touched. Inline failure → `purge_session` (Drive **and** Firestore); queued failure → `PROVISION_FAILED` so a poller stops waiting |
| C16 | `POST /v1/tasks/provision-session` | `tasks.tasks_caller` | Cloud Tasks callback, authenticated by the OIDC token's audience **and** invoker service-account email. Not reachable with a user token |
| C6 | `GET /v1/sessions/{sid}/uploads` | C4 | The resume list. Carries Drive capability URIs, which is why it is attested (the ID-token-only migration window was retired 2026-09-26, FI-7). `status` PROVISIONING means "poll", not "nothing to do" |
| C7 | `POST /v1/files/{id}/complete` | C4 | Verifies against Drive's own metadata: size, md5 (skipped only when Drive has none), and that the object's `parents` contains this session's folder — the confused-deputy guard. Advances the session the **file** belongs to, never the one the client named |
| C8 | `GET /v1/sessions` | C1 | The asking app's sessions (`X-App-Id`; `?app=semper\|semperclassroom\|all` overrides, ADR-014; the old `materialtesting` is still accepted, ADR-030), each with its `app` and `name` (`""` when the backup was made without one: the app then names it from `specimen`, the reference's file name); `quota.used` is the whole account's. Cursor-paginated; `?verify=true` probes this page's folders in Drive. Purges metadata **only** on a confirmed `MISSING`; an `UNKNOWN` is counted and reported so the client knows the check was incomplete |
| C9 | `GET /v1/sessions/{sid}/files` | C1 | The restore manifest, cursor-paginated (it used to truncate at 2000). Answers only the app that backed the session up; the other app gets `404 session_not_found` (ADR-014) |
| C10 | `GET /v1/files/{id}/content` | C4 | The one route that streams file bytes out. Checks the licence of the app the file's session was backed up from (ADR-033), as the bundle does, before the first byte. Forwards `Range` to Drive and returns 206 so a restore resumes; audits only the window starting at byte 0; sanitises the client-supplied filename before it reaches `Content-Disposition` |
| C11 | `DELETE /v1/sessions/{sid}` 🔒 | C4 | Drive folder then Firestore metadata; nothing soft-deleted. A Drive failure is `502 drive_write_failed` with nothing erased. While the session is PROVISIONING (last written within 30 min, `session_provision.PROVISIONING_GRACE`; a provisioning attempt stamps `updatedAt` as it starts, `repo.start_provisioning`, TD-296) it is `409 session_provisioning` with `Retry-After`, so the folder the worker is creating is never orphaned |
| C21 | `PUT /v1/sessions/{sid}/metadata` | C4 | Replaces a COMPLETED session's metadata.json in place, for a change made after the backup (a rename; in Semper Classroom also a deflection or tensile curve correction; ADR-013). Refuses an unfinished upload (409 `session_not_complete`), another session's metadata or an unknown schema (422), over 256 KB (413). The file doc's size and checksums follow the new bytes, and a non-blank `name` in the body becomes the session's listed `name` (TD-208). The only write to a completed file |
| C-health | `GET /healthz`, `GET /readyz` | none | Liveness never probes dependencies; readiness pings Firestore + Drive and answers with stable `DependencyError` codes |

Backend tests mirror these one-to-one — `backend/tests/test_route_authz_matrix.py`
(who may call what), `test_device_auth.py`, `test_content_verified_device.py`,
`test_upload_integrity.py`, `test_session_metadata.py`, `test_create_session_rollback.py`,
`test_async_provisioning.py`, `test_verify_degradation.py`, `test_access_log.py`,
`test_security_controls.py`, `test_error_codes.py` (the code contract with the
app), and the rest of `backend/tests/`.

---

## D. Ops workflows

| Id | Workflow | File | Trigger |
|---|---|---|---|
| D1 | CI gate | `.github/workflows/ci.yml` | PR to `main`, push to `main`, weekly `schedule` (full matrix), or `workflow_dispatch` (`full_ci` runs every tier on any branch). Path filters pick the tiers on PRs and `main` pushes alike; `full-ci` / `e2e` / `release` / `benchmark` labels widen them |
| D2 | Secret scan | same, `secret-scan` job | Every run, full history, `.gitleaks.toml` |
| D3 | Legal pages match source, and docs point at real files | same, `legal-pages` job | Every run: `python scripts/render_legal_pages.py --check` (edit `docs/legal/`, never `firebase-hosting/public/`) and `python scripts/check_doc_paths.py` (§E3) |
| D4 | Backend deploy | `.github/workflows/deploy-backend.yml` | Manual |
| D5 | Release | `.github/workflows/release.yml` | Manual — see [ops/RELEASING.md](ops/RELEASING.md) |
| D6 | Firestore backup / restore drill 🔒 | `firestore-backup.yml` (daily), `firestore-restore-drill.yml` (monthly) | Scheduled — see [backend/FIRESTORE_DATA_PROTECTION.md](backend/FIRESTORE_DATA_PROTECTION.md) |
| D7 | Cache cleanup | `cache-cleanup.yml` | Weekly + on PR close |

Details and failure triage: [ops/CI.md](ops/CI.md).

---

## E. Backtracking

### E1 Symptom → first file

| Symptom | Start here |
|---|---|
| A message, label or dialog text on screen | `rg "<the phrase>" app/src/main/res/values/strings.xml` → then `rg "R.string.<name>"`. Every user-facing string is in `strings.xml`; a phrase you cannot find there is either formatted (`_fmt`, plurals) or comes from an exception message |
| An error with a **Why?** action | `ui/common/dialog/FaqRedirect` and [app/FAQ_LINKS.md](app/FAQ_LINKS.md) — the FAQ url resource names the case |
| A wrong number in the viewer or ⓘ sheet | The extras it was opened with: `AnalysisNavHelper.openResults` (fresh run) or `ui/home/SessionOpenHelper.intentFor` (reopen) → read in `ResultViewerActivity` / `SettingsUsedSheet` / `ViewerReportFactory`. **Check which of the two packed it** — see E2.1 |
| A wrong number in an export | `report/ReportBuilder` (fusion), `report/AnalysisCsvWriter`, `report/VisualizationEngine`; the source of truth is `DicResult.decodeDatFile` over `frame_%04d.dat` |
| "Analysis failed" wording | `ui/analysis/run/EngineFailure` (code → string) + `field/RunStop` (the stop codes); the code itself comes from the engine or `ConvergenceGate` |
| A backup that failed | Home cloud icon dialog text = `IntentKeys.UPLOAD_FAIL_REASON` from `UploadRun.failure` → `UploadWorkOutcomes` for the decision. `adb logcat -s Semper` shows `Upload RETRY`/reject lines in release too |
| A restore that failed | `DicRestoreWorker.KEY_ERROR` (the `ApiException` message) → `CloudRestore` → `RestoreDownloadOutcomes` for retry vs terminal |
| Any cloud 4xx/5xx | The reason carries `(ref: <id>)` — that is the backend's `X-Request-Id`. Search the Cloud Run log for `requestId="<id>"` to get the exact access line (`opClass`, `routeTemplate`, `errorCode`, latency) |
| A cloud call that is rejected consistently | `backend/app/errors.py` names the `detail` code; the client's branch is in `data/net/ApiErrors.kt` + `SemperApi.failSigned` |
| Something the user says vanished | `CloudSync.reconcile` (B7) and `C8`'s `MISSING` vs `UNKNOWN` rule, then `StorageBudget` (B5) — "Only in cloud" is not "gone" |
| Work that never started | It is WorkManager: `adb shell dumpsys jobscheduler`, plus the unique work names in `CloudRestore.workName` / `bundleDownloadWorkName` |
| A backend behaviour with no obvious owner | `backend/app/observability.py` `classify_route` gives the `opClass`, which names the workflow group in §C |

### E2 Where backtracking is genuinely hard

Ranked by how often it costs someone an afternoon. Each has a proposed fix in
[ops/FUTURE_IMPROVEMENTS.md](ops/FUTURE_IMPROVEMENTS.md).

**E2.1 The viewer's input state (resolved).** It was packed in two places and
read in four, with a missing extra silently defaulting. `ViewerArgs`
(`ui/viewer/ViewerArgs.kt`) is now the one writer (`toIntent`, both entry
points; `ViewerArgsTest` compares their key sets) and the one parse
(`ViewerArgs.from(intent, record)`, `:296`): a missing key is filled from the
session record, else from one documented default, and logged
([ADR-003](adr/ADR-003-viewerargs-read-side.md); FI-1, done).

**E2.2 Upload/restore instrumentation.** *(Fixed 2026-08-31 — `TransferLog` JSON phase lines.)*

**E2.3 Sweep skip provenance.** *(Fixed 2026-08-31 — `SkippedNode` / `SWEEP_SKIPPED`; legacy arrays read as fallback.)*

**E2.4 An unknown engine code reads as a known failure.** *(Fixed 2026-08-31 —
`EngineFailure.Cause.UNKNOWN` for unrecognised positive codes.)* `EngineFailure.cause`
used to map `0` and everything unrecognised to the VSG / strain-window case. That
is a good guess for a real zero-point solve and a bad one for a code we have never
seen — the user was told to change the strain window for a failure nobody had
diagnosed.

**E2.5 On-screen copy has no id.**
Finding the code behind a message means guessing its wording well enough to grep
`strings.xml`. Formatted strings and plurals defeat that. → **FI-5**

**E2.6 Wizard run state has no single owner until commit.**
`StaticAnalysisActivity` (~1600 lines) plus ~15 helpers plus ~40 public `var`s on
`AnalysisViewModel` hold what a run will use; the authoritative record only
exists once `SessionRepository.buildSessionRecord` assembles it. To answer "what
settings did this run actually use", you read the ViewModel — or wait for the
`.dat` files. → **FI-6**

**E2.7 A backend `detail` code and its client branch are two edits.**
`backend/app/errors.py` and `data/net/ApiErrors.kt` now hold the same list, and
`backend/tests/test_error_codes.py` fails if they drift — but they are still two
files. Adding a code the client must branch on means remembering both.

### E3 Keeping this file true

A workflow map that has rotted is worse than none. When a change adds, removes or
re-routes a flow, update the row here in the **same PR** — the same rule
[CLAUDE.md](../CLAUDE.md) applies to `docs/`.

Renames are caught for you: `scripts/check_doc_paths.py` fails CI when any doc
points at a file that does not exist (D3). Run it before you push:

```bash
python scripts/check_doc_paths.py
```

It cannot tell whether a chain is still *correct*, only that its files exist — so
when you touch a flow, re-read its row against the source.
