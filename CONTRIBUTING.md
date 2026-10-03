# Contributing to Semper

Thanks for helping. This repo is a single Android app (`:app`) with an optional
FastAPI backend. The correlation engine is C++ and runs on-device, but it lives
in **its own repository** and is linked here as a pinned submodule — most UI work
is Kotlin and needs no native toolchain at all.

**This file is the source of truth for build, test and quality-gate commands.**
The README links here rather than repeating them.

## Who should read what

| You are… | Start here |
|---|---|
| **New developer** | [README](README.md) → [docs/README.md](docs/README.md) → clone with submodules (below) → [docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md). Offline analysis works with no backend. |
| **Contributor** | This file (build/test/PR) + [docs/ops/CI.md](docs/ops/CI.md) for why a check is red. Open PRs against **`main`**. |
| **Maintainer** | [docs/ops/ENVIRONMENTS.md](docs/ops/ENVIRONMENTS.md), [CI.md](docs/ops/CI.md), [RELEASING.md](docs/ops/RELEASING.md), [PRODUCTION_READINESS_GATE.md](docs/ops/PRODUCTION_READINESS_GATE.md). |

## Clone and native deps

The `--recursive` matters: `engine/` is a submodule
([`sempermechanics/semper-dic-engine`](https://github.com/sempermechanics/semper-dic-engine)),
and it has submodules of its own for Eigen and OpenCV. A non-recursive clone
gives you an empty `engine/` and a confusing CMake failure on first build.

A checkout from before the folder was renamed from `native/` (ADR-019) needs, after
the pull: `git submodule sync && git submodule update --init --recursive`, then
delete `app/.cxx` (its CMake cache holds the old path).

```bash
git clone <repo-url>
cd semperdic-app
git submodule update --init --recursive
```

Already cloned without it? `git submodule update --init --recursive` fixes it
in place.

OpenCV's full tree is large. After the first submodule init, apply a sparse
checkout so unused `doc/`, `samples/`, `data/`, and `apps/` trees are dropped
(~100+ MB). The script lives in the engine submodule and is run from there:

```bash
cd native
./scripts/sparse-opencv.sh          # Git Bash / macOS / Linux
.\scripts\sparse-opencv.ps1         # Windows PowerShell
cd ..
```

It is safe to re-run. Eigen stays a normal submodule (headers only, small).

### Bumping the engine

Changing the pinned engine commit is a normal PR in this repo:

```bash
cd native && git fetch && git checkout <commit-or-tag> && cd ..
git add native && git commit -m "Bump engine to <tag>"
```

That gitlink change is what triggers CI tiers 3 and 5 **on push to `main`** (or
on a PR labeled `e2e` / `release` / `full-ci`). The engine's own tests ran in its
repository before that commit existed; this repo only proves the pinned version
still links and behaves. If the bump changes numeric results, declare it against
the tiers in
[docs/engine/ENGINE_APP_CONTRACT.md](docs/engine/ENGINE_APP_CONTRACT.md).
material_testing must re-run its real-data checks after it syncs a bump
([docs/ops/FORK_SYNC.md](docs/ops/FORK_SYNC.md)).

### Local disk hygiene

Native OpenCV builds cache under `app/.cxx/` (~GB). Safe to delete when you
need space; the next native build recreates it:

```bash
rm -rf app/.cxx app/build
# Windows: Remove-Item -Recurse -Force app\.cxx, app\build
```

## First reads

1. [README](README.md) — what the product is and how to build
2. [docs/README.md](docs/README.md) — DIC primer, glossary, doc index
3. [docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md) — Activity flow and
   Kotlin package map (start here for Android UI changes)

## Build and test

```bash
# Full local push gate (mirrors CI tiers 1 + 5)
./gradlew ciReleaseGate

# Individual steps
./gradlew :app:testDebugUnitTest spotlessCheck :app:detekt :app:lintDebug

# Per-chunk tests — see docs/app/TESTING.md for what each chunk owns
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.auth.*"
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.session.*"
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.analysis.*"
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.results.*"
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.cloud.*"
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.settings.*"
./gradlew :app:testDebugUnitTest --tests "com.sempermechanics.semper.viewer.*"

# Emulator smoke (needs an x86_64 emulator running)
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64 "-Pandroid.testInstrumentationRunnerArguments.notPackage=com.sempermechanics.semper.benchmark"

# Performance benchmarks — not part of the push gate; they need a device and are
# label-gated in CI. See docs/app/TESTING.md#performance-benchmarks before running.

# Backend
cd backend && pip install -r requirements-test.txt && pytest tests/ -v

# Engine tests — these build the submodule; they run in the engine repo's CI,
# not this one. See docs/engine/TESTING.md.
cmake -S engine/tests -B build/engine-tests -DCMAKE_BUILD_TYPE=Release
cmake --build build/engine-tests -j
./build/engine-tests/dic_tests
```

On Windows use `gradlew.bat` instead of `./gradlew`.

### Three gates that run on every PR

None is path-filtered, so a documentation-only change is still subject to all
of them, and each blocks `ci-ok`:

```bash
python scripts/render_legal_pages.py --check   # legal pages match docs/legal/
python scripts/check_console.py                # console wiring, CSP, gateway paths
gitleaks detect --config .gitleaks.toml        # secrets (full history; human PRs)
```

`check_console.py` is the consoles' only gate — they have no compiler, so a
renamed element id or an undeclared `/v1` path fails nowhere else. See
[firebase-hosting/public/console/README.md](firebase-hosting/public/console/README.md).

Dependabot PRs use a CLI scan of **only the PR commit range** (no org
`GITLEAKS_LICENSE` on Dependabot). Details: [docs/ops/CI.md](docs/ops/CI.md).

**Never hand-edit `firebase-hosting/public/privacy/` or `.../terms/`.** They are
generated from `docs/legal/*.md` by `scripts/render_legal_pages.py`, and the app
links them as its real user-facing policy. Edit the markdown, re-run the script
without `--check`, and commit both. See
[firebase-hosting/README.md](firebase-hosting/README.md).

Cloud features need `SEMPER_API_BASE_URL` in `local.properties` and Firebase
setup — see [docs/backend/AUTH_SETUP.md](docs/backend/AUTH_SETUP.md). Local
analysis works fine without it. Note that a **release** build is stricter: it
fails outright if that URL is missing or not HTTPS, so cloud sync cannot ship
silently disabled.

## CI

CI is defined in [`.github/workflows/ci.yml`](.github/workflows/ci.yml).
See [docs/ops/CI.md](docs/ops/CI.md) for the tier map and required checks.
The single required status check is `CI OK` (`ci-ok`).

- PRs into **`main`**: gates + path-filtered Tier 1 / Tier 4. Add label `e2e`,
  `release`, or `full-ci` for Tier 3 / Tier 5 on the PR.
- Push to **`main`**: full matrix.
- Dependabot: cheap path (actions = gates only; pip = Tier 4; gradle = Tier 1).

Warm full-matrix wall clock is ~45–60 min (emulator and signed release run in
parallel). Kotlin/docs-only PRs run ~10–15 min via path filters.

## Where to change what

| Area | Entry point |
|---|---|
| Android UI / sessions / viewer | [docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md) |
| Every workflow and the files behind it | [docs/WORKFLOWS.md](docs/WORKFLOWS.md) |
| Every user-facing screen, as a manual test pass | [docs/app/WORKFLOWS.md](docs/app/WORKFLOWS.md) |
| App tests (workflow chunks) | [docs/app/TESTING.md](docs/app/TESTING.md) |
| C++ correlation engine | The `engine/` submodule — the app-facing rules are in [docs/engine/ENGINE_APP_CONTRACT.md](docs/engine/ENGINE_APP_CONTRACT.md) |
| Engine tests | [docs/engine/TESTING.md](docs/engine/TESTING.md) |
| Sign-in / allow-list | [docs/backend/AUTH_SETUP.md](docs/backend/AUTH_SETUP.md) |
| Backend behaviour | [docs/backend/CLOUD_ARCHITECTURE_GCP.md](docs/backend/CLOUD_ARCHITECTURE_GCP.md) |
| GCP backend deploy | [docs/backend/BACKEND_SETUP_GCP.md](docs/backend/BACKEND_SETUP_GCP.md) |
| Environments / secrets | [docs/ops/ENVIRONMENTS.md](docs/ops/ENVIRONMENTS.md) |
| Legal pages / asset links | [firebase-hosting/README.md](firebase-hosting/README.md) |
| CI / release | [docs/ops/CI.md](docs/ops/CI.md), [docs/ops/RELEASING.md](docs/ops/RELEASING.md) |

Prefer extracting a `*Helper` / `*Runner` next to existing ones over growing a
god Activity. Do not introduce a DI framework for tiny helpers — there is no
Hilt/Dagger in this app (removed as unused, zero `@Inject` sites); if a screen
grows real injectable dependencies, propose the framework in its own PR rather
than sneaking it into an unrelated one.

## Pull requests

- Target **`main`**. Keep PRs focused (one concern: dead-code cleanup, one helper
  extract, one feature).
- Match existing naming and package layout.
- Run the relevant tests above before asking for review.
- Link issues when applicable; `good first issue` tags are scoped for newcomers.
- If you change auth, quotas, deploy env vars, or CI modes, update the matching
  doc under `docs/` in the same PR.
- Do not force-push `main`. Prefer revert of a bad merge over history rewrite.
- A fix to code material_testing shares lands here first, even when it was found
  there; port its commit with `git cherry-pick -x` ([docs/ops/FORK_SYNC.md](docs/ops/FORK_SYNC.md)).

## License

See [LICENSE](LICENSE). Until public license terms are finalized, all rights are reserved as stated there.
