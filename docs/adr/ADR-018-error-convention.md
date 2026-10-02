# ADR-018: One typed outcome per failure domain; cancellation is never a failure

**Status:** Accepted, built (quality program, #311, #312, #318, #321, #323–#331)
**Date:** 2026-10-03
**Deciders:** app owner

## Context

Before the program each caller of the backend fetched its own token, wrapped
its call in its own `try`, and caught `NotApprovedException`, then
`ApiException` (branching on the status), then `IOException` as "offline", in
its own order. `CloudSync.eraseAccountInCloud` folded no token, a 401, a 5xx
and no network into one `false`, so the account deletion told users nothing
was deleted when their data was gone (fixed in #327 and #329). Run stops were
`Int` codes with constants in four classes, and a stored `-99` (cancelled) read
back as "strain-window failure" (#330). Every failed session save was reported
as the session limit, even when the index could not be read (#330). And
`runCatching` around suspend calls turned cancellation into an ordinary
failure at 21 sites (TD-41), so a cancelled export said "export failed".

## Decision

Each failure domain has one typed outcome. A caller maps that type to what
the user is told and to whether to retry, in one `when`.

| Domain | Type | Where | User message | Retry |
|---|---|---|---|---|
| A backend call | `Authed<T>`: `Ok`, `Disabled` (no backend), `NoToken`, `Failed(HttpFailure)` | `data/net/Authed.kt:9`, made by `CloudApi.authed` (`:37`) | The caller maps the outcome, e.g. an erase to `EraseResult` (`data/cloud/CloudErase.kt:101-112`), a status check to an access status (`data/account/AccessStatusResolver.kt:82-95`) | Per caller; `HttpFailure.isRetryable` (429, 5xx, no answer) is only the generic rule (`data/net/HttpFailure.kt:92`) |
| What a thrown call means | `HttpFailure(kind, cause)`, `HttpFailure.classify(e)` | `data/net/HttpFailure.kt:17`, `:98` | From `kind`; the backend's `detail` code is in `body`, its request id in `requestId` for the "(ref: …)" suffix | `isRetryable`; `isGoneOrNotOurs` (404/403) means give up |
| A non-200 backend answer, inside the client | `ApiAnswer(code, body, requestId)` with per-route mappers (`failSigned`, `failMe`, `failApprovedOnly`) | `data/net/IndicApiHttp.kt:78`, `data/net/IndicApiCalls.kt:61-93` | Becomes the specific exception (`DeviceConflictException`, `NotApprovedException`, …) or an `ApiException` that `HttpFailure` classifies | — |
| Why a run stopped | `RunStop` (sealed; `wireCode` is the stored `Int`, `fromWireCode` maps any `Int` back) | `field/RunStop.kt` | `EngineFailure.reasonRes` / `shortReason` (`ui/analysis/run/EngineFailure.kt:46`, `:72`); an unknown code is shown with its number, never as a known cause | A run is not retried; Compute is re-enabled after every outcome (`BatchRunController.kt:116`) |
| A restore or Save-to-Files download | `DownloadFailure`: `Rejected` (404/403), `Unusable` (corrupt or incomplete backup), `Transient` | `data/cloud/restore/DownloadFailure.kt` | `Rejected`: the backend's licence message (`LicenseErrors.restoreMessage`); `Unusable`: "restore failed", the reason code only logged (`data/DicRestoreWorker.kt:94-114`) | `Transient` → `Result.retry()`; the other two end the work |
| A backup upload | `UploadFailures`: one method per thrown failure, catch order in `DicUploadWorker` (`data/DicUploadWorker.kt:137-155`); HTTP refusals by `UploadErrors.classify` → `Kind` (`data/cloud/UploadErrors.kt:50`) | `data/UploadFailures.kt` | `run.failure(<string>)` writes the reason Home shows; quota opens the limit screen instead | `UploadLog.retry` keeps staging; a stale session or expired link is discarded and recreated, at most a bounded number of times; terminal paths go through one `abandon` |
| A session index write | `SessionStore.UpsertResult`: `SAVED`, `QUOTA_FULL`, `INDEX_UNAVAILABLE` | `data/session/SessionStore.kt:91` | `afterSave` (`ui/analysis/run/DicBatchRunner.kt:415-420`): quota → the session-limit screen; unavailable index → "Analysis not saved". Sweeps go the same way: `finishSolvedSweep` (`ui/analysis/wizard/SweepRunner.kt:141`) saves through `persistSweepSession`, which returns `afterSave`'s reading (`:259`) | Not retried |

**Cancellation.**

- **Rethrow `CancellationException`.** In a `suspend` path use
  `suspendRunCatching` (`util/SuspendRunCatching.kt`), never `runCatching`, or
  put `catch (e: CancellationException) { throw e }` ahead of a generic catch
  (`data/DicUploadWorker.kt:147-151`).
- **A cancelled Firebase `Task` is not the caller's cancellation.**
  `Task.await()` throws `CancellationException` when the task was cancelled
  while the caller is still active. In a generic catch around Firebase or
  backend calls, call `rethrowIfCallerCancelled()` first
  (`util/CallerCancellation.kt`); it rethrows only if this coroutine was
  cancelled, so the rest is mapped as an ordinary failure. `authed` does this
  for every backend call (`data/net/Authed.kt:47-52`).
- **`NonCancellable` only for cleanup that must finish**, such as the
  erase → wipe → sign-out sequence (`data/cloud/CloudErase.kt:47`) or putting
  the UI back after a cancelled import (`ui/analysis/frames/AnalysisVideoExtractHelper.kt:69`,
  `:99`). Never to make a cancellation disappear. See
  [ADR-016](ADR-016-work-that-outlives-the-activity.md) for long work.
- **Classify after cancellation is dealt with.** `HttpFailure.classify` and
  `DownloadFailure.of` read a `CancellationException` as an ordinary
  (unexpected) failure, so they must not see the caller's own.
- A scope that is never cancelled may use `runCatching` and must say so
  (`ui/settings/AccountDeletionRun.kt:83-87`, `ui/common/auth/SignOutRun.kt:77-80`).

**Rule for new code.** A new backend call goes through `authed` and maps the
`Authed` outcome where it is used; a new failure domain gets a sealed type (or
an enum) with the user message and the retry decision decided once, next to
it. An `Int` or `Boolean` failure result is not added again.

## Options considered

### A: One sealed outcome per domain (chosen)

Callers branch with an exhaustive `when`, so a new case is a compile error at
every site that has to decide about it.

### B: Exceptions all the way up

What the code did. Each catch chain chose its own order and subset, and they
disagreed (the metadata send retries kinds the generic rule does not).

### C: `kotlin.Result` everywhere

It carries a `Throwable`, not a reason, so every caller still re-derives what
the failure means; and `runCatching` swallows cancellation.

## Trade-off analysis

A adds a type per domain and a mapping step, and keeps exceptions inside the
client (`IndicApi` still throws; `HttpFailure` reads them once). In exchange
the user message and the retry rule are each written down once per domain,
and the tests pin them (`HttpFailureTest`, `AuthedTest`, `AfterSaveTest`,
`RunStopTest`, `CloudSyncFailureMappingTest`, `MetadataSendFailureRuleTest`).

## Consequences

- An account deletion that fails after the cloud erase says so
  (`PHONE_NOT_CLEARED`, #329); a failed index write says "not saved" instead
  of "limit reached" (#330, sweeps in #331).
- A cancelled run reads "Cancelled", and an unknown engine code is shown with
  its number (#330, #331).
- Not every caller is converted: `RetryOnTransient` keeps its own 503 rule
  (idempotent calls only) rather than `HttpFailure.SERVER`, which would change
  which calls retry.
- detekt does not check cancellation handling: `SuspendFunSwallowedCancellation`
  needs type resolution, which `:app:detekt` does not run (TD-171).

## Action items

1. [x] `Authed`, `HttpFailure`, `ApiAnswer` (#321, #324) and their adoption
   (#323, #326, #327).
2. [x] `RunStop` (#318) and its adoption (#325, #330); `UpsertResult` (#326,
   #330, #331); `DownloadFailure` (#326); `UploadFailures` (#327).
3. [ ] TD-171: run detekt with type resolution and enable
   `SuspendFunSwallowedCancellation`.
