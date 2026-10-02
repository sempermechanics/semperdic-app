# ADR-005: The wizard survives process death through a draft

**Status:** Accepted
**Date:** 2026-09-23
**Deciders:** app owner

## Context

TD-26 said the wizard loses state on rotation. It does not:
`StaticAnalysisActivity` declares the full `configChanges` set
(`AndroidManifest.xml:147-149`), so rotation, dark mode and font scale never
recreate it. **Process death** does, and nothing survives it:

- `AnalysisViewModel` has no `SavedStateHandle` (none exists anywhere in
  `app/src/main`), and the Activity has no `onSaveInstanceState`.
- Lost: reference bytes and name, deformed-frame lists, ROI and mask, sweep
  configuration, the current step (it resets to 1), and `workingLocalId` — so a
  re-run after a kill creates a **second** Home row and counts twice against
  the quota.
- `CacheJanitor.sweepOnStartup` deletes `cacheDir/temp_deformed` on every
  process start (`CacheJanitor.kt:132`), on purpose, because the ViewModel did
  not survive — so saving paths alone would restore nothing.
- If the process dies while `RoiDrawActivity` is open, its result lands on a
  fresh ViewModel with `realRefWidth == 0`; `applyRoiResult` computes
  `hasCustomRoi = true` and applies a ROI with no reference loaded.

Android kills background processes routinely on low-RAM phones; a user who
switches to the gallery or a file manager mid-import is exactly the case.

## Decision

A **wizard draft**:

- `AnalysisViewModel` takes a `SavedStateHandle` (created with
  `SavedStateViewModelFactory` via `by viewModels`). It holds the small state:
  current step, ROI + `hasCustomRoi`, single-run settings, sweep configuration,
  `workingLocalId`, subset-recommendation key.
- The heavy inputs — reference bytes, mask, staged deformed frames — are staged
  under `filesDir/wizard_draft/` instead of `cacheDir/temp_deformed`, with a
  marker file carrying the draft's creation time.
- `CacheJanitor` leaves a live draft alone. It reclaims one older than 24 h, and
  the run's commit removes it.
- On recreate, the ViewModel restores from the handle and the draft. If any
  staged file is missing, the wizard resets to step 1 with a snackbar that says
  the draft was lost.
- `applyRoiResult` ignores a result that arrives with no reference loaded.

## Options considered

### A: `SavedStateHandle` for scalar state only

**Cons:** step, settings and ROI come back, but the frames do not
(`CacheJanitor` deletes them), leaving a half-restored wizard.

### B: Draft = `SavedStateHandle` + files in `filesDir` (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium |
| Cost | ViewModel, staging helpers, `CacheJanitor`, restore path |
| Scalability | New editing state is one handle key |
| Team familiarity | Standard Jetpack API |

**Pros:** a kill is invisible to the user; no second Home row.
**Cons:** staged frames now live in `filesDir` until the run commits or 24 h
pass, so they count toward storage.

### C: Accept the loss; only guard the unsafe paths

**Cons:** the user still redoes the import; only the crash-like edge cases go
away.

## Trade-off analysis

B costs disk space for at most one draft for at most 24 hours; the draft is
already on disk today (in cache). A and C trade that space for a user redoing
the import, which on a 150-frame batch is minutes.

## Consequences

- Easier: long imports survive app switches; quota is not double-counted.
- Harder: `StorageBudget` must count the draft; the draft directory is one more
  path for `SessionPaths`/`CacheJanitor` to own.
- Revisit: combined with ADR-004's `RunSpec`, a full `StateFlow` UI state is a
  small step if the wizard grows.

## Action items

1. [x] `SavedStateHandle` on `AnalysisViewModel`; keys for the small state.
2. [x] Stage heavy inputs under `filesDir/wizard_draft/` + marker; leaving
       the wizard deletes it (see As built: not the commit).
3. [x] `CacheJanitor`: keep a live draft; reclaim after 24 h.
4. [x] Restore path + "draft lost" snackbar; guard `applyRoiResult`.
5. [x] Tests: JVM restore from a `SavedStateHandle`; emulator
       `WizardDraftRestoreTest`; scripted `adb shell am kill` pass.
6. [x] Close TD-26.

## As built (2026-09-24)

- **Split by size.** `WizardState` packs the scalars (step, ROI and
  `hasCustomRoi`, reference size and name, frame order, sweep ranges,
  line cut, VSG frame, `workingLocalId`) into one Bundle under
  `SavedStateHandle` key `wizard_state`. They are applied in the view
  model's `init`, so they are back before the Activity draws. The frame list
  can hold 500 paths, too big for a saved-state Bundle, so it goes to
  `wizard_draft/frames.json` beside `reference.bin` and `mask.bin`.
- **The staged frames stay in `cacheDir/temp_deformed`.** They are not moved
  to `filesDir`: four writers, the FileProvider path and the run's
  `renameTo` all use that directory. `CacheJanitor.sweepOnStartup` keeps it
  while the draft's `live` marker is under 24 h old. An OS cache eviction is
  caught on restore: any missing part (a frame, the reference, the mask)
  means **LOST**, with inputs reset to an empty step 1 and a snackbar. A
  partial restore would look ready and solve something else.
- **Mirroring.** All draft I/O runs on `WizardDraft.io`, one serial lane
  shared by every wizard, so writes land in the order they were queued.
  They are queued through `WizardDraft.queue`, on a process-lifetime scope,
  not `viewModelScope`: "Don't keep activities" clears the view model right
  after the stop, and that must not cancel the stop's write. The `refBytes`
  and `roiMaskBytes` setters queue their write there. The saved-state
  provider encodes the frame list on Main as the Activity stops, queues its
  write on the same lane, and puts the SHA-256 of that JSON in the Bundle
  (`framesFingerprint`). On restore, `frames.json` must match it, so a kill
  before the write lands reports **LOST**, even when the previous list holds
  as many frames. A Bundle without the key (saved by an older app) is
  checked on the frame count alone.
- **Ordering across wizards** is closed by a generation tag
  (`WizardDraftBinding`). A finishing wizard queues its delete from
  `onDestroy`, which can land after a new wizard's first writes. So each
  fresh wizard takes the next value of a process-wide counter when it
  attaches the draft, and every write or delete a wizard queues runs only
  while the counter still holds that wizard's value: a stale delete finds a
  newer owner and does nothing. A wizard restored from a Bundle takes the
  counter's current value instead of the next one, so the writes its
  predecessor queued before the process died or the Activity was recreated
  still land for it.
- **When the draft goes.** It is deleted when the wizard finishes
  (`onDestroy` with `isFinishing`), not when a run commits: the user
  re-runs from the same wizard, and a kill after a run must still restore.
  `discard()` does not block Main: it sets a `@Volatile` flag that turns
  any write still queued into a no-op, then deletes the files on the same
  lane. `ViewModel.onCleared`
  is not the signal, because "Don't keep activities" clears the view model
  on a destroy the system will restore.
- **Hand-off extras.** Home's picked Uris are consumed only when
  `savedInstanceState == null`. A restored Activity gets its original
  Intent back, extras included, and would re-import.
- **Storage.** `StorageBudget` adds the draft's size to the total it
  enforces. It never drops the draft.
- **Settings sliders** come back from the Activity's saved view state, not
  from the view model. Verified on the emulator.
- **Verified** on `emulator-5556` by killing the process from Home and
  reopening it from Recents. `am kill`, or `kill -9` through `run-as` when
  `am kill` declines:
  - Kept draft: step 2, subset 21, step 5, overlap 0.76, window 15 and the
    2000 × 435 ROI all come back; step 1 shows the reference and both
    frames; Compute solves both frames.
  - Staged frames deleted while the process was dead: LOST, an empty
    step 1, and the snackbar.
  - Exit from the wizard: `files/wizard_draft` is gone.
