# ADR-035: A deleted cloud backup goes to a 30-day recycle bin, held in its own collection

**Status:** Accepted 2026-11-01, in progress (backend built). The app's data layer
and the bin's screens follow in two later PRs; production waits for counsel's
Privacy Policy release (TD-277).
**Date:** 2026-11-01
**Deciders:** product owner
**Register:** TD-384 (a phone that never sees the bin re-uploads after the purge)

Paths are from `backend/app/` unless they start at the repository root.

## Context

The 2026-11-01 whole-repo review found two ways a deleted cloud backup came back:

- **L5-2: a delete on one phone was undone by another phone's reconcile.** Phone A
  deletes an analysis everywhere. Phone B still holds it as SYNCED; its next
  reconcile sees the backup missing from `GET /v1/sessions`, marks the row PENDING
  and uploads it again (`CloudReconcile.repairRows`,
  `app/src/main/java/com/sempermechanics/semper/data/cloud/CloudReconcile.kt`).
  Phone A then restores the new copy through the sync from other phones. Nothing
  on the server recorded that the analysis had been deleted, so B could not tell
  "deleted" from "lost".
- **L5-3: an upload in flight survived a delete.** No delete cancels the upload
  work, and `DELETE /v1/sessions/{sid}` erased the Drive folder and the metadata
  at once. The worker then found its session gone, created a new one and marked
  the row SYNCED.

Two facts shape the fix:

- **One analysis has one local id on every phone.** A restored row keeps the
  backup's `localSessionId`, so the server can recognise a re-upload by
  `(uid, localSessionId)`.
- **The session document cannot carry a "deleted" status safely.** Several
  writers change `sessions/{sid}` without knowing about a delete:
  `repo/sessions.set_session_status` (it only refuses to leave COMPLETED),
  `repo/sessions.bump_session_progress` (it moves a non-COMPLETED session to
  COMPLETED when the last file lands) and `session_provision.provision_session`.
  Every reader would also need a filter: `list_user_sessions`,
  `count_user_sessions`, `count_device_sessions` and each `_owned_session`
  lookup in `routers/sessions.py`.

The product owner's decision: a deleted cloud backup goes to a per-account recycle
bin for 30 days, where it can be restored or deleted permanently. After 30 days it
is purged. While it is binned, no phone may upload it again or download it. On
quota, the owner accepted the default: binned items count toward no cap, and a
restore needs room.

## Decision

- **A separate tombstone collection, `deleted_sessions/{sid}`.** This follows
  ADR-007's `deleted_licenses`. `DELETE /v1/sessions/{sid}` moves the session
  document there in one transaction (`repo/recycle_bin.move_to_bin`): the
  tombstone is written and the session deleted together. A binned session is
  absent from `sessions`, so it drops out of every listing, count and ownership
  check at once, and the writers above find no document to update.
- **Three states.**
  - `binned`: every field the session had, plus `priorStatus`, `deletedAt`,
    `deletedByDeviceId`, `deletedByApp`, `filesBinned` and `purgeAt`
    (`deletedAt` + `RECYCLE_BIN_DAYS`, 30).
  - `purging`: the same, while a purge deletes the Drive folder. `purgingAt` says
    since when. A purge that died is taken over after `PURGE_LEASE` (15 minutes,
    past the Cloud Run request timeout of 300 s).
  - `erased`: the document is replaced, not patched, by a marker of ids alone:
    `uid`, `app`, `localSessionId`, `erasedAt`, `erasedReason` (`user` or
    `expired`) and `expireAt` (`erasedAt` + `ERASED_MARKER_DAYS`, 30). The marker
    lets a phone tell "deleted" from "lost" after the purge. `expireAt` is the
    Firestore TTL field; `purgeAt` is not, because the folder in Drive has to be
    deleted first.
- **The files fail closed.** File documents stay in `files/`, flagged `binnedAt`.
  `bin_session` (`services/recycle_bin.py`) flags them before the move, and a
  restore clears the flag after the move back. A crash between the two steps
  leaves files that refuse completion and download, never the reverse. Binning
  makes no Drive call; the bytes stay where they are until the purge.
- **What a binned analysis refuses.**
  - `POST /v1/sessions` for a `(uid, localSessionId)` that is binned or purging:
    409 `session_in_recycle_bin: <sid>` (`refuse_if_binned`, after the resume
    lookup and before the quota check). An erased marker does not block it: a
    permanent delete leaves the holder free to back the analysis up again.
  - `POST /v1/files/{id}/complete` for a flagged file: 409
    `session_in_recycle_bin: <sid>`. This is the server half of L5-3.
  - `GET /v1/files/{id}/content` for a flagged file: 404 `file_not_found`.
  - `/uploads`, `/files`, `PUT /metadata` and `/bundle` already answer 404
    `session_not_found`, because `_owned_session` finds no session.
- **The routes.**
  - `DELETE /v1/sessions/{sid}` bins. The provisioning guard (409
    `session_provisioning`) stays. Its answer keeps `deleted` and `filesRemoved`
    (now the files binned) and adds `binned: true` and `purgeAt`. A repeat for a
    session already in the caller's bin answers the same (200).
  - `GET /v1/recycle-bin` (`routers/recycle_bin.py`): one app's bin (by
    `X-App-Id`, or `?app=` as `GET /v1/sessions` takes it, `all` for the
    account), cursor-paginated. `items` hold the binned analyses, each with its
    `status` as it was when binned, `deletedAt` and `purgeAt`. `erased` holds the
    markers. `retentionDays` is 30.
  - `POST /v1/recycle-bin/{sid}/restore`: device-signed, paused with uploads.
    It recreates `sessions/{sid}` with `status = priorStatus` and deletes the
    tombstone in one transaction (`repo/recycle_bin.restore_from_bin`), then
    unflags the files. A repeat answers the same.
  - `DELETE /v1/recycle-bin/{sid}`: device-signed. It runs the purge now. A
    Drive failure is 502 and leaves the entry in the bin.
  - `POST /v1/tasks/recycle-bin-purge`: Cloud Scheduler, daily, OIDC as the
    invoker SA (`tasks.tasks_caller`), off the gateway like the other task
    routes.
- **The purge (`purge_one`), shared by the job and the permanent delete.**
  1. `claim_purge` moves `binned` to `purging` in a transaction; an entry already
     being purged within the lease is skipped.
  2. The Drive folder is deleted (`drive.delete_file`; Drive's 404 counts as
     gone). Any failure puts the entry back to `binned` (`release_purge`) and
     raises, so the job finds it due again the next day.
  3. The session's file documents are deleted.
  4. The tombstone becomes the erased marker.
  5. The audit row `SESSION_PURGE` is written.

  The job selects `purgeAt <= now`, ordered by `purgeAt`, at most
  `RECYCLE_BIN_PURGE_BATCH` (100) a run, and answers `{due, purged, failed}`.
  One failure does not stop the rest. `GET /v1/recycle-bin` also purges up to 3
  of the page's due entries on the way (failures are logged and left to the
  job), so an entry past its 30 days does not wait for the job. A restore past
  `purgeAt` is refused 404 `session_not_found` even before the purge, because the
  job can run a day late.
- **Quota.** A binned analysis counts toward neither the account's cap nor a
  demo device's, so the quota remedy "delete an older analysis" still frees a
  place. A restore needs room: `services/sessions.require_room` is split, and its
  count half, `require_capacity`, is shared by a new backup and a restore. A
  restore with no room is refused 409 `session_quota_exceeded` or
  `device_quota_exceeded`, as a new backup is. A restored session keeps the
  `deviceId` it was binned with, so a staff release or lock clear that drops a
  phone's `deviceId` from the account's sessions drops it from the bin's entries
  too (`repo/devices.forget_device_sessions`, TD-276).
- **Erasure and export.**
  - Account erasure (`repo/account.delete_all_user_data`) deletes every
    `deleted_sessions` document of the account, in any state, and counts them as
    `binnedSessions` in the `ACCOUNT_DELETE` row.
  - Their file documents go with the account's files. Their Drive folders go
    with the user folder, or one by one in `_purge_drive`'s fallback
    (`services/account.py`), which now lists the bin's `driveFolderId`s too.
  - `GET /v1/me/export` gains `recycleBin` (each binned analysis as the bin
    lists it, with its file manifest) and `erasedBackups` (`sessionId`,
    `localSessionId`, `erasedAt`). Both come after `sessionCount` and before
    `complete`, which stays last. The change is additive; the schema stays
    `indic.account.export/1`.
- **Audit.** `SESSION_BIN` {`localSessionId`, `app`, `filesBinned`, `purgeAt`},
  `SESSION_BIN_RESTORE` {`localSessionId`, `app`} and `SESSION_PURGE` {`reason`
  (`user` or `expired`), `filesRemoved`, `localSessionId`}. A purge's row is
  written under the owner's uid, whoever ran it. `SESSION_DELETE` is no longer
  written.
- **Fixed constants.** `RECYCLE_BIN_DAYS`, `ERASED_MARKER_DAYS` and
  `RECYCLE_BIN_PURGE_BATCH` are constants in `config.py`, not environment
  variables: the Privacy Policy is to name the 30 days (TD-277), so changing them
  is a policy change, not a deploy setting.

### Installed builds

No build in the field knows the bin, and none needs to:

- **An old build's delete now bins.** It reads `deleted` and `filesRemoved` as
  before and forgets the backup. The data lives 30 days longer than that build
  says, which is the safe direction.
- **An old build's reconcile re-upload is refused.** Its `POST /v1/sessions` gets
  409 `session_in_recycle_bin: <sid>`. `UploadErrors.classify`
  (`app/src/main/java/com/sempermechanics/semper/data/cloud/UploadErrors.kt`)
  maps a 409 it does not know to `REJECTED`, so the worker gives up and marks the
  row FAILED, and the reconcile requeues only PENDING rows. The user sees a
  failed backup with the generic message; a manual retry gets the same 409.
- **An old build's upload in flight stops.** The completion of a flagged file is
  refused 409, which ends the same way. A worker that asks for its upload targets
  first gets 404 `session_not_found`, discards the session (a repeat delete of a
  binned session answers 200) and creates it again, which is refused 409.

## Options

| Option | Why not |
|--------|---------|
| A `deleted` status on the session document | The writers named above would undo it, and every reader would need a filter (Context). ADR-007 rejected the same option for licences for the same reasons |
| Keep the hard delete and add a TTL'd marker of ids alone | Fixes L5-2's re-upload, but leaves no way back from a delete made by mistake, which the owner asked for |
| Purge by TTL on `purgeAt`, as ADR-007 does | TTL deletes the Firestore document only; the Drive folder would be left with nothing pointing at it. The job deletes the folder first, then the documents |
| Binned items count toward the quota | A demo account at its cap could not free a place by deleting, which is the remedy the quota refusal names. The owner chose the default the other way |
| Block a re-upload while the erased marker lives too | A permanent delete is the holder's choice to be rid of the backup, not to forbid one. New builds use the marker to hold off on their own (the app PR) |

## Trade-offs

- **Data is kept 30 days after a delete.** That is the point of the bin, but it
  changes what the Privacy Policy promises about a deleted backup. Counsel holds
  the policy text (TD-277), so production waits for its release.
- **The quota is softer.** The capacity check runs before the restore's
  transaction, as the create's does before its write, so two restores at once can
  both pass it. The quota is already soft for the same reason.
- **A binned entry belongs to the app that backed it up.** ADR-014 lets either
  app delete any of the account's backups. The bin lists, restores and purges only
  the asking app's entries (`?app=all` lists every app's), so an entry the other
  app deleted is restored from the app that made it.
- **One more read on each create.** `find_binned_session` is a two-field equality
  query on `deleted_sessions`, served by the automatic indexes. The read budget
  is in `backend/tests/test_read_budget.py`.

## Consequences

- **Residual risk (TD-384).** A phone that is offline for the whole bin window,
  or an old build that never reconciles during it, can still upload the analysis
  again after the purge, because nothing then blocks a re-upload. New builds hold
  off while the erased marker lives (30 days after the purge); old builds do not
  read it.
- **Deploy.** The TTL policy on `deleted_sessions.expireAt` is declared in
  `backend/firestore.indexes.json` and enabled on the shared database by the
  owner (`docs/backend/BACKEND_SETUP_CONSOLE.md` §3a). Without it erased markers
  are never deleted; nothing else changes. The Cloud Scheduler job is created per
  environment ([DEPLOY_CHECKLIST_2026-10.md](../ops/DEPLOY_CHECKLIST_2026-10.md)).
  Without it nothing is purged unless the holder opens the bin, and a restore
  past `purgeAt` is still refused, so the only effect is data kept longer than
  promised.
- **Contract.** The OpenAPI snapshot (ADR-029) gains the three bin routes, the
  task route. The only line it changes is the description of
  `DELETE /v1/sessions/{sid}`; nothing is removed, so it is not breaking.
  `contracts/recycle_bin_response.json` pins the listing's shape for
  `backend/tests/test_wire_contracts.py` and, from the app PR, the app's DTO test.
- **Gateway.** `backend/gateway/openapi.yaml` declares the three public bin routes
  and not the task route (`backend/tests/test_gateway_parity.py`).
- **Firestore rules.** `deleted_sessions` is in
  `firestore-rules-tests/collections.mjs`, so the deny-all rules are tested on it.

## Action items

- [x] Backend: collection, states, routes, purge job, quota split, erasure,
      export, audit, gateway, contract (this PR).
- [x] App data layer: index field for a binned row, reconcile against the bin and
      the erased markers, the delete that cancels the upload work, and the upload
      guard (second PR; `session_in_recycle_bin` is in `errors.CLIENT_BRANCHED`
      with it).
- [ ] App UI: the recycle bin screen, its entry points and the dialog copy (third
      PR).
- [ ] Privacy Policy: the bin and the erased marker in the retention table, held
      with counsel (TD-277).
- [ ] Deploy, staging first: TTL policy, backend, gateway, the Scheduler job
      ([DEPLOY_CHECKLIST_2026-10.md](../ops/DEPLOY_CHECKLIST_2026-10.md),
      "Recycle bin"). Production only after counsel releases the Privacy Policy
      change.
