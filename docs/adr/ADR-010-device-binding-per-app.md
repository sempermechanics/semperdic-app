# ADR-010: Device binding per app — one phone per app, not one phone per account

**Status:** Accepted. Phase 0 (a lock mismatch is no longer stored, #279)
and phases 1–3 (one phone per app) are built; the backend is deployed
(2026-09-28), with the consoles. Material Testing builds from
material_testing `main` send the header (material_testing#90).
**Date:** 2026-09-28
**Deciders:** product owner, backend owner

## Context

Material Testing became its own Android app on 2026-09-28
(`com.indicvision.semper.materialtesting`, material_testing ADR-009) and
shares this backend and Firebase project. Android scopes `ANDROID_ID` to the
signing key, so Semper and Material Testing on the same phone report two
different device ids (`and-{ANDROID_ID}`, `DeviceKeyManager`).

The backend bound an account to **one** device: `users.activeDeviceId`
(`repo/devices.py`), the licence or seat `deviceIdLock`, and `_may_bind`
(`repo/devlock.py`). A second app on a licensed phone was therefore a
second device:

1. Signing in to Material Testing was refused at `POST /v1/devices/register`
   (`device_conflict`, which the app shows as already linked).
2. Worse, every request it sent went through `revalidate_device_lock`, which
   stored `mode: demo` on the account on a lock mismatch. Revalidation
   returns early for an account already on Demo, so the demotion stuck: the
   licensed phone was Demo too, while the console still showed the licence
   as live. That is what happened to the first account to try it
   (2026-09-28).

## Decision

**Phase 0 (#279).** A lock mismatch is Demo for the mismatched device's
requests only; nothing is written. A revoked licence or a revoked/disabled
seat still stores Demo, since that is the account's state and not one
device's. `check_device_lock` refuses both (`_LOCK_REFUSED`). The clear's mode
re-stamp (`_settle_holder`) stays, for accounts demoted before this change.

**Phases 1–3.** Bind one device **per app**:

1. **App.** Every backend call carries `X-App-Id`, the build's
   `applicationId` (`data/net/AppIdHeader.kt`, scoped to the API host like
   the App Check header). A request without it is Semper, so every build in
   the field keeps working. An id the backend does not list is refused with
   `400 unknown_app` rather than read as Semper (`backend/app/apps.py`).
2. **Backend.** Each app has its own registered device, release hold,
   licence or seat lock, and self-service cooldown. Semper's are the fields
   accounts already had (`activeDeviceId`, `releasedDeviceId`, `releasedAt`,
   `deviceIdLock`, `deviceChangedAt`); another app's are the same names with
   a suffix (`activeDeviceIdMaterialTesting`, ...), named by `apps.field`.
   `deps._authenticate` resolves the app once and `revalidate_device_lock`,
   registration, activation and the self-service unbind act on that app's
   fields. A staff or IT clear, and a staff phone release, move every app:
   the holder changed phones, and both apps are on it. The holder's own
   clear moves the asking app only, against that app's cooldown.
3. **Consoles.** Seats and the pending-users list show each app's device
   (`util.seatDevices`); the operator's release and device history name the
   Material Testing half; the account page has **Use Material Testing on a
   different device**, which names the app as `?app=materialtesting`
   because a browser cannot send `X-App-Id` (CORS allows `Authorization` and
   `Content-Type` only).

Flat suffixed fields rather than the `activeDevices.{app}` maps first
proposed: every existing single-field update, `DELETE_FIELD`, query and
transaction reads them unchanged, the test double needs no dotted-path
support, and nothing is migrated.

## Options

| Option | Why not |
|--------|---------|
| Keep one device per account | Semper and Material Testing cannot both be used on one phone by one person, which is the normal case |
| Share one device id across both apps (same signing key, or an id both apps derive) | Undoes material_testing ADR-009's separate signing key, and a shared id is spoofable across apps |
| Trust the App Check `app_id` claim instead of a header | App Check is off by default (`APP_CHECK_MODE`) and not registered for Material Testing; the header works now and can be checked against the claim once it is |
| Raise the device limit to two, any apps | Lets one licence run on two phones of the same app |
| Per-app maps (`activeDevices.{app}`) | Every read and write of the five fields changes shape, with a fallback for existing accounts; the suffix keeps Semper's fields as they are |

## Trade-offs

- An account can be licensed on two phones at once, if each runs a different
  app. Accepted: the apps do different work, and each still has one phone.
- `X-App-Id` is a claim until App Check enforces it. A modified Semper build
  can call itself Material Testing and take that slot, which gives it no more
  than the one extra phone above.
- A third app means a new entry in `apps.py` and nothing else in the
  backend; the consoles name Material Testing explicitly and would need the
  same.

## Consequences

- The backend deploys first; a Semper build without the header keeps
  Semper's binding. Material Testing signs in beside Semper once a build
  with `AppIdHeader` is installed.
- Accounts demoted before phase 0 stay Demo until an operator re-stamps
  `mode`/`plan` or clears the device.
- Devices registered from now on carry `app` on `devices/{id}`.

## Action items

- [x] Phase 0: request-scoped mismatch (#279); TD-137.
- [x] Phase 1: `X-App-Id` from the app (`AppIdHeader`); TD-138.
- [x] Phase 2: per-app fields and cooldown (`apps.py`, `repo/devlock.py`,
      `repo/devices.py`, `repo/seats.py`, `repo/users.py`); TD-138.
- [x] Phase 3: consoles and docs; TD-138.
- [x] Deploy the backend (2026-09-28, `deploy-backend.yml` run 36419849032).
- [x] Deploy the consoles (2026-09-28, from `0c3946f7`).
- [x] Merge this repository's `main` into material_testing so its builds send
      the header (material_testing#90, merged 2026-09-28; a debug build signed
      in licensed on a Pixel 6).
- [ ] Register Material Testing for App Check (Play Integrity) and check the
      header against the token's `app_id`.
