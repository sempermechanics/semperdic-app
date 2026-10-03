package com.sempermechanics.semper.ui.common.auth

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The context block support mails end with, line for line as each screen built it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SupportMailContextTest {

    @Test
    fun `Pending's and Settings' block`() {
        // PendingApprovalActivity.requestAccessByEmail / SettingsHelpSupportSection.emailSupport.
        val byHand = buildString {
            append("Account: ").append("a@b.c").append('\n')
            append("Device ID: ").append("dev-123").append('\n')
            append(SupportMail.deviceLines())
        }
        assertEquals(byHand, SupportMail.contextLines("a@b.c", "dev-123"))
    }

    @Test
    fun `Session limit's block, with its quota line`() {
        // SessionLimitActivity.emailSupport.
        val byHand = buildString {
            append("Account: ").append("(unknown account)").append('\n')
            append("Quota: ").append(25).append('/').append(25).append('\n')
            append("Device ID: ").append("dev-9").append('\n')
            append(SupportMail.deviceLines())
        }
        assertEquals(byHand, SupportMail.contextLines("(unknown account)", "dev-9", listOf("Quota: 25/25")))
    }
}
