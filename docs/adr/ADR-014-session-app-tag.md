# ADR-014: Cloud sessions are tagged with the app that backed them up

**Status:** Accepted, built, deployed 2026-10-01 (backfill, console, backend).
**Date:** 2026-10-01
**Deciders:** product owner, backend owner

[2026-10-26: Material Testing is renamed Semper Classroom (`com.sempermechanics.semperclassroom`), and its sessions are still tagged `materialtesting` (`apps.stored_name`), which a backend from before the rename reads; the tag is read, compared (`apps.same_app`) and answered as `semperclassroom` until TD-330 retags it (`backend/scripts/rename_classroom_app.py --retag`) and drops the alias. See [ADR-030](ADR-030-semper-classroom-app-identity.md).]

## Context

Semper (`com.indicvision.semper`) and Material Testing
(`com.indicvision.semper.materialtesting`) share this backend and its accounts
(material_testing ADR-009, [ADR-010](ADR-010-device-binding-per-app.md)). A
cloud session (`sessions/{sid}`) recorded who owned it (`uid`) and which phone
sent it (`deviceId`), but not which app. `GET /v1/sessions` listed the whole
account, so each app's Home backups card and restore list showed the other
app's backups. Semper restoring a Material Testing backup reads the parts it
understands and silently drops Material Testing's test data, and the restored
copy is then marked backed up.

Every app call already names its app (`X-App-Id`, ADR-010), resolved once in
`deps._authenticate` and read back with `Depends(deps.request_app)`.

## Decision

1. **Tag on create, server-derived.** `POST /v1/sessions` takes the app from
   `request_app` and `repo/sessions.create_session` writes `"app": app`
   (`semper` or `materialtesting`, the vocabulary of `devices/{id}.app`). The
   body has no app field; the server decides, as it does for the device
   binding. The `SESSION_CREATE` audit detail carries the app.
2. **Defaulting.** `repo/sessions.session_app(doc)` is `doc["app"]` or
   `semper`: every session from before the tag is read as Semper's, the same
   rule as a request without `X-App-Id`. `backend/scripts/tag_session_apps.py` stamps
   the Material Testing ones before the backend that filters is deployed.
3. **Listing.** `GET /v1/sessions` lists the asking app's sessions.
   `?app=semper|materialtesting` names one (for a caller that cannot send the
   header), `?app=all` lists the whole account (the account console), and any
   other value is `400 unknown_app`. Each entry carries `app`.
   `repo/sessions.list_user_sessions(uid, limit, page_token, app=None)`
   filters in Python over the existing `where("uid", "==", uid)` query,
   ordered by document id, and fills the page: it reads on past the other
   app's sessions until `limit` match or the account ends. The token is the
   id of the last session returned. `app=None` is the old, unfiltered listing,
   which `iter_all_user_sessions` keeps for `GET /v1/me/export` and erasure.
4. **Quota stays account-wide.** `count_user_sessions` and `quota.used` count
   every app's sessions. The cap is the account's, so one app cannot make room
   by not seeing the other's backups. *Superseded by
   [ADR-033](ADR-033-licence-per-app-explicit-activation.md) (2026-10-29): each
   app's licence has its own cap, so the count is the asking app's.*
5. **One per-session route is gated: the restore manifest.**
   `GET /v1/sessions/{sid}/files` answers `404 session_not_found` when
   `session_app(session)` is not the asking app. It is the step that starts a
   restore, so gating it means a stale list or a hand-typed id cannot restore
   the other app's backup.
6. **Console.** The account page asks `GET /v1/sessions?app=all` and shows an
   App column.

## Why the other per-session routes stay account-wide

| Route | Why it is not gated |
|-------|---------------------|
| `GET /v1/sessions/{sid}/bundle` | The browser's way out (§20.11). A browser cannot send `X-App-Id` (CORS allows `Authorization` and `Content-Type` only), so it would always read as Semper and lose Material Testing's backups. It is a zip download, not a restore into an app. |
| `GET /v1/files/{id}/content`, `POST /v1/files/{id}/complete` | Per file, not per session; reaching them needs a file id from the gated manifest or from an upload this app started. Gating them costs a session read per range window. *Since [ADR-033](ADR-033-licence-per-app-explicit-activation.md) `/content` pays that read anyway: it checks the licence of the session's app, not the asking app's (2026-11-01), and still answers either app.* |
| `GET /v1/sessions/{sid}/uploads` | The resume path of an upload the asking app created, so it is that app's session already. |
| `PUT /v1/sessions/{sid}/metadata` | Written only by the app that holds the analysis (ADR-013); the body must match the session's `localSessionId`. |
| `DELETE /v1/sessions/{sid}` | Erasure is the account holder's right whatever the app, and a phone that restored the other app's backup before this change holds a link to it (`cloudSessionId`) that its delete must still reach. |

## Options

| Option | Why not |
|--------|---------|
| A `where("app", "==", app)` query | An equality filter cannot match a document with no `app` field, which is every session before the tag, and `uid` + `app` needs a new composite index on the database staging shares with production. Filtering in Python reads the same documents the unfiltered page did when one app holds the account. |
| The app sends its app in the session body | A client could file a backup under the other app; the header is already resolved and audited for the device binding. |
| Filter on the phone | Every build in the field would still list both apps' backups; the console would still not tell them apart. |
| Per-app quotas | Doubles what an account can store, and nothing in the licence terms says that. |
| Gate every per-session route | Breaks the browser bundle download for Material Testing and costs reads on the hot file routes, for no restore it would prevent beyond the manifest. |

## Trade-offs

- A page can cost more reads than it returns: a Material Testing caller on an
  account that is mostly Semper's reads past Semper's sessions to fill a page.
  It is bounded by the account's session count, which the quota caps, and the
  app lists 100 a page.
- The tag is as good as `X-App-Id`, a claim until App Check checks the token's
  `app_id` (ADR-010, Trade-offs). A modified build can list and restore the
  other app's backups, which are its own account's anyway.
- The backfill attributes a session by its device's `app`. Material Testing
  builds from before they sent `X-App-Id` registered their phones as Semper, so
  their sessions are tagged Semper. Their `metadata.json` schema
  (`indic.session.metadata/6`) would tell them apart, but it is in Drive; the
  few lab builds involved do not justify reading every session's metadata.

## Consequences

- No app release: both apps already send `X-App-Id`, and a list filtered to
  the asking app is a list the app already reads.
- Rollout order: dry-run the backfill, apply it, deploy the console (so it asks
  `?app=all` before the backend filters), deploy the backend, then run the
  backfill again for sessions the old backend wrote in between. Untagged
  sessions are Semper's from the moment the backend deploys, so a Material
  Testing backup missed by the backfill disappears from that app until tagged.
- A phone that restored the other app's backup before this change keeps a
  backed-up row the new list does not show. Its reconcile would have marked it
  PENDING and uploaded it again as a duplicate (TD-153), so the app's
  reconcile asks `?app=all` once whenever a SYNCED row is missing from its own
  list, and leaves a row SYNCED when another app's COMPLETED backup holds its
  local id or its stored cloud id (`CloudReconcile.list`). If that listing
  fails, the pass repairs no row.
- A third app is one more entry in `apps.py`; the console names the apps it
  knows and shows any other value as sent.

## Action items

- [x] Tag on create, `session_app`, filtered listing, `/files` gate
      (`backend/app/repo/sessions.py`, `backend/app/routers/sessions.py`).
- [x] Backfill script and tests (`backend/scripts/tag_session_apps.py`,
      `backend/tests/test_session_app_tag.py`).
- [x] Console App column and `?app=all`
      (`firebase-hosting/public/console/account/account.js`).
- [x] Run the backfill against production (dry-run, then `--apply`): 2026-10-01,
      75 sessions tagged, 68 `semper` and 7 `materialtesting`.
- [x] Deploy the console, then the backend; re-run the backfill: 2026-10-01,
      production run 36844645753, `semper-gw` `v202610010948-83`; 0 left to tag.
- [x] TD-153: settle rows restored across apps before the deploy: the app's
      reconcile checks `?app=all` before it repairs a missing row
      (`app/src/main/java/com/sempermechanics/semper/data/cloud/CloudReconcile.kt`,
      `CrossAppReconcileTest`). No backend or gateway change: the gateway
      already declares `app` on `GET /v1/sessions`.
