package com.sempermechanics.semper.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-screen startup and scroll benchmarks.
 *
 * [StartupBenchmark] only covers app launch, which misses the two screens where
 * jank actually shows up: the settings sheet (the highest-churn file in the app,
 * an 800+ line layout) and the analysis wizard (a ~900 line layout inflated in
 * one pass).
 *
 * **Why these two screens and not Home / the result viewer:** the `benchmark`
 * build type is `initWith(release)`, so `BuildConfig.DEBUG` and `DEV_AUTH_BYPASS`
 * are both false and `DevAuth.active` is off. A launch therefore routes
 * `SplashActivity → AuthActivity`, and the Home session list is unreachable
 * without real credentials. The two screens below are launched directly by
 * component instead. (The result viewer *is* covered — by [ViewerScrubBenchmark],
 * which seeds a synthetic session.)
 *
 * Both screens are `android:exported="false"` in every shipped variant, and since
 * API 34 the shell (uid 2000) cannot start a non-exported component — `am start`
 * fails with "SecurityException: Permission Denial ... not exported". They are
 * therefore exported for the `benchmark` variant only, via the overlay at
 * `app/src/benchmark/AndroidManifest.xml`.
 *
 * **Known environment limitation:** on an earlier API 37 emulator image these failed
 * with "Unable to confirm activity launch completion []" — `startActivityAndWait`
 * confirms a launch by parsing `dumpsys gfxinfo <pkg> framestats`, which came back
 * empty there for *every* activity. The Pixel_10_2 API 37 image runs them (2026-09-25);
 * if a launch cannot be confirmed, use a physical device or an older API image.
 * [ViewerScrubBenchmark] deliberately avoids that API.
 *
 * Run: `./gradlew :benchmark:connectedBenchmarkAndroidTest`
 *
 * No thresholds are asserted here: a phone run is checked against
 * `benchmark/gates.json` by `scripts/ci_test_report.py --gates`, in the state
 * [DeviceStateRule] records (docs/adr/ADR-008-startup-gates-phone-state.md).
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ScreenBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** The phone's thermal, charger and memory state around each test (TD-135). */
    @get:Rule
    val deviceState = DeviceStateRule()

    /** Cold start straight into the settings sheet. */
    @Test
    fun settingsColdStartup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = StartupBenchmark.COLD_START_ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(),
    ) {
        startActivityAndWait(intentFor(SETTINGS_ACTIVITY))
    }

    /**
     * Frame timing while flinging settings top to bottom. Sections start
     * collapsed, so a fling on the empty headers produces no frames and
     * [FrameTimingMetric] throws "Observed no expect/actual slices". Expand
     * first in [setupBlock]; measure only the scroll.
     */
    @Test
    fun settingsScroll() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            startActivityAndWait(intentFor(SETTINGS_ACTIVITY))
            expandAllSettingsSections()
        },
    ) {
        val scroll = settingsList()
        repeat(FLINGS) { scroll.fling(Direction.DOWN) }
        repeat(FLINGS) { scroll.fling(Direction.UP) }
        device.waitForIdle()
    }

    /** Cold start into the analysis wizard — the heaviest layout inflation. */
    @Test
    fun analysisWizardColdStartup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = StartupBenchmark.COLD_START_ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(),
    ) {
        startActivityAndWait(intentFor(ANALYSIS_ACTIVITY))
    }

    private fun intentFor(className: String) = Intent().apply {
        setClassName(PACKAGE, className)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The settings list, looked up again for every gesture: expanding a section
     * re-lays the list out, and a [UiObject2] held across that went stale mid-fling.
     */
    private fun MacrobenchmarkScope.settingsList(): UiObject2 {
        val scroll = device.wait(Until.findObject(By.res(PACKAGE, "settingsScroll")), FIND_TIMEOUT_MS)
            ?: error("settingsScroll not found — did SettingsActivity fail to render standalone?")
        // Keep the gesture clear of the system back/nav edges.
        scroll.setGestureMargin(device.displayWidth / GESTURE_MARGIN_FRACTION)
        return scroll
    }

    /**
     * Opens every section, searching for each header from the top in half-screen
     * steps. Flings overshot: once Storage was open, one fling could carry "Your
     * data" past the top of a tall screen, and later flings only went further down.
     */
    private fun MacrobenchmarkScope.expandAllSettingsSections() {
        SECTION_HEADERS.forEach { id ->
            val header = findHeader(id)
            if (header == null) {
                // The seeded benchmark app has no licence, and SettingsActivity
                // leaves the cloud sections out for an unlicensed account.
                if (id in LICENSED_ONLY_HEADERS) return@forEach
                error("$id not found — settings section header missing")
            }
            header.click()
            device.waitForIdle()
        }
        scrollToTop()
    }

    private fun MacrobenchmarkScope.findHeader(id: String): UiObject2? {
        val selector = By.res(PACKAGE, id)
        scrollToTop()
        repeat(SCROLL_ATTEMPTS) {
            device.findObject(selector)?.let { return it }
            if (!settingsList().scroll(Direction.DOWN, SCROLL_STEP)) return device.findObject(selector)
        }
        return device.findObject(selector)
    }

    private fun MacrobenchmarkScope.scrollToTop() {
        repeat(SCROLL_ATTEMPTS) {
            if (!settingsList().scroll(Direction.UP, 1f)) return
        }
    }

    companion object {
        private const val PACKAGE = BuildConfig.TARGET_PACKAGE
        private const val SETTINGS_ACTIVITY = "com.sempermechanics.semper.ui.settings.SettingsActivity"
        private const val ANALYSIS_ACTIVITY = "com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity"
        private const val ITERATIONS = 5
        private const val FLINGS = 3
        private const val FIND_TIMEOUT_MS = 5_000L
        private const val GESTURE_MARGIN_FRACTION = 5
        private const val SCROLL_ATTEMPTS = 20

        /** Half a screen: a header is a few dp tall, so a step never jumps one. */
        private const val SCROLL_STEP = 0.5f
        private val SECTION_HEADERS = listOf(
            "headerAccount",
            "headerCloud",
            "headerAnalysesData",
            "headerStorage",
            "headerYourData",
            "headerAnalysisPrefs",
            "headerHelpSupport",
        )

        /** Shown only when `LicenseEntitlements.cloudBackupEnabled` (backup and restore). */
        private val LICENSED_ONLY_HEADERS = setOf("headerCloud", "headerAnalysesData")
    }
}
