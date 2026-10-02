package com.indicvision.semper.settings

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import com.indicvision.semper.cloud.FakeCloudApi
import com.indicvision.semper.cloud.FakeTokens
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.fixtures.idleUntil
import com.indicvision.semper.ui.common.AuthRoute
import com.indicvision.semper.ui.settings.AccountDeletionRun
import com.indicvision.semper.ui.settings.AccountDeletionRun.Outcome
import com.indicvision.semper.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.IOException

/**
 * What the user is told when an account deletion fails depends on how far it
 * got. A throw used to read as "nothing was deleted" even after the cloud
 * erase had gone through.
 */
@RunWith(RobolectricTestRunner::class)
class AccountDeletionStagesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private var signOuts = 0

    @Before
    fun setUp() {
        AccountDeletionRun.resetForTest()
        AccountDeletionRun.cloudApi = { api }
        AccountDeletionRun.signOut = { signOuts++ }
    }

    private val built = mutableListOf<ActivityController<SettingsActivity>>()

    @After
    fun tearDown() {
        built.forEach { runCatching { it.pause().stop().destroy() } }
        idle()
        AccountDeletionRun.resetForTest()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Runs a deletion that does [steps] with the backend the run hands
     * [CloudSync.deleteAccount], and returns where it ended.
     */
    private fun outcomeOf(steps: suspend (CloudApi) -> CloudSync.AccountDeletion): Outcome? {
        AccountDeletionRun.delete = { _, cloud -> steps(cloud) }
        assertTrue(AccountDeletionRun.start(app))
        idle()
        return (AccountDeletionRun.state.value as? AccountDeletionRun.State.Done)?.outcome
    }

    @Test
    fun `a throw after the cloud erase says the phone copy remains`() {
        api.onDeleteAccount = {}

        val outcome = outcomeOf { cloud ->
            cloud.deleteAccount("token")
            throw IOException("could not delete the session index")
        }

        assertEquals(Outcome.PHONE_NOT_CLEARED, outcome)
        assertEquals(listOf("deleteAccount"), api.calls)
        assertEquals("the sign-out the throw skipped still runs", 1, signOuts)
    }

    @Test
    fun `a sign-out that also fails after the erase leaves the outcome as it was`() {
        api.onDeleteAccount = {}
        AccountDeletionRun.signOut = {
            signOuts++
            throw IOException("seat release failed")
        }

        val outcome = outcomeOf { cloud ->
            cloud.deleteAccount("token")
            throw IOException("could not delete the session index")
        }

        assertEquals(Outcome.PHONE_NOT_CLEARED, outcome)
        assertEquals(1, signOuts)
    }

    @Test
    fun `nothing is signed out when the cloud was never reached or the deletion finished`() {
        assertEquals(Outcome.CLOUD_NOT_REACHED, outcomeOf { error("no token") })
        AccountDeletionRun.consume()
        assertEquals(Outcome.DELETED, outcomeOf { CloudSync.AccountDeletion.DELETED })

        assertEquals(0, signOuts)
    }

    /** [inner], counting reads of [enabled] so a test can see the run's wrapper forwards it. */
    private class CountingApi(private val inner: FakeCloudApi) : CloudApi by inner {
        var enabledReads = 0

        override val enabled: Boolean
            get() {
                enabledReads++
                return inner.enabled
            }
    }

    @Test
    fun `the real deleteAccount runs through the run's wrapper unchanged`() {
        val counting = CountingApi(api)
        AccountDeletionRun.cloudApi = { counting }
        api.onDeleteAccount = { token -> assertEquals("tok", token) }
        // The real sequence, on the backend the run hands it; only the token source is faked.
        AccountDeletionRun.delete = { context, cloud -> CloudSync.deleteAccount(context, cloud, FakeTokens("tok")) }

        assertTrue(AccountDeletionRun.start(app))
        idleUntil("the deletion to end") { AccountDeletionRun.state.value is AccountDeletionRun.State.Done }

        assertTrue("enabled is read through the wrapper", counting.enabledReads > 0)
        assertEquals("the erase reaches the backend once", 1, api.calls.count { it == "deleteAccount" })
        val outcome = (AccountDeletionRun.state.value as AccountDeletionRun.State.Done).outcome
        assertTrue(
            "the erase answered, so the run never says nothing was touched: $outcome",
            outcome != Outcome.CLOUD_NOT_REACHED,
        )
    }

    @Test
    fun `a throw before the cloud erase answered says nothing was touched`() {
        assertEquals(Outcome.CLOUD_NOT_REACHED, outcomeOf { error("no token") })
    }

    @Test
    fun `an erase that fails says nothing was touched`() {
        api.onDeleteAccount = { throw IOException("offline") }

        val outcome = outcomeOf { cloud ->
            cloud.deleteAccount("token")
            CloudSync.AccountDeletion.DELETED
        }

        assertEquals(Outcome.CLOUD_NOT_REACHED, outcome)
    }

    @Test
    fun `what the deletion returns is told as it was`() {
        api.onDeleteAccount = {}
        val returned = mapOf(
            CloudSync.AccountDeletion.DELETED to Outcome.DELETED,
            CloudSync.AccountDeletion.IDENTITY_KEPT to Outcome.IDENTITY_KEPT,
            CloudSync.AccountDeletion.CLOUD_UNREACHABLE to Outcome.CLOUD_NOT_REACHED,
        )
        for ((deletion, told) in returned) {
            assertEquals(told, outcomeOf { deletion })
            AccountDeletionRun.consume()
        }
    }

    @Test
    fun `Settings tells an erased account with a phone copy left, and leaves for sign-in`() {
        api.onDeleteAccount = {}
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup().also { built += it }
        val settings = controller.get()
        AccountDeletionRun.delete = { _, cloud ->
            cloud.deleteAccount("token")
            throw IOException("could not delete the session index")
        }

        assertTrue(AccountDeletionRun.start(settings))
        idle()

        val pill = settings.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>(CRISP_TAG)
        assertEquals(
            settings.getString(R.string.delete_account_phone_not_cleared),
            pill.findViewById<TextView>(R.id.tvToast).text.toString(),
        )
        assertEquals(AuthRoute.signInIntent(settings).component, shadowOf(settings).nextStartedActivity.component)
        assertTrue(settings.isFinishing)
    }

    private companion object {
        const val CRISP_TAG = "semper_crisp_toast"
    }
}
