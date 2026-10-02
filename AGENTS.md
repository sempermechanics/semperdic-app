# Semper — agents

Read in this order:

1. [CONTEXT.md](CONTEXT.md) — product, architecture, invariants, current state.
2. **This file** — how to find the code behind a behaviour, and what must not
   break while you change it.
3. [CLAUDE.md](CLAUDE.md) — commands and Claude-specific guardrails.
   Human contributor commands: [CONTRIBUTING.md](CONTRIBUTING.md).
   Doc index: [docs/README.md](docs/README.md).

## Find the code behind a behaviour

[docs/WORKFLOWS.md](docs/WORKFLOWS.md) is the map: every app screen (`A0`–`A9`),
every background/data flow (`B1`–`B15`), every backend request (`C1`–`C20`) and
the ops workflows (`D1`–`D7`) — each with its entry point, file chain, what it
writes, where its failure surfaces and which test pins it. Quote the ids
(`A5.4`, `B1`, `C7`) in commits and PRs.

- Symptom → first file: [§E1](docs/WORKFLOWS.md#e1-symptom--first-file).
- Places where tracing is genuinely hard, and why:
  [§E2](docs/WORKFLOWS.md#e2-where-backtracking-is-genuinely-hard).
- What we intend to do about them: [docs/ops/FUTURE_IMPROVEMENTS.md](docs/ops/FUTURE_IMPROVEMENTS.md).
- Walking the UI by hand: [docs/app/WORKFLOWS.md](docs/app/WORKFLOWS.md).
- Which file to edit for a screen: [docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md).

Four shortcuts that answer most questions:

| Question | Answer |
|---|---|
| Where did this on-screen text come from? | `app/src/main/res/values/strings.xml`, then `rg "R.string.<name>"` — strings are never hardcoded |
| Why did this cloud call fail? | The reason carries `(ref: <id>)` — the backend's `X-Request-Id`. Grep the Cloud Run log for that `requestId` |
| What does this backend `detail` mean? | `backend/app/errors.py`; the client's branch is `data/net/ApiErrors.kt` (the two are pinned by `backend/tests/test_error_codes.py`) |
| Which files does workflow *X* touch? | The row for its id in [docs/WORKFLOWS.md](docs/WORKFLOWS.md) |

## What must not break

The product promises accurate numbers and careful handling of the data behind
them. These are the rules that carry those promises; CONTEXT.md has the full
invariant list, and none of them are drive-by changes.

**Accuracy**

- `.dat` packing, the ZNSSD threshold and the DatCodec / GIF oracles are
  bit-exact. Do not "improve" a number without an engine contract bump.
- The JNI output buffer is sized to the ROI grid and the engine's returned point
  count is checked against that capacity *before* read-back.
  `computeFullFieldDirect` stays inside the one batch loop.
- Fused hot loops (`VisualizationEngine` pixel loops, `GifEncoder` LZW, the
  `ReportBuilder` fusion pass, `DicResult.decodeDatFile`, `prefetchAround`,
  `PointSpatialIndex.build`) keep their body whole in one function; their files
  may be split. `DicUploadWorker.doWork` may become named steps as long as the
  staged bytes stay identical across attempts.
- A failure reason must be the *real* reason: never widen a specific engine code
  into a generic message, and never report an unknown code as a known cause.

**Privacy and security** 🔒

- One consent flag (`DicSettings.diagnosticsEnabled`) gates **both** Crashlytics
  and product analytics, and it is off until accepted. Analytics params stay
  PII-free — enums and coarse buckets, never images, results, session ids,
  specimen names or paths (`B10`).
- Logs and crash breadcrumbs follow the same rule. The request id you may log is
  opaque by design; an email, uid or specimen name is not.
- Erasure order is Drive **then** Firestore, and the local wipe happens only
  after the backend confirms (`B9`, `C11`, `C13`).
- Drive `UNKNOWN` ≠ deleted: never drop local metadata on an indeterminate probe
  (`B7`, `C8`).
- Storage reclaim frees local frames of **backed-up** analyses only (`B5`).
- Mutating routes are device-attested (`C4`); admin mutations need attestation on
  top of the admin role (`C14`). Do not add a route that mints or accepts Drive
  capability URIs without it.
- Never commit `local.properties`, keystores or service-account keys; never
  hand-edit `firebase-hosting/public/{privacy,terms}/` (generated from
  `docs/legal/`).

## Working rules

- Lint and detekt baselines are **empty**. Extract or `@file:Suppress` a specific
  finding; do not stuff a baseline. Detekt's line limit is 120 chars.
- New Kotlin code follows four decisions: where a file goes and how big it gets
  ([ADR-015](docs/adr/ADR-015-package-layout.md)), where work that must outlive a
  screen runs ([ADR-016](docs/adr/ADR-016-work-that-outlives-the-activity.md)),
  ViewBinding and the `ui/common` kit ([ADR-017](docs/adr/ADR-017-viewbinding-and-ui-kit.md)),
  and typed failure outcomes that never swallow cancellation
  ([ADR-018](docs/adr/ADR-018-error-convention.md)).
- Gate before pushing: `./gradlew ciReleaseGate` (spotless, detekt, lintDebug,
  unit tests, R8, assembleRelease). Backend:
  `cd backend && python -m pytest tests/ -q --cov=app --cov-fail-under=75`.
  Full command table: [CLAUDE.md](CLAUDE.md).
- Without an Android SDK, run the full matrix in CI instead: `workflow_dispatch`
  on the **CI** workflow with `full_ci: true`, which ignores path filters and
  runs every tier on your branch.
- Change a workflow → update its row in [docs/WORKFLOWS.md](docs/WORKFLOWS.md) in
  the same PR, then the **Current state** section of
  [CONTEXT.md](CONTEXT.md) if the picture changed. `python scripts/check_doc_paths.py`
  fails when a doc points at a file that no longer exists; it runs in CI too.
- PRs target `main`. No force-push to `main`, no `--no-verify`.
