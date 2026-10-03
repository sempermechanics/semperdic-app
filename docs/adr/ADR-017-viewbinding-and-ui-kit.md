# ADR-017: Views through ViewBinding, and one `ui/common` helper per UI job

**Status:** Accepted, built (quality program, #310, #319, #323–#331)
**Date:** 2026-10-03
**Deciders:** app owner

## Context

At `main` @ `ff0cfc3d` (2026-10-01) the app looked up its views by hand: 416
`findViewById` calls and 183 `lateinit var`s, most of them view fields
([QUALITY_BASELINE_2026-10-01.md](../ops/QUALITY_BASELINE_2026-10-01.md)).
A wrong id or a wrong cast failed at run time, often only on the screen state
that reached it. The same UI jobs were written many ways: 65
`Toast.makeText` calls, 6 info and 12 confirm dialogs built by hand, 11
`job?.cancel(); job = launch { … }` pairs, three thumbnail loaders (one with a
recycled-row bug), 12 hand-written show/hide sites for the wizard's warning
chips and 7 copies of the Settings section header.

## Decision

**ViewBinding for every screen's own layout.** `viewBinding = true`
(`app/build.gradle.kts:155-160`, #310). Each Activity inflates its binding in
`onCreate` and keeps it (`ResultViewerActivity.kt:65`, `:182`); 46 main-source
files use generated bindings.

Conventions:

- **Controllers built before `onCreate` read `binding` lazily.** The viewer's
  parts are property initialisers of the Activity (`ResultViewerActivity.kt:69-73`),
  so they exist before the binding does. Each one reads it through a getter,
  `private val binding get() = host.binding` (`ui/viewer/ViewerScaleController.kt:26-30`),
  never in its constructor.
- **A ViewStub page gets its own binding.** Inflate the stub, then bind the
  inflated view: `WizardStepSettingsBinding.bind(binding.stubStepSettings.inflate())`
  (`ui/analysis/StaticAnalysisActivity.kt:111-115`). Pages stay in stubs so the
  host layout stays under lint's `TooManyViews`.
- **A content view exposes its binding.** `WizardStepSettingsContentView`
  (`ui/analysis/wizard/WizardStepSettingsContentView.kt:18`) and
  `SettingsScrollContentView` own their inflated layout and hand out
  `binding`, so callers never search by id across the Activity.
- **An `<include>` is bound through its own binding**, as the seven Settings
  section headers are (`SettingsSectionHeader.bind`, `ui/common/SettingsSectionHeader.kt:24`).
- **Parts take the binding (or the views) they need**, not the Activity's
  whole view tree.

**One way to do each common UI job** (`ui/common/` and its subpackages, #319):

| Job | Use | Not |
|---|---|---|
| A short message | `Feedback.toast` (`ui/common/dialog/Feedback.kt:19`); a placed pill is `CrispToast` | `Toast.makeText` (one call left, inside `Feedback`, `ui/common/dialog/Feedback.kt:28`) |
| An info or yes/no dialog; an "i" button | `Dialogs.info` / `Dialogs.confirm`, `View.bindInfo` (`ui/common/dialog/Dialogs.kt:23`, `:71`) | A hand-built `MaterialAlertDialogBuilder` |
| Latest request wins | `SerialJob` (`ui/common/SerialJob.kt:21`), main thread only | `job?.cancel(); job = launch { … }` |
| A list thumbnail | `ThumbnailLoader<K>` (`ui/common/media/ThumbnailLoader.kt:41`): tags every bind, so a late decode never lands on a recycled row | A per-adapter cache |
| A wizard warning row | `WarnChip` (`ui/common/dialog/WarnChip.kt:19`) | Show/hide by hand |
| A bottom sheet of rows | `inflateSheet` + `Sheet.row` (`ui/common/dialog/Sheet.kt:48`, `:26`) | A `BottomSheetDialog` per screen |
| A busy spinner over controls | `View.setBusy` (`ui/common/Busy.kt:15`) | |
| One refresh at a time, a burst folded into one more | `ConflatedRefresh` (`ui/common/ConflatedRefresh.kt:22`) | |

**Still hand-built, and why.**

- **The run's "Keep running" confirm** (`RunChrome.confirmCancel`,
  `ui/analysis/run/RunChrome.kt:67-82`): its negative button says "Keep
  running", not Cancel, because Cancel is what the user just pressed.
  `Dialogs.confirm` has fixed button labels.
- **Dialogs with their own view or behaviour:** the rename field
  (`ui/home/SessionSelectionController.kt:220`), the custom colour scale
  (`ui/viewer/ViewerScaleController.kt:83`), list pickers
  (`ui/analysis/sweep/SweepFramePicker.kt:51`, `ui/home/CloudBackupsCard.kt:76`),
  the non-cancellable beta notice and deletion progress
  (`ui/home/FirstRunPrompts.kt:48`, `ui/settings/SettingsYourDataSection.kt:251`).
- **The wizard's batch and sweep jobs** keep their own `Job` fields
  (`ui/analysis/wizard/RunChannels.kt:52-53`): they run on the native
  dispatcher, and `SerialJob` is main-thread only.
- **46 `findViewById` calls remain.** 23 are the sweep page
  (`SweepSetupController`, `SweepRangeFields`), which still finds its views on the
  page instead of taking its binding. 7 are `TransferBannerController`, which
  wraps a strip included in two hosts. Most of the rest are kit pieces that
  work on any host's view (a row by id, `android.R.id.content`, Material's
  bottom-sheet container).

**Not Compose.** The app has no Compose dependency. Moving to it would mean a
new compiler plugin and runtime, rewriting every screen at once or living
with interop at each boundary, and keeping the custom drawing views
(`StudioOverlayView`, `VsgPlotView`, `TouchImageView`, `VsgLatticeView`) as
Views anyway. The Robolectric view tests (TD-57) are written against Views.
ViewBinding removes the run-time lookup failures with one Gradle flag and no
change to how a screen is built.

## Options considered

### A: ViewBinding plus a small helper kit (chosen)

Compile-time view access with no new runtime; the kit removes the copies
without changing what any screen looks like.

### B: Kotlin synthetics

Deprecated and removed from the Kotlin Android plugin.

### C: Compose

See above: a rewrite, not a cleanup.

### D: Keep `findViewById`

Free today, and every new screen adds lookups that fail only at run time.

## Trade-off analysis

ViewBinding generates a class per layout, a little build time and APK size
(the generated classes are excluded from Kover, `app/build.gradle.kts:383-384`).
The kit is one more place to look, but each helper replaced between 3 and
about 65 copies, and some copies had drifted (sign-out confirm wording,
spinner visibility, the thumbnail tag bug).

## Consequences

- `findViewById` 416 → 46, `lateinit var` 183 → 56, `Toast.makeText` 65 → 2
  (`scripts/quality_metrics.py`, [QUALITY_PROGRAM_RESULTS.md](../ops/QUALITY_PROGRAM_RESULTS.md)).
- A renamed or removed view id fails the build.
- Layout XML names custom views by fully qualified name, and the binding
  compiles against them, so a package move that misses a layout fails the
  build too ([ADR-015](ADR-015-package-layout.md)).
- Toasts now use the application context (`Feedback`), so a toast outlives the
  screen that showed it.

## Action items

1. [x] `viewBinding = true` (#310); the kit (#319); adoption per screen
   (#323–#330); the kit sorted into subpackages (#331).
2. [ ] Pass the sweep page's binding into `SweepSetupController` and
   `SweepRangeFields` and drop their 23 lookups.
3. [ ] Update the `viewBinding` comment in `app/build.gradle.kts:158-159`,
   which still says screens are moving one per PR.
