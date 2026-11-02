# Architecture decision records

One file per decision that is expensive to rediscover and easy to undo by
accident. Format: Status / Context / Decision / Options / Trade-offs /
Consequences / Action items. A superseded record stays; its status names the
record that replaced it.

| ADR | Title | Status | Register |
|-----|-------|--------|----------|
| [001](ADR-001-firestore-repo-package.md) | Split `firestore_repo.py` into a package behind a facade | Accepted, built; facade superseded by 021 | TD-53, TD-58 |
| [002](ADR-002-cloudapi-seam.md) | `CloudApi` interface seam for `SemperApi` | Accepted, built | TD-25 |
| [003](ADR-003-viewerargs-read-side.md) | `ViewerArgs.from` read side with a `SessionRecord` fallback | Accepted, built | TD-3, TD-61 |
| [004](ADR-004-runspec.md) | Immutable `RunSpec` built once at Compute | Accepted, built | FI-6, TD-61 |
| [005](ADR-005-wizard-process-death.md) | The wizard survives process death through a draft | Accepted, built | TD-26 |
| [006](ADR-006-gateway-deploy-job.md) | CI deploys the API Gateway after the Cloud Run promote | Accepted, built (not yet dispatched) | TD-27 |
| [007](ADR-007-licence-lifecycle.md) | Licence lifecycle: one per person, replace by revoke, delete into a 30-day hold | Accepted, built (TTL policies owed); superseded in part by 024 (no key typed in the app) | — |
| [008](ADR-008-startup-gates-phone-state.md) | Real-device startup gates check the phone's state; a trip is settled A/B | Accepted, built | TD-135 (material_testing), TD-155 |
| [010](ADR-010-device-binding-per-app.md) | Device binding per app: one phone per app, not per account | Accepted, built; superseded in part by 033 (several phones per app) | TD-137, TD-138 |
| [011](ADR-011-viewer-deformed-frame.md) | The viewer draws each frame on its own photo, at the displaced positions | Accepted, built (ported from material_testing 2026-10-01); exports follow 2026-10-13 | TD-139 (closed) |
| [013](ADR-013-session-metadata-replace.md) | A backed-up session's metadata.json can be replaced | Accepted, built, deployed 2026-10-01 | material_testing TD-150 |
| [014](ADR-014-session-app-tag.md) | Cloud sessions are tagged with the app that backed them up | Accepted, built, deployed 2026-10-01; cross-app reconcile 2026-10-13 | TD-153 (closed) |
| [015](ADR-015-package-layout.md) | Feature subpackages of about 15 files and files of about 500 lines; workers, JNI classes and Activities keep their names | Accepted, built; amended 2026-10-03 | — |
| [016](ADR-016-work-that-outlives-the-activity.md) | Work that must outlive the Activity: ViewModel, an app-lifetime run, `NonCancellable` cleanup, or WorkManager | Accepted, built; amended 2026-10-19 (a lint or detekt check for rule 1 proposed) | — |
| [017](ADR-017-viewbinding-and-ui-kit.md) | ViewBinding for every screen, and one `ui/common` helper per UI job | Accepted, built | — |
| [018](ADR-018-error-convention.md) | One typed outcome per failure domain; cancellation is never a failure | Accepted, built | TD-41, TD-171 |
| [019](ADR-019-sempermechanics-app-id.md) | The app is `com.sempermechanics.semper`; "indic" leaves the code, and the engine submodule is `engine/`; amended 2026-10-28: the user-facing name is lower-case "semper" | Accepted, released 2026-10-17; amended 2026-10-28 (Admin) | TD-176 |
| [020](ADR-020-backend-refusal-model.md) | Backend refusals are one exception (`errors.Refusal`) with one status table (`errors.STATUS`) | Accepted, built | TD-185, TD-186 |
| [021](ADR-021-retire-firestore-repo-facade.md) | Retire the `firestore_repo` facade; `app.repo` is a plain package and tests patch through `repo_view.patch` | Accepted, built | TD-190 |
| [022](ADR-022-rerun-as-copy.md) | A finished analysis is never overwritten; Home's Re-run copies its inputs into a new analysis | Accepted, built | TD-211 |
| [023](ADR-023-signed-offline-entitlement.md) | A signed, time-limited entitlement (ES256 JWS, pinned by `kid`) the app verifies offline, with a bounded grace and clock-rollback detection; never a lock-out | Accepted, built; key not yet provisioned | TD-215, TD-219, TD-234, TD-235, TD-236, TD-238, TD-261 (TD-263–TD-266 closed 2026-10-18) |
| [024](ADR-024-account-anchored-entitlements.md) | Paid entitlements are anchored to the account and never entered in the app; the backend checks every cloud request; the app is consumption-only | Accepted, merged 2026-10-19 (#448, #449), deployed 2026-10-22; supersedes in part 007 and TD-259 | TD-274–TD-278, FI-24 (TD-259 and TD-272 closed 2026-10-18) |
| [025](ADR-025-test-type-extension-points.md) | Test types plug into Semper through seven no-op extension points (`ext.AppExtensions`); Material Testing becomes a `materialTesting` product flavour, and the fork's backend, console, rules, contracts and ops copies are deleted | Accepted 2026-10-22 | TD-298 (carrying it out); material_testing TD-269, TD-270, TD-276 (the drift, closed there by its #139) |
| [026](ADR-026-licence-fanout-marker.md) | A licence-wide fan-out (revoke, terms edit) is recorded on the licence in its own write and finished by a delayed Cloud Task scheduled before it | Accepted, merged 2026-10-20 (#457), deployed 2026-10-22 | TD-294 |
| [027](ADR-027-test-accounts.md) | Staff test accounts for QC and CI: a tagged, pre-approved, licensed login on a company domain, several phones per app, its own Drive tree, out of the demo counts, erased at its end | Accepted, deployed 2026-10-22 (#460); amended 2026-10-22: CI accounts rotate, and staff release one phone, which may not come back | — (TD-314 closed by the per-phone release) |
| [028](ADR-028-tamper-evident-records.md) | A signed sidecar manifest (`record_manifest.json`: run spec, app and engine pin, SHA-256 per `.dat`, signed with the device key) makes a measurement record tamper-evident without changing a `.dat` byte; it travels in `Session.zip` under `dat/` | Accepted, merged 2026-10-22 (#471), in no release yet | TD-247 (closed by it) |
| [029](ADR-029-api-contract-snapshot.md) | The backend's `app.openapi()` is committed as `backend/contracts/app-openapi.json` and diffed by a test on every backend run; a change is rewritten on purpose and reviewed for breakage | Accepted 2026-10-24, merged 2026-10-26 (#489) | — |
| [030](ADR-030-semper-classroom-app-identity.md) | Material Testing becomes Semper Classroom: app id `com.sempermechanics.semperclassroom`, short name `semperclassroom`, field suffix `SemperClassroom`; the old names are read and mirrored until TD-330, and the old id is retired | Accepted, in progress | TD-330 |
| [033](ADR-033-licence-per-app-explicit-activation.md) | A licence per app; a phone is licensed only after Activate; a per-licence device count (an institution's is a pool) replaces the self-service cooldown; deactivation scoped by role; upgrade issues a new licence the holder activates | Accepted 2026-10-29, in progress (steps 1–3 built) | TD-363 |
| [034](ADR-034-qc-framework.md) | QC is a catalogue of data and lanes of config: scenarios as TOML tables with permanent ids under `qa/`, lanes in `qa/lanes.toml`, and `scripts/qa/qa.py` to check, plan and report; `check` runs in CI | Accepted, built 2026-10-29 | — |
| [035](ADR-035-recycle-bin.md) | A deleted cloud backup goes to a 30-day recycle bin, a tombstone in its own collection (`deleted_sessions`); a binned analysis cannot be uploaded again, a daily job purges it, and an erased marker of ids stays 30 days | Accepted 2026-11-01, in progress (backend built) | TD-384 |

Register IDs refer to [../ops/TECH_DEBT.md](../ops/TECH_DEBT.md); one no longer listed there is closed, and its history is in git. ADR-008, ADR-009,
ADR-011 (ported here), ADR-012 and ADR-013 are material_testing's (ADR-008's benchmark harness and ADR-013's backend route live here too: the harness is shared code, and the backend deploys from this repo); the numbers are shared so they do not collide. One did: material_testing's ADR-022 (Semper's UI plus two test choices, 2026-10-14) and this repo's ADR-022 (re-run as a copy, 2026-10-15) share a number; ADR-025 names the fork's by its file name. 031 and 032 are taken by material_testing.
