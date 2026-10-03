# Engine and viewer hot paths: regression check (2026-09-25)

Pass 6 of the measured optimisation plan only measures. A lever would be
considered only if a number had regressed beyond noise. **None has, so nothing
changed.**

## Native solver throughput

`Perf.SubsetSolveThroughput` from the engine's host tests (`engine/tests/perf/`),
checked against the reference in
[engine/docs/PERF_BASELINE_bd44af0.md](../../engine/docs/PERF_BASELINE_bd44af0.md):
≥ 4557 solves/s, 95 % of 4797.

**Environment:**
- Engine `72b6286` (v0.2.2, the submodule `main` pins).
- Built with `cmake -S tests -B build/tests -G Ninja -DCMAKE_BUILD_TYPE=Release`.
- g++ 16.1.0 (WinLibs UCRT) on an Intel i7-12700H, Windows 11, 15 runs.

| Metric | Value |
|---|--:|
| Subsets solved per run | 162 in all 15 runs (baseline: 162) |
| Solve rate, median | **5491 solves/s** |
| IQR | 281 |
| Min / max | 5012 / 5588 |

[Measured] Every run is above the 4557 floor.

The baseline's machine and compiler aren't recorded, so the +14 % over 4797 is
not a like-for-like gain [Estimated]. The same solve count is the stronger
signal: the solver does the same work.

## App hot paths (`HotPathMicroBenchmark`)

**Environment:**
- Pixel 6, API 37, debug build of `main` at 0495ac5.
- Run with the `am instrument` command in
  [app/TESTING.md](../app/TESTING.md#performance-benchmarks).
- Compared with the "branch" column of
  [round2-main-vs-branch.md](round2-main-vs-branch.md), which was measured on a
  Pixel_8 emulator, API 37, x86_64.

Allocation counts are fixed for a given piece of code, so they are the
comparison. Timing isn't comparable across a phone and an emulator, and this run
was debuggable and thermally throttled: the library paused for 90 s several
times.

| Hot path | allocs, round 2 | allocs, now | Change |
|---|--:|--:|--:|
| `profileAlong` ×3 (control, unchanged code) | 1,492 | 1,492 | **0** |
| `valueRanges` ×1 | 88 | 28 | fewer |
| `valueRanges` ×150 | 13,226 | 4,203 | fewer |
| `buildReport` ×1 | 1,782 | 1,714 | fewer |
| `computeFieldExtrema` ×1 | 34 | 2 | fewer |
| `generateHeatmap` ×1 | 54 | 22 | fewer |
| `gifEncode` 10 frames | 32 | 30 | fewer |
| `gifEncode` 150 frames | 313 | 312 | fewer |
| `decodeDatFile` ×1 | 35 | 35 | 0 |
| `pointSpatialIndexBuild` ×1 | 286,742 | 286,740 | −2 |

[Measured] Allocation counts had a spread of 0 within each case. The control
matches exactly, so this comparison across devices holds. **No path allocates
more than it did in round 2.**

The time medians, for the record only:

| Hot path | timeNs median |
|---|--:|
| `decodeDatFile` | 0.50 ms |
| `computeFieldExtrema` | 2.5 ms |
| `valueRanges` ×1 | 9.5 ms |
| `generateHeatmap` | 19.2 ms |
| `gifEncode` 10 | 22.2 ms |
| `pointSpatialIndexBuild` | 98 ms |
| `buildReport` | 195 ms |
| `gifEncode` 150 | 305 ms |
| `valueRanges` ×150 | 1.43 s |

These are directional only. A timing regression check would need both builds
run on the same device, back to back.

## Running on a phone

`./gradlew :app:installDebug` installs on **every** connected device and
emulator. Set `ANDROID_SERIAL` to the phone's serial before running it when
emulators that belong to other work are attached. Afterwards, uninstall `com.sempermechanics.semper.test`.
