# Incident response

What to do when something is wrong in production: how serious it is, who is
told, and the runbooks for the incidents the readiness framework names
(item 8.2; Part E go/no-go step 3,
[READINESS_FRAMEWORK_2026-10.md](READINESS_FRAMEWORK_2026-10.md)). Alerts and
what each means: [ops/monitoring/README.md](../../ops/monitoring/README.md).
Release levers: [RELEASING.md](RELEASING.md) "Rollback levers". Production is
`semper-api` in `indicvision-dic-app`, region asia-south1, behind the gateway
`semper-gw` ([ENVIRONMENTS.md](ENVIRONMENTS.md)).

Response targets per severity are a business commitment and are set in
[SUPPORT_POLICY.md](SUPPORT_POLICY.md) (TBD there; TD-258).

## Severity

| Severity | Meaning | Examples | First actions |
|---|---|---|---|
| SEV1 | Personal data exposed, or the service unusable for most users, or a licensing control defeated at scale | Breach of personal data; signing-key compromise; `/readyz` down in production; fast SLO burn | Open an incident log; one person leads; start the breach playbook if personal data may be involved |
| SEV2 | A main path broken for many users, no data exposed | Uploads or restores failing; App Check refusing genuine builds; mass revocation needed | Incident log; lead; rollback lever first, diagnosis second |
| SEV3 | A path broken for some users, or a control degraded | One institution's roster import failing; `audit-write-failed` alert; slow SLO burn | Ticket today |
| SEV4 | Cosmetic or a single user | One device bound to the wrong account | Normal support |

Every SEV1 and SEV2 ends with a short written review (timeline, cause, what
changed) and a [CHANGELOG.md](CHANGELOG.md) entry when production changed.

## Personal-data breach (DPDP Act 2023, Rule 7)

The DPDP Rules, 2025 apply their breach obligations from **13 May 2027**. Rule 7
requires, on becoming aware of a personal-data breach:

1. intimation to **each affected Data Principal** and to the **Data Protection
   Board** "without delay": what happened, its likely consequences, what is being
   done, and what the person can do;
2. a **detailed report to the Board within 72 hours** of becoming aware (or a
   longer period the Board allows on request).

Until then the Privacy Policy's own commitment applies (notify the competent
authority within the period required by law and affected users without undue
delay, `docs/legal/PRIVACY_POLICY.md:233-239`). Counsel review of this playbook
and a tabletop exercise are owed (TD-241).

**Playbook.**

1. **Contain.** Revoke the access path (a leaked credential: disable it; a bug:
   roll back the revision or set a kill switch). Do not delete evidence: Cloud
   Logging and `audit_logs` are the record.
2. **Scope with the data map.** Which stores and fields ([DATA_MAP.md](../legal/DATA_MAP.md)),
   which accounts (uids), which window. Logs carry `uid`, `deviceId` and
   `requestId`; `audit_logs` carries actor, action and target.
3. **Clock starts** at awareness. Record the time in the incident log; the 72-hour
   report is due from it.
4. **Notify** affected principals by email to the account address, and the Board
   through its prescribed channel, without waiting for the full analysis. The
   Grievance Officer named in the policy (TD-36) is the contact on both.
5. **Report** to the Board within 72 hours: facts, cause, personal data and
   principals affected, measures taken, contact.
6. **Review** and record what changed.

## Runbook: entitlement signing-key compromise or rotation

Background: [ADR-023](../adr/ADR-023-signed-offline-entitlement.md). A token only
narrows what the cached `/v1/config` allows, never widens it
(`app/src/main/java/com/sempermechanics/semper/data/account/entitlement/EntitlementPolicy.kt:20-23`),
and the next online `/v1/config` replaces any stored token. A forged token can
therefore extend offline use on a device whose cached config was already licensed;
it cannot license a device the backend never licensed.

**Compromise (SEV1).**

1. **Stop issuing with the key.** Clear the lever variable
   `ENTITLEMENT_SIGNING_SECRET` (on the `production` environment and the
   repository: [RELEASING.md](RELEASING.md), Lever variable scope) and redeploy (`deploy-backend.yml` mounts the key
   only when it is set, `.github/workflows/deploy-backend.yml:266-286`). `/v1/config`
   then carries no `entitlement`, and every app that receives that answer forgets
   its token (`EntitlementPolicy.intake` answers CLEAR, `EntitlementPolicy.kt:151-156`)
   and runs on the cached config as before tokens.
2. **Disable the secret version** (`gcloud secrets versions disable <v>
   --secret=<name> --project indicvision-dic-app`) and find how it leaked
   (Secret Manager Data Access logs, TD-225).
3. **New key, new `kid`** (`ent-2`), as in [BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) A8.
   Release a build whose `SEMPER_ENTITLEMENT_PUBKEYS` pins **only** `ent-2`. It
   ignores tokens signed with the old key: an unverifiable token counts as no
   token and the cached config decides (`EntitlementPolicy.kt:177-184`), so a
   forged `ent-1` token gains nothing on it.
4. After adoption, raise `MIN_SUPPORTED_VERSION_CODE` (and the `_SEMPER_CLASSROOM`
   pair for Semper Classroom) to that build, so builds pinning the old key stop
   running new analyses.
5. Re-enable signing: `ENTITLEMENT_SIGNING_SECRET` to the new secret,
   `ENTITLEMENT_KID=ent-2`, redeploy.

**Planned rotation (no incident).** Ship a build pinning both `kid`s; wait for
adoption; switch the secret and `ENTITLEMENT_KID`; a later build drops the old
`kid`. Switching the signing `kid` before builds pin it does not lock anyone out
(a build that sees an unknown `kid` ignores the token and runs on its cached
config, [ADR-023](../adr/ADR-023-signed-offline-entitlement.md) decision 7), but
those builds lose the offline bound until they pin the new key, so pin first.

## Runbook: mass revocation

For a leaked institution key, a contract ended early, or abuse found by the
activation-anomaly alert.

1. **Whole licence:** staff console Revoke (`POST /v1/admin/licenses/{id}/revoke`,
   fresh step-up). **Seats:** IT or staff remove seats
   (`DELETE …/licenses/{id}/seats/{uid}`). **Accounts:** suspend
   (`POST /v1/admin/users/{uid}/revoke`), which also revokes refresh tokens once
   TD-214 is done.
2. **Online devices** drop to Demo on their next request carrying `X-Device-Id`
   (`backend/app/deps.py:183-193`) and their next `/v1/config` carries a demo
   token. **Offline devices** keep their entitlement until their token's
   `graceUntil` (at most `ENTITLEMENT_OFFLINE_GRACE_DAYS`, default 14 days, and
   never past the licence's end plus grace). Say so to the customer; that is the
   designed latency.
3. To shorten the exposure of tokens issued from now on, lower
   `ENTITLEMENT_OFFLINE_GRACE_DAYS` and redeploy. Tokens already issued keep their
   `graceUntil`.
4. Watch `revokedStillRunning` on the licence (`GET /v1/admin/licenses/{id}/reconcile`)
   and `revocationPending` on the institution's summary tiles; `devlock_drop`
   events (`semper_devlock_drop`) count devices that have seen it.

## Runbook: activation or check-in outage, against the grace period

1. Uptime or burn-rate alert fires. Read the `/readyz` 503 detail first: a
   dependency outage (Firestore, Drive) is not fixed by rolling back code.
2. If it began with a deploy: `gcloud run services update-traffic semper-api
   --to-revisions=<previous>=100 --region asia-south1 --project indicvision-dic-app`
   (the deploy also tags the previous image `rollback-prev`, [BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) A7).
   Traffic is now pinned to that revision: before any lever or redeploy, read
   **After a revision rollback** in [RELEASING.md](RELEASING.md) (revert on `main`
   first; an env change goes on the serving image with `--no-traffic`).
3. Effect on users: analysis is offline and keeps working. A device holding a
   valid token keeps its entitlement until `graceUntil`; a device without one runs
   on its cached config. **An outage shorter than the grace period does not change
   any offline user's entitlement.** Do not shorten the grace during an outage.
4. Uploads and restores queue in WorkManager and resume; nothing is lost.

## Runbook: Firestore restore

The procedure, verification and the monthly drill are in
[FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md); the last
drill passed with an import RTO of 111 s
([PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md)).

1. **Decide the recovery point.** PITR reads any minute of the last 7 days;
   daily exports (`firestore-backup.yml`, 02:17 UTC) go further back.
2. **Restore to a new database or the drill project first**
   (`scripts/firestore-restore-drill.sh`, which refuses the production project),
   verify with `scripts/firestore_verify.py`, then decide what to copy back.
3. **Stop writers** while copying back: set `KILL_SWITCHES="backup restore upload"`
   and redeploy, so phones queue instead of writing. A redeploy is a full
   `deploy-backend.yml` run (~5 min plus the production approval) that fails on
   any new dependency advisory and ships every undeployed `main` commit; when
   that is in the way, use the **Emergency lever** in
   [RELEASING.md](RELEASING.md) (the serving image, env only, no code change). The backend refuses new
   backups, their completion, restores and downloads with 503 `feature_paused`
   for every build (`backend/app/deps.py:301-336`); deletes
   (`DELETE /v1/sessions/{sid}`), metadata updates and sign-ins still write.
4. Record the RTO, recovery point and verification in the gate.

## Runbook: App Check enforcement rollback

Symptom: the `app-check-refused` alert (any `AUTH_DENIED` with
`stage="app_check"` in 10 min; `auth-denied-spike` fires only above its total
threshold), or users of a genuine build refused with `app_check_required`.

1. Set the lever variable `APP_CHECK_MODE` to `monitor` (or `off`) on the
   `production` environment (`gh variable set APP_CHECK_MODE --env production`;
   [RELEASING.md](RELEASING.md), Lever variable scope) and redeploy
   (`.github/workflows/deploy-backend.yml:348`). For speed, `gcloud run services
   update semper-api --update-env-vars APP_CHECK_MODE=monitor --region asia-south1
   --project indicvision-dic-app` first; the variable must change too, or the next
   deploy re-applies `enforce`. If traffic is pinned by a revision rollback, that
   update serves 0 %: follow **After a revision rollback** in RELEASING.md.
2. Find the build that lacks a token (`appId`, `appVersion` on `app_check_missing`
   lines), and do not return to `enforce` while it is installed (TD-226).
