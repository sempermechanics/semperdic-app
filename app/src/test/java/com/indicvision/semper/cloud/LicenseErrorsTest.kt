package com.indicvision.semper.cloud

import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.account.LicenseErrors
import com.indicvision.semper.data.net.ApiErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LicenseErrorsTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `device mismatch maps to the restore-before-bind message`() {
        val msg = LicenseErrors.restoreMessage(
            ctx,
            """{"detail":"${ApiErrors.LICENSE_DEVICE_MISMATCH}"}""",
        )
        assertTrue(msg.contains("bound"))
        assertFalse(msg.contains(ApiErrors.LICENSE_DEVICE_MISMATCH))
    }

    @Test
    fun `demo feature refusal maps to the demo-mode restore message`() {
        val msg = LicenseErrors.restoreMessage(ctx, ApiErrors.FEATURE_NOT_LICENSED)
        assertEquals(ctx.getString(com.indicvision.semper.R.string.restore_not_licensed), msg)
        assertTrue(msg.contains("demo"))
    }

    @Test
    fun `demo feature refusal with the backend's readable suffix still maps by code`() {
        // The backend sends "feature_not_licensed: <sentence>" so pre-licensing
        // builds, which print the raw detail, show something readable.
        val body = """{"detail":"${ApiErrors.FEATURE_NOT_LICENSED}: Restore isn't available in demo mode."}"""
        val msg = LicenseErrors.restoreMessage(ctx, body)
        assertEquals(ctx.getString(com.indicvision.semper.R.string.restore_not_licensed), msg)
    }

    @Test
    fun `refused bundle download says demo mode, anything else keeps the connection hint`() {
        val refused = LicenseErrors.downloadMessage(
            ctx,
            """{"detail":"${ApiErrors.FEATURE_NOT_LICENSED}: Restore isn't available in demo mode."}""",
        )
        assertEquals(ctx.getString(com.indicvision.semper.R.string.download_not_licensed), refused)
        assertEquals(
            ctx.getString(com.indicvision.semper.R.string.download_analysis_failed),
            LicenseErrors.downloadMessage(ctx, "HTTP 502: upstream request timeout"),
        )
        assertEquals(
            ctx.getString(com.indicvision.semper.R.string.download_analysis_failed),
            LicenseErrors.downloadMessage(ctx, null),
        )
    }

    @Test
    fun `a backup gone from Drive says so on restore and on download`() {
        val body = """{"detail":"${ApiErrors.DRIVE_FILE_GONE}"}"""
        val gone = ctx.getString(com.indicvision.semper.R.string.restore_backup_gone)
        assertEquals(gone, LicenseErrors.restoreMessage(ctx, body))
        assertEquals(gone, LicenseErrors.downloadMessage(ctx, body))
    }

    @Test
    fun `unknown detail stays formatted rather than blank`() {
        val msg = LicenseErrors.restoreMessage(ctx, "something_else")
        assertEquals(
            ctx.getString(com.indicvision.semper.R.string.restore_failed_fmt, "something_else"),
            msg,
        )
    }
}
