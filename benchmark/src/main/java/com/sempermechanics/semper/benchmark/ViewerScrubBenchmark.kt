package com.sempermechanics.semper.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Result-viewer scrub Macrobenchmark: opens a synthetic N-frame session and steps
 * through frames, measuring
 * - [FrameTimingMetric] — scrub jank (round-2 moved stats/extrema off the UI thread),
 * - [MemoryUsageMetric] — heap under the workload,
 * - [TraceSectionMetric] on `Semper.viewer.decodeDat` — the per-frame `.dat` decode.
 *
 * **Why the session is opened by shell-starting [BenchmarkSeedActivity] rather than
 * `startActivityAndWait`:** `ResultViewerActivity` is not exported, and since API 34+
 * the shell (uid 2000) cannot start a non-exported component — `am start` fails with
 * `SecurityException: Permission Denial ... not exported`. So the benchmark starts the
 * *exported*, benchmark-variant-only seeder, which fabricates the frames and then
 * starts the viewer from inside the app (same uid, allowed).
 *
 * That seeder is a trampoline: it calls `finish()` after handing off, so it never draws
 * a frame of its own and `startActivityAndWait` could never confirm its launch
 * ("Unable to confirm activity launch completion []"). None of the metrics here are
 * startup metrics, so the launch is driven with a plain `am start` and the viewer's own
 * UI is awaited instead.
 *
 * Run: `./gradlew :benchmark:connectedBenchmarkAndroidTest`
 */
@OptIn(ExperimentalMetricApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class ViewerScrubBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** The phone's thermal, charger and memory state around each test (TD-135). */
    @get:Rule
    val deviceState = DeviceStateRule()

    @Test
    fun scrub150Frames() = scrub(frameCount = 150)

    @Test
    fun scrub10Frames() = scrub(frameCount = 10)

    private fun scrub(frameCount: Int) = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
            TraceSectionMetric("Semper.viewer.decodeDat", TraceSectionMetric.Mode.Sum),
        ),
        iterations = ITERATIONS,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            killProcess()
            device.executeShellCommand(
                "am start -n $PACKAGE/$SEEDER --ei frameCount $frameCount",
            )
            // The first iteration also fabricates the .dat files, so allow for that.
            check(device.wait(Until.hasObject(By.res(PACKAGE, NEXT_BUTTON)), LAUNCH_TIMEOUT_MS)) {
                "Viewer did not open on the seeded $frameCount-frame session"
            }
            device.waitForIdle(IDLE_TIMEOUT_MS)
        },
    ) {
        val next = device.findObject(By.res(PACKAGE, NEXT_BUTTON))
            ?: error("$NEXT_BUTTON not found — did the viewer close?")
        repeat(SCRUBS) {
            next.click()
            device.waitForIdle(IDLE_TIMEOUT_MS)
        }
    }

    companion object {
        private const val PACKAGE = BuildConfig.TARGET_PACKAGE
        private const val SEEDER = "com.sempermechanics.semper.benchmark.BenchmarkSeedActivity"
        private const val NEXT_BUTTON = "btnNextFrame"
        private const val ITERATIONS = 5
        private const val SCRUBS = 12
        private const val LAUNCH_TIMEOUT_MS = 120_000L
        private const val IDLE_TIMEOUT_MS = 5_000L
    }
}
