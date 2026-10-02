# Semper documentation

Start with the [project README](../README.md) for what Semper is and how to
build it. This page routes you to the rest.

## Find your way by what you're doing

| I want to… | Read |
|---|---|
| Understand DIC itself before touching anything | [DIC in five minutes](#dic-in-five-minutes) below |
| Change the Android UI / understand app flow | [app/ARCHITECTURE.md](app/ARCHITECTURE.md) |
| Find the files behind any flow — app, background or backend | [WORKFLOWS.md](WORKFLOWS.md) |
| Backtrack a symptom to its code | [WORKFLOWS.md §E](WORKFLOWS.md#e-backtracking) |
| Run a manual test pass over every screen | [app/WORKFLOWS.md](app/WORKFLOWS.md) |
| See which errors open public Troubleshooting | [app/FAQ_LINKS.md](app/FAQ_LINKS.md) · canonical text [app/FAQ.md](app/FAQ.md) |
| Operate the app to analyse a DIC image set | [OPERATING_MANUAL.md](OPERATING_MANUAL.md) |
| Understand lighting / measurement-floor accuracy | [app/NOISE_FLOOR_STRAIN_ACCURACY.md](app/NOISE_FLOOR_STRAIN_ACCURACY.md) · short FAQ [app/FAQ.md — lighting-and-accuracy](app/FAQ.md#lighting-and-accuracy) |
| Change the correlation engine (C++) | [engine/ARCHITECTURE.md](engine/ARCHITECTURE.md) — the engine is a submodule, so this points into `native/docs/` |
| Know what the app may assume of the engine | [engine/ENGINE_APP_CONTRACT.md](engine/ENGINE_APP_CONTRACT.md) |
| Check the math, or write it up | [engine/MATHEMATICS.md](engine/MATHEMATICS.md) |
| Add or run app tests | [app/TESTING.md](app/TESTING.md) |
| Benchmark the app, or check a perf claim | [app/TESTING.md — Performance benchmarks](app/TESTING.md#performance-benchmarks), then [perf/on-device-characterization.md](perf/on-device-characterization.md), [perf/round2-main-vs-branch.md](perf/round2-main-vs-branch.md), [perf/backup-restore-split.md](perf/backup-restore-split.md), [perf/request-volume.md](perf/request-volume.md), [perf/backend-cost.md](perf/backend-cost.md), [perf/startup.md](perf/startup.md), [perf/engine-viewer-check-2026-09.md](perf/engine-viewer-check-2026-09.md) |
| Add or run engine tests | [engine/TESTING.md](engine/TESTING.md) |
| Build, test or bump the engine pin | [../CONTRIBUTING.md](../CONTRIBUTING.md) |
| Know why CI is red / how tiers work | [ops/CI.md](ops/CI.md) |
| Cut a release | [ops/RELEASING.md](ops/RELEASING.md) |
| Sync material_testing, or port a fix from it | [ops/FORK_SYNC.md](ops/FORK_SYNC.md) |
| Map GitHub Environments / secrets / main hygiene | [ops/ENVIRONMENTS.md](ops/ENVIRONMENTS.md) |
| Production launch checklist | [ops/PRODUCTION_READINESS_GATE.md](ops/PRODUCTION_READINESS_GATE.md) |
| Tech-debt status / deferred gates | [ops/TECH_DEBT.md](ops/TECH_DEBT.md) |
| Know why a structural choice was made | [adr/](adr/README.md) — architecture decision records |
| See what changed and when (history moved out of CONTEXT.md) | [ops/CHANGELOG.md](ops/CHANGELOG.md) |
| Pick up a proposed improvement | [ops/FUTURE_IMPROVEMENTS.md](ops/FUTURE_IMPROVEMENTS.md) |
| Compare code-quality metrics with the 2026-10 baseline | [ops/QUALITY_BASELINE_2026-10-01.md](ops/QUALITY_BASELINE_2026-10-01.md) |
| What the 2026-10 quality program fixed, split and deferred | [ops/QUALITY_PROGRAM_RESULTS.md](ops/QUALITY_PROGRAM_RESULTS.md) |
| Work on the cloud backend | [backend/CLOUD_ARCHITECTURE_GCP.md](backend/CLOUD_ARCHITECTURE_GCP.md) |
| Deploy the backend myself | [backend/BACKEND_SETUP_GCP.md](backend/BACKEND_SETUP_GCP.md) (CLI) or [BACKEND_SETUP_CONSOLE.md](backend/BACKEND_SETUP_CONSOLE.md) (browser) |
| Fix sign-in / set up auth | [backend/AUTH_SETUP.md](backend/AUTH_SETUP.md) |
| Understand licensing, seats and entitlements | [backend/CLOUD_ARCHITECTURE_GCP.md §20](backend/CLOUD_ARCHITECTURE_GCP.md#20-licensing--entitlements) (design) · [OPERATING_MANUAL.md Appendix D](OPERATING_MANUAL.md) (mint / revoke / seat support) |
| Use or deploy the web consoles | [backend/CLOUD_ARCHITECTURE_GCP.md §20.8](backend/CLOUD_ARCHITECTURE_GCP.md#208-the-consoles-and-what-a-browser-may-do) · [firebase-hosting/public/console/README.md](../firebase-hosting/public/console/README.md) |
| Change the Firestore schema | [backend/FIRESTORE_SCHEMA_RUNBOOK.md](backend/FIRESTORE_SCHEMA_RUNBOOK.md) |
| Back up, restore or drill Firestore | [backend/FIRESTORE_DATA_PROTECTION.md](backend/FIRESTORE_DATA_PROTECTION.md) |
| Check the engine's performance floor | [engine/PERF_BASELINE_bd44af0.md](engine/PERF_BASELINE_bd44af0.md) (checked by hand in the engine repo; no CI job enforces it) |
| Edit the privacy policy, terms or cookie notes | [legal/PRIVACY_POLICY.md](legal/PRIVACY_POLICY.md) · [legal/TERMS_OF_SERVICE.md](legal/TERMS_OF_SERVICE.md) · [legal/COOKIE_CONSENT.md](legal/COOKIE_CONSENT.md) — then `python scripts/render_legal_pages.py` |

Nothing in `backend/` is needed to build, run, or contribute to the app — the
analysis engine is entirely on-device and offline.

## How the docs are organized

```
docs/
  app/       Android UI layer — Activity flow, packages, session layout
  engine/    the app-facing engine contract, plus stubs into the submodule's own docs
  backend/   the optional GCP cloud side — architecture, setup, sign-in
  legal/     privacy policy and terms — the source the hosted pages are generated from
  ops/       running the project — CI, releases, environments, tech debt, readiness gate, changelog
  adr/       architecture decision records
  perf/      measured before/after reports backing performance changes
  design/    UI wireframes kept as reference for a redesign in flight
```

`docs/engine/` is mostly signposts: the engine lives in its own repository, and
its architecture, math and test docs are canonical at `native/docs/`. The one
real document here is
[engine/ENGINE_APP_CONTRACT.md](engine/ENGINE_APP_CONTRACT.md), which states what
the app is allowed to assume across the JNI boundary.

## DIC in five minutes

Digital Image Correlation measures deformation from photographs:

1. A specimen is painted with a random **speckle pattern**.
2. A **reference** photo is taken, then more photos while it deforms.
3. The image is divided into small windows called **subsets** (41×41 px by
   default). Subset widths are always odd — a subset is indexed around its
   center pixel.
4. For each subset the engine finds where it moved in the deformed image, to
   ~1/100th of a pixel, using **ICGN**: an iterative Gauss-Newton solver that
   minimizes **ZNSSD**, a difference score immune to lighting changes (lower is
   better; ≤ 0.15 is accepted).
5. Displacements (**U** horizontal, **V** vertical, in pixels) are
   differentiated into **strains** (**Exx**, **Eyy**, **Exy**) — how much the
   material stretched and sheared.

That is enough theory for most contributions. The full pipeline — AKAZE feature
seeding → Delaunay mesh → RGDIC propagation → ICGN refinement → VSG strain — is
in `native/docs/ARCHITECTURE.md` inside the engine submodule.

## Terms you'll meet in the code

| Term | Meaning |
|---|---|
| `subset` | The small pixel window being tracked (`SubsetData`). Odd width, always |
| `step` | Grid spacing between tracked points, in px |
| `corr` / ZNSSD | Match quality. 0 = perfect, > 0.15 = rejected, < 0 = failed-point sentinel |
| `ICGN` | The iterative sub-pixel solver (`OptimizationEngine`) |
| `RGDIC` | Reliability-guided propagation — solved points seed their neighbors |
| `VSG` / strain window | Least-squares plane fit over neighboring points that turns displacement into strain. The window is a count of data points (odd); the VSG is the distance it covers, `(window − 1) × step + 1` px |
| `.dat` files | Binary results: 8 floats per point (x, y, u, v, exx, eyy, exy, znssd) |

For the directory layout, see [Where — repository map](../README.md#where--repository-map)
in the project README — it is maintained in one place so the two cannot drift.

## Conventions

- UI strings belong in `strings.xml`, never hardcoded.
- Shared intent keys live in `DicKeys.kt`; binary-format constants in
  `DicResult.kt`.
- Engine changes happen in the engine repository and must keep `dic_tests`
  green there. Results must stay inside the tolerance contract in
  [engine/ENGINE_APP_CONTRACT.md](engine/ENGINE_APP_CONTRACT.md); if a change
  legitimately moves results, say so explicitly and update the contract on both
  sides.
- Lint and detekt baselines are empty — new findings fail CI. Size and
  complexity findings are silenced per file with `@file:Suppress` (58 files as
  of 2026-10-03); prefer extracts over widening those lists.
- Run `./gradlew spotlessApply` before pushing.
