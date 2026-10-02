# ADR-004: Immutable `RunSpec` built once at Compute

**Status:** Accepted
**Date:** 2026-09-23
**Deciders:** app owner

## Context

"What settings did this run actually use?" has no single answer until
`SessionRepository.buildSessionRecord` assembles one at commit. Before that the
answer is spread across:

- 47 public `var`s on `AnalysisViewModel` (inputs, results, editing state and
  session identity mixed together; `hasCompletedAnalysis` is written and never
  read);
- five slider/radio views on `StaticAnalysisActivity` (1,827 lines) — subset,
  step, overlap, strain window and interpolator exist *only* in views and are
  read once when `BatchAnalysisParams` / `SweepRequest` are built
  (`StaticAnalysisActivity.kt:1288-1328`, `:1545-1559`);
- a ROI that exists twice: the requested one on the ViewModel and the resolved,
  inset one in `BatchAnalysisParams.finalRect*` (`RoiResolveHelper.resolve`).

The fault this causes is TD-61: `AnalysisNavHelper.openResults` reads the
ViewModel after the run, so it passes the *requested* ROI, the slider values
at the moment of opening, and `last*` fields that a sweep path never resets.
The saved session carries the resolved ROI and the run's real values, so the
viewer's metadata depends on how it was opened. Separately, the sweep path
reads `roiMaskBytes` directly from the ViewModel mid-run
(`AnalysisViewModel.kt:450`) rather than from its request.

Constraint: the JNI `computeFullFieldDirect` call stays inside the one batch
loop in `DicBatchRunner.kt`, and `.dat` output is bit-exact against oracles.

## Decision

Introduce `RunSpec`, an immutable data class snapshotted once when the user
taps Compute:

```kotlin
data class RunSpec(
    val localSessionId: String,
    val ref: RefSpec,                    // name, width, height, bytes handle
    val frames: List<FrameSpec>,         // path, original name
    val roiRequested: Roi,
    val roiResolved: Roi,                // RoiResolveHelper.resolve output
    val maskBytes: ByteArray?,
    val subset: Int, val step: Int, val strainWindow: Int,
    val overlap: Double, val use6x6: Boolean,
    val sweep: SweepSpec?,               // plan, lineCutHorizontal, vsgFrameIndex
)
```

- `BatchAnalysisParams` and `SweepRequest` are **derived** from it; the engine
  receives exactly what it receives today.
- `RunResult` carries the `RunSpec`; `AnalysisNavHelper` and
  `buildSessionRecord` read the spec, never the ViewModel's editing fields.
- Removed: `hasCompletedAnalysis`, the nav-only `last*` reads, the sweep's
  direct `roiMaskBytes` read.
- The wizard keeps its mutable editing state on the ViewModel.

## Options considered

### A: `RunSpec` snapshot (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium |
| Cost | ViewModel, `DicBatchRunner.kt`, sweep body, nav helper, record builder |
| Scalability | New run parameters get one home |
| Team familiarity | Plain Kotlin data class, like `BatchAnalysisParams` |

**Pros:** fixes TD-61 at the source; the engine's inputs are unchanged by
construction. **Cons:** one more type between the UI and the engine.

### B: Hoist all wizard state into `StateFlow` UI state

**Cons:** a rewrite of a 1,800-line native-solve screen to fix a fault that
needs only the run's inputs frozen. Kept as a possible later step (see
ADR-005 for the part of it that process death needs).

### C: Status quo, patch `AnalysisNavHelper` to read `params.finalRect*`

**Cons:** fixes the ROI only; subset/strain window and stale `last*` stay
wrong, and the next field added repeats the fault.

## Trade-off analysis

A is the smallest change that makes "the settings this run used" a value
instead of a reconstruction. Bit-exactness is protected because params are
derived field for field; the proof is mechanical (identical `.dat` hashes).

## Consequences

- Easier: the viewer, report, CSV and cloud metadata all read one value.
- Harder: nothing may mutate a run's inputs after Compute (intended).
- Revisit: if the wizard grows further, ADR-005's draft plus `RunSpec` make a
  full UI-state hoist cheap.

## Action items

1. [x] `RunSpec` + builders (`ui/analysis/RunSpec.kt`); `BatchAnalysisParams`
       derived from it.
2. [x] `RunResult.spec` and `RunResult.settings`; the nav helper and both
       record builders read them.
3. [x] Removed `hasCompletedAnalysis`, `lastStep`, `currentSessionId`,
       `SweepRequest`, the sweep's direct mask read and the duplicate sweep-frame
       resolver.
4. [x] `RunSpecTest` (7): params and record settings field-equal to the
       hand-built ones; fresh-run and Home `ViewerArgs` agree; an edited ROI
       does not leak into the viewer; an all-failed sweep carries no stale stop code.
5. [x] Emulator: engine inputs proven identical by digest (see As built; the
       `.dat` hash check was replaced); `EnginePipelineSmokeTest` passes.

## As built (2026-09-23)

- **Smaller than the sketch.** `RunSpec` holds subset, step, strain window, the
  resolved ROI, the mask, the interpolator, the debug target and, for a sweep,
  `Sweep(plan, labels, lineCutHorizontal, frameIndex)`. Left out:
  - the local session id, which is resolved inside the run after the quota
    check; resolving it at Compute would make `wouldCreateNewSession` false
    before the check runs;
  - reference and frame paths, which the run moves into the session directory
    (`repointDeformedPaths` follows them);
  - the requested ROI, which nothing after Compute reads;
  - overlap, which is derived from step.
- **`RunResult` carries two values.** `spec` (the inputs) and `settings` (what
  the saved record got). They differ for a sweep, whose record takes the first
  *solved* combination. `viewerSettings()` prefers `settings` and falls back
  to the spec for a sweep that solved nothing. The viewer therefore shows
  exactly what Home will show.
- **`SweepRequest` is gone.** The sweep body reads the spec directly.
  `BatchAnalysisParams` stayed at first, because the batch loop read it;
  it has since been deleted, and the loop reads the spec's `DicParams` and
  `Roi` directly.
- **`SESSION_ID` is the local id.** The `Pending_Cloud_Sync_…` placeholder was
  only ever passed to the viewer, never stored, so the PDF's Session ID line
  now matches a reopened session.
- **A sweep starts from a clean `RunResult`**, as the batch path already did.
  It used to inherit the previous run's stop code, reference and planned-frame
  count.
- **Verification changed.** Two runs of the *same* build on the emulator do not
  give bit-identical `.dat` files (TD-65), so equal hashes could not show that
  this change left the engine alone. Instead, a temporary uncommitted patch
  logged SHA-256 of every JNI input (reference, each frame, mask) plus ROI,
  step, subset, strain window and interpolator, before and after the change:
  **identical**. `.dat` fields after vs before stayed inside the before-vs-before
  envelope (max |Δu| 0.0015 / 0.0031 px). On device, the fresh-run and
  reopened-from-Home `ViewerArgs` now agree on every field except `defPath`
  (ADR-003), for a single run and for a sweep.
- *Later (2026-10-01):* engine 0.2.3 made the Path B flood fill deterministic
  (TD-65), so a `.dat` hash comparison is a valid check again.
- *Later (2026-10-03):* `RoiResolveHelper.resolve`, named in the sketch above
  as the source of `roiResolved`, is deleted (#331); the run's ROI is
  `Roi.forSolve` (`field/Roi.kt`). What `RunSpec` still leaves out (reference
  bytes and size, frame paths) the batch loop reads from the view model:
  TD-173.
