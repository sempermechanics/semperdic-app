# Workflow index — every flow, and the files behind it

One map for the whole product: the app's screens, the work that keeps running
after you leave a screen, and every backend request. The engine is deliberately
absent — it lives in the `native/` submodule and has its own docs
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
under `app/src/main/java/com/indicvision/semper/`. Backend modules are under
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
| Chain | `data/AuthRepository` → `data/net/IndicApi.me` (with `getConfig` in parallel, fetched again once if its `mode` disagrees with `me.license.mode`, as when the invite claim landed between the two) → `ui/auth/AccessRouter` (+ `data/AccessStatus`), `data/DevAuth` for the emulator bypass. A device approved and bound last time opens Home without waiting; `ui/auth/StatusRecheck` runs the same check behind it and moves the user only on PENDING or a refused sign-in (`AuthRepository.AccessLostException`), never on a timeout or 5xx |
| Writes | `data/net/TokenStore` cached uid / email / status / role |
| Fails as | Routing error passed on as `DicKeys.ROUTING_ERROR`, shown by A1 as a red pill |
| Tests | `auth/AccessRouterTest`, `auth/StatusRecheckTest` |

The quota check is **not** here — it runs in `HomeActivity.onCreate` (A3), which
is why a capped account still lands on Home first.

### A1 Login 🔒

One screen, four credential paths plus two deep links.

```
AuthActivity ─┬─ Google SSO ............ ui/auth/GoogleSignInHelper
              ├─ email + password ...... data/AuthRepository
              ├─ create account ........ + ui/auth/PasswordPolicy
              ├─ email sign-in link .... /finishSignIn App Link
              └─ forgot password ....... /finishReset App Link → in-app reset form
```

| Field | Value |
|---|---|
| Entry | `ui/auth/AuthActivity` |
| Chain | `data/AuthRepository` (Firebase Auth) → `data/net/IndicApi.me` / `registerDevice` → `ui/auth/AccessRouter` |
| Writes | Firebase session; `TokenStore` identity; device registration flag |
| Fails as | `ui/common/CrispToast` pill; policy failures inline from `PasswordPolicy` |
| Tests | `auth/PasswordPolicyTest`, `auth/ReauthFlowTest`, `auth/FirebaseAuthIntegrationTest` (instrumented) |

Email verification is enforced for password accounts only; Google and email-link
users arrive verified. The App Links depend on Digital Asset Links being
verified at the Firebase host — see [backend/AUTH_SETUP.md](backend/AUTH_SETUP.md).

### A2 Pending approval 🔒

| Field | Value |
|---|---|
| Entry | `ui/auth/PendingApprovalActivity` |
| Chain | `data/AuthRepository` + `data/DeviceKeyManager` (device id shown) → `IndicApi.me` on **Check status** |
| Server side | The PENDING account was mailed to support at creation by `C1` → `backend/app/notify.py` |
| Note | It does not poll, despite the KDoc; only the button checks |

### A3 Home

```
HomeActivity ─┬─ beta notice + diagnostics prompt (first run) ......... B10
              ├─ session list ...... ui/home/SessionListAdapter → ui/home/SessionOpenHelper → A7 / A8
              ├─ selection mode .... ui/home/SessionSelectionController → B4 / B12
              ├─ pull to refresh ... data/CloudSync.reconcile(deep = true) ....... B7
              ├─ row badges ........ WorkInfo from B1 / B2
              ├─ quota chip ........ data/net/TokenStore + AppRemoteConfig → A9
              └─ FAB ............... ui/common/MediaPickerSheet (A3a) → A5
```

| Field | Value |
|---|---|
| Entry | `ui/home/HomeActivity` |
| Chain | `data/SessionStore.list` (local index) ⋈ `data/CloudSync.reconcile` (cloud) |
| Writes | Session index (rename, delete, sync state), coach-mark flags in `data/CoachPrefs` |
| Fails as | Message pill + a "why + retry" dialog on the sync badge, fed by the worker's `DicKeys.UPLOAD_FAIL_REASON` / `DicRestoreWorker.KEY_ERROR` |
| Tests | `session/SessionStoreAtomicTest`, `cloud/QuotaGateTest` |

### A3a New-analysis media picker

| Field | Value |
|---|---|
| Entry | `ui/common/MediaPickerSheet` (shared by the Home FAB and both wizard dropzones) |
| Chain | `ui/common/MediaStoreBrowser` + `MediaGridAdapter` for the Images tab; `ui/common/MediaSourceChooser` for the Files (SAF) tab |
| Permissions | `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`, asked only when the Images tab needs them |
| Tests | `ui/MediaStoreBrowserTest` |

### A4 Settings 🔒

Seven collapsible sections; each is its own file, and only restore / download /
delete stay on the Activity.

| Section | File | Workflow |
|---|---|---|
| Account (+ admin) | `ui/settings/SettingsAccountSection` | → A4.1 |
| Cloud backup | `ui/settings/SettingsPreferencesSection` | B1 |
| Analyses data management | `ui/settings/SettingsActivity` + `AnalysisEntries` / `AnalysisDataAdapter` | B2, B3, B4 |
| Storage | `ui/settings/SettingsStorageSection` | B5, B6 |
| Your data 🔒 | `ui/settings/SettingsYourDataSection` | B8, B9, B10 |
| Analysis preferences | `ui/settings/SettingsPreferencesSection` | `data/DicSettings` + `data/net/AppRemoteConfig` |
| Help & support | `ui/settings/SettingsHelpSupportSection` | mailto / hosted pages |

Long jobs are non-modal: `ui/common/TransferBannerController` carries restores,
bundle downloads and both exports. Terminal restore failures are observed here
as well as on Home.

Tests: `settings/AnalysisEntriesTest`, `settings/HelpSupportSectionTest`,
`settings/DeleteAccountReauthTest`, `settings/DicSettingsMigrateTest`,
`settings/TransferBannerControllerTest`.

### A4.1 Admin `[admin]` 🔒

`ui/admin/AdminActivity` → `IndicApi.listUsers` / `setUserStatus` → `C14`.
Visible only when `TokenStore.isAdmin` (backend-reported role).

### A5 Analysis wizard

One Activity, three pages; pages 2 and 3 inflate from ViewStubs and `goToStep`
stays on the Activity.

| Id | Step | Entry | Chain |
|---|---|---|---|
| A5.1 | Load frames | `StaticAnalysisActivity` + `AnalysisWizardSlots` / `AnalysisWizardCoach` | `ui/common/MediaPickerSheet` → `ui/analysis/FrameImportHelper` → `imaging/BitmapDecode`; ordering via `AnalysisFrameOrderMenuHelper` + `FrameOrderHelper` / `FrameOrderAdapter` |
| A5.1a | Video source | same | `AnalysisVideoExtractHelper` → `VideoFrameExtractor` |
| A5.2 | Confirm settings | same | `AnalysisSettingsSheetHelper`, `SubsetRecommender` (SSSIG seed), `data/ParamClipboard` (Paste params), ROI card → A6, `AnalysisReadyGate` |
| A5.3 | Sweep summary `[sweep]` | same | `SweepSetupHelper` + `VsgStudy` (plan) + `LineCutPreviewView` |
| A5.4 | Running | `BatchRunController` + `ComputeOverlayHelper` | `AnalysisViewModel.launchBatchAnalysis` → `runBatchAnalysis` → `DicBatchRunner` → `DicFieldIo` → JNI `SemperNativeLib.computeFullFieldDirect`; sweeps go `runVsgSweep` → `VsgStudyRunner` |
| A5.5 | Terminal states | `EngineFailure` + `ui/common/FaqRedirect` | `AnalysisRunCodes`, `ConvergenceGate`, `AnalysisCancelGate` |

| Field | Value |
|---|---|
| Writes | `<sessionDir>/frame_%04d.dat` (`data/SessionPaths`), `raw_deformed/`, reference copy and the index row via `data/SessionRepository.buildSessionRecord` → `data/SessionStore.upsert` |
| Then | `SessionRepository` calls `data/CloudSync.enqueueUpload` → B1 when cloud backup is on |
| Fails as | `EngineFailure.reasonRes` dialog with **Why?** → FAQ; stop reason persisted on the record (`stopCode`, `plannedFrameCount`) so it survives a restart, and in the backup's `metadata.json` `metrics` so it survives a restore. A re-run that saves nothing updates or drops its Home row to match what is left on disk (`DicBatchRunner.afterUnsavedRerun`); a cancelled re-run is saved as a partial run |
| Signals | Timber; `android.os.Trace` sections; `analytics/SemperAnalytics` analysis started / completed / failed (consent-gated, buckets only) |
| Tests | `analysis/VsgStudyTest`, `analysis/SubsetRecommenderTest`, `analysis/ConvergenceGateTest`, `session/FailureProvenanceTest`, `results/DicResultDecodeTest`, `EngineFailureTest`, `AnalysisViewModelTest`, instrumented `pipeline/EnginePipelineSmokeTest` |

**Accuracy invariants** (do not "clean up"): the JNI output buffer is sized to
the ROI grid and the returned point count is checked against that capacity
before read-back; `computeFullFieldDirect` stays inside the one batch loop;
progress is a buffered `SharedFlow`, not a `StateFlow`.

### A6 ROI editor

| Field | Value |
|---|---|
| Entry | `ui/analysis/RoiDrawActivity` (started for result by A5.2) |
| Chain | `ui/analysis/StudioOverlayView` (draw / hit-test / mask) → `StudioOverlayMaskEncoder` → `util/OverlayFormats`; resolved back by `ui/analysis/RoiResolveHelper` |
| Writes | Mask file at `DicKeys.MASK_FILE_PATH`; ROI rect in `DicKeys.ROI_*` |
| Tests | `util/OverlayFormatsTest` |

Circle / ellipse / freeform are implemented in `StudioOverlayView` but not
exposed by `activity_roi_draw.xml` — see §11 of [app/WORKFLOWS.md](app/WORKFLOWS.md).

### A7 Parameter-sweep lattice `[sweep]`

| Field | Value |
|---|---|
| Entry | `ui/analysis/VsgLatticeActivity` (a sweep opens here, not in the viewer) |
| Chain | `VsgLatticeView` (nodes) + `VsgPlotView` (line-cut plot) + `VsgStudy` (plan maths); node open → A8 |
| Reads | `DicKeys.SWEEP_*` extras packed by `AnalysisNavHelper.openResults` or `ui/home/SessionOpenHelper.intentFor` |
| Writes | `data/ParamClipboard` on a parameter-chip copy |
| Fails as | Hollow node → `EngineFailure.shortReasonRes` + FAQ |
| Tests | `analysis/VsgStudyTest`, `EngineFailureTest` |

### A8 Result viewer

| Field | Value |
|---|---|
| Entry | `ui/viewer/ResultViewerActivity` (+ `ResultViewerViewModel`) |
| Reads | `.dat` frames via `DicResult.decodeDatFile` (memory-mapped) and `data/DatCodec` |
| Renders | `report/VisualizationEngine` heatmaps, `ui/viewer/HeatmapFit` rest-fit, `TouchImageView` zoom/pan, `ViewerFieldPills`, `ScrubFrameCache` look-ahead; single-setting only: `ViewerSummaryHelper` + `SummaryAnimation` + `report/GifEncoder` |
| Probe | `ViewerInspectHelper` + `PointSpatialIndex` (built lazily on first tap) + `InspectOverlayView` |
| Details | `ViewerSettingsSheet` (ⓘ). On a frame: true extrema plus a Scott-binned histogram of accepted values (`FieldHistogramView`). On the summary: min of every frame's colour-bar min and max of every frame's colour-bar max, matching the GIF; no mean, no histogram |
| Exports | `ShareCenter` → `ViewerReportFactory` / `report/ReportBuilder` / `PdfReportGenerator` / `AnalysisCsvWriter` / `data/SessionEverythingExporter` → `SendToSheet` → `SaveExportActivity` (SAF) |
| Fails as | Snackbar + **Why?** FAQ (`no_batch_data`, OOM, scale) |
| Tests | `results/*` (decode, histogram, CSV, heatmap, PDF, GIF, summary), `viewer/ScrubFrameCacheTest`, `viewer/FrameNumberEntryTest`, `HeatmapFitTest`, `ViewerFieldPillsTest` |

**Memory invariants**: the scrub cache is byte-bounded and filled by one
serialized worker; whole-batch passes (summary scan, spatial index) start on
demand, never on open; report compositing is capped at
`VisualizationEngine.REPORT_MAX_EDGE`.

### A9 Session limit

`ui/limit/SessionLimitActivity` → `CloudSync.reconcile(deep = true)` +
`TokenStore` quota → back to A3 when the cap clears. Reached from Home cold
start, the FAB, the quota chip, a pre-run check (`AnalysisNavHelper.ensureSessionQuota`)
or a quota rejection during B1. Not a paywall: the way past it is an email.

---

## B. App — background and data workflows

Nothing here has a screen of its own. These are the flows that keep the user's
data correct while they are elsewhere, and the ones a bug report usually means
when it says "it just didn't work". Four are WorkManager jobs and survive
leaving the app; the rest run on `lifecycleScope` or at process start.

### B1 Back up an analysis to the cloud 🔒

```
CloudSync.enqueueUpload → DicUploadWorker.doWork
    ├─ prepare: SessionUploadBundler → ReportBuilder / VisualizationEngine / GifEncoder
    │            → SessionZip (+ ZipDirectory) → Session.zip + .sha256 in upload_staging/
    ├─ declare: SessionUploadMetadata → IndicApi.createSession ................ C5
    ├─ resume : IndicApi.sessionUploads (poll while PROVISIONING) ............. C6
    ├─ bytes  : DriveTransfer → Drive resumable URI (never through Cloud Run)
    └─ finish : IndicApi.completeFile (size + md5 verified server-side) ....... C7
```

| Field | Value |
|---|---|
| Triggered by | `SessionRepository.saveSession` after a run, the Home badge retry, Settings **Back up now**, turning **Save to cloud** on, and each reconcile that lists the cloud, for rows still PENDING (`CloudSync.reconcile`) |
| Decisions | `data/UploadWorkOutcomes` — HTTP → retry/fail, resume classification, staging reuse, verified `Session.zip`, incomplete staging (`classifyIncompleteStaging`: retry while the reference/`.dat` inputs exist, the row was saved < 15 min ago, or they have been missing < 10 min by the `<sessionDir>/upload_inputs_missing_since` marker; else terminal `inputs_missing`) |
| Writes | `<sessionDir>/upload_staging/`, sync state + `cloudSessionId` on the index row; `StorageBudget.enforce` runs at the end |
| Fails as | Terminal: `DicKeys.UPLOAD_FAIL_REASON` in the worker output → Home pill + badge dialog. Retryable: `Result.retry()` with a Timber `Upload RETRY` line |
| Signals | `data/TransferNotifications` foreground notification; `DicKeys.UPLOAD_PHASE` / `UPLOAD_PERCENT` progress; `SemperAnalytics` cloud_upload_* buckets; the backend's `X-Request-Id` appended by `UploadWorkOutcomes.withRef` |
| Tests | `cloud/UploadResumableTest`, `cloud/DicUploadWorkerOutcomesTest`, `cloud/UploadChunkSizingTest`, `cloud/BackupSplitTest`, `cloud/SessionZipTest`, `cloud/WaitingUploadsTest` |

`doWork` may be broken into named steps, but the resume contract depends on
the staged bytes being **identical** across attempts (the declared size and sha256 are what Drive's
resumable URI and `C7` reconcile against).

### B2 Restore an analysis from the cloud 🔒

| Field | Value |
|---|---|
| Entry | `CloudRestore.enqueueRestore` (Home row tap, or Settings **Restore**) → `data/DicRestoreWorker` |
| Chain | `CloudRestore.restore` → `IndicApi.listSessionFiles` (C9) → `IndicApi.downloadFile` / `downloadRange` → `DriveTransfer` → `SessionZip` unpack → `SessionStore.upsert` |
| Writes | A fresh `<sessionDir>` with `.dat` frames + `raw_deformed/`; `*.part` files while in flight |
| Fails as | `DicRestoreWorker.KEY_ERROR` (carries the `ApiException` message, and with it the backend `ref:` id) → pill on Home **and** Settings |
| Retry rules | `data/RestoreDownloadOutcomes` — 5xx/timeout Range-resumes, `ZipException`/corrupt transfer is terminal |
| Tests | `cloud/RestoreAndImportSafetyTest`, `cloud/RestoreDownloadOutcomesTest`, `cloud/DriveTransferDownloadTest`, `cloud/DownloadWindowSizingTest` |

### B3 Download a session bundle to a file

`CloudRestore.enqueueBundleDownload` (Settings **Download**, SAF destination
chosen **first**) → `data/DicBundleDownloadWorker` → the cloud `Session.zip`, or
a locally packed one via `SessionZip` when the cloud copy is unavailable. A
failed download deletes the empty destination rather than leaving a 0-byte file.

### B4 Erase a cloud backup 🔒

`ui/home/SessionSelectionController` or `SettingsActivity` → `data/SessionDeletes.enqueue`
(one unique `session-delete` chain, so a second confirm queues behind the first) →
5-second undo → `data/BackupDeleteWorker` → one analysis at a time through
`CloudSync.eraseCloudBackup` / `eraseEverywhere` → `IndicApi.deleteSession` (C11).
A 429 waits 5 s and retries that analysis (up to six tries) instead of reporting it
as still in the cloud; a cloud delete clears the row's `cloudSessionId`, so a later
delete of the phone copy sends nothing. `ui/common/DeleteFeedback` shows Undo,
"Deleting x of y…" and the outcome, with **Try again** for what is left. Settings
re-lists with `CloudRestore.listCompleted`, which is uncached, so the list stops
offering what no longer exists.

### B5 Reclaim local space

`data/StorageBudget` — `enforce` at `SemperApp.onCreate` (before any screen),
`freeAllBackedUpAsync` / `enforceAsync` from `ui/settings/SettingsStorageSection`.
It frees local frames of **backed-up** analyses only; a row whose frames were
freed becomes "Only in cloud", not "data gone".
Pinned by `session/LocalStorageFootprintTest`.

### B6 Clear the cache

`data/CacheJanitor` from `SettingsStorageSection` — regenerable files only
(previews, GIF cache, staging leftovers). Also provides the size measurement the
Storage section shows.

### B7 Reconcile with the cloud 🔒

`CloudSync.reconcile(deep = …)` from Home pull-to-refresh and the limit screen →
`IndicApi.listSessions` (paged, `verify=true` on a deep refresh) → C8. Repairs
sync state, re-enqueues B1 for local-only rows, refreshes the quota in
`TokenStore` / `AppRemoteConfig`.

**A Drive outage must not look like deleted data**: local metadata is dropped
only when the backend *confirms* a blob is missing (`C8` returns `MISSING`, not
`UNKNOWN`).

### B8 Export data 🔒

| Export | Entry | Chain |
|---|---|---|
| Everything on this phone | Settings **Export my data** | `data/SessionEverythingExporter` → `SessionZip` / `ZipDirectory` → `SendToSheet` |
| Everything in the cloud | Settings **Download my cloud account data** | `IndicApi.exportAccount` (streamed) → C12 → `SendToSheet` |
| Viewer exports | A8 Share | `ui/viewer/ShareCenter` → `report/*` → `SendToSheet` |

All three run behind `ui/common/TransferBannerController`, not a modal dialog.
Tests: `cloud/SessionEverythingExporterTest`, `results/*`.

### B9 Delete the account 🔒

`ui/settings/SettingsYourDataSection` → re-authentication via `ui/common/AuthRoute`
(password, Google or email link) → `CloudSync.deleteAccount` → `IndicApi.deleteAccount`
(C13) → **only on success** the local wipe (`SessionStore`, `TokenStore`,
`DicSettings`) and Firebase `delete()`. Backend first, deliberately: a local wipe
on a failed server call would strand the cloud copy.
Tests: `cloud/AccountDeletionTest`, `settings/DeleteAccountReauthTest`.

### B10 Diagnostics consent 🔒

One flag, two consumers:

```
DicSettings.diagnosticsEnabled ─┬─ Diagnostics.apply/setEnabled → Crashlytics + CrashReportingTree
                                └─ analytics/SemperAnalytics.event (dropped, not queued, when off)
```

Asked once on first run (after the beta notice, defaulting to **off**), mirrored
by the Settings toggle (**Send crash reports and usage data**). Events carry
buckets and enums only — never images, results, session ids, specimen names or
paths. The label, its subtitle, the first-run prompt and
[legal/PRIVACY_POLICY.md](legal/PRIVACY_POLICY.md) §2.4 all name both halves;
keep them in step if the event set changes.
Tests: `analytics/SemperAnalyticsTest`.

### B11 Device identity and attestation 🔒

| Field | Value |
|---|---|
| Key | `data/DeviceKeyManager` — EC P-256 in the AndroidKeyStore, private key never leaves it |
| Register | `IndicApi.registerDevice` → C2 (409 = this account or device is bound elsewhere) |
| Per call | `IndicApi.fetchChallenge` → C3, then `signedHeaders` signs `(nonce ‖ METHOD ‖ path) ‖ SHA-256(body)` → verified by `C4` |
| Tokens | `data/net/TokenProvider` / `TokenStore` — Firebase ID tokens are held in memory, never persisted |

### B12 Local session index

`data/SessionStore` (atomic index write, `synchronized`, corruption flag) +
`data/SessionRecord` + `data/SessionQuotaGate` + `data/SessionPaths` (the one
place that knows `frame_%04d.dat` and `raw_deformed/`).
Tests: `session/SessionStoreAtomicTest`, `upgrade/PrefsUpgradeSmokeTest`.

### B13 Remote product limits

`data/net/AppRemoteConfig` caches `GET /v1/config` (C-config): max sessions, max
files per session, max frames. The wizard's frame cap and the quota chip read it,
falling back to compile-time defaults when it has never been fetched.

### B14 Parameter clipboard

`data/ParamClipboard` — one subset/step/VSG (px) triple, copied from the A7
parameter chip and pasted in A5.2. In memory only.

### B15 Transfer progress surfaces

One notification channel (`semper_transfers`, `data/TransferNotifications`)
carrying upload, restore and download; per-row badges and bars on Home; the
`TransferBannerController` strip in Settings and the viewer. There are no
completion notifications by design — terminal failures surface in-app.

---

## C. Backend — request workflows

FastAPI on Cloud Run, in front of Firestore (index + metadata) and a Google
Drive shared drive (bytes). **File bytes never transit Cloud Run on the way up**
— the phone PUTs straight to a Drive resumable URI; only downloads are proxied,
because Drive has no anonymous signed read.

Handlers stay plain `def` (Firestore and Drive calls are blocking, so Starlette
runs them in its threadpool). Every route is in `backend/app/routers/`; shared
pieces are `deps.py` (auth), `firestore_repo.py` (all Firestore access, a facade over `repo/`),
`drive.py` (all Drive access), `errors.py` (the `detail` codes),
`validation.py`, `rate_limit.py`, `audit.py`, `observability.py`.

### Cross-cutting

| Id | Concern | File | What it does |
|---|---|---|---|
| C17 | Access log | `main.py` `access_log` middleware | One JSON line per request: `requestId`, `opClass`, `routeTemplate`, status, latency, outcome, uid/deviceId when known. Stamps **`X-Request-Id`** on the response — the app now echoes it into failure reasons (B1/B2), so a user's screenshot joins to this line |
| C17a | Route classes | `observability.classify_route` | `health` / `attest` / `login` / `config` / `account` / `backup` / `sync` / `restore` / `admin`; ids in paths collapse to `{id}` so nothing identifying lands in `routeTemplate` |
| C18 | Audit trail 🔒 | `audit.record` | Append-only `audit_logs`: AUTH_DENIED, DEVICE_*, SESSION_CREATE/DELETE, UPLOAD_COMPLETE, FILE_DOWNLOAD, DATA_EXPORT, ACCOUNT_DELETE, ADMIN_*. Best-effort — an audit write never fails the request |
| C19 | Access-request mail 🔒 | `notify.access_request` | On first PENDING user, mails support via Resend on a daemon worker with retry + per-uid idempotency. Off (silently) without `RESEND_API_KEY` |
| C20 | Rate limits | `rate_limit.py` | Per-instance token buckets per uid (`export`, `erase` (account), `session_erase` (one analysis), `download`, `session`, `session_verify`, `challenge`, `device_register`, `file_complete`, `listing`, `admin`, `health`). The durable cross-instance limits are the gateway quotas in `backend/gateway/openapi.yaml` |
| C20a | Security headers | `main.py` `security_headers` | nosniff, `frame-ancestors 'none'`, no-referrer, Permissions-Policy, HSTS behind HTTPS |
| C20b | Startup checks | `main.py` `_startup_checks` | Refuses to start on Cloud Run without the required env; refuses `DEV_INSECURE_AUTH` unless explicitly acknowledged. Interactive docs are served locally only |

### Authentication pipeline 🔒

| Id | Workflow | Entry | Chain |
|---|---|---|---|
| C1 | Identify the caller | `deps.current_user` | `google_auth.verify_id_token` → `firestore_repo.get_or_create_user` (auto-approve rules, device binding, `DeviceInUseError` → 409) → 403 unless APPROVED. First PENDING user triggers C19 |
| C2 | Register a device | `POST /v1/devices/register` (`routers/devices.py`) | One account per device and one device per account: a different bound device is `device_conflict`, a device owned by another uid is `device_in_use` (audited). Re-registering the same id heals the stored public key |
| C3 | Mint a nonce | `POST /v1/challenge` | `firestore_repo.issue_nonce(uid, deviceId)` — single-use, bound to the pair |
| C4 | Verify a device-signed call | `deps.verified_device` | ACTIVE device → `consume_nonce` (replay = 401) → ECDSA P-256 over `(nonce ‖ METHOD ‖ path) ‖ SHA-256(body)` → `bad_signature` audited on failure |
| C14 | Admin | `routers/admin.py` | Listing and **device-history** need only an admin ID token; **approve / revoke / config-patch / mint additionally require a step-up** — a device attestation, or a second factor plus a recent sign-in for the staff console — so a stolen ID token alone cannot change access. Whole-licence revoke uses a tighter freshness window. All audited |

### Account and identity routes

| Id | Route | Auth | Notes |
|---|---|---|---|
| C-me | `GET /v1/me` | C1 | uid, email, role, access_status. 409 = device conflict |
| C-config | `GET /v1/config` | C1 | Resolved limits (per-user override → fleet default) — feeds B13 |
| C12 | `GET /v1/me/export` 🔒 | C4 | GDPR Art. 20. **Streamed** — profile, devices, then each session with its file manifest, `"complete": true` written last so a truncated transfer is detectable. `Cache-Control: no-store`. Audited as DATA_EXPORT |
| C13 | `DELETE /v1/me` 🔒 | C4 | GDPR erasure. Drive subtree **first** (one call via the stored user-folder pointer, else per-session by stored id), Firestore second — never the other way round. Per-phase timings in the audit detail. Only the audit trail survives |

### Session and file routes

| Id | Route | Auth | Notes |
|---|---|---|---|
| C5 | `POST /v1/sessions` | C4 | Idempotent on `localSessionId` while in flight. Quota + file-count check → **reserve the session doc before any Drive I/O** → batch-write file docs → enqueue provisioning (C15) or provision inline with rollback. The session is tagged with the asking app (`app`, ADR-014) |
| C15 | Provisioning | `session_provision.provision_session` | Opens one Drive resumable URI per unprovisioned file, bounded fan-out. Idempotent: only files with no `uploadUrl` are touched. Inline failure → `purge_session` (Drive **and** Firestore); queued failure → `PROVISION_FAILED` so a poller stops waiting |
| C16 | `POST /v1/tasks/provision-session` | `tasks.tasks_caller` | Cloud Tasks callback, authenticated by the OIDC token's audience **and** invoker service-account email. Not reachable with a user token |
| C6 | `GET /v1/sessions/{sid}/uploads` | C4 | The resume list. Carries Drive capability URIs, which is why it is attested (the ID-token-only migration window was retired 2026-09-26, FI-7). `status` PROVISIONING means "poll", not "nothing to do" |
| C7 | `POST /v1/files/{id}/complete` | C4 | Verifies against Drive's own metadata: size, md5 (skipped only when Drive has none), and that the object's `parents` contains this session's folder — the confused-deputy guard. Advances the session the **file** belongs to, never the one the client named |
| C8 | `GET /v1/sessions` | C1 | The asking app's sessions (`X-App-Id`; `?app=semper\|materialtesting\|all` overrides, ADR-014), each with its `app`; `quota.used` is the whole account's. Cursor-paginated; `?verify=true` probes this page's folders in Drive. Purges metadata **only** on a confirmed `MISSING`; an `UNKNOWN` is counted and reported so the client knows the check was incomplete |
| C9 | `GET /v1/sessions/{sid}/files` | C1 | The restore manifest, cursor-paginated (it used to truncate at 2000). Answers only the app that backed the session up; the other app gets `404 session_not_found` (ADR-014) |
| C10 | `GET /v1/files/{id}/content` | C4 | The one route that streams file bytes out. Forwards `Range` to Drive and returns 206 so a restore resumes; audits only the window starting at byte 0; sanitises the client-supplied filename before it reaches `Content-Disposition` |
| C11 | `DELETE /v1/sessions/{sid}` 🔒 | C4 | Drive folder then Firestore metadata; nothing soft-deleted |
| C21 | `PUT /v1/sessions/{sid}/metadata` | C4 | Replaces a COMPLETED session's metadata.json in place, for a change made after the backup (a rename; in Material Testing also a deflection or tensile curve correction; ADR-013). Refuses an unfinished upload (409 `session_not_complete`), another session's metadata or an unknown schema (422), over 256 KB (413). The file doc's size and checksums follow the new bytes. The only write to a completed file |
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
| D1 | CI gate | `.github/workflows/ci.yml` | PR to `main`, push to `main`, or `workflow_dispatch` (`full_ci` runs every tier on any branch). Path filters pick the tiers; `full-ci` / `e2e` / `release` / `benchmark` labels widen them |
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
| An error with a **Why?** action | `ui/common/FaqRedirect` and [app/FAQ_LINKS.md](app/FAQ_LINKS.md) — the FAQ url resource names the case |
| A wrong number in the viewer or ⓘ sheet | The extras it was opened with: `AnalysisNavHelper.openResults` (fresh run) or `ui/home/SessionOpenHelper.intentFor` (reopen) → read in `ResultViewerActivity` / `ViewerSettingsSheet` / `ViewerReportFactory`. **Check which of the two packed it** — see E2.1 |
| A wrong number in an export | `report/ReportBuilder` (fusion), `report/AnalysisCsvWriter`, `report/VisualizationEngine`; the source of truth is `DicResult.decodeDatFile` over `frame_%04d.dat` |
| "Analysis failed" wording | `ui/analysis/EngineFailure` (code → string) + `AnalysisRunCodes`; the code itself comes from the engine or `ConvergenceGate` |
| A backup that failed | Home badge dialog text = `DicKeys.UPLOAD_FAIL_REASON` from `DicUploadWorker.failure` → `UploadWorkOutcomes` for the decision. `adb logcat -s Semper` shows `Upload RETRY`/reject lines in release too |
| A restore that failed | `DicRestoreWorker.KEY_ERROR` (the `ApiException` message) → `CloudRestore` → `RestoreDownloadOutcomes` for retry vs terminal |
| Any cloud 4xx/5xx | The reason carries `(ref: <id>)` — that is the backend's `X-Request-Id`. Search the Cloud Run log for `requestId="<id>"` to get the exact access line (`opClass`, `routeTemplate`, `errorCode`, latency) |
| A cloud call that is rejected consistently | `backend/app/errors.py` names the `detail` code; the client's branch is in `data/net/ApiErrors.kt` + `IndicApi.failSigned` |
| Something the user says vanished | `CloudSync.reconcile` (B7) and `C8`'s `MISSING` vs `UNKNOWN` rule, then `StorageBudget` (B5) — "Only in cloud" is not "gone" |
| Work that never started | It is WorkManager: `adb shell dumpsys jobscheduler`, plus the unique work names in `CloudRestore.workName` / `bundleDownloadWorkName` |
| A backend behaviour with no obvious owner | `backend/app/observability.py` `classify_route` gives the `opClass`, which names the workflow group in §C |

### E2 Where backtracking is genuinely hard

Ranked by how often it costs someone an afternoon. Each has a proposed fix in
[ops/FUTURE_IMPROVEMENTS.md](ops/FUTURE_IMPROVEMENTS.md).

**E2.1 The viewer's input state is packed in two places and read in four.**
~25 `DicKeys` extras are built by `AnalysisNavHelper.openResults` (fresh run) and
again by `SessionOpenHelper.intentFor` (reopen from Home / Settings), and the two
sets are not identical — `DEF_PATH` and `DEF_FILE_PATHS`, for instance, exist
only on the fresh-run path. They are then read in `ResultViewerActivity` (~20
sites), `VsgLatticeActivity` (~16), `ViewerSettingsSheet` (~12) and
`ViewerReportFactory` (~8). A missing extra is not an error: it silently becomes
a default, so the same session can render differently depending on how it was
opened, with nothing in the log. → **FI-1**

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
