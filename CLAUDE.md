# Semper — Claude Code

Read [CONTEXT.md](CONTEXT.md) before editing. Human build/test source of truth:
[CONTRIBUTING.md](CONTRIBUTING.md).

## Commands

Windows: `gradlew.bat`. Quote `-Pandroid.testInstrumentationRunnerArguments…` in PowerShell.

| Command | What |
|---------|------|
| `./gradlew ciReleaseGate` | Spotless, detekt, lintDebug, unit tests, R8, assembleRelease |
| `./gradlew :app:testDebugUnitTest spotlessCheck :app:detekt :app:lintDebug` | Tier 1 without R8 |
| `./gradlew :app:koverLog :app:koverVerify` | Coverage log + floor (49; measured 51.7 % on 2026-09-24; enforced in CI tier 1 and `ciReleaseGate`) |
| `./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64 "-Pandroid.testInstrumentationRunnerArguments.notPackage=com.indicvision.semper.benchmark"` | Emulator instrumented; exclude benchmark package on debug |
| `cd backend && python -m pytest tests/ -q --cov=app --cov-fail-under=75` | Backend (install lock + `requirements-test.txt`) |
| `python scripts/render_legal_pages.py --check` | Hosted legal pages match `docs/legal/` |
| `python scripts/check_console.py` | Console pages: wiring, CSP, placeholders, gateway paths |
| `gitleaks detect --config .gitleaks.toml` | Secret scan |

Clone needs `git submodule update --init --recursive`. First native build compiles
OpenCV; cache is `app/.cxx/` (safe to delete).

Engine tests are **not** this CI. From the submodule: see [docs/engine/TESTING.md](docs/engine/TESTING.md).

## Where to edit

| Change | Start |
|--------|-------|
| Wizard chrome / steps | `StaticAnalysisActivity.goToStep`; slots/coach if present |
| Full-field batch / `.dat` write | `DicBatchRunner.kt` (`AnalysisViewModel.runBatchAnalysisBody`) + `DicFieldIo`, else `AnalysisViewModel` |
| A structural change | Check [docs/adr/](docs/adr/README.md) for a decision first |
| A new Kotlin file / package split | Feature subpackage per [ADR-015](docs/adr/ADR-015-package-layout.md); never move Workers, `SemperNativeLib`, Activities |
| Viewer / exports | `ResultViewerActivity`, `ShareCenter` |
| Settings sections | `Settings*Section`; restore/delete stay on `SettingsActivity` |
| Session paths | `SessionPaths` only |
| Backend routes | `backend/app/routers/`; app/middleware in `main.py` |
| Firestore access | `backend/app/repo/<aggregate>.py`; routers call it through `firestore_repo` ([ADR-001](docs/adr/ADR-001-firestore-repo-package.md)) |
| Engine math / solver | `native/` submodule + [docs/engine/ENGINE_APP_CONTRACT.md](docs/engine/ENGINE_APP_CONTRACT.md) |

## Guardrails

- Empty lint/detekt baselines. Extract or `@file:Suppress`; do not stuff findings.
- Bit-exact `.dat` / GIF oracles. JNI `computeFullFieldDirect` stays in the one batch loop.
- Do not split VisualizationEngine, GifEncoder LZW, ReportBuilder, upload `doWork`, scrub cache, `PointSpatialIndex.build`.
- Do not bump `targetSdk` 36→37 or enable `warningsAsErrors` without an explicit decision.
- Never bump `backend/requirements.txt` without `pip-compile --generate-hashes` on Python **3.12**.
- Never commit `local.properties` (API URL) or keystores.
- PRs target `main`. No force-push to `main`. No `--no-verify`.

## Docs to update in the same PR

Auth, quotas, deploy env, CI modes, or architecture extracts → matching file under
`docs/` (see [docs/README.md](docs/README.md)). A structural decision → a new
`docs/adr/ADR-NNN-*.md`. Debt found or closed → a row in
[docs/ops/TECH_DEBT.md](docs/ops/TECH_DEBT.md), with `path:line` evidence.

Then refresh the short **Current state** section of CONTEXT.md if the branch/PR
picture changed, and move entries that are merged **and** deployed into
[docs/ops/CHANGELOG.md](docs/ops/CHANGELOG.md). Current state is a snapshot,
not a log — keep it under ~40 lines.

Verify doc claims against the code before repeating them; docs here have drifted
before (the 2026-09-23 audit found ~25 wrong claims).
