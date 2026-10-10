# ADR-013: A backed-up session's metadata.json can be replaced

**Status:** Accepted, built; backend deployed 2026-10-01 (semperdic-app#288, gateway `v202610010433-80`)
**Date:** 2026-09-30
**Deciders:** app owner

> **In this repo:** the backend route and its tests, and since 2026-10-01 the
> app side for a **rename** (`SessionStore.rename` marks the row;
> `SessionMetadataSync`, `SessionMetadataWorker` and the reconcile re-queue were
> ported from `sempermechanics/material_testing` #112/#113). Material Testing
> marks its deflection and curve corrections the same way (its TD-150, TD-152).

## Context

A cloud backup is written once. `POST /v1/sessions` reserves three files
(`metadata.json`, `Session.zip`, `Extras.zip`). The app uploads each one
straight to Drive, and `POST /v1/files/{id}/complete` seals it. There was no
route that changes a file after that, and the app had nothing that re-sends
one: `DicUploadWorker` sees a COMPLETED session and stops (`Resume.Done`).

Bending's deflection scale and bias (#108) are set in the viewer after the
run. They live on `SpecimenGeometry`, and the upload writes them into
`metadata.json` (`test.geometry.deflectionScale` / `deflectionBiasMm`), where
the restore reads them back. A correction made after the backup therefore
stayed on the phone only. A restore on another phone brought back the old δ,
both E values and the old report (TD-150).

Two ways to reach the cloud copy were weighed:

- **Upload the whole session again.** This works with no backend change. But
  every correction would re-send every frame (tens to hundreds of MB), hold a
  second quota slot while the old copy is deleted, and leave the session with
  no cloud copy in between.
- **Replace only metadata.json.** A few KB per correction. It needs a backend
  route and a deploy.

## Decision

1. **Backend route.** `PUT /v1/sessions/{sid}/metadata` (device-signed) takes
   the metadata JSON as its body and writes it over the session's existing
   `metadata.json` Drive object (`drive.replace_content`). It keeps the same id
   and folder. The file doc's `sizeBytes`, `sha256` and `driveMd5` follow the
   new bytes, so the restore's size check and the bundle manifest stay true.
   It refuses:
   - a session that is not COMPLETED: 409 `session_not_complete`, because the
     upload still carries a metadata.json of its own;
   - a body whose `localSessionId` is not the session's, or whose `schema` is
     not `indic.session.metadata/*`: 422 `metadata_invalid`;
   - a body over 256 KB: 413;
   - a session with no completed metadata file: 404 `metadata_not_found`.

   Every other file in a session stays write-once.
2. **App.** `SessionRecord.metadataStale` marks a row whose cloud copy
   predates a local change.
   - `SessionStore.setDeflectionCorrection` sets it when the correction changes
     on a row that has, or is getting, a cloud copy.
   - `SessionMetadataSync` rebuilds the metadata the way the upload does
     (`SessionUploadMetadata.buildMetadataJson`) and sends it.
   - `SessionStore.clearMetadataStale` clears the mark only if what was sent
     is still the row's (here the name; in Material Testing also the geometry
     and curve correction), so a change made during the send is sent again.
   - Here, `SessionStore.rename` sets the mark the same way: the name is in
     `metadata.json`, and a restore with no local row takes it from there
     (`CloudRestore.recordFrom`).
3. **Who sends.**
   - The viewer queues `SessionMetadataWorker` after a correction (Home's
     rename dialog after a rename, `SessionSelectionController.promptRename`). The worker
     waits out an upload in flight and transient failures with WorkManager's
     backoff, and stops after nine tries.
   - `CloudSync.reconcile` queues it again for any SYNCED row still marked.
     That covers a send that gave up, or a backend that does not have the
     route yet: a 404 leaves the mark for the next reconcile.

## Consequences

- A correction set after the backup survives a restore on another phone.
- The metadata's `capturedAtUtc` becomes the time of the last send, as it
  already was for a session uploaded again after a repair. So the file also
  carries `createdAtUtc`, the analysis's own creation time (added
  2026-10-05). A restore onto a new phone dates the row from it, and from
  `capturedAtUtc` for files written before that; it used to date the row from
  the restore itself.
- **The route must be deployed before it does anything.** The backend deploys
  from semperdic-app only, so this change goes upstream first. Until then the
  app's send gets the gateway's 404 and leaves the row marked. Nothing is
  lost: the next reconcile after the deploy sends it.
- A rename reaches the cloud copy too (added 2026-10-01). It was first left
  out on the grounds that the local name wins after a restore. That holds only
  on a phone that still has the row: on a new phone, or after a reinstall,
  there is no row, so the restore named the analysis from the old
  `metadata.json`. (A restore started from a backup list writes a placeholder
  row named after the reference file first; since 2026-10-10 the name in
  `metadata.json` replaces it when the restore lands, `CloudNaming.restoredName`.)
