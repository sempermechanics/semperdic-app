# Engine architecture — moved

The C++ correlation engine no longer lives in this repository. It is developed
in [`sempermechanics/semper-dic-engine`](https://github.com/sempermechanics/semper-dic-engine)
and linked here as a pinned git submodule at `engine/`.

**Read the canonical document at [`engine/docs/ARCHITECTURE.md`](../../engine/docs/ARCHITECTURE.md)**
(after `git submodule update --init --recursive`), or browse it in the engine
repo.

## What you probably want instead

| Question | Where |
|---|---|
| What API does the app get from the engine, and what may change? | [ENGINE_APP_CONTRACT.md](ENGINE_APP_CONTRACT.md) — the app-side copy of the stability contract |
| How is the solver built, and what are Path A/B/C? | `engine/docs/ARCHITECTURE.md` |
| The math behind ICGN, ZNSSD and VSG | `engine/docs/MATHEMATICS.md` |
| How do I run the engine tests? | `engine/docs/TESTING.md` |
| How does the JNI layer bind into the app? | [../app/ARCHITECTURE.md](../app/ARCHITECTURE.md) |

## Building the engine from this repo

The app's Gradle build compiles the submodule automatically. To shrink the
OpenCV worktree first (optional, saves ~100 MB):

```bash
git submodule update --init --recursive
cd native && ./scripts/sparse-opencv.sh && cd ..   # Windows: .\scripts\sparse-opencv.ps1
```

Engine host tests, AddressSanitizer/UndefinedBehaviorSanitizer and
ThreadSanitizer suites run in the engine repo's own CI, not in this repo's
`ci.yml`. See [../ops/CI.md](../ops/CI.md) for what this repo does run.
