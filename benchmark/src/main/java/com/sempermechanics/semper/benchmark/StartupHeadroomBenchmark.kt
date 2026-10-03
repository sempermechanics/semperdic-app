package com.sempermechanics.semper.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * How much a cold start gains from ahead-of-time compilation, which is the most a
 * baseline profile could buy (docs/perf/startup.md).
 *
 * - `None`: everything JIT-compiled, as on the first launch after an install
 *   without a profile.
 * - `Partial()`: what the installed profile gives (`app/src/main/baseline-prof.txt`).
 * - `Full`: everything compiled, the ceiling.
 *
 * Measure on a phone, not an emulator. Use `am instrument` rather than gradle's
 * connected task, which uninstalls the app afterwards (docs/perf/startup.md has
 * the steps). Skipped unless `-e startupHeadroom true` is passed, so the CI
 * benchmark job doesn't spend 30 emulator cold starts on a number it can't
 * measure.
 *
 * The phone warms up during a run, and the mode that runs first comes out
 * fastest. Run once in each order and pool the results (startup.md).
 */
@LargeTest
@RunWith(Parameterized::class)
class StartupHeadroomBenchmark(private val compilationMode: CompilationMode) {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** The phone's thermal, charger and memory state around each test (TD-135). */
    @get:Rule
    val deviceState = DeviceStateRule()

    @Before
    fun onlyWhenAsked() {
        assumeTrue(InstrumentationRegistry.getArguments().getString(ARG) == "true")
    }

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = compilationMode,
    ) {
        startActivityAndWait()
    }

    companion object {
        private const val PACKAGE = BuildConfig.TARGET_PACKAGE
        private const val ITERATIONS = 10
        private const val ARG = "startupHeadroom"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<CompilationMode> =
            listOf(CompilationMode.None(), CompilationMode.Partial(), CompilationMode.Full())
    }
}
