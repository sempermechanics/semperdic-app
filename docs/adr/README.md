# Architecture decision records

One file per decision that is expensive to rediscover and easy to undo by
accident. Format: Status / Context / Decision / Options / Trade-offs /
Consequences / Action items. A superseded record stays; its status names the
record that replaced it.

| ADR | Title | Status | Register |
|-----|-------|--------|----------|
| [001](ADR-001-firestore-repo-package.md) | Split `firestore_repo.py` into a package behind a facade | Accepted, built | TD-53, TD-58 |
| [002](ADR-002-cloudapi-seam.md) | `CloudApi` interface seam for `IndicApi` | Accepted, built | TD-25 |
| [003](ADR-003-viewerargs-read-side.md) | `ViewerArgs.from` read side with a `SessionRecord` fallback | Accepted, built | TD-3, TD-61 |
| [004](ADR-004-runspec.md) | Immutable `RunSpec` built once at Compute | Accepted, built | FI-6, TD-61 |
| [005](ADR-005-wizard-process-death.md) | The wizard survives process death through a draft | Accepted, built | TD-26 |
| [006](ADR-006-gateway-deploy-job.md) | CI deploys the API Gateway after the Cloud Run promote | Accepted, built (not yet dispatched) | TD-27 |
| [007](ADR-007-licence-lifecycle.md) | Licence lifecycle: one per person, replace by revoke, delete into a 30-day hold | Accepted, built (TTL policies owed) | — |
| [010](ADR-010-device-binding-per-app.md) | Device binding per app: one phone per app, not per account | Accepted, built | TD-137, TD-138 |

Register IDs refer to [../ops/TECH_DEBT.md](../ops/TECH_DEBT.md). ADR-008, ADR-009,
ADR-011 and ADR-012 are material_testing's; the numbers are shared so they do not collide.
