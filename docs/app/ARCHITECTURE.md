# Android app architecture

Start with the [documentation index](../README.md) for the DIC primer and
glossary. This page is the map of the **Kotlin / UI layer** — where screens
live, how they hand off, and which helpers to edit.

Nothing here requires the optional cloud backend. Analysis runs entirely
on-device.

## Activity flow

```
SplashActivity
    ├─ (no session / error) → AuthActivity
    ├─ PENDING              → PendingApprovalActivity
    └─ APPROVED / offline   → HomeActivity
                                ├─ Import → StaticAnalysisActivity
                                │              └─ ResultViewerActivity
                                └─ open session → ResultViewerActivity
                                                     (or VsgLatticeActivity for sweeps)
```

Access-status routing is centralized in
[`AccessRouter`](../../app/src/main/java/com/sempermechanics/semper/ui/auth/AccessRouter.kt)
using constants from
[`AccessStatus`](../../app/src/main/java/com/sempermechanics/semper/data/account/AccessStatus.kt).
Do not re-encode `"APPROVED"` / `"PENDING"` switches in new screens — call the
router.

Intent extras shared across Activities live in
[`IntentKeys`](../../app/src/main/java/com/sempermechanics/semper/navigation/IntentKeys.kt).

## Package map

Layout and rules (about 15 files per package, about 500 lines per file, the
fused hot loops that stay whole, what never moves):
[ADR-015](../adr/ADR-015-package-layout.md). How views are reached and which
shared UI helper to use: [ADR-017](../adr/ADR-017-viewbinding-and-ui-kit.md).
Tests mirror the package of the class they test. Counts are main Kotlin files
on 2026-10-03.

| Package | Files | Role |
|---|---|---|
| `ui/auth/` | 13 | Splash, sign-in (`AuthActivity` with `AuthTotpController` and `AuthPasswordReset`; its form, an `AuthScreen`, and its calls live in `AuthViewModel`, which a rotation keeps), pending approval, terms, `AccessRouter`, `GoogleSignInHelper`, `PasswordPolicy` |
| `ui/home/` | 19 | `HomeActivity` and its parts: `HomeViewModel` (the list read, queued deletes, offered backups and restores, which a rotation keeps; ADR-016), `HomeNewAnalysis` (the + button's source chooser and Re-run), session list and selection (cards under the day headers `SessionDays` inserts, painted by `SessionRowText`, `SessionStateIcon` and `SessionThumbs`, the last caching the last frame's U heatmap as `SessionPaths.resultThumb`), `HomeQuotaCard`, `HomeLicenseNotice` (the expiry chip, naming the day grace ends; no call to action), `CloudBackupsCard`, `FirstRunPrompts`, `HomeFabLayout`, `HomeTransferWatch` (backup and restore jobs), `BackupBadgeActions` |
| `ui/analysis/` | 3 | The three analysis Activities only: `StaticAnalysisActivity` (the wizard), `RoiDrawActivity`, `VsgLatticeActivity` |
| `ui/analysis/wizard/` | 22 | `AnalysisViewModel` with `RunChannels` (launch batch and sweep) and `SweepAnalysis`; `WizardStep`, `AnalysisWizardChrome.applyStep`, `AnalysisWizardHost`; `WizardState` / `WizardDraftBinding` (process death, [ADR-005](../adr/ADR-005-wizard-process-death.md)); slots, coach, nav, ready / cancel / leave gates; parameter fields and sliders; settings sheet |
| `ui/analysis/run/` | 19 | `BatchAnalysis.kt` (`runBatchAnalysisBody`, the one JNI loop, and `afterSave`) + `DicFieldIo`; `RunRecordSave` (`saveRunRecord`); `BatchRunController` (shows every way a run ends, `RunEnd`, read once from its outcome by the pure `RunEnd.of`; a result opens from its saved row through `ViewerArgs.of`, a sweep that solved nothing through `ViewerArgs.forFailedSweep`), `WizardRunLauncher`, `RunStatusLine`, `RunChrome`; the run sheet `ComputeOverlayController` (docked at the bottom over a light scrim, padded clear of the navigation bar: a header row of what is running and its percent, the run's reference and time left, then a batch run's convergence bins, a sweep's live lattice with the combination being solved and how the last one went, or an import's progress bar; Cancel at the end; and `showDone`, which `BatchRunController` holds for 800 ms, none with animations off, before the viewer opens on a run that solved every frame) with its wording (`RunOverlayText`) and convergence bins (`ConvergenceBinsView`, one bin a frame at most 18 dp wide, labelled with their values up to 12 frames, merged keeping the lowest over 100 frames; `ConvergenceTrace`, which warns one strike before `ConvergenceGate` stops a run); `RunSpec` ([ADR-004](../adr/ADR-004-runspec.md)) and `BatchRun`, whose `RunInputs` (reference, frames, working session) are frozen on Main at launch so the run reads its images from no wizard field; `EngineFailure`, `ConvergenceGate`, `SemperEngine` |
| `ui/analysis/rerun/` | 6 | Home's Re-run as a new analysis ([ADR-022](../adr/ADR-022-rerun-as-copy.md)): `RerunInputs` (the `inputs.json` schema and `readComplete`), `RerunSetup` (the wizard state frozen with `RunInputs`), `RerunKeeper` (`keepBatchRerun` / `keepSweepRerun`, writing `<session>/rerun/` once a run saves), `LegacyRerun` (`RerunSource`, and inputs from the record for a row without `rerun/`, whose reference Home's `ui/home/RerunReferencePicker` has picked again), `RerunLoader` (copies into the import cache) and `RerunImportController` (the wizard side, `AnalysisViewModel.applyRerun`) |
| `ui/analysis/frames/` | 13 | Reference and frame import (`ReferenceImportController` with `ReferenceSlotBusy`, `FrameImportController`, `WizardMediaPickers`), ordering (`FrameOrderController`, adapter, menu), deformed batch, video (`VideoSamplingSheet`, extract controller) |
| `ui/analysis/roi/` | 7 | ROI studio: `StudioOverlayView` with its geometry, viewport and mask encoder; `RoiViewport`, `RoiResolveHelper`, `RoiStudioLauncher` |
| `ui/analysis/recommend/` | 11 | `SubsetRecommender` and `SubsetRecommendationController` with the `SubsetBand` shaded behind the subset slider, `RunEstimate` (the ROI row's point count and the time on Compute), speckle scale, noise floor, good-practice and strain-window copy, EXIF patch map |
| `ui/analysis/sweep/` | 18 | Parameter sweep: `SweepSetupController` with `SweepRangeFields` / `SweepFramePicker` (the step-2 frame field and its picker dialog), `SweepStudy` / `SweepStudyRunner`, the lattice (`SweepControls`, `SweepProfiles`, `SweepGraphExport`), the run overlay's `LiveSweepLattice`, and plot views (`SweepPlotView` with viewport, axes, palette; `PlotStyle`), line-cut preview |
| `ui/viewer/` | 19 | `ResultViewerActivity` and its controllers (`ViewerChromeController`, `ViewerImageLoader`, `ViewerFrameLoader`, `ViewerScaleController`, `FrameJumpController`, `ViewerCaptions`, `ViewerShareController`, `FieldPopup`), `SaveExportActivity`, their ViewModels, `ViewerArgs` ([ADR-003](../adr/ADR-003-viewerargs-read-side.md)), scrub cache, `ViewerFieldPills`, the ⓘ details sheet; heatmaps draw each frame on its own photo at the displaced positions ([ADR-011](../adr/ADR-011-viewer-deformed-frame.md)) |
| `ui/viewer/share/` | 13 | `ShareCenter` (its sheet copy in `ShareSheetCopy`) → `ShareExportJobs` (held by `ResultViewerViewModel`) → `ShareExportBuilder` (`FieldImageExport`, `BundleExport`, `DataExport`); `ExportProgress` (each kind's per-frame / per-field progress); `ShareExportController`, `ShareKind`, `SendToSheet`, `ViewerReportFactory` |
| `ui/viewer/summary/` | 3 | Summary GIF (`SummaryAnimation`, each frame at the displaced positions), caption, `ViewerSummaryController` |
| `ui/viewer/inspect/` | 5 | Tap-to-probe: `InspectOverlayView`, `PointSpatialIndex`, `TouchImageView`, field histogram view |
| `ui/settings/` | 15 | `SettingsActivity` (restore, download, delete) and its sections: account, cloud, analyses, storage, preferences, your data, help, footer; `AccountDeletionRun`, `BusyTransfers`; scroll body via `SettingsScrollContentView` |
| `ui/phones/` | 3 | Your phones ([ADR-033](../adr/ADR-033-licence-per-app-explicit-activation.md)): `PhonesActivity` + `PhonesViewModel` list the account's phones for this app, Activate this one and Deactivate any; `PhonesView` holds the wording rules. Home's `HomeActivationCard`, Settings' account row and the limit screen open it. Calls go through `data/account/phones/DeviceActivation` over `PhonesApi` |
| `ui/admin/` | 1 | Admin screen — approve/revoke users via `/v1/admin/*` |
| `ui/limit/` | 5 | Session-quota, seat-required and update-required screens (`UpdateSource` picks Play or the release page), and `LicenseRefresh`, the "Refresh licence" fetch shared with Settings ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md); 2026-10-18) |
| `ui/common/` | 18 | Cross-screen basics: `LauncherIcon` (enables the light or the jet-black launcher alias of `SplashActivity` to match the system theme, at start and on a configuration change), insets, motion (`Motion.reduced` for animations off), `CollapsibleSection` (the wizard's closed **Advanced** sections), keyboard (`Keyboard`, `ImeReveal`), toggle groups, `dp`, `SerialJob`, `ConflatedRefresh`, `Busy` (`setBusy`), `InlineBusy` (a slot's busy state for a wait past 300 ms: the reference slot, the speckle chip, the video read, the viewer's opening pill), `ViewportMath`, `ByteSize`, `EtaEstimator` (time left for runs, exports and transfers), `ProgressText` (their "34.6%", "4.2 of 12.0 MB · 1.1 MB/s" wording), coach marks, the settings section header |
| `ui/common/dialog/` | 9 | `Feedback.toast`, `CrispToast`, `Dialogs` (info, confirm, i-buttons), `Sheet` (`inflateSheet`), `WarnChip`, `FaqRedirect`, delete-choice and progress dialogs, `LicenseLock` (a licence-locked control and its note) |
| `ui/common/auth/` | 6 | `AuthRoute` (re-authentication), sign-out confirm and run, `ExternalLinks`, `SupportMail` |
| `ui/common/media/` | 7 | `MediaPickerSheet` (Home **+** and the wizard dropzones), `MediaStoreBrowser`, `MediaSourceChooser`, `ThumbnailLoader`, `DisplayNames` (a picked image's or video's file name) |
| `ui/common/transfer/` | 4 | `TransferBannerController`, `TransferWorkObserver` (one reading of WorkManager jobs for Home and Settings), `DeleteFeedback`, `RestoreFailureNotice` |
| `data/` | 11 | The six WorkManager workers (WorkManager stores their class names, so they never move) and the backup's steps beside `DicUploadWorker`: `UploadStaging`, `UploadSessionPlanner`, `UploadRun`, `UploadFailures`, `UploadTuning` |
| `data/session/` | 17 | `SessionStore` / `SessionRecord`, `SessionPaths` / `SessionLayout`, `SessionRepository`, `SessionNaming`, zip and `.dat` codecs, storage budget, cache janitor |
| `data/cloud/` | 24 | `CloudSync` with `CloudErase` / `CloudReconcile`, upload bundling / metadata / outcomes, `SessionMetadataDoc` (its field serializers in `SessionMetadataSerializers`), deletes, backup listing, account export, `WorkTags` / `TransferWork`, transfer log; the notifications: running (`TransferNotifications`, fed by `TransferMeter` / `TransferProgressUpdates`), outcome (`TransferResultNotifications`) and its Retry (`TransferRetryReceiver`, named in the manifest, so it stays put) |
| `data/cloud/restore/` | 10 | `CloudRestore` with `RestoreBundleFetcher`, `RestoreUnpacker`, `RestoreZipVerifier`; `DownloadFailure`, restore start, download outcomes and progress |
| `data/cloud/sync/` | 3 | "Download from my other phones": `PhoneSyncWorker` (periodic), `PhoneSyncRun` (one run), `PhoneSync` (what it restores, pure) |
| `data/account/` | 15 | `AuthRepository` with `AuthLinks`, `AccessStatusResolver`, `FirebaseOp`, `ReauthCredentials`; device key and env, licence entitlements / errors, seat lease and heartbeat, legal terms, TOTP |
| `data/prefs/` | 7 | `AppSettings`, `CoachPrefs`, `ParamClipboard`, `WizardDraft`, `AccountDeletionMarker`, `PrefKey` / `PrefFiles` |
| `data/net/` | 23 | `SemperApi` with `SemperApiCalls`, `SemperApiSigning`, `SemperApiClients` (the shared OkHttp clients), `Paging`, `ApiHost`; the interceptors; `Authed` / `HttpFailure`; token store/provider; remote config |
| `data/net/drive/` | 5 | `DriveTransfer` over `DriveUploader` and `DriveDownloader` (one `DriveDownload` per call) — bytes straight to and from Drive |
| `report/` | 22 | PDF (`ReportBuilder` with extrema, annotations, colour bar; `PdfReportGenerator`), CSV, GIF, heatmaps (`VisualizationEngine` over `HeatmapColorScale`, `HeatmapRenderer`, `DeformedHeatmap`) |
| `imaging/` | 9 | `BitmapDecoder`, `ImageEncoder`, AVI reader, PNG encoder — decode/encode away from the UI classes |
| `imaging/video/` | 8 | Video frame extraction: hardware / AVI decoders, keyframes, `FrameSink`, `ImageLuma`, `RetrieverFrameClock` (the retriever rung's frame times) |
| `field/` | 12 | `DicResult`, `DatDecoder`, `FieldHistogram`, and the small value types: `ImageSize`, `Roi`, `RunStop`, `DicParams` / `FrameParams`, `ValueRange`, `FieldStats` |
| `diagnostics/` | 4 | `Diagnostics`, `CrashReportingTree`, `EngineDebug`, `SemperAnalytics` (consent-gated Firebase Analytics events) |
| `navigation/` | 2 | `AppIntents` — intent factories so `data` / `report` never import a `ui` Activity — and `IntentKeys`, the shared intent extras |
| `util/` | 11 | `AtomicFiles` / `AtomicWrites`, `Streams`, `Zips`, `Digests`, `Mime`, `suspendRunCatching` and caller cancellation, `BrandAssets` |
| *(root)* | 2 | `SemperApp`, `SemperNativeLib` / `ProgressCallback` (JNI symbol names; never move) |

Style for shared UI logic: plain `object` / small classes. A part that owns a
screen region's views and behaviour is a `*Controller` (or is named for the region
it is: `*Section`, `*Card`, `*Sheet`) and takes the Activity and the binding it
draws on; a `*Helper` is a stateless `object` of functions only. The full naming
rules are in [CONTRIBUTING](../../CONTRIBUTING.md#code-style).
Prefer extracting a part over growing an Activity further. Activity Result
launchers are registered before the Activity starts, either as an Activity
property or by a part built in `onCreate` (`WizardMediaPickers`,
`RoiStudioLauncher`, `ViewerShareController`). Work that must outlive the screen
does not run on `lifecycleScope`
([ADR-016](../adr/ADR-016-work-that-outlives-the-activity.md)), and failures are
typed outcomes that rethrow cancellation
([ADR-018](../adr/ADR-018-error-convention.md)).

## Session layout on disk

Each saved analysis lives under the app's session directory (see
[`SessionStore`](../../app/src/main/java/com/sempermechanics/semper/data/session/SessionStore.kt)):

```
<sessionId>/
  *.dat                 # per-frame correlation results
  field_ranges.bin      # SessionLayout.FIELD_RANGES — the summary's per-frame colour ranges (a cache)
  record_manifest.json  # SessionLayout.MANIFEST — signed SHA-256 of the above, run spec, app and engine pin (ADR-028)
  raw_deformed/         # SessionPaths.RAW_DEFORMED_SUBDIR — original frames
  …                     # metadata / previews as written by the ViewModel
```

The constants `SessionPaths.RAW_DEFORMED_SUBDIR`, `FRAME_DAT_FMT`, and
`SessionPaths.frameDat` are shared by the ViewModel / `BatchAnalysis`,
[`DicUploadWorker`](../../app/src/main/java/com/sempermechanics/semper/data/DicUploadWorker.kt),
and cloud restore so path segments and `frame_0000.dat` names never diverge.

The Home list's rows are `SessionRecord`s in `sessions/index.json`. Fields added
since the first index are optional with a default, and the encoder leaves a
default out, so an older index loads and an older row writes as it did
(`SessionIndexGoldenTest`). A video analysis adds two: `videoName` (the clip's
name without its extension, or "Video") and `frameTimesMs` (each deformed
frame's time in the clip, index-aligned with `defNames`). Home's row line and
the viewer's frame title read them. Neither goes to the cloud copy's
`metadata.json`, so a restored video analysis has neither and its frames are
known by their names.

## Sync workers

When cloud is configured (`SEMPER_API_BASE_URL`):

| Type | File | Job |
|---|---|---|
| Upload | `DicUploadWorker` | Resume/create remote session, stage artifacts, upload bundles. Home tells a failed backup's reason once, not once per Home (`BackupFailureLedger`) |
| Metadata JSON | `SessionUploadMetadata` | frames / device / engine JSON for the API |
| Bundle build | `SessionUploadBundler` | Render frame bundles + CSV lists offline-testable |
| Restore | `CloudRestore` / `DicRestoreWorker` | Pull remote sessions back into local session dirs. Home (row tap, multi-select **Restore**, the cloud-backups card) and Settings all start one through `RestoreStart.start`, which writes the row first so either screen shows its progress; `RestoreFailureLedger` announces each failure once across both screens |
| Backups not on this phone | `CloudBackupListing` | The COMPLETED backups the last successful reconcile listed, saved in prefs, so Home's `CloudBackupsCard` can offer those no row claims (by local id or stored cloud id, the rule `AnalysisEntries.merge` uses) without another request. A cloud delete forgets its entry, sign-out clears it, Hide is remembered per cloud id until that backup leaves the cloud |
| Sync from other phones | `PhoneSyncWorker` / `PhoneSyncRun` / `PhoneSync` | Every 6 hours on an unmetered network with the battery not low, while Settings' **Download from my other phones** is on (default on) and the licence has restore (`LicenseEntitlements.restoreEnabled`: licence, backup, the `restore` kill switch, the version gate): lists this app's backups (`CloudRestore.listCompleted`, no `app`, so Semper and Semper Classroom never cross), saves the listing for the Home card, and starts a restore through `RestoreStart.startAll`, as the card's **Restore** does, of each backup no row on this phone claims. Skips backups hidden from the card, restores already queued, and any it has started before (`PrefFiles.CloudListing.SYNC_TRIED`, cleared at sign-out), so a removed-from-this-phone row ("Only in cloud") or a failed or cancelled restore is never pulled down again on its own. Scheduled on each approved status check (`AccessStatusResolver`), cancelled by the switch and at sign-out. Deletes and changes nothing on the phone |
| Bundle download | `DicBundleDownloadWorker` | Write a session `.zip` into a SAF document the user picked **before** enqueue. Falls back to packing the local session when the cloud copy is unavailable, and deletes the empty destination on failure |
| Delete queue | `SessionDeletes` / `BackupDeleteWorker` | Every delete that touches the cloud: one unique chain, a 5-second undo window, one analysis at a time, 429s waited out. Phone-only deletes stay inline (`CloudSync.eraseLocalOnly`). `ui/common/transfer/DeleteFeedback` reports progress and the outcome on Home and Settings |

A backup's `Session.zip` holds what a restore needs: since `metadata.json`
schema `/4` the reference as `ref/Reference.png` (`SessionZip.REFERENCE_ENTRY`),
the frames under `raw/` by their own names, and `dat/` (the `.dat` frames and,
since ADR-028, `dat/record_manifest.json`); `Extras.zip` holds the
regenerable rest. `RestoreUnpacker.destFor` still reads an older backup's
`raw/Reference.png` as the reference when the archive has no `ref/` entry. The
layouts by schema, and what an older app makes of a `/4` backup, are in
[CLOUD_ARCHITECTURE_GCP.md §4](../backend/CLOUD_ARCHITECTURE_GCP.md#4-upload-sequence-5-gb-resumable-keyless).
A skipped sweep node's `code` is null when the backup predates codes
(`SkippedNode`); it then reads "This combination was skipped" rather than a
strain-window failure.

`SemperApi.listSessions` **pages**: it follows `nextPageToken` until the backend
stops returning one, so a deep refresh sees the whole account rather than the
first page. Anything that lists cloud sessions should go through it rather than
issuing a single request. It lists this app's sessions (`X-App-Id`, ADR-014);
`app = CloudApi.ALL_APPS` lists the whole account, which the reconcile asks
only when a SYNCED row is missing from its own list, so a row restored from the
other app's backup is not uploaded again (`CloudReconcile.list`).

### The interceptors on the shared client

`SemperApiClients.api` carries four application interceptors, in order:
`RetryOnTransient`, `AppCheckHeader`, `AppIdHeader` (`X-App-Id`,
[ADR-010](../adr/ADR-010-device-binding-per-app.md)) and
`ClientNonce.ServerDateObserver`; `SemperApiClients.download` inherits them
through `newBuilder()`. The first two carry the rules below. Retry is added first, so it
wraps the header: a retried attempt reads a fresh App Check token rather than
replaying one that may have expired while it waited.

`RetryOnTransient` retries **429 unconditionally** — the token bucket
(`backend/app/rate_limit.py`) and the gateway quota both reject before the
handler runs, so nothing happened. On a device-signed route that includes the
nonce: the bucket is a route dependency (`deps.rate_limited`) resolved ahead of
`verified_device`, so the unchanged retry is not a replay, and the 429 carries
`Retry-After` for when the bucket next has a token. **503 is not the same promise**: ESPv2 emits
it before *and* after handing a request on, so it is retried only for GET and
for the POSTs whose handlers are idempotent by contract. Session create and the
upload broker are deliberately absent — a duplicate there costs a Drive object.
Three attempts with a ceiling, honouring `Retry-After`; where a call is
worker-mediated, WorkManager's own backoff owns the long game.

`AppCheckHeader` attaches `X-Firebase-AppCheck` to the configured API host
only — Drive shares this client and has no use for it — and **fails open**: a
build that cannot attest sends no header rather than a failed call, because the
decision to refuse belongs to the backend, which is the side that knows whether
it is in `monitor` or `enforce`. See
[AUTH_SETUP.md §3.2](../backend/AUTH_SETUP.md).

"The API host" is the base URL's host **and port** (`ApiHost`, `host:port` with
the default port spelled out), for `AppCheckHeader`, `AppIdHeader` and
`ClientNonce.ServerDateObserver` alike; the `SEMPER_API_CERT_PINS` pins are
registered for the bare host name, the only pattern OkHttp's pinner accepts.
The shared clients follow **no redirects** (`SemperApiClients`): OkHttp drops
only `Authorization` on a cross-host hop, so a followed redirect would carry
`X-App-Id`, `X-Firebase-AppCheck`, `X-Device-Id`, `X-Nonce` and `X-Signature`
to the new host. A 30x reaches the caller as an unexpected status.

## Licensing & entitlements

The app never decides its own plan — `data/account/LicenseEntitlements.kt` is the one
place that answers "am I demo or licensed," and it reads through
`data/net/AppRemoteConfig.kt`, which caches whatever the backend's
`GET /v1/config` last reported (`plan`, `cloudBackupEnabled`, `shareEnabled`,
`licensePrefix`, `licenseKind`). Fails closed: before the first successful
fetch, and on any ambiguous value, everything reads as Demo.
At launch the status check and the cloud reconcile both ask for config, a few
milliseconds apart; `SemperApi.getConfig` shares one in-flight request between
them (`data/net/SingleFlight.kt`, [perf/request-volume.md](../perf/request-volume.md) Pass 2).

The app redeems nothing: paid entitlements are attached to the account by the
server (roster seat, individual licence, or a claim code redeemed on the web
account page), and the app is consumption-only ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)). It
asks for the current answer with **Refresh licence** (`ui/limit/LicenseRefresh`,
Settings → Account and the limit screen) and, on a first sign-in, again once the
device is registered (`EntitlementRefresh.afterSignIn`). What the licence lacks is
shown locked (`data/policy/FeatureLocks`, `ui/common/dialog/LicenseLock`). See
[CLOUD_ARCHITECTURE_GCP.md §20](../backend/CLOUD_ARCHITECTURE_GCP.md#20-licensing--entitlements)
for the backend's individual-vs-institution split. **On the Android side there is
no distinction** between an individual key and a institution seat — both resolve
to `mode=licensed` with identical entitlements; `licenseKind` is carried
through only for display/support (e.g. "activated via university.edu"), not as a
gating input anywhere in `LicenseEntitlements`.

| Concern | File |
|---|---|
| Plan resolution / gating | `data/account/LicenseEntitlements.kt` |
| Cached config, wire → prefs | `data/net/AppRemoteConfig.kt` (`AppConfigDto` in `ApiDtos.kt`) |
| Fetch the licence now | `LicenseRefresh.run` (`ui/limit/LicenseRefresh.kt`) |
| Locked, paused or hidden | `FeatureLocks` (`data/policy/FeatureLocks.kt`), `LicenseLock` (`ui/common/dialog/LicenseLock.kt`) |
| Expiry notice | `LicenseEntitlements.expiryNoticeDays()` — advisory only; suppressed on a cache older than a week. `mode` stays the only gate. See [WORKFLOWS.md §9.3](WORKFLOWS.md#9-session-limit) |
| Local analysis cap | `SessionQuota.blocked()` (`data/session/SessionQuota.kt`) — the one rule every start check and a new row's save ask: no cap with no backend or for licensed before its config; blocked once an upload's 409 forced the stop; otherwise blocked when max(server count, the phone's rows) reaches `LicenseEntitlements.analysisCap()` (the backend's `maxSessions` once known; before that demo 25, licensed uncapped). A run admitted at its start saves even if the cap fills meanwhile; see [WORKFLOWS.md §9](WORKFLOWS.md#9-session-limit) |

## Storage, diagnostics and the parameter clipboard

Three small subsystems added alongside the cloud work. Each is a plain object
with no framework behind it:

| Concern | Files | Notes |
|---|---|---|
| Local disk budget | `data/session/StorageBudget.kt`, `data/session/CacheJanitor.kt` | Measures analyses and cache; frees the local frames of **backed-up** analyses only. A user-set GB budget is enforced from `SemperApp.onCreate`, so it runs before any screen |
| Crash reporting | `Diagnostics.kt`, `CrashReportingTree.kt` | Crashlytics collection is **off in the manifest** and enabled only on consent (first-run prompt or the Settings toggle). `CrashReportingTree` is a release-only Timber tree feeding breadcrumbs and non-fatals |
| Product analytics | `diagnostics/SemperAnalytics.kt` | Same consent flag as Crashlytics (`AppSettings.diagnosticsEnabled`) — events are dropped, not queued, when it is off. Params must stay PII-free: enums, coarse buckets, success/fail. `Diagnostics.applyAccountType` tags a QC or CI test account `account_type` (`test` / `ci`) under the same flag. The consent copy names both halves (**Send crash reports and usage data**) — keep it and [PRIVACY_POLICY.md](../legal/PRIVACY_POLICY.md) §2.4 in step with the event set |
| Parameter hand-off | `data/prefs/ParamClipboard.kt` | Holds one subset/step/VSG (px) triple, copied from the sweep lattice's parameter chip and pasted into the analysis wizard's correlation section |

An analysis whose local frames were freed becomes a **cloud-only row**: Home
still lists it, badges it, and downloads it on open rather than reporting the
data as gone. The "session data gone" path now means *no* copy exists anywhere.

## Export hand-off

Exports do not go straight to the system chooser. `ui/viewer/share/ShareCenter.kt`
hands off to `SendToSheet`, a bottom sheet offering **Save to Files** (SAF) or
**Share**. For the single-photo target the artifact is built first and then
offered through the transparent `SaveExportActivity`; for the five slow targets
the sheet comes **first** and the export is written straight into the chosen
document. Settings' two data exports use the same path, so there is one place to
change export UX.

Two exports deliberately bypass it: Settings' **Download** already has its
destination (§4 of [WORKFLOWS.md](WORKFLOWS.md)), and the lattice's **Save graph**
goes to the system chooser directly. Save graph still takes the Share check
(`ShareCenter.access` / `blockedMessage`, greyed and explained like the viewer's Share).

Every export of a frame draws what the viewer draws for it
([ADR-011](../adr/ADR-011-viewer-deformed-frame.md), TD-139). The field PNGs
(`FieldImageExport.renderAnnotated`) and the report's field images
(`ReportBuilder.buildReport`, from `ViewerReportFactory` and the cloud bundle)
look up the frame's own photo as the viewer does (`deformedImagePath`), decode it
capped and once per frame, and draw on it with a displaced
`HeatmapRequest` (`VisualizationEngine.render`; the report's fusion pass keeps
its positional `generateFrameHeatmap` call), the viewer's own render, with the MAX / MIN markers where their points moved to. A frame whose
photo is gone or does not decode falls back to the reference and the
reference-position map, as the viewer does. The summary GIF has no photo (its
palette is the jet ramp and the canvas colour), so `SummaryAnimation` draws
every frame displaced (`VisualizationEngine.indices`) in one box widened to
take in the whole sequence's movement; the viewer's summary slot shows that
same GIF.

Long exports are not modal. The progress dialog (`ui/common/dialog/DeterminateProgressDialog`)
shows the job's status, its percent to one decimal and the time left (`EtaEstimator`);
every kind but the one-photo share reports as it goes (`ExportReport`, built in
`ExportProgress.kt`), from callbacks around the encoders, never inside their loops.
The one report from inside a loop is the CSV's: `AnalysisCsvWriter.write` hears each
frame, and its point-row loop (`writeFrame`, not one of the fused hot loops) also
reports every `ROWS_PER_REPORT` (4096) rows, because one dense frame's rows take
seconds. The CSV gives its statistics pass the first 10 % and its point rows the rest.
The everything ZIP gives each stage its share of the bar (`ZipBudget` in
`BundleExport.kt`):

| Stage | Bar | Moves by |
|---|---|---|
| PDF | 0–45 % | frame, then "Finishing the report" for `PdfDocument.writeTo` |
| CSV | 45–62 % (sweep 45–72 %) | frame, and every 4096 point rows |
| Animations (not on a sweep) | 62–72 % | frame of each field |
| Raw photos and GIFs into the archive | 72–75 % | bytes copied |
| Heatmaps | 75–96 % | frame |
| CSV and PDF into the archive | 96–100 % | bytes deflated |

Dismissing the dialog parks the job in
`ui/common/transfer/TransferBannerController` — a non-modal strip with the same status,
percent and time left, Cancel and prev/next paging — hosted by both
`ResultViewerActivity` and `SettingsActivity`, where it also carries restores and
bundle downloads.

## Memory & failure invariants

Non-obvious rules the analysis and transfer paths depend on. Breaking one tends to
show up as an OOM, a mid-run crash, or a "nothing happened" report:

- **JNI output buffer is bounded.** `BatchAnalysis` / `SweepStudyRunner` allocate
  one direct `ByteBuffer` via `DicFieldIo` sized to the ROI grid (`(w/step)·(h/step)`
  points). The engine's returned point count is checked against that capacity
  *before* the buffer is read back — a count over capacity is treated as an engine
  failure, not read past the buffer. JNI `computeFullFieldDirect` stays in that
  one batch loop; do not fragment it.
- **Report/upload compositing is capped to `VisualizationEngine.REPORT_MAX_EDGE`
  (1280 px).** The PDF/cloud heatmaps downscale to 600 px anyway; the cap only
  stops intermediate full-res `ARGB_8888` bitmaps from OOMing on large (e.g. 26 MP)
  references. On-screen scrub uses the separate `DISPLAY_MAX_EDGE` (1080).
- **The viewer's frame look-ahead is bounded by bytes, not just by count.**
  `ScrubFrameCache` caps decoded frames on both a frame count and a byte ceiling
  (`maxDataBytes`, heap/8 by default), so a heavy PLC frame simply holds fewer slots
  instead of the window growing with frame size. `ViewerFrameLoader.prefetchAround`
  fills that window with **one serialized worker**, cancelled and restarted as the user
  scrubs, admitting a frame only while the cache has room and the heap guard passes.
  It must stay serialized: an earlier version launched a coroutine per neighbour on
  every frame load, so peak memory scaled with *how fast the user scrubbed* rather than
  with any bound.
  Out of memory, the loader drops the decoded frames only (`ScrubFrameCache.clearData`):
  the cached heatmaps include the one on screen, so they are recycled only by `clear`
  in the viewer's `onDestroy`, which spares that one. The caches key a render and its
  stats by the frame whose data was drawn (`ResultViewerActivity.rawFrameIndex`), not
  by `currentFrameIndex`, which Prev / Next move before the debounced decode lands.
- **Whole-batch passes are started on demand, never on open.** The summary's colour-scale
  scan (`ViewerSummaryController.start`) decodes **every frame in the batch**, so it runs from
  `show()` rather than from viewer startup — opening straight onto a frame must not pay
  for an N-frame decode the user may never look at. The inspect-mode spatial index
  follows the same rule (built lazily on first tap, invalidated on frame load).
- **Batch progress is a buffered `SharedFlow`** (`replay=1`, `extraBufferCapacity`,
  `DROP_OLDEST`), not a `StateFlow` — a conflating flow dropped intra-frame ticks
  when the native solve emitted faster than the UI collected, stalling the bar.
- **Transfer failures are surfaced, not swallowed.** Terminal worker failures carry
  a human reason in their `WorkInfo` output, read through `TransferWorkObserver`:
  Home (`HomeTransferWatch`) follows **both** backups (cloud icon dialog + snackbar) and
  restores (snackbar), and Settings (`SettingsAnalysesSection`) follows restores and
  Save-to-Files downloads. Quota-full is the one exclusion —
  it routes to its own screen. Progress from the same `WorkInfo` drives the
  per-row state icon, progress text and bar on Home, for downloads as well as uploads.
- **Cancelling a sweep abandons the sweep.** `SweepStudyRunner` checks the cancel
  token *between* combinations as well as inside a solve, so Cancel does not merely
  skip to the next parameter set.
- **A cancel keeps nothing.** A batch run and a sweep write their `.dat` files to
  `<session>/run_staging/` (`SessionPaths.RUN_STAGING_SUBDIR`, from
  `RunChannels.openRunSession`); only an end that is not a cancel moves them into the
  session (`RunSession.commit`, which is also when the previous run's results go) and
  saves the row. A cancel (`BatchAnalysis.runBatchAnalysisBody` → `discardCancelledBatch`,
  `SweepAnalysis.endSweep`) deletes the staging, puts the deformed images the loop
  moved back into the import, deletes a new session's folder only if no row names it
  (`SessionStore.deleteUnsavedDir`, `RunChannels.discardRun`). A run writes none of the
  wizard's fields: it reads the working session frozen at launch (`RunInputs.working`)
  and hands back what it learned in its outcome, which the view model takes once on
  Main (`RunChannels.applyRunOutcome`); a cancel hands back nothing, so the wizard is
  as it was. A cancelled run consumes no quota (the
  quota counts index rows), and since a saved session is never a run's target
  (`applyRunOutcome` drops it once saved; ADR-022), it cannot touch a saved analysis. A stop that is not a cancel
  (low convergence, an engine failure, the session limit) still keeps its frames.
- **A Drive outage must not look like deleted data.** A verifying refresh drops
  local metadata only when the backend confirms a blob is missing, never on an
  indeterminate answer. See [CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md).

## Where to edit

| I want to… | Start here |
|---|---|
| Change sign-in providers / access gate | `data/account/AuthRepository.kt`, `docs/backend/AUTH_SETUP.md` |
| Change post-auth navigation | `ui/auth/AccessRouter.kt` |
| Change the analysis wizard UI | `StaticAnalysisActivity.goToStep(WizardStep)` → `AnalysisWizardChrome.applyStep`; slot chrome / coach in `AnalysisWizardSlots` / `AnalysisWizardCoach`; later steps inflate through ViewStubs; each step's controllers sit in `ui/analysis/frames`, `roi`, `recommend`, `run`, `sweep` |
| Change the full-field batch loop | `BatchAnalysis` + `DicFieldIo` (shared with the sweep). Do not split `computeFullFieldDirect` out of that loop |
| Change Home list / settings | `ui/home/HomeActivity.kt` + `Session*`, `HomeQuotaCard`, `HomeTransferWatch` / `ui/settings/SettingsActivity` + `Settings*Section` |
| Change import / video extraction | `FrameImportController` / `ReferenceImportController` → `FrameImportHelper`, `VideoSamplingSheet`, `VideoFrameExtractor` (three rungs: `AviVideoDecoder` → `HardwareVideoDecoder` → `MediaMetadataRetriever`; all write through `FrameSink`). Fixed-interval instants are `VideoKeyframeHelper.uniformTimestampsUs` for the sheet's estimate and every rung, over a segment the sheet caps at `lastFrameStartMs`. Every rung plans through `VideoKeyframeHelper.planFor`: Keyframes mode takes the clip's sync frames when it has two or more in the segment (hardware: its `MediaExtractor`; AVI: the `idx1` flags via `aviFramePlan`, none for an index-less or all-intra file; retriever: `findKeyframeTimestampsUs(context, uri, …)`), else the uniform instants, logged as a fallback. A frame's clip time is its decoder's own (`DecodedFrame.ptsUs`) on the hardware and AVI rungs; on the retriever, the time of the frame `getFrameAtTime` returns, from `RetrieverFrameClock`'s `MediaExtractor` sample walk (the requested time only when the extractor will not open the clip) |
| Change AVI support | `imaging/AviReader` (demuxer), `imaging/AviLuma` (uncompressed layouts), `imaging/MjpegHuffman` (table repair), `AviCodecDecoder` (`MediaCodec` for Xvid/H.264) |
| Change parameter-sweep setup UI | `SweepSetupController` + `SweepRangeFields` / `SweepFramePicker`; the run is `SweepAnalysis.runSweep` → `SweepStudyRunner` |
| Change the sweep result lattice | `ui/analysis/VsgLatticeActivity.kt` + `SweepControls` / `SweepProfiles` / `SweepGraphExport`, `SweepLatticeView`, `SweepPlotView` |
| Change heatmap / probe | `ui/viewer/ViewerScaleController.kt` (heatmap, colour scale), `ViewerImageLoader.kt` (photo under the map), `inspect/ViewerInspectController.kt` (probe); wired in `ResultViewerActivity.kt` |
| Change how exports are handed off | `ui/viewer/share/ShareCenter.kt` → `ShareExportJobs.kt` → `ShareExportBuilder.kt`, `SendToSheet.kt`, `SaveExportActivity.kt` |
| Change transfer progress UI | `ui/common/transfer/TransferBannerController.kt` (Settings + viewer), `ui/common/ProgressText.kt` (the wording), `data/cloud/TransferNotifications.kt` (the one channel; running notification) and `TransferResultNotifications.kt` (outcome + Retry) |
| Change the new-analysis media sheet | `ui/common/media/MediaPickerSheet.kt` / `MediaSourceChooser.kt` — shared by the Home **+** and both wizard dropzones |
| Show a toast, dialog, sheet or thumbnail | `ui/common/dialog/Feedback`, `Dialogs`, `Sheet`; `ui/common/media/ThumbnailLoader` ([ADR-017](../adr/ADR-017-viewbinding-and-ui-kit.md)) |
| Add an analytics event | `diagnostics/SemperAnalytics.kt` — keep params PII-free and consent-gated |
| Change storage reclaim behaviour | `data/session/StorageBudget.kt`, `data/session/CacheJanitor.kt` |
| Change crash-reporting consent | `Diagnostics.kt`, `CrashReportingTree.kt` |
| Change the C++ engine | The engine is a submodule — see [ENGINE_APP_CONTRACT.md](../engine/ENGINE_APP_CONTRACT.md), not this page |

## Related docs

- [Engine ↔ app contract](../engine/ENGINE_APP_CONTRACT.md) (the engine itself
  lives in the `engine/` submodule — see [engine/ARCHITECTURE.md](../engine/ARCHITECTURE.md))
- [Auth setup](../backend/AUTH_SETUP.md)
- [Cloud architecture](../backend/CLOUD_ARCHITECTURE_GCP.md)
- [Workflow index](../WORKFLOWS.md) — every flow's entry point, file chain and
  failure surface, plus where backtracking is hard (§E)
- [Contributing](../../CONTRIBUTING.md)
