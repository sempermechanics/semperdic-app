# ADR-033: A licence per app, explicit activation per phone, a device count instead of a cooldown, upgrade by issue

**Status:** Accepted 2026-10-29; in progress. Step 1 built: each app reads its own
licence off the user (`apps.licence_view`), with no change in behaviour. Step 2
built: an account holds several phones per app. Step 3 built: the activation
model, its routes and the cooldown's removal, for licences that name their app.
Step 4 built: staff mint a licence for one app with its device count, and it
lives in that app's mirror, invite and quota (CLOUD_ARCHITECTURE_GCP §20.17).
Licences minted without an app, every one before the split, keep the old
model. Step 5 built: upgrade by issue, applied on Activate (CLOUD_ARCHITECTURE_GCP §20.18), and QC
test accounts on the activation model. The split's migration is built
(`backend/scripts/split_licences_per_app.py`, CLOUD_ARCHITECTURE_GCP §20.19);
the owner runs it. Every later step is listed under Action items.
**Date:** 2026-10-29
**Deciders:** product owner
**Supersedes in part:** [ADR-007](ADR-007-licence-lifecycle.md) (one licence per person becomes one per person per app; conversion becomes issue then activate),
[ADR-010](ADR-010-device-binding-per-app.md) (one bound phone per app becomes N activated phones per licence; the self-service cooldown goes),
[ADR-014](ADR-014-session-app-tag.md) decision 4 (the analysis quota becomes per app)
**Amends:** [ADR-027](ADR-027-test-accounts.md) (QC test accounts run the activation model; CI accounts keep theirs)
**Keeps:** [ADR-024](ADR-024-account-anchored-entitlements.md) (nothing is typed or sold in the app), [ADR-023](ADR-023-signed-offline-entitlement.md), [ADR-030](ADR-030-semper-classroom-app-identity.md) (new per-app fields go through `apps.written_fields` / `read_field` while TD-330 is open)

## Context

A licence attaches to the account on the server (ADR-024) and binds itself to a phone
with no step by the user:

- `revalidate_device_lock` runs on every authenticated request. On an empty lock, the
  first registered phone to ask takes it (`backend/app/repo/devlock.py`, the `UNBOUND`
  branch).
- One licence covers both apps. The licence has no `app`; the user holds one
  `licenseId` and one set of mirrored terms; each app has its own `deviceIdLock*`
  field (ADR-010).
- The holder can move their licence to another phone themselves, at most once every
  `SELF_DEVICE_CHANGE_COOLDOWN_DAYS` (30) (`repo/seats.clear_device_lock`, self path).
- Moving from individual to institution converts in place and seats the holder
  without asking (`repo/upgrade.convert_to_institution`).

The product owner's decisions (2026-10-29):

1. **Binding is explicit.** A phone is licensed only after the user taps **Activate**
   on it. The licence still reaches the account by mint, roster add or web claim
   code, and nothing is typed in the app.
2. **A device count replaces the cooldown.** Each licence has `maxDevices` (default 1).
   - Every activated phone counts, whoever activated it.
   - Any other phone of the holder runs as Demo.
   - For an institution licence the count is a pool across the whole licence.
3. **A licence belongs to one app.** Existing licences are split into a Semper licence
   and a Semper Classroom licence with the same terms, each keeping its phone. The
   analysis quota counts per app.
4. **Upgrade is a new licence.** Staff issue it; the old licence works until the
   holder taps Activate on the new one, which revokes the old one in the same
   transaction.
5. **Who may deactivate follows the role.**
   - The holder: their own phones on their own licence.
   - IT: phones of members in their domain, on licences they administer.
   - Staff: anyone; staff may also add an activation.
6. **Licence changes keep the phone's files.** Demo → licensed unlocks at once, and
   nothing local is deleted on any change. A holder's activated phones sync their
   analyses through the existing backup and restore flow.

## Decision

### Data

- **Licence:** `licenses/{id}` gains `app` (`apps.stored_name`), `maxDevices`, a
  transactional `devicesActive` counter and `supersedes`.
  - A licence **without** `app` keeps today's lock code unchanged. The licence decides
    its model, so the backend ships before the migration.
  - Demo keys stay app-less and are not split.
- **Activations:** one document per activated phone,
  `licenses/{id}/activations/{deviceId}` with `{uid, app, status, activatedAt/By,
  deactivatedAt/By, model}`.
  - A deactivated phone is marked, not deleted, so it keeps its history.
  - Revalidation reads the licence, the activation and (institution) the seat in one
    batched get.
- **Per-app user mirror:** follows ADR-010's flat suffixed fields, not a map.
  - Semper's mirror is the unsuffixed fields every account has. Another app's is the
    same names suffixed (`apps.LICENCE_MIRROR_FIELDS`).
  - Readers go through `apps.licence_view(user, app)`; writers through
    `apps.licence_patch(patch, app)`.
  - An account with no `licenseApp` is from before the split, and its one licence
    holds for both apps until the migration stamps it.
- **Pending upgrade:** `pendingLicenseId[app]` on the user names an issued upgrade
  until it is activated or revoked.

### Behaviour

- **Activate:** `POST /v1/me/devices/activate`, signed by the phone, app from
  `X-App-Id`. One transaction checks:
  - the phone is registered to the account for the app;
  - the licence is live and the seat active;
  - the licence is not floating;
  - `devicesActive < maxDevices`, else `409 device_limit_reached`.

  The answer carries the fresh `/v1/config`, so locks lift without a restart.
- **Deactivate:** by role, each scope enforced in the repository, not only by route.
  - The holder: their own activations, from the app or the web account page with a
    second factor.
  - IT: `adminEmails` plus `domainLock`.
  - Staff: any; staff also add.

  An IT or staff deactivation also releases the phone's registration (with the 24 h
  hold); a holder's does not. The roster's **New device** deactivates with its
  caller's scope too (`deactivate_holder(caller=…)`, 2026-11-01).
- **Suspension keeps the activations** (owner decision, 2026-11-01). Suspending an
  account revokes its registrations, not its activations, so a reinstated holder's
  phone is licensed again once it registers, and the slots stay counted meanwhile.
  IT or staff deactivate the phones to free them (CLOUD_ARCHITECTURE_GCP §20.16).
- **Delete and restore** (`repo/deletion.py`): a restore keeps the phones of the
  holders it puts back and deactivates the rest, then counts `devicesActive` again.
- **No cooldown.** The count is the limit.
- **Several phones per account per app may register.** Real accounts, as ADR-027
  already allows test accounts. Up to `MAX_PHONES_PER_APP`; the oldest phone that is
  not activated makes room.
- **Floating:** the lease is the activation. `maxSeats` is the pool, and only the phone
  that checked the lease out is licensed.
- **Upgrade:** `issue_upgrade` mints the new licence with `supersedes`.
  `activate(upgrade=True)` revokes the old licence, moves the app's mirror, seats the
  holder (institution), activates the phone and clears the pointer, all in one
  transaction.
- **Quota per app:** `count_user_sessions(uid, app)`; the Demo per-device cap stays.
- **Compatibility:** installed builds (Semper 1036, Classroom v1.4-beta.2) never tap
  Activate.
  - New builds send `X-Activation: explicit`.
  - While `IMPLICIT_ACTIVATION=legacy`, a request without it auto-activates the
    holder's first registered phone on a licence with an `app`, if a slot is free.
  - `off` ends that once adoption allows.

## Options considered

- **Keep one licence for both apps with a per-app device count:** rejected. The owner
  wants a licence, its terms and its count per app.
- **A per-app map on the user (`licences.{app}`):** rejected for the reasons ADR-010
  gave. Every single-field update, `DELETE_FIELD`, query and the test double would
  change shape, and it would sit outside the TD-330 helpers.
- **Mirror activated device ids onto the user (no extra read):** rejected. IT and pool
  deactivations would write members' user documents, and the licence read is needed
  for revoke anyway.
- **A version threshold instead of a capability header:** rejected. Debug, emulator
  and CI builds report versionCode 1, and the installed builds send no version.
- **Count phone changes, not active phones:** rejected by the owner in favour of a
  count of active phones, which reads like the vendors' activation limits.

## Trade-offs

- **Licence passing:** without a cooldown, a licence can be passed round a lab by
  deactivating one phone and activating another. The device count bounds it at N
  concurrent phones. Every activation and deactivation is audited, and churn is
  visible in phone history and the `event=activation` log.
- **Offline phones:** a phone deactivated while offline keeps its last config until it
  next reaches the backend, or its token's grace ends once TD-215 provisions signing.
- **Doubled Demo allowance:** a Demo account gets the Demo analysis allowance per app,
  so up to twice the stored analyses across both apps. The per-device cap still holds
  for each app.
- **User documents grow:** about 14 mirror fields per extra app, three names each while
  TD-330 is open.

## Consequences

- **Backend:** `apps.licence_view` / `licence_patch` / `LICENCE_MIRROR_FIELDS`;
  `user_config` takes `app`; `repo/activations.py` (the check, Activate, the
  lists), `repo/deactivations.py` (role-scoped deactivation),
  `routers/activations.py`, the gateway and the contract snapshot;
  `repo/licence_upgrade.py`; `backend/scripts/split_licences_per_app.py`, the
  migration. Still to come: an index override on `activations.uid` once a
  query crosses licences (every one so far stays inside one licence's
  subcollection).
- **App:** an Activate step, a phones screen with Deactivate, instant unlock, a sync
  toggle. Semper Classroom gets it by fork sync.
- **Consoles:** mint per app with `maxDevices`, per-phone Deactivate, staff Add phone,
  Issue upgrade, the institution pool, per-app account sections; the cooldown text
  goes.

## Action items

- [x] Step 1: each app reads its own licence (`apps.licence_view`), `user_config`
      takes `app`; no change in behaviour (`backend/tests/test_licence_view.py`).
- [x] Step 2: several phones per account per app (`MAX_PHONES_PER_APP`, default 5;
      the oldest phone not holding the licence makes room); any ACTIVE phone of the
      account signs (`deps._device_is_bound`); a clear signs out only a registered
      phone it released, and another phone registering no longer ends its hold.
- [x] Before the step-2 backend serves: `backend/scripts/retire_stale_devices.py`
      dry run, then `--apply` (owner), so no ACTIVE record an older path left
      behind can sign. Run 2026-10-30, just after the deploy: 0 to retire.
- [x] Step 3: the activation model (`repo/activations.py`), Activate and the
      phones routes for holder, IT and staff (`routers/activations.py`),
      role-scoped deactivation (`repo/deactivations.py`), `/v1/config`'s
      `activation` and `/v1/me`'s `licences`, `X-Activation` and
      `IMPLICIT_ACTIVATION`, the cooldown removed, the floating lease's phone
      mirrored (CLOUD_ARCHITECTURE_GCP §20.16).
- [x] Before the step-3 backend serves: drop `SELF_DEVICE_CHANGE_COOLDOWN_DAYS`
      from the service (`--remove-env-vars`, owner); it is no longer read. Gone from
      both services on 2026-10-30.
- [x] Step 4: a licence per app: one per person per app (`holders.licence_held_by`),
      per-app invites (`licenses.invite_id`), mint `app` and `maxDevices`, the staff
      edit of `maxDevices`, each mirror writer and guard by the licence's slot
      (`apps.licence_slot`, `apps.slot_holding`), the per-app quota
      (`sessions.count_user_sessions`), and a seat revoke or account erasure giving
      the phones back (`backend/tests/test_licence_per_app.py`).
- [x] Step 5: issue and apply upgrade (`repo/licence_upgrade.py`,
      `POST /v1/admin/licenses/{id}/upgrade` and `…/apply-upgrade`, Activate with
      `{"upgrade": true}`, `pendingLicence` on `/v1/config` and `/v1/me`), and QC
      test accounts on the activation model, one licence per app
      (`backend/tests/test_licence_upgrade.py`, `test_test_accounts.py`). `/convert`
      stays for licences from before the split.
- [x] The split's migration: `backend/scripts/split_licences_per_app.py`, with
      `--only-domain` for a QC rehearsal (FIRESTORE_SCHEMA_RUNBOOK,
      `backend/tests/test_script_split_licences_per_app.py`, the emulator tier).
- [x] Run the split (owner): dry run, the QC rehearsal, then `--apply`, once the
      step-5 backend serves all traffic. Applied 2026-10-30 (CHANGELOG).
- [x] App (Semper): `X-Activation: explicit` (`AppIdHeader`), Activate / list /
      Deactivate (`PhonesApi`, `data/account/phones/DeviceActivation.kt`), the
      Your phones screen (`ui/phones/`), Home's card, Settings' row, the limit
      screen's Activate, and the config's `activation` and `pendingLicence` kept
      by `AppRemoteConfig`. Activate stores the config it answers, so the
      licence's features open at once; no licence change touches a local analysis.
- [x] App: sync across the holder's phones: Settings' **Download from my other
      phones** (default on) and `data/cloud/sync/PhoneSyncWorker` (every 6 hours,
      unmetered, battery not low), which restores the account's backups this
      phone lacks through `RestoreStart`. Its leftovers are TD-364.
- [x] Consoles: the account page's per-app cards (own phones, Deactivate, a
      waiting licence), the institution page's pool and Deactivate, and the
      operator desk's Issue per app with a phone count, Phones (Deactivate, Add
      phone), Issue upgrade / Apply now, Edit's count and QC test accounts'
      licence fields.
- [x] Fork sync: material_testing#159 (2026-10-31) merged `12a89d31`; Semper
      Classroom reaches Activate through the shared screens and gates, and its
      own FORK_SYNC.md has the section. Its release is still owed.
- [ ] Set `IMPLICIT_ACTIVATION=off` once adoption allows, then remove the legacy
      paths (TD-363).
