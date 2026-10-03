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

This repo's CI verifies that the pinned submodule still *links*: an arm64-v8a
native build in tier 1 and an x86_64 emulator run in tier 3. Bumping the
submodule pointer triggers both. See [../ops/CI.md](../ops/CI.md).

## Changing engine behavior

If a change legitimately moves numeric results, it must be declared against the
stability tiers in [ENGINE_APP_CONTRACT.md](ENGINE_APP_CONTRACT.md) and the
tolerance contract in the engine repo updated in the same change.
