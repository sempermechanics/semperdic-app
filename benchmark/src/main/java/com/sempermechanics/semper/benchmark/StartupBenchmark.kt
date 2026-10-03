package com.sempermechanics.semper.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup Macrobenchmarks for Semper. Run on a physical device / emulator with:
 * `./gradlew :benchmark:connectedBenchmarkAndroidTest`
 *
 * Gated in CI behind the `benchmark` label (see docs/ops/CI.md).
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** The phone's thermal, charger and memory state around each test (TD-135). */
    @get:Rule
    val deviceState = DeviceStateRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = COLD_START_ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(),
    ) {
        startActivityAndWait()
    }

    @Test
    fun warmStartup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
    ) {
        startActivityAndWait()
    }

    companion object {
        private const val PACKAGE = BuildConfig.TARGET_PACKAGE

        /**
         * 15, not 5: a Pixel 6's cold starts spread 404–478 ms within one run, so a
         * 5-start median moved with one or two slow starts (TD-135).
         */
        const val COLD_START_ITERATIONS = 15
    }
}
