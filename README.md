<p align="center">
  <img src="docs/images/semper-mark.png" width="72" alt="Semper mark">
  <br><br>
  <strong>Semper</strong> — Digital Image Correlation on Android
  <br>
  <a href="https://github.com/sempermechanics/semperdic-app/actions/workflows/ci.yml"><img src="https://github.com/sempermechanics/semperdic-app/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
</p>

<p align="center">
  <img src="docs/images/result-viewer.png" width="220" alt="Interactive strain heatmap in the result viewer">
</p>

Speckle a specimen, photograph it before and under load, and Semper computes
full-field **displacement** (U, V — ~1/100 px) and **strain** (Exx, Eyy, Exy)
on the phone. Results are interactive heatmaps plus PDF, CSV, and PNG exports.
The **C++ engine runs fully offline** (OpenCV + Eigen, NEON on device / SSE on
emulator); cloud sync is optional for signed-in testers.

<p align="center">
  <img src="docs/images/home.png" width="220" alt="Home — sessions">
  &nbsp;
  <img src="docs/images/step1-frames.png" width="220" alt="Wizard — pick frames">
  &nbsp;
  <img src="docs/images/roi-editor.png" width="220" alt="ROI editor">
</p>

| | |
|---|---|
| **Full-field 2D DIC** | ICGN sub-pixel solve, AKAZE + Delaunay seeding, RGDIC propagation, VSG strain |
| **Batch & video** | One reference vs many frames, or auto-extract from video |
| **ROI** | Rectangle or freehand mask |
| **Viewer & exports** | Per-field heatmaps, probe, custom scales, PDF / CSV / PNG / ZIP |
| **Cloud (optional)** | On-device queue → Google Drive via GCP backend when online — [architecture](docs/backend/CLOUD_ARCHITECTURE_GCP.md) |

---

## Who

| You are | Start here |
|---|---|
| **New developer** | This page → [docs/README.md](docs/README.md) (DIC primer) → clone below → [docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md) |
| **Contributor** | [CONTRIBUTING.md](CONTRIBUTING.md) (build, test, PR rules) + [docs/ops/CI.md](docs/ops/CI.md) when a check fails |
| **Maintainer** | [docs/ops/ENVIRONMENTS.md](docs/ops/ENVIRONMENTS.md) · [RELEASING.md](docs/ops/RELEASING.md) · [PRODUCTION_READINESS_GATE.md](docs/ops/PRODUCTION_READINESS_GATE.md) |
| **Engine author** | [`semper-dic-engine`](https://github.com/sempermechanics/semper-dic-engine) submodule at `native/` — app contract: [docs/engine/ENGINE_APP_CONTRACT.md](docs/engine/ENGINE_APP_CONTRACT.md) |
| **Backend author** | [docs/backend/CLOUD_ARCHITECTURE_GCP.md](docs/backend/CLOUD_ARCHITECTURE_GCP.md) · deploy: [GCP](docs/backend/BACKEND_SETUP_GCP.md) or [Console](docs/backend/BACKEND_SETUP_CONSOLE.md) |

PRs target **`main`**. UI and docs work need no C++ toolchain. Issues tagged
`good first issue` are scoped for newcomers.

---

## How — build & run

```bash
git clone https://github.com/sempermechanics/semperdic-app
cd semperdic-app
git submodule update --init --recursive   # engine + Eigen/OpenCV (large, one-time)
cd native && ./scripts/sparse-opencv.sh && cd ..   # Windows: .\scripts\sparse-opencv.ps1
```

Open in Android Studio → **Run**. First build compiles OpenCV once (`app/.cxx/`
cache). **No API keys required** for local analysis; without them cloud sync
stays off. Release builds require HTTPS `INDIC_API_BASE_URL` in
`local.properties` (cloud cannot ship silently disabled).

**Emulator (x86_64):** `./gradlew :app:installDebug -PabiFilters=x86_64` — debug
boots straight to Home as a local dev account (no backend). Set
`INDIC_DEV_AUTH_BYPASS=false` in `local.properties` to exercise real sign-in.

Full commands, engine bumps, disk hygiene, and backend setup:
[CONTRIBUTING.md](CONTRIBUTING.md).

---

## Where — repository map

> **The C++ engine is not in this repo.** It is pinned in
> [`native/`](https://github.com/sempermechanics/semper-dic-engine) as a git
> submodule; engine CI proves that commit. This repo proves it still **links**.

<p align="center">
  <img src="docs/images/wizard.svg" width="640" alt="Setup wizard flow">
</p>

| Path | What |
|---|---|
| `native/` | **Submodule** — solver, strain, seeding, JNI, engine tests & docs |
| `app/src/main/cpp/` | App-side JNI boundary |
| `app/src/main/java/.../ui/analysis/` | Setup wizard, ROI, parameter-sweep lattice |
| `app/src/main/java/.../ui/capture/` | Home **Record**: test shot, noise floor, timed capture |
| `app/src/main/java/.../ui/viewer/` | Heatmaps, probe, exports |
| `app/src/main/java/.../ui/auth/` · `ui/settings/` | Sign-in, access gating, settings |
| `app/src/main/java/.../report/` | PDF report generation |
| `app/src/main/java/.../data/` | Cloud client, upload/restore workers, storage budget |
| `app/src/test/` · `app/src/androidTest/` | JVM and instrumented tests |
| `backend/` | FastAPI on Cloud Run |
| `firebase-hosting/` | Auth continue URLs, asset links, generated legal pages |
| `scripts/` | Legal-page renderer, Firestore export/restore |
| `docs/` | **[Documentation index](docs/README.md)** — app, engine contract, backend, ops |

Screen flow and package roles: [docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md).
Every user-facing flow: [docs/app/WORKFLOWS.md](docs/app/WORKFLOWS.md).

---

## How — analysis pipeline

<p align="center">
  <img src="docs/images/pipeline.svg" width="720" alt="DIC pipeline: decode → seed → mesh → RGDIC → ICGN → strain → exports">
</p>

Each tracked **subset** (odd-width window) is matched with **ICGN** (6-DOF warp)
scored by **ZNSSD** (0 = perfect, ≤ 0.15 accepted — lighting-invariant). Engine
detail: `native/docs/ARCHITECTURE.md` · [docs/engine/](docs/engine/).

### Parameters (Advanced in the wizard)

<p align="center">
  <img src="docs/images/subset-step.svg" width="480" alt="Subset size and step on the analysis grid">
</p>

| Parameter | Default | Range | Role |
|---|---|---|---|
| Subset size | measured (41 px fallback) | 15–121, **odd** | Tracked window — larger = robust, less detail |
| Step size | 5 px | 1–30 | Grid spacing between points |
| Strain window | 15 px | 5–101, **odd** | VSG gauge length (displacement → strain) |

Subset width is **suggested from the reference speckle** (SSSIG criterion, Pan
et al. 2008): weak speckle → larger window. Typed evens snap to odd; out-of-range
values clamp. Override freely — **Reset** restores the suggestion.

---

## Test & CI

```bash
./gradlew :app:testDebugUnitTest spotlessCheck :app:detekt :app:lintDebug
```

That is the usual app gate. Backend, emulator tier, and **engine host /
sanitizer / DICe suites run in the engine repo**, not here — this CI proves the
pinned engine still links (arm64-v8a release + x86_64 emulator).

Release gate, per-chunk tests, benchmarks, and legal-page checks:
[CONTRIBUTING.md](CONTRIBUTING.md) · tier map: [docs/ops/CI.md](docs/ops/CI.md).

---

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) is the source of truth for build, test, and PR
workflow. [docs/README.md](docs/README.md) has the DIC primer and glossary.

## License

See [LICENSE](LICENSE). Terms finalize before public release; until then, all
rights reserved.
