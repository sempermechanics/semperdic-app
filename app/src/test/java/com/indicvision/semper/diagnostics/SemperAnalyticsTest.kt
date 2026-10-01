package com.indicvision.semper.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.prefs.DicSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SemperAnalyticsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val recorded = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun setUp() {
        recorded.clear()
        SemperAnalytics.sink = SemperAnalytics.Sink { _, name, params ->
            recorded += name to params
        }
        DicSettings.setDiagnosticsEnabled(context, false)
    }

    @After
    fun tearDown() {
        SemperAnalytics.sink = SemperAnalytics.Sink { _, _, _ -> }
        DicSettings.setDiagnosticsEnabled(context, false)
    }

    @Test
    fun `drops events when diagnostics are off`() {
        SemperAnalytics.event(context, SemperAnalytics.SIGN_IN, mapOf("method" to "google"))
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `emits events when diagnostics are on`() {
        DicSettings.setDiagnosticsEnabled(context, true)
        SemperAnalytics.event(context, SemperAnalytics.SIGN_IN, mapOf("method" to "google"))
        assertEquals(1, recorded.size)
        assertEquals(SemperAnalytics.SIGN_IN, recorded[0].first)
        assertEquals("google", recorded[0].second["method"])
    }

    @Test
    fun `duration buckets are coarse`() {
        assertEquals("lt_1s", SemperAnalytics.durationBucket(500))
        assertEquals("1_5s", SemperAnalytics.durationBucket(3_000))
        assertEquals("5_30s", SemperAnalytics.durationBucket(12_000))
        assertEquals("gt_30s", SemperAnalytics.durationBucket(60_000))
    }

    @Test
    fun `frame count buckets are coarse`() {
        assertEquals("1", SemperAnalytics.frameCountBucket(1))
        assertEquals("2_5", SemperAnalytics.frameCountBucket(4))
        assertEquals("6_20", SemperAnalytics.frameCountBucket(10))
        assertEquals("gt_20", SemperAnalytics.frameCountBucket(50))
    }
}
