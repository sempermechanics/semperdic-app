# Cold start: is there anything for a baseline profile to win?

**Answer (2026-09-25): no.** Compiling the whole app ahead of time doesn't make a
cold start measurably faster, so a baseline profile has nothing to buy. The
profile stays as it is (`app/src/main/baseline-prof.txt`, comments only; the APK
still ships the AndroidX libraries' own profile rules).

## Question and gate

A baseline profile makes the startup path precompiled instead of JIT-compiled.
The most it can gain is the gap between `CompilationMode.None` (nothing
precompiled) and `CompilationMode.Full` (everything precompiled). The gate from
the optimisation plan:
- Generate a profile only if `Full` beats `None` by at least 15 % on median
  `timeToInitialDisplayMs`.
- Otherwise record the numbers and stop.

## Instrument

`benchmark/.../StartupHeadroomBenchmark.kt` runs 10 cold starts under each of
`None`, `Partial()` (the installed profile, reported as `BaselineProfile`) and
`Full`. Each start goes Splash → Home with the account signed in.

**Environment:**
- Pixel 6, Android API 37, CPU clocks not locked.
- On AC power, Battery Saver off; battery 37.8 → 39.0 °C over the runs.
- `:app` `benchmark` build type (release-like, not debuggable, not minified).
- Built from `main` at 7169395 with `-PabiFilters=arm64-v8a`.

**Steps.** They avoid gradle's connected task, because it uninstalls the app and
its data afterwards.

```bash
./gradlew :app:assembleBenchmark :benchmark:assembleBenchmark -PabiFilters=arm64-v8a
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
adb install -r benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk
adb shell am instrument -w -e startupHeadroom true -e androidx.benchmark.suppressErrors UNLOCKED -e class "com.sempermechanics.semper.benchmark.StartupHeadroomBenchmark#coldStartup[Full]" com.sempermechanics.semper.benchmark/androidx.test.runner.AndroidJUnitRunner
```

- Repeat the last command for `[BaselineProfile]` and `[None]`, then run all
  three in the opposite order.
- The phone's 30 s screen timeout can end a run, so send
  `adb shell input keyevent KEYCODE_WAKEUP` every 10 s while it runs.
- Results are in
  `/storage/emulated/0/Android/media/com.sempermechanics.semper.benchmark/*benchmarkData.json`.
- The benchmark APK installs over a debug build (same debug key) without losing
  data. Afterwards, reinstall the debug build and uninstall
  `com.sempermechanics.semper.benchmark`.

## Results

**The order matters more than the mode.** In each run the mode that went first
was fastest, as the phone warmed up:

| TTID median (10 cold starts) | None | BaselineProfile | Full |
|---|--:|--:|--:|
| Run 1, order None → BaselineProfile → Full | **473** | 574 | 579 |
| Run 2, order Full → BaselineProfile → None | 507 | 473 | **453** |

**Pooled.** Both runs together, 20 cold starts per mode, so the drift cancels:

| Mode | TTID median | IQR | TTFD median | IQR |
|---|--:|--:|--:|--:|
| None | 500 ms | 66 | 532 ms | 43 |
| BaselineProfile | 518 ms | 106 | 537 ms | 96 |
| Full | 522 ms | 137 | 539 ms | 121 |

[Measured] `Full` against `None`: −4.4 % (slower), inside the spread. **Gate not
met**, so no profile was generated.

Cold start here is not limited by how the app's code is compiled. `am start -W`
TotalTime on a debug build is about 700 ms
([request-volume.md](request-volume.md), Pass 3). The release-like build's
500 ms is the figure users see.

**Emulator note.** The CI `tier-benchmark` job (API 34 emulator) can't answer
this question: emulator timing moves ±40 %. The new class is skipped there
unless `-e startupHeadroom true` is passed.
