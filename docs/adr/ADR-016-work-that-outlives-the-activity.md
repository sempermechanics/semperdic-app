# ADR-016: Work that must outlive the Activity has one home per kind

**Status:** Accepted, built (quality program, #311–#331)
**Date:** 2026-10-03
**Deciders:** app owner

## Context

Most screens have no `configChanges`, so a rotation destroys and recreates
them. Work started on an Activity's `lifecycleScope` is cancelled with it. The
2026-10-01 review found four places where that lost work the user had asked
for:

- **Viewer exports (H9).** A share or Save-to-Files export ran in
  `ShareCenter` on the viewer's scope. Rotating mid-export cancelled it and
  dropped its progress dialog.
- **Account deletion (H7).** `CloudSync.deleteAccount` ran on the Settings
  scope. A rotation after the cloud erase cancelled the local wipe and the
  sign-out, so the phone kept its data and a signed-in session for an account
  the server no longer had.
- **Sign-out.** Settings, Terms and Pending released the floating seat and
  signed out on their own scope. A rotation during the seat release skipped
  the Firebase sign-out and the token clear, and a screen closed before the
  sign-out finished left the app on Home with no session.
- **Upload, restore, download and delete** already ran in WorkManager. They
  are the model for work that must also survive process death.

There was no rule for which of these homes new work should take, so each
screen chose its own.

## Decision

Pick the home by how long the work must live and who must see its outcome.

| The work must survive | Home | Built as |
|---|---|---|
| A configuration change, while the user stays on the screen | The screen's ViewModel (`viewModelScope`) | Viewer exports: `ResultViewerViewModel.exports` (`ui/viewer/ResultViewerViewModel.kt:37`) runs `ShareExportJobs` (`ui/viewer/share/ShareExportJobs.kt:47`); the custom colour scales (`:34`) and the field-metrics cache (`:72`) live there too. Save to Files: `SaveExportViewModel` (`ui/viewer/SaveExportViewModel.kt:32`) |
| The screen closing, but not the process | An application-lifetime scope in a singleton `object` that exposes a `StateFlow` | Account deletion: `AccountDeletionRun` (`ui/settings/AccountDeletionRun.kt:34`, scope `:59`). Sign-out: `SignOutRun` (`ui/common/auth/SignOutRun.kt:42`, scope `:56`) |
| Cancellation of the caller, for a short sequence that must not stop half-way | `withContext(NonCancellable)` around that sequence only | The erase → wipe → sign-out sequence, `CloudErase.deleteAccount` (`data/cloud/CloudErase.kt:42-62`) |
| Process death, or the app leaving the screen | WorkManager unique work | `oneTimeWork` + `enqueueUnique` (`data/cloud/WorkTags.kt:90`, `:113`): upload (`data/cloud/CloudSync.kt:377-383`), restore and bundle download (`data/cloud/restore/CloudRestore.kt:73-81`, `:102-112`), backup deletes (`data/cloud/SessionDeletes.kt:91-96`), metadata sends (`data/cloud/SessionMetadataSync.kt:135-139`); the licence refresh is periodic (`data/LicenseConfigWorker.kt:45`) |

**What each home promises.**

- **ViewModel.** The job and its state survive a rotation; leaving the
  screen for good (Back, or the screen finishing) clears the ViewModel and
  cancels the job, as the user expects. A job holds plain data, never the Activity, so a rotation
  frees the old screen at once (`ShareExportJobs.kt:41-46`). The recreated
  screen re-attaches to the job's state and takes each finished outcome once.
- **Application-lifetime object.** The run has an explicit state
  (`Idle` → `Running` → `Done`), and an outcome is consumed once
  (`AccountDeletionRun.consume`, `:100`; `SignOutRun.consume`, `:114`), so a
  recreated screen renders it and a second screen does not repeat it.
  `SignOutRun` also counts the live screens of each class (`:65`, `:124-141`)
  and, when the screen that asked has closed, routes to sign-in from the
  application context and leaves the outcome `Unclaimed` (`:53`, `:100-104`)
  until a screen claims it. A rotation is not a close: the recreated screen
  observes again in the same main-thread step.
- **`NonCancellable`.** Only for a sequence that is short, ends on its own
  timeouts, and is worse stopped half-way than finished; never around a
  network loop the user might want to cancel. See ADR-018 for the
  cancellation rules around it.
- **WorkManager.** Survives process death and reboots, retries with backoff,
  and reports through `WorkInfo`, which screens read with
  `TransferWorkObserver` (`ui/common/transfer/TransferWorkObserver.kt:27`).
  Worker class names are persisted, so workers never move or rename
  ([ADR-015](ADR-015-package-layout.md)).

**The wizard is the exception, on purpose.** `StaticAnalysisActivity`
absorbs every configuration change (`app/src/main/AndroidManifest.xml:154-158`),
so rotation never recreates it. Its batch and sweep runs sit on
`viewModelScope` (`ui/analysis/wizard/RunChannels.kt:74`, `:133`), but
`onDestroy` cancels them (`ui/analysis/StaticAnalysisActivity.kt:243`): a
solve belongs to the screen that shows it. Process death is covered by the
draft ([ADR-005](ADR-005-wizard-process-death.md)), not by keeping the run.

**Rule for new code.**

1. If the user would lose something they asked for when the screen rotates,
   do not run it on `lifecycleScope`. Use the screen's ViewModel.
2. If it must finish even after the user leaves the screen, and it is short,
   use an application-lifetime `object` with a `StateFlow` state and a
   consume-once outcome, like `AccountDeletionRun`.
3. If it must survive process death, or it is long or retryable (any
   transfer), use WorkManager through `oneTimeWork` / `enqueueUnique`.
4. Never hold an Activity or a View in a longer-lived home; hold the
   `Application` (`SignOutRun.kt:62`) or plain data.
5. Do not widen `NonCancellable` to make cancellation "go away"; rethrow
   `CancellationException` everywhere else (ADR-018).

Other application-lifetime scopes exist for housekeeping, not for user
requests: startup cleanup and the seat heartbeat (`SemperApp.kt:27`, `:45-52`),
the status re-check after splash (`ui/auth/StatusRecheck.kt:27`), the wizard
draft's write lane (`data/prefs/WizardDraft.kt:101`) and the shared config
fetch (`data/net/SingleFlight.kt:18`). The summary GIF's build records and
locks are process-wide by output path (`ui/viewer/summary/SummaryAnimation.kt:273-275`)
so a rotated viewer reuses a GIF the old one finished.

## Options considered

### A: One home per kind of lifetime (chosen)

Each home matches a lifetime Android already defines (configuration,
process, beyond the process). The code says which one it needed by where it
lives.

### B: Everything that matters in WorkManager

WorkManager survives everything, but it has no way to hand a result to the
screen that is waiting except through `WorkInfo`, adds scheduling latency,
and runs under constraints and quotas. A share sheet that opens seconds
later, or a sign-out that waits for a worker slot, is worse than the bug.

### C: `configChanges` on every screen

It is what the wizard does, and it is right there: a solve is minutes of
native work. Elsewhere it moves the burden to hand-written layout changes for
rotation, dark mode and font scale, and it does nothing for a screen the user
closes or for process death.

## Trade-off analysis

A leaves three patterns to learn instead of one, and an application-lifetime
`object` is global state: tests must reset it (`AccountDeletionRun.resetForTest`,
`SignOutRun.resetForTest`). In exchange each piece of work lives exactly as
long as its outcome matters, and the singletons stay small: one run at a
time, a `StateFlow`, and a consume.

## Consequences

- A rotation no longer cancels an export, an account deletion or a
  sign-out. Tests: `ShareExportJobsTest`, `ViewerRotationTest`,
  `AccountDeletionRotationTest`, `AccountDeletionStagesTest`,
  `SignOutRunTest`, `EraseCancellationTest`.
- Application-lifetime runs still end with the process. Account deletion
  killed between the cloud erase and the local wipe leaves the phone's data
  in place (TD-165).
- The wizard's run outcome is a `SharedFlow` with no replay, collected only
  while the wizard is started, so an outcome emitted while it is in the
  background is dropped (TD-168).

## Action items

1. [x] Viewer exports in the ViewModel (#314), then the field metrics (#328).
2. [x] Account deletion under `NonCancellable` (#311), on an application
   scope with a consume-once outcome (#316), with the erase stage recorded
   (`ErasureWatch`, `AccountDeletionRun.kt:123-132`, #329).
3. [x] `SignOutRun` with live-screen tracking and the `Unclaimed` outcome
   (#316, #329).
4. [ ] TD-165: persist the deletion stage and finish the wipe at startup.
5. [ ] TD-168: give the wizard's run outcome a replay, or hold it in the
   ViewModel's state.
