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
[`AccessRouter`](../../app/src/main/java/com/indicvision/semper/ui/auth/AccessRouter.kt)
using constants from
[`AccessStatus`](../../app/src/main/java/com/indicvision/semper/data/account/AccessStatus.kt).
Do not re-encode `"APPROVED"` / `"PENDING"` switches in new screens — call the
router.

Intent extras shared across Activities live in
[`DicKeys`](../../app/src/main/java/com/indicvision/semper/navigation/DicKeys.kt).

## Package map

Layout and rules (about 15 files per package at most; what stays pinned and
why): [ADR-015](../adr/ADR-015-package-layout.md). Tests mirror the package
of the class they test.

| Package | Role |
|---|---|
| `ui/auth/` | Splash, sign-in, pending approval, Google / AccessRouter helpers |
| `ui/home/` | Session list, selection, open-session intents |
| `ui/analysis/` | The three analysis Activities only: `StaticAnalysisActivity` (setup wizard, `goToStep`), `RoiDrawActivity`, `VsgLatticeActivity` |
| `ui/analysis/wizard/` | `AnalysisViewModel`, `WizardState` (process death, [ADR-005](../adr/ADR-005-wizard-process-death.md)), wizard chrome / slots / coach (ViewStub steps 2/3), nav, ready and cancel gates, settings sheet, `WizardStepSettingsContentView` |
| `ui/analysis/run/` | `DicBatchRunner.kt` (`AnalysisViewModel.runBatchAnalysisBody`) + `DicFieldIo`, `BatchRunController`, `ConvergenceGate`, `RunSpec` ([ADR-004](../adr/ADR-004-runspec.md)), run codes, engine failures, compute overlay |
| `ui/analysis/frames/` | Frame import, ordering (adapter, menu), deformed batch, video-extract helper |
| `ui/analysis/roi/` | ROI studio: `StudioOverlayView`, its mask encoder, `RoiViewport`, `RoiResolveHelper` |
| `ui/analysis/recommend/` | `SubsetRecommender`, speckle scale, noise floor, good-practice and strain-window copy, EXIF patch map |
| `ui/analysis/sweep/` | VSG sweep: setup, `VsgStudy` / runner, lattice and plot views, line-cut preview |
| `ui/viewer/` | `ResultViewerActivity`, `SaveExportActivity`, their ViewModels, `ViewerArgs` ([ADR-003](../adr/ADR-003-viewerargs-read-side.md)), scrub cache, `ViewerFieldPills`, the ⓘ details sheet; heatmaps draw each frame on its own photo at the displaced positions ([ADR-011](../adr/ADR-011-viewer-deformed-frame.md)) |
| `ui/viewer/share/` | `ShareCenter`, export builder / jobs / UI, `SendToSheet`, `ViewerReportFactory` |
| `ui/viewer/summary/` | Summary GIF (`SummaryAnimation`), caption, summary helper |
| `ui/viewer/inspect/` | Tap-to-probe: `InspectOverlayView`, `PointSpatialIndex`, `TouchImageView`, field histogram view |
| `ui/settings/` | Settings screen; scroll body inflates via `SettingsScrollContentView`; account/storage/prefs/your-data/help live in `Settings*Section`; restore/download/delete stay on `SettingsActivity` |
| `ui/admin/` | Admin screen — approve/revoke users via `/v1/admin/*` |
| `ui/limit/` | Session-quota screen |
| `ui/common/` | Insets, motion, `MediaPickerSheet` (Import / wizard dropzones), `CrispToast`, `TransferBannerController` |
| `data/` | The six WorkManager workers only; WorkManager stores their class names, so they never move |
| `data/session/` | `SessionStore` / `SessionRecord`, `SessionPaths`, `SessionRepository`, zip and `.dat` codecs, storage budget, cache janitor |
| `data/cloud/` | `CloudSync`, upload bundling / metadata / outcomes, deletes, backup listing, account export, transfer log and notifications |
| `data/cloud/restore/` | `CloudRestore`, restore start, download outcomes and progress |
| `data/account/` | `AuthRepository`, access status, device key and env, licence entitlements / errors, seat lease and heartbeat, legal terms, TOTP |
| `data/prefs/` | `DicSettings`, `CoachPrefs`, `ParamClipboard`, `WizardDraft` |
| `data/net/` | Backend HTTP client (`IndicApi`), its two OkHttp interceptors (`RetryOnTransient`, `AppCheckHeader`), token store/provider |
| `report/` | PDF / CSV / visualization |
| `imaging/` | `BitmapDecode`, `ImageEncode`, AVI reader, PNG encoder — decode/encode away from the UI classes |
| `imaging/video/` | Video frame extraction: hardware / AVI decoders, keyframes, batch writer, `ImageLuma` |
| `field/` | `DicResult`, `DatDecoder`, `FieldHistogram` — the decoded displacement/strain field |
| `diagnostics/` | `Diagnostics`, `CrashReportingTree`, `EngineDebug`, `SemperAnalytics` (consent-gated Firebase Analytics events) |
| `navigation/` | `AppIntents` — intent factories so `data` / `report` never import a `ui` Activity — and `DicKeys`, the shared intent extras |
| `util/` | `BrandAssets`, `Digests`, `OverlayFormats`, `AtomicFiles`, caller cancellation |
| *(root)* | `SemperApp`, `SemperNativeLib` / `ProgressCallback` (JNI symbol names; never move) |

Style for shared UI logic: plain `object` / small classes named `*Helper`,
`*Extractor`, `*Router`, `*Bundler` — same pattern as
`MediaSourceChooser` and `GoogleSignInHelper`. Prefer extracting a helper
over growing an Activity further. Keep `lifecycleScope` and Activity Result
launchers in the Activity.

## Session layout on disk

Each saved analysis lives under the app's session directory (see
[`SessionStore`](../../app/src/main/java/com/indicvision/semper/data/session/SessionStore.kt)):

```
<sessionId>/
  *.dat                 # per-frame correlation results
  raw_deformed/         # SessionPaths.RAW_DEFORMED_SUBDIR — original frames
  …                     # metadata / previews as written by the ViewModel
```

The constants `SessionPaths.RAW_DEFORMED_SUBDIR`, `FRAME_DAT_FMT`, and
`SessionPaths.frameDat` are shared by the ViewModel / `DicBatchRunner`,
[`DicUploadWorker`](../../app/src/main/java/com/indicvision/semper/data/DicUploadWorker.kt),
and cloud restore so path segments and `frame_0000.dat` names never diverge.

## Sync workers

When cloud is configured (`INDIC_API_BASE_URL`):

| Type | File | Job |
|---|---|---|
| Upload | `DicUploadWorker` | Resume/create remote session, stage artifacts, upload bundles |
| Metadata JSON | `SessionUploadMetadata` | frames / device / engine JSON for the API |
| Bundle build | `SessionUploadBundler` | Render frame bundles + CSV lists offline-testable |
| Restore | `CloudRestore` / `DicRestoreWorker` | Pull remote sessions back into local session dirs. Home (row tap, multi-select **Restore**, the cloud-backups card) and Settings all start one through `RestoreStart.start`, which writes the row first so either screen shows its progress; `RestoreFailureLedger` announces each failure once across both screens |
| Backups not on this phone | `CloudBackupListing` | The COMPLETED backups the last successful reconcile listed, saved in prefs, so Home's `CloudBackupsCard` can offer those no row claims (by local id or stored cloud id, the rule `AnalysisEntries.merge` uses) without another request. A cloud delete forgets its entry, sign-out clears it, Hide is remembered per cloud id until that backup leaves the cloud |
| Bundle download | `DicBundleDownloadWorker` | Write a session `.zip` into a SAF document the user picked **before** enqueue. Falls back to packing the local session when the cloud copy is unavailable, and deletes the empty destination on failure |
| Delete queue | `SessionDeletes` / `BackupDeleteWorker` | Every delete that touches the cloud: one unique chain, a 5-second undo window, one analysis at a time, 429s waited out. Phone-only deletes stay inline (`CloudSync.eraseLocalOnly`). `ui/common/DeleteFeedback` reports progress and the outcome on Home and Settings |

`IndicApi.listSessions` **pages**: it follows `nextPageToken` until the backend
stops returning one, so a deep refresh sees the whole account rather than the
first page. Anything that lists cloud sessions should go through it rather than
issuing a single request.

### The two interceptors on the shared client

Both are application interceptors on `IndicApi`'s companion client, which
`downloadClient` inherits through `newBuilder()`. Retry is added first, so it
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

## Licensing & entitlements

The app never decides its own plan — `data/account/LicenseEntitlements.kt` is the one
place that answers "am I demo or licensed," and it reads through
`data/net/AppRemoteConfig.kt`, which caches whatever the backend's
`GET /v1/config` last reported (`plan`, `cloudBackupEnabled`, `shareEnabled`,
`licensePrefix`, `licenseKind`). Fails closed: before the first successful
fetch, and on any ambiguous value, everything reads as Demo.
At launch the status check and the cloud reconcile both ask for config, a few
milliseconds apart; `IndicApi.getConfig` shares one in-flight request between
them (`data/net/SingleFlight.kt`, [perf/request-volume.md](../perf/request-volume.md) Pass 2).

`IndicApi.activateLicense()` calls `POST /v1/licenses/activate` (bearer +
`X-Device-Id`, not device-signed) to redeem a key — see
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
| Redeem a key | `IndicApi.activateLicense()` |
| Expiry notice | `LicenseEntitlements.expiryNoticeDays()` — advisory only; suppressed on a cache older than a week. `mode` stays the only gate. See [WORKFLOWS.md §9.3](WORKFLOWS.md#9-session-limit) |
| Local analysis cap | `LicenseEntitlements.analysisCap()` — the backend's `maxSessions` once known; before that demo 25, licensed uncapped; see [WORKFLOWS.md §9](WORKFLOWS.md#9-session-limit) |

## Storage, diagnostics and the parameter clipboard

Three small subsystems added alongside the cloud work. Each is a plain object
with no framework behind it:

| Concern | Files | Notes |
|---|---|---|
| Local disk budget | `data/session/StorageBudget.kt`, `data/session/CacheJanitor.kt` | Measures analyses and cache; frees the local frames of **backed-up** analyses only. A user-set GB budget is enforced from `SemperApp.onCreate`, so it runs before any screen |
| Crash reporting | `Diagnostics.kt`, `CrashReportingTree.kt` | Crashlytics collection is **off in the manifest** and enabled only on consent (first-run prompt or the Settings toggle). `CrashReportingTree` is a release-only Timber tree feeding breadcrumbs and non-fatals |
| Product analytics | `diagnostics/SemperAnalytics.kt` | Same consent flag as Crashlytics (`DicSettings.diagnosticsEnabled`) — events are dropped, not queued, when it is off. Params must stay PII-free: enums, coarse buckets, success/fail. The consent copy names both halves (**Send crash reports and usage data**) — keep it and [PRIVACY_POLICY.md](../legal/PRIVACY_POLICY.md) §2.4 in step with the event set |
| Parameter hand-off | `data/prefs/ParamClipboard.kt` | Holds one subset/step/VSG (px) triple, copied from the sweep lattice's parameter chip and pasted into the analysis wizard's advanced parameters |

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
goes to the system chooser directly.

Long exports are not modal. Dismissing the progress dialog parks the job in
`ui/common/TransferBannerController` — a non-modal strip with progress, Cancel
and prev/next paging — hosted by both `ResultViewerActivity` and
`SettingsActivity`, where it also carries restores and bundle downloads.

## Memory & failure invariants

Non-obvious rules the analysis and transfer paths depend on. Breaking one tends to
show up as an OOM, a mid-run crash, or a "nothing happened" report:

- **JNI output buffer is bounded.** `DicBatchRunner` / `VsgStudyRunner` allocate
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
  instead of the window growing with frame size. `ResultViewerActivity.prefetchAround`
  fills that window with **one serialized worker**, cancelled and restarted as the user
  scrubs, admitting a frame only while the cache has room and the heap guard passes.
  It must stay serialized: an earlier version launched a coroutine per neighbour on
  every frame load, so peak memory scaled with *how fast the user scrubbed* rather than
  with any bound.
- **Whole-batch passes are started on demand, never on open.** The summary's colour-scale
  scan (`ViewerSummaryHelper.start`) decodes **every frame in the batch**, so it runs from
  `show()` rather than from viewer startup — opening straight onto a frame must not pay
  for an N-frame decode the user may never look at. The inspect-mode spatial index
  follows the same rule (built lazily on first tap, invalidated on frame load).
- **Batch progress is a buffered `SharedFlow`** (`replay=1`, `extraBufferCapacity`,
  `DROP_OLDEST`), not a `StateFlow` — a conflating flow dropped intra-frame ticks
  when the native solve emitted faster than the UI collected, stalling the bar.
- **Transfer failures are surfaced, not swallowed.** Terminal worker failures carry
  a human reason in their `WorkInfo` output; `HomeActivity` observes **both** the
  `upload` tag (badge dialog + snackbar) and the `restore` tag (snackbar), and
  `SettingsActivity` observes `restore` as well. Quota-full is the one exclusion —
  it routes to its own screen. Progress from the same `WorkInfo` drives the
  per-row badge and progress bar on Home, for downloads as well as uploads.
- **Cancelling a sweep abandons the sweep.** `VsgStudyRunner` checks the cancel
  token *between* combinations as well as inside a solve, so Cancel does not merely
  skip to the next parameter set.
- **A Drive outage must not look like deleted data.** A verifying refresh drops
  local metadata only when the backend confirms a blob is missing, never on an
  indeterminate answer. See [CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md).

## Where to edit

| I want to… | Start here |
|---|---|
| Change sign-in providers / access gate | `data/account/AuthRepository.kt`, `docs/backend/AUTH_SETUP.md` |
| Change post-auth navigation | `ui/auth/AccessRouter.kt` |
| Change the analysis wizard UI | `StaticAnalysisActivity.goToStep`; slot chrome / coach in `AnalysisWizardSlots` / `AnalysisWizardCoach`; later steps inflate through ViewStubs |
| Change the full-field batch loop | `DicBatchRunner` + `DicFieldIo` (shared with VSG). Do not split `computeFullFieldDirect` out of that loop |
| Change Home list / settings | `ui/home/HomeActivity.kt` + `Session*` / `ui/settings/SettingsActivity` + `Settings*Section` |
| Change import / video extraction | `FrameImportHelper`, `VideoFrameExtractor` (three rungs: `AviVideoDecoder` → `HardwareVideoDecoder` → `MediaMetadataRetriever`; all write through `FrameSink`). Fixed-interval instants are `VideoKeyframeHelper.uniformTimestampsUs` for the sheet's estimate and every rung, over a segment the sheet caps at `lastFrameStartMs` |
| Change AVI support | `imaging/AviReader` (demuxer), `imaging/AviLuma` (uncompressed layouts), `imaging/MjpegHuffman` (table repair), `AviCodecDecoder` (`MediaCodec` for Xvid/H.264) |
| Change parameter-sweep setup UI | `SweepSetupHelper` (run loop stays in the Activity + `VsgStudyRunner`) |
| Change the sweep result lattice | `ui/analysis/VsgLatticeActivity.kt`, `VsgLatticeView`, `VsgPlotView` |
| Change heatmap / probe | `ui/viewer/ResultViewerActivity.kt` + `Viewer*` helpers |
| Change how exports are handed off | `ui/viewer/share/ShareCenter.kt`, `SendToSheet.kt`, `SaveExportActivity.kt` |
| Change transfer progress UI | `ui/common/TransferBannerController.kt` (Settings + viewer), `data/cloud/TransferNotifications.kt` (the one channel) |
| Change the new-analysis media sheet | `ui/common/MediaPickerSheet.kt` / `MediaSourceChooser.kt` — shared by the Home **+** and both wizard dropzones |
| Add an analytics event | `diagnostics/SemperAnalytics.kt` — keep params PII-free and consent-gated |
| Change storage reclaim behaviour | `data/session/StorageBudget.kt`, `data/session/CacheJanitor.kt` |
| Change crash-reporting consent | `Diagnostics.kt`, `CrashReportingTree.kt` |
| Change the C++ engine | The engine is a submodule — see [ENGINE_APP_CONTRACT.md](../engine/ENGINE_APP_CONTRACT.md), not this page |

## Related docs

- [Engine ↔ app contract](../engine/ENGINE_APP_CONTRACT.md) (the engine itself
  lives in the `native/` submodule — see [engine/ARCHITECTURE.md](../engine/ARCHITECTURE.md))
- [Auth setup](../backend/AUTH_SETUP.md)
- [Cloud architecture](../backend/CLOUD_ARCHITECTURE_GCP.md)
- [Workflow index](../WORKFLOWS.md) — every flow's entry point, file chain and
  failure surface, plus where backtracking is hard (§E)
- [Contributing](../../CONTRIBUTING.md)
