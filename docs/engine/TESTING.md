# Engine tests — moved

The C++ test suite (`dic_tests`) lives with the engine source in
[`sempermechanics/semper-dic-engine`](https://github.com/sempermechanics/semper-dic-engine),
linked here as a pinned submodule at `engine/`.

**Read the canonical document at [`engine/docs/TESTING.md`](../../engine/docs/TESTING.md)**
for the test catalog and the numeric tolerance contract.

## Running them from this checkout

```bash
git submodule update --init --recursive
cmake -S engine/tests -B build/engine-tests -DCMAKE_BUILD_TYPE=Release
cmake --build build/engine-tests -j
./build/engine-tests/dic_tests
```

## Where engine tests run in CI

They do **not** run in this repository's `ci.yml`. Host builds, the DICe
reference comparisons, AddressSanitizer/UndefinedBehaviorSanitizer and
ThreadSanitizer suites all run in the engine repo's own CI, gating the tags
this repo pins to.

This repo's CI verifies that the pinned submodule still *links and behaves*:
the arm64-v8a release build (R8, signed) in tier 5, and the x86_64 emulator run
in tier 3, where `EnginePipelineSmokeTest` drives the solver through the JNI.
Bumping the submodule pointer — or changing `app/src/main/cpp/**` or
`SemperNativeLib.kt` — triggers both, on the PR and again on the push to
`main`. Tier 1 is the JVM suite only; it builds no native code. See
[../ops/CI.md](../ops/CI.md).

## Changing engine behavior

If a change legitimately moves numeric results, it must be declared against the
stability tiers in [ENGINE_APP_CONTRACT.md](ENGINE_APP_CONTRACT.md) and the
tolerance contract in the engine repo updated in the same change.
