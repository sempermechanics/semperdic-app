package com.sempermechanics.semper.auth

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.DeviceKeyManager
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.ui.auth.PendingApprovalActivity
import com.sempermechanics.semper.ui.common.auth.SupportMail
import com.sempermechanics.semper.ui.limit.SessionLimitActivity
import com.sempermechanics.semper.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The support mails Pending ("request access") and Session limit ("raise my
 * limit") send, byte for byte: support triages by these lines, so moving the
 * shared block into `SupportMail.contextLines` must not change one of them.
 */
@RunWith(RobolectricTestRunner::class)
class SupportMailBodiesTest {

    private val built = mutableListOf<ActivityController<out Activity>>()

    @After
    fun tearDown() = built.forEach { runCatching { it.pause().stop().destroy() } }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val unknown = context.getString(R.string.pending_unknown_account)
    private val deviceId get() = DeviceKeyManager.deviceId(context)

    /** Pending reads the signed-in address through Firebase Auth, which needs an app; nobody is signed in. */
    @Before
    fun firebase() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApplicationId("1:1:android:1")
                .setApiKey("test-api-key")
                .setProjectId("test-project")
                .build()
            FirebaseApp.initializeApp(context, options)
        }
    }

    private fun <A : Activity> mailFrom(type: Class<A>, button: Int): Intent {
        val controller = Robolectric.buildActivity(type).setup().also { built += it }
        val activity = controller.get()
        activity.findViewById<View>(button).performClick()
        return shadowOf(activity).nextStartedActivity
    }

    @Test
    fun `session limit mail names the account, its quota, then the device`() {
        val mail = mailFrom(SessionLimitActivity::class.java, R.id.btnEmailSupport)

        val quota = "${TokenStore.quotaUsed(context)}/${TokenStore.quotaMax(context)}"
        assertEquals(
            "I've reached my Semper analysis limit and would like it raised.\n\n" +
                "Account: $unknown\n" +
                "Quota: $quota\n" +
                "Device ID: $deviceId\n" +
                SupportMail.deviceLines(),
            mail.getStringExtra(Intent.EXTRA_TEXT),
        )
        assertEquals(
            context.getString(R.string.limit_subject) + " — " + unknown,
            mail.getStringExtra(Intent.EXTRA_SUBJECT),
        )
    }

    @Test
    fun `settings support mail leaves room to write above the account and device`() {
        val mail = mailFrom(SettingsActivity::class.java, R.id.btnEmailSupport)

        assertEquals(
            "\n\n---\n" +
                "Account: $unknown\n" +
                "Device ID: $deviceId\n" +
                SupportMail.deviceLines(),
            mail.getStringExtra(Intent.EXTRA_TEXT),
        )
    }

    @Test
    fun `access request mail names the account, then the device`() {
        val mail = mailFrom(PendingApprovalActivity::class.java, R.id.btnRequestAccess)

        assertEquals(
            "I'd like access to Semper.\n\n" +
                "Account: $unknown\n" +
                "Device ID: $deviceId\n" +
                SupportMail.deviceLines(),
            mail.getStringExtra(Intent.EXTRA_TEXT),
        )
        assertEquals(
            context.getString(R.string.request_access_subject) + " — " + unknown,
            mail.getStringExtra(Intent.EXTRA_SUBJECT),
        )
    }
}
