package com.sempermechanics.semper.ui.common

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Plurals whose "one" form once hard-coded its numbers: one analysis done
 * against a cap of 999 read "1 / 1 analyses used", as though the cap were hit,
 * and a one-combination sweep read "subset 1–1 px".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QuantityCopyTest {

    private val res = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun `the home chip shows the real cap when one analysis is used`() {
        assertEquals("1 / 999 analyses used", res.getQuantityString(R.plurals.home_quota_fmt, 1, 1, 999))
        assertEquals("2 / 999 analyses used", res.getQuantityString(R.plurals.home_quota_fmt, 2, 2, 999))
    }

    @Test
    fun `the limit screen shows the real cap when one analysis is used`() {
        assertEquals("Using 1 of 25 analyses", res.getQuantityString(R.plurals.limit_quota_fmt, 1, 1, 25))
    }

    @Test
    fun `cloud prompts agree in number`() {
        assertEquals(
            "1 analysis on this phone is not in the cloud. Upload it now?",
            res.getQuantityString(R.plurals.cloud_backfill_body, 1, 1),
        )
        assertEquals(
            "3 analysis backups are missing from the cloud — re-uploading.",
            res.getQuantityString(R.plurals.cloud_resync_fmt, 3, 3),
        )
    }

    @Test
    fun `a one-combination sweep names its real ranges`() {
        assertEquals(
            "1 analysis · subset 21–21 px · window 5–5 points",
            res.getQuantityString(R.plurals.sweep_plan_grid_fmt, 1, 1, 21, 21, 5, 5),
        )
        assertEquals(
            "1 combination · 0 solved · 1 skipped · step subset÷3",
            res.getQuantityString(R.plurals.vsg_lattice_summary_fmt, 1, 1, 0, 1, 3),
        )
    }

    /**
     * A "one" form may write the count the quantity is chosen by as a word or a
     * literal ("tomorrow", "1 analysis"), but every other argument the "other"
     * form formats it must format too.
     */
    @Test
    fun `no one form hard-codes an argument the other form formats`() {
        val xml = File("src/main/res/values/strings.xml").readText()
        val args = { text: String -> Regex("%(\\d)\\$").findAll(text).map { it.groupValues[1] }.toSet() }
        val offenders = Regex("<plurals name=\"([^\"]+)\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .mapNotNull { plural ->
                val items = Regex("<item quantity=\"(\\w+)\">(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(plural.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
                val one = items["one"] ?: return@mapNotNull null
                val missing = args(items["other"].orEmpty()) - args(one)
                plural.groupValues[1].takeIf { missing.size > 1 }
            }
            .toList()
        assertTrue("one forms missing arguments: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the PDF share row uses the same separator as its neighbours`() {
        assertEquals(
            "fields, stats, telemetry · All 4 frames",
            res.getQuantityString(R.plurals.share_pdf_sub_fmt, 4, 4),
        )
    }
}
