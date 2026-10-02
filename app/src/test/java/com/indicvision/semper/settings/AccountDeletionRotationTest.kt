package com.indicvision.semper.settings

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.ui.common.AuthRoute
import com.indicvision.semper.ui.settings.AccountDeletionRun
import com.indicvision.semper.ui.settings.SettingsActivity
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog

/**
 * Account deletion must not be cancelled by a rotation. Settings has no
 * `configChanges`, so a rotation recreates it; the deletion used to run on
 * the old Activity's `lifecycleScope`, which a rotation cancelled half-way
 * (cloud erased, sign-in kept) and whose progress dialog leaked.
 */
@RunWith(RobolectricTestRunner::class)
class AccountDeletionRotationTest {

    private val gate = CompletableDeferred<CloudSync.AccountDeletion>()
    private var deletions = 0

    @Before
    fun setUp() {
        AccountDeletionRun.resetForTest()
        AccountDeletionRun.delete = { _, _ ->
            deletions++
            gate.await()
        }
    }

    /** Every Settings a test builds; destroyed after it, so none outlives it and reads the next test's run. */
    private val built = mutableListOf<ActivityController<SettingsActivity>>()

    private fun settings(): ActivityController<SettingsActivity> =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().also { built += it }

    @After
    fun tearDown() {
        built.forEach { runCatching { it.pause().stop().destroy() } }
        // Let a run still waiting on the gate finish, so no coroutine is left
        // suspended on the process-wide scope for the next test.
        gate.complete(CloudSync.AccountDeletion.CLOUD_UNREACHABLE)
        idle()
        AccountDeletionRun.resetForTest()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun signInComponent(activity: Activity) = AuthRoute.signInIntent(activity).component

    /** Confirms the delete dialog and answers the re-auth screen with OK, as a user would. */
    private fun confirmAndReauthenticate(activity: SettingsActivity) {
        activity.findViewById<View>(R.id.btnDeleteAccount).performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val reauth: Intent = shadowOf(activity).nextStartedActivityForResult.intent
        // The re-auth screen is also on the plain started list; drain it so the
        // tests below only see where the outcome sends the user.
        assertEquals(reauth.component, shadowOf(activity).nextStartedActivity.component)
        shadowOf(activity).receiveResult(reauth, Activity.RESULT_OK, null)
        idle()
    }

    @Test
    fun `a rotation mid-deletion neither cancels it nor strands the user`() {
        val controller = settings()
        confirmAndReauthenticate(controller.get())

        val firstDialog: Dialog = ShadowDialog.getLatestDialog()
        assertTrue("the progress dialog is up", firstDialog.isShowing)
        assertEquals(1, deletions)

        controller.recreate()
        idle()
        assertFalse("the old window's dialog went with it", firstDialog.isShowing)
        assertTrue("the new screen shows the same run", ShadowDialog.getLatestDialog().isShowing)

        gate.complete(CloudSync.AccountDeletion.DELETED)
        idle()

        val recreated = controller.get()
        val next = shadowOf(recreated).nextStartedActivity
        assertNotNull("a deleted account ends on sign-in", next)
        assertEquals(signInComponent(recreated), next.component)
        assertTrue(recreated.isFinishing)
        assertFalse(ShadowDialog.getLatestDialog().isShowing)
        assertEquals("one deletion, not a second after the rotation", 1, deletions)
        assertEquals(AccountDeletionRun.State.Idle, AccountDeletionRun.state.value)
    }

    @Test
    fun `an outcome that lands while Settings is stopped waits for it`() {
        val controller = settings()
        confirmAndReauthenticate(controller.get())

        controller.pause().stop()
        gate.complete(CloudSync.AccountDeletion.IDENTITY_KEPT)
        idle()
        assertNull("nothing routes from a stopped screen", shadowOf(controller.get()).nextStartedActivity)

        controller.restart().resume()
        idle()
        assertEquals(signInComponent(controller.get()), shadowOf(controller.get()).nextStartedActivity.component)
    }

    @Test
    fun `an unreachable cloud keeps the user here, told so`() {
        val controller = settings()
        confirmAndReauthenticate(controller.get())

        gate.complete(CloudSync.AccountDeletion.CLOUD_UNREACHABLE)
        idle()

        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertFalse(controller.get().isFinishing)
        assertFalse(ShadowDialog.getLatestDialog().isShowing)
    }

    @Test
    fun `a second start while one runs is refused`() {
        val activity = settings().get()

        assertTrue(AccountDeletionRun.start(activity))
        assertFalse(AccountDeletionRun.start(activity))
        idle()
        assertEquals(1, deletions)
    }

    @Test
    fun `a deletion that throws ends, keeps the user here, and can be retried`() {
        AccountDeletionRun.delete = { _, _ -> error("boom") }
        val controller = settings()

        assertTrue(AccountDeletionRun.start(controller.get()))
        idle()

        assertEquals("never stuck in Running", AccountDeletionRun.State.Idle, AccountDeletionRun.state.value)
        assertNull("told it failed, not routed", shadowOf(controller.get()).nextStartedActivity)
        assertFalse(ShadowDialog.getLatestDialog().isShowing)

        AccountDeletionRun.delete = { _, _ -> CloudSync.AccountDeletion.DELETED }
        assertTrue("a retry starts", AccountDeletionRun.start(controller.get()))
        idle()
        assertEquals(signInComponent(controller.get()), shadowOf(controller.get()).nextStartedActivity.component)
    }

    @Test
    fun `a throw with no screen to read it still reaches Done`() {
        AccountDeletionRun.delete = { _, _ -> throw IllegalStateException("boom") }
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()

        assertTrue(AccountDeletionRun.start(app))
        idle()

        assertEquals(
            AccountDeletionRun.State.Done(AccountDeletionRun.Outcome.CLOUD_NOT_REACHED),
            AccountDeletionRun.state.value,
        )
    }
}
