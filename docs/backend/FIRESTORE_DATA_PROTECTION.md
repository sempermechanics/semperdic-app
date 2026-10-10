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

## Account-erasure audit policy

Account erasure deletes users, devices, sessions, files, and Drive artifacts.
`audit_logs` is deliberately excluded so security and deletion events remain
available for incident response and proof of erasure. Audit records must not
contain uploaded content, filenames, specimen names, email addresses, tokens,
keys, or resumable URLs. They may retain the opaque uid/device id and event
metadata only. Access is server/admin-only under the deny-all client rules.
Legal/privacy owners must set and document the retention period and configure a
Firestore TTL field before production launch; backups inherit the same approved
retention/legal-hold policy.
