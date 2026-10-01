# material_testing: syncing and porting

[material_testing](https://github.com/sempermechanics/material_testing) is a fork of
this repo. It adds the tensile, bending and lab-report features, ships under its own
app id (`com.indicvision.semper.materialtesting`, its ADR-009) and uses this repo's
backend. It takes this `main` with a plain `git merge` on a `sync/semperdic-<sha>`
branch.

In the week to 2026-10-01, nine changes were written twice, once in each repo: the
frame ceiling, the metadata route, TD-134, TD-77, TD-79/80 and TD-82..85 among them.
They ended up byte-identical, but they doubled the history, split the docs (ADR-013,
WORKFLOWS C21) and collided TD numbers. The rules below stop that.

## Where a change starts

| Change | Lands in | Reaches the other repo by |
|---|---|---|
| `backend/`, `firebase-hosting/`, `firestore.*`, `.github/`, `scripts/`, `benchmark/` harness | here | the next sync |
| A fix to app code both repos have (wizard, ROI editor, viewer, Home, settings, cloud sync) | here | the next sync |
| A fix found in material_testing in shared code | here, as `git cherry-pick -x <fork sha>` if it landed there first | the next sync (identical blobs merge silently) |
| Lab, tensile, bending, loads, lab report | material_testing | never |

material_testing does not edit `backend/`; the backend deployed from here serves both
apps. The deploy and Firestore workflows only run in this repo
(`if: github.repository == …`).

## Syncing into material_testing

1. `git fetch semperdic main` and branch `sync/semperdic-<short sha>` from its `main`.
2. `git merge semperdic/main`. Keep its `applicationId`, `rootProject.name` and
   `google-services.json`. The benchmark module and CI read the package from
   `applicationId` (ADR-008), so nothing else names the app.
3. Docs conflicts are the norm: keep its CONTEXT and TECH_DEBT, both sides of
   CHANGELOG, and both sides of any ADR both repos added to.
4. An engine bump (the `native` gitlink) means re-running its real-data checks
   (its `real_data_steel_tensile.py` and `real_data_pmma_bending.py` scripts); its JVM
   tests use arrays recorded on the old engine and stay green either way.

### The 2026-10 package move (ADR-015)

The sync that brings in [ADR-015](../adr/ADR-015-package-layout.md) moves 149
files. The merge follows the renames for files both repos have. Files only
the fork has, and its edits to moved files, need one more pass:

1. Resolve the merge, then replay the mapping. It is idempotent, so files the
   merge already moved are skipped, and only the imports still pointing at the
   old packages are fixed, the fork's lab files included:
   `python scripts/move_kotlin_packages.py --mapping scripts/package_moves_2026_10.json`.
2. Move every non-Worker file left in `data/` too, so `data/` holds only the
   six Workers here. Add them to a copy of the mapping and run it again. Never
   move a `*Worker` class or an Activity (e.g. `BeamEdgeTapActivity`):
   WorkManager and the manifest record their names. On the fork's `main` of
   2026-10-01 those files, with a suggested package, are:

   | Fork-only file in `data/` | Suggested package |
   |---|---|
   | `TestType`, `MechanicalTestInputs`, `SpecimenGeometry`, `BeamEdgeTaps`, `CurveCorrection`, `TypedLoads` | `data/mechanical/` (what the wizard records about the test) |
   | `MachineLoadCsv`, `MachineLoadMapper` | `data/mechanical/` (the machine's load log, read and matched to frames) |
   | `DocumentText` | `util/` (reads a small SAF text document; nothing mechanical in it) |

   That makes `data/mechanical/` eight files. Re-list `data/` before you
   map it, since the fork may have added files since. The fork's other
   lab-only files fit the same pattern. In `ui/analysis/`, the load and
   beam-tap UI (`AnalysisLoadCard`, `Load*`, `TypedLoadsSheet`,
   `SpecimenGeometryFields`, `BeamEdgeTapOverlay`, `BeamTapPlacement`,
   `PhotoCaptureTime`) can go to `ui/analysis/load/`, `VideoKeyframes` and
   `VideoSampling` to `imaging/video/`, and `VideoSamplingSheet` to
   `ui/analysis/frames/`. In `ui/viewer/`, the mechanical results
   (`ViewerStressStrain*`, `ViewerBendingResults`, `ViewerCurveCorrection`,
   `ViewerDeflectionCorrection`, `ViewerFrameRows`) and `LabReportExporter`
   can go to `ui/viewer/mechanical/`.
3. Run `./gradlew --no-daemon spotlessApply`, then the script again with
   `--compile`. It runs the compile tasks and adds imports for any
   `Unresolved reference` until a pass fixes nothing. Fix what is left by
   hand: an inline FQCN that grew past detekt's 120 columns, a class name
   inside a string literal (the script reports these and leaves them
   alone), or a name that is declared in two packages.
4. Run the script with `--kdoc` and `--docs` for KDoc links and doc paths,
   then `python scripts/check_doc_paths.py`. `--docs` leaves dated records
   as written: `docs/ops/QUALITY_BASELINE_*`, `docs/ops/CHANGELOG.md`, the
   ADRs (`docs/adr/ADR-*.md`), `docs/engine/PERF_BASELINE_*` and dated
   `docs/perf/*-20NN-*` reports. It names each one it skipped that mentions
   a moved file. Pass `--docs-skip GLOB...` to change the list. If
   `check_doc_paths.py` then fails on a link in one of those files, fix
   that link by hand.
5. Before release, do the upgrade check in ADR-015's action items: queued
   work from the old APK must still run.

## Porting back

List the fork's commits that touch shared paths and are not here:

```bash
git fetch mat main
git log --oneline --no-merges mat/main --not origin/main -- app/src/main .github scripts benchmark backend
```

Ignore the lab-only files (`report/Lab*`, `report/StressStrain*`, `report/Beam*`,
`ui/viewer/ViewerBending*`, `data/MachineLoad*`, `data/TypedLoads*`). Port with
`git cherry-pick -x`; when a fix is mixed into a lab commit, extract it into an
adapted commit and name the fork commit in the message.

## TD and ADR numbers

The two repos share one TD and one ADR number space. Before taking a number, read
both registers (`docs/ops/TECH_DEBT.md`, `docs/adr/README.md`) on both `main`s and
take the next number free in **both**. Update the reservation note at the top of the
open register here when the fork has used new numbers.
