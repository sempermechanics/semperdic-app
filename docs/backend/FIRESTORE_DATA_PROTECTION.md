# Firestore backup, PITR, retention, and restore drills

These commands mutate cloud resources and require an authorized operator. They
were not run while preparing this repository.

## One-time controls

1. Create a dedicated backup bucket in a second region or dual-region and
   enable uniform bucket-level access and object versioning.
2. Grant the Firestore service agent permission to write exports to the bucket.
   Grant the GitHub backup service account only the Firestore export and bucket
   permissions it needs; use Workload Identity Federation, not a JSON key.
3. Enable PITR and verify its state:

   `gcloud firestore databases update --database='(default)' --enable-pitr --project=PROJECT_ID`

   `gcloud firestore databases describe --database='(default)' --project=PROJECT_ID --format='value(pointInTimeRecoveryEnablement,earliestVersionTime)'`

4. Apply a reviewed bucket lifecycle policy retaining daily exports for the
   approved period (recommended baseline: delete after 35 days, with a separate
   monthly archive policy if regulation requires it). Example policy:

   `{"rule":[{"action":{"type":"Delete"},"condition":{"age":35,"matchesPrefix":["firestore/"]}}]}`

   Apply with `gcloud storage buckets update gs://BUCKET --lifecycle-file=FILE`.
   Retention duration is a business/legal decision; do not shorten it during an
   incident.
5. Configure the `production-backup` GitHub environment variables used by
   `.github/workflows/firestore-backup.yml`: `GCP_PROJECT`,
   `FIRESTORE_BACKUP_BUCKET`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, and
   `FIRESTORE_BACKUP_SERVICE_ACCOUNT`. If any are empty, the workflow now fails
   immediately with a named list (instead of google-github-actions/auth's
   opaque "must specify exactly one of workload_identity_provider or
   credentials_json"). On a Free private org, repository Variables work when
   Environment-scoped vars cannot be set — see [ENVIRONMENTS.md](../ops/ENVIRONMENTS.md).

## Scheduled exports

The workflow runs daily at 02:17 UTC. `scripts/firestore-export.sh` refuses to
export unless PITR is enabled and waits for export completion. Alert on workflow
failure and review bucket objects weekly. API Gateway/in-process application
rate limits are unrelated to these Admin API operations.

## Restore drill

Automated: `.github/workflows/firestore-restore-drill.yml` runs monthly (03:40
UTC on the 1st) and on demand. It imports the latest export into the drill
project, **waits** for the operation, verifies the result, and purges the drill
database afterwards so a second full copy of production is not left sitting in a
weaker project. Both the verify step (`scripts/firestore_verify.py`) and the
purge cover every collection the backend writes — including `licenses`, their
`seats` subcollection, `licenseInvites`, `auth_links`, and `deleted_licenses`
with its `deleted_seats` (a deleted licence held 30 days, §20.6 of
CLOUD_ARCHITECTURE_GCP.md) — so add a new collection to both when the backend
starts writing one. The drill project has no TTL policies, so held licences
are compared exactly like the rest.

### Configuring the `restore-drill` environment

The workflow runs in a GitHub environment named **`restore-drill`**, separate
from the backup workflow's. Keeping it separate is the point: the drill
credentials can write to a throwaway project and must never be able to import
over production, so they should not be reachable from any other workflow.

Create it under **Settings → Environments → New environment**, name it
`restore-drill`, and add these repository/environment **variables**:

| Variable | What it is |
|---|---|
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | The WIF provider the drill authenticates through — no JSON key |
| `FIRESTORE_RESTORE_DRILL_SERVICE_ACCOUNT` | The identity it assumes. Grant it Firestore import/export on the **drill** project only |
| `FIRESTORE_RESTORE_DRILL_PROJECT` | The throwaway project the export is imported into |
| `FIRESTORE_BACKUP_BUCKET` | Where `firestore-export.sh` writes; the drill reads the latest export from here |
| `GCP_PROJECT` | The **production** project. Passed in so the script can refuse to target it |

Adding required reviewers to the environment is worth doing: it makes an
on-demand drill a deliberate act rather than a button anyone can hit.

The workflow checks these five first and fails naming the ones that are empty.
The drill project and the variables were set up on 2026-10-09, and the first
drill passed on 2026-10-10 (an RTO of 111 s; recorded in
[PRODUCTION_READINESS_GATE.md](../ops/PRODUCTION_READINESS_GATE.md)). Runs before
that failed for want of them (TD-156). The one-time setup, should it be needed
again, is
`scripts/setup-restore-drill.sh`, run by someone who can create projects, owns
the billing account and the production backup bucket, and is a repo admin:

```bash
scripts/setup-restore-drill.sh --dry-run                          # prints the steps
scripts/setup-restore-drill.sh --billing-account <account id>     # gcloud billing accounts list
gh workflow run firestore-restore-drill.yml -R sempermechanics/semperdic-app
```

It creates the drill project `indicvision-dic-restore-drill` (`--project` to
change it) and its `(default)` Firestore database in asia-south1, and the
`restore-drill@` identity with `roles/datastore.owner` on that project only. It
lets the repo's workflows act as that identity through the existing `github`
Workload Identity pool on production, whose provider admits only this repo. It
gives the identity and the drill project's Firestore agent `objectViewer` and
`legacyBucketReader` (the import checks `storage.buckets.get`) on the
backup bucket, and sets the five variables above. Each step checks before it
creates, so a rerun continues where a failed one stopped.

Cost: about nothing. The database is a few thousand documents
([backend-cost.md](../perf/backend-cost.md)), so a monthly import and purge stay
inside the drill database's own free tier (20k writes and 20k deletes a day), and
the export it reads is a few MB from the ASIA multi-region bucket. Billing must
still be linked: the managed import needs it.

Nothing here grants the drill identity any role on production: it can read the
backup bucket and nothing else there.

Verification is not a checklist — it is `scripts/firestore_verify.py`, which
compares the restored database against `manifest.json`, written beside each
export by `scripts/firestore-export.sh` at export time:

* per-collection document counts, exactly (`challenges` is excluded — 120 s TTL
  nonces legitimately differ between export and restore);
* a 25-session sample re-walked as `session → files`, so a restore that keeps the
  counts but loses the relationships fails;
* `schemaVersion` presence, which catches importing a stale or wrong export.

Any mismatch fails the workflow. Exports taken before manifests existed cannot be
verified automatically — the drill exits 3 rather than passing silently.

To run it by hand against a specific export:

```
PRODUCTION_PROJECT=prod RESTORE_DRILL_PROJECT=recovery \
FIRESTORE_EXPORT_URI=gs://... bash scripts/firestore-restore-drill.sh
```

The script refuses to target the named production project.

**RTO is whatever the drill reports.** The import is timed and printed; record
that number, the recovery point, and the verification output against
`docs/ops/PRODUCTION_READINESS_GATE.md`. Until a drill has passed at least once,
treat the restore path as unproven.

PITR complements exports; it does not replace independently retained exports or
restore drills.

## The recycle bin (`deleted_sessions`)

A deleted cloud backup is held 30 days before it is erased
([ADR-035](../adr/ADR-035-recycle-bin.md); CLOUD_ARCHITECTURE_GCP.md §4.3).

**What it holds.** While an entry is `binned` (or `purging`), it is the session
document as it stood, moved unchanged: `uid`, `app`, `localSessionId`, the
analysis's `name` and `specimen`, the creating `deviceId`, `driveFolderId`, sizes
and the engine `metrics`, plus `priorStatus`, `deletedAt`, `deletedByDeviceId`,
`deletedByApp`, `filesBinned` and `purgeAt`. The session's file documents stay in
`files` flagged `binnedAt`, and its Drive folder stays where it is. Once purged,
the entry is replaced by an erased marker of ids alone: `uid`, `app`,
`localSessionId`, `erasedAt`, `erasedReason` (`user` or `expired`) and
`expireAt`. The marker holds no name, specimen, device or Drive id.

**Retention.**

| What | Kept | Removed by |
|---|---|---|
| A binned entry, its file documents and its Drive folder | 30 days from the delete (`purgeAt` = `deletedAt` + `RECYCLE_BIN_DAYS`), unless restored or deleted permanently sooner | The daily purge job (`POST /v1/tasks/recycle-bin-purge`), the holder's "Delete permanently" (`DELETE /v1/recycle-bin/{sid}`), or the holder opening the bin past `purgeAt` (it purges up to three due entries on the way). Drive first, then the file documents, then the entry |
| The erased marker | 30 days from the purge (`expireAt` = `erasedAt` + `ERASED_MARKER_DAYS`) | The Firestore TTL policy on `deleted_sessions.expireAt` |

Both periods are constants in `backend/app/config.py`, not environment
variables: the Privacy Policy is to name them (the change is held with counsel,
TD-277), so changing one is a policy change. `purgeAt` is not a TTL field, because TTL would delete the
entry and leave its Drive folder. If the job stops, entries are kept past their
30 days (a restore past `purgeAt` is still refused); if the TTL policy is
missing, markers are kept indefinitely. The purge job takes 100 entries a run
(`RECYCLE_BIN_PURGE_BATCH`); a backlog clears over the following days. The purge
does not reach the database exports: a purged entry stays in them for as long as the backup bucket's lifecycle policy keeps them (One-time
controls, step 4).

**Erasure.** Account erasure deletes every `deleted_sessions` document of the
account at once, binned and erased alike, without waiting for the purge
(`delete_all_user_data`, counted `binnedSessions`). Their file documents go with
the account's files, and their Drive folders with the user folder, or one by one
in the erasure's fallback (`_purge_drive` in `backend/app/services/account.py`).

**Export.** `GET /v1/me/export` lists the bin after `sessionCount` and before
`complete`: `recycleBin` (each binned analysis as the bin lists it, with
`deletedAt`, `purgeAt` and its file manifest) and `erasedBackups` (`sessionId`,
`localSessionId`, `erasedAt` per marker). The schema stays
`indic.account.export/1`. A binned analysis's files are not downloadable
(`GET /v1/files/{id}/content` answers 404 until it is restored).

**Audit.** `SESSION_BIN` {`localSessionId`, `app`, `filesBinned`, `purgeAt`},
`SESSION_BIN_RESTORE` {`localSessionId`, `app`} and `SESSION_PURGE` {`reason`,
`filesRemoved`, `localSessionId`}, each targeting the session id. A purge is
recorded under the owner's uid whoever ran it; `reason` says whether the holder
(`user`) or the 30 days (`expired`) erased it. None names an analysis, a file or
an address. `SESSION_DELETE` is no longer written; rows from before remain until
their own `expireAt`.

**Restore drill.** Both of the drill's collection lists name each collection
(`COLLECTIONS` in `scripts/firestore_verify.py`, and the bulk-delete loop of the
"Purge the drill database" step in `.github/workflows/firestore-restore-drill.yml`),
and `deleted_sessions` has to be on both, as the section above asks of every new
collection.

## Account-erasure audit policy

Account erasure deletes users, devices, sessions, the recycle bin's entries
(`deleted_sessions`, below), files, Drive artifacts, and
every `auth_links` row that maps a sign-in to the account. Seats the account
held, on any licence and in a deleted licence's 30-day hold, are kept (they
count against the licence and appear in its audit export) with `email` blanked;
a live one is revoked first (`backend/app/repo/account.py:166-265`). The
`ACCOUNT_DELETE` audit row records the counts, including `binnedSessions`,
`authLinks`, `seatsBlanked` and `seatScanComplete`. The seat search is a collection-group
query on `uid` that needs the `seats.uid` / `deleted_seats.uid` COLLECTION_GROUP
index (`backend/firestore.indexes.json:161-204`): deploy the indexes before the
backend. Without it the search fails, only the current licence's seat is
blanked, `seatScanComplete` is false and `erasure_seat_scan_failed` is logged
(TD-271).

`audit_logs` is deliberately excluded so security and deletion events remain
available for incident response and proof of erasure. Audit records must not
contain uploaded content, filenames, specimen names, email addresses, tokens,
keys, or resumable URLs. They may retain the opaque uid/device id and event
metadata only. Where an action needs to name an address it records
`audit.address_tag` (`observability.short_hash`, a 12-hex hash;
`backend/app/audit.py:27-38`): `emailLockHash` on an individual mint, and
`adminEmailHashes` with `adminEmailCount` on an institution mint, a convert and an
`adminEmails` edit (`backend/app/services/licenses.py:10-14`, `backend/app/routers/admin.py:29-40`). A reader who already
knows an address can match it. The hash is unkeyed (`backend/app/observability.py:225-232`),
so it keeps the address out of clear text; it does not stop someone with a list of
candidate addresses from confirming one. A licence's free-text `note` is
recorded by `ADMIN_LICENSE_EXTEND` only as changed and its length
(`backend/app/routers/admin.py:29-40`). Access is server/admin-only under the deny-all client rules.
Rows are kept **one year** (365 days; the owner's decision of 2026-10-22, TD-237):
each carries `expireAt` = its time + `AUDIT_RETENTION_DAYS` (`backend/app/config.py`,
default 365), set as it is added (`add_audit_row`, `backend/app/repo/audit_log.py`),
and a Firestore TTL policy on `audit_logs.expireAt` deletes it then. Rows from
before the change get `expireAt` from the TD-246 backfill (below). Backups inherit
the same retention/legal-hold policy.

### The audit trail is append-only

What the code holds (TD-237):

- Only `add_audit_row` writes `audit_logs`, and it only adds a document
  (`backend/app/repo/audit_log.py`). No other module names the collection, and
  the audit repo calls no update, set or delete; `backend/tests/test_audit_append_only.py`
  fails if either changes. The row's `expireAt` (now + `AUDIT_RETENTION_DAYS`) is
  written with it, so retention never needs the row rewritten.
- A migration that walks `audit_logs` must say why, in a module-level
  `rewrites_append_only` (`backend/scripts/migrations/__init__.py`, `APPEND_ONLY`);
  the loader refuses one that does not. `001_stamp_schema_version` is the one
  that did, before the guard. The one other rewrite written,
  `backend/scripts/stamp_audit_licence_ids.py` (TD-246: adds `licenseId` to
  rows from before 2026-10-18 so a licence's export reaches them, and
  `expireAt` = `ts` + `AUDIT_RETENTION_DAYS` to rows written before
  `add_audit_row` set it, so TTL reaches them; nothing else, and never a field
  a row already has), refuses `--apply` without `--decision` and records it in
  `_migrations`. The owner approved running it on 2026-10-22, and the operator
  ran it on 2026-10-23 (a rerun found all 6145 rows with `expireAt`); the TTL
  policy below went on the same day, after it.
- Clients cannot read or write it: `firestore.rules` denies everything, and
  `firestore-rules-tests/rules.test.mjs` tries an update, a merge, an
  overwrite and a delete of an audit row as each caller, an Org Owner's claims
  included.
- Every row is also logged, before the Firestore write, as one Cloud Logging
  line: `jsonPayload.event="audit_row"`, the row under `jsonPayload.auditRow`
  (logger `semper.audit_trail`, `backend/app/audit.py`). A row Firestore loses
  (`audit_write_failed`) still has that line.

What it does not hold: anyone with Firestore IAM on the project can still
edit or delete a row through the Admin API. The immutable copy is the log
line, once a sink routes it to a bucket whose retention is locked; the `audit-trail` bucket (`asia-south1`, 365 days) and its sink on `jsonPayload.event="audit_row"` were created and the bucket locked on 2026-10-23. The
retention is the one year decided above, the same as `AUDIT_RETENTION_DAYS`.
In this order, after the backend that writes `expireAt` is deployed and the
TD-246 backfill has run (dry run, then `--apply --decision "…"`), so every row
carries `expireAt` before TTL acts on any:

```bash
gcloud logging buckets create audit-trail --location=$REGION \
  --retention-days=365 --project=$PROJECT
gcloud logging sinks create audit-trail \
  logging.googleapis.com/projects/$PROJECT/locations/$REGION/buckets/audit-trail \
  --log-filter='resource.type="cloud_run_revision" AND jsonPayload.event="audit_row"' \
  --project=$PROJECT
# Irreversible: a locked bucket's retention can only be raised, and no one
# can delete a log entry or the bucket before it expires.
gcloud logging buckets update audit-trail --location=$REGION --locked --project=$PROJECT
# Then Firestore deletes each row at its expireAt. A backfilled row already
# older than a year has an expireAt in the past and goes within about a day.
gcloud firestore fields ttls update expireAt --collection-group=audit_logs \
  --enable-ttl --project=$PROJECT
```

Check: an audited action (a staff licence edit) appears in the bucket, and
`gcloud logging buckets update audit-trail --location=$REGION --retention-days=1`
is refused; `gcloud firestore fields ttls list --collection-group=audit_logs
--project=$PROJECT` shows `expireAt` in state `ACTIVE` (a few minutes after).

The policy is also declared in `backend/firestore.indexes.json` (`fieldOverrides`,
`"ttl": true`), as the other TTL policies are, so `scripts/deploy-firestore.sh
indexes` keeps it instead of stopping on a live policy the file omits. It also
means an indexes deploy run before the command above creates the policy then;
run either only once the backfill is done. Never pass `--force`.
