package com.sempermechanics.semper.ui.phones

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.entitlement.ConfigFetch
import com.sempermechanics.semper.data.account.phones.ActivateOutcome
import com.sempermechanics.semper.data.account.phones.DeactivateOutcome
import com.sempermechanics.semper.data.account.phones.PhonesOutcome
import com.sempermechanics.semper.data.net.AccountPhoneDto
import com.sempermechanics.semper.data.net.AccountPhonesDto
import com.sempermechanics.semper.data.net.ActivationDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * Your phones ([PhonesActivity], ADR-033) over a stand-in backend: the count,
 * this phone's place, Activate (from a tap or the opening intent), and
 * Deactivate behind a question that survives a rotation.
 */
@RunWith(RobolectricTestRunner::class)
class PhonesActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<ActivityController<PhonesActivity>>()

    /** The stand-in backend; [phones] is what the next list answers. */
    private inner class Fake : PhonesViewModel.Phones {
        val calls = mutableListOf<String>()
        var phones = listOf(here(activated = false), other(activated = true))
        var active = 1
        var max = 2
        var state = ActivationDto.STATE_AVAILABLE
        var pending: String? = null
        var fetch = ConfigFetch.ANSWERED

        override suspend fun list(): PhonesOutcome {
            calls += "list"
            return PhonesOutcome.Listed(AccountPhonesDto("lic-1", active, max, model = "activation", devices = phones))
        }

        override suspend fun activate(upgrade: Boolean): ActivateOutcome {
            calls += if (upgrade) "activate(upgrade)" else "activate"
            phones = listOf(here(activated = true), other(activated = true))
            active = 2
            state = ActivationDto.STATE_ACTIVE
            return ActivateOutcome.Activated(already = false, devicesActive = 2, maxDevices = 2)
        }

        override suspend fun deactivate(deviceId: String): DeactivateOutcome {
            calls += "deactivate($deviceId)"
            return DeactivateOutcome.Deactivated(devicesActive = 0, maxDevices = 2)
        }

        override suspend fun cachedState() = state

        override suspend fun pendingPrefix() = pending

        override suspend fun refreshConfig(): ConfigFetch {
            calls += "refreshConfig"
            return fetch
        }

        override fun queueRefresh() {
            calls += "queueRefresh"
        }
    }

    private val fake = Fake()

    private fun here(activated: Boolean) =
        AccountPhoneDto("and-here", "Pixel 5", activated = activated, thisDevice = true)

    private fun other(activated: Boolean) = AccountPhoneDto("and-other", "Galaxy S22+", activated = activated)

    @Before
    fun standIn() {
        PhonesViewModel.factory = { fake }
    }

    @After
    fun tearDown() {
        screens.forEach { runCatching { it.pause().stop().destroy() } }
        PhonesViewModel.factory = { PhonesViewModel.Phones.Live(it) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun screen(intent: Intent = Intent(context, PhonesActivity::class.java)) =
        Robolectric.buildActivity(PhonesActivity::class.java, intent).setup().also {
            screens += it
            idle()
        }

    private fun PhonesActivity.text(id: Int) = findViewById<TextView>(id).text.toString()

    private fun PhonesActivity.row(index: Int): View {
        val list = findViewById<RecyclerView>(R.id.rvPhones)
        list.measure(0, 0)
        list.layout(0, 0, 1000, 2000)
        return list.getChildAt(index)
    }

    @Test
    fun `the screen shows the count, this phone's place, and each phone`() {
        val activity = screen().get()

        assertEquals("Licensed on 1 of 2 phones", activity.text(R.id.tvPhonesCount))
        assertEquals(activity.getString(R.string.phones_this_available), activity.text(R.id.tvPhonesThis))
        assertTrue(activity.findViewById<View>(R.id.btnPhonesActivate).isShown)
        val first = activity.row(0)
        assertEquals("Pixel 5", first.findViewById<TextView>(R.id.tvPhoneModel).text.toString())
        assertFalse(first.findViewById<View>(R.id.btnPhoneDeactivate).isShown)
        assertTrue(activity.row(1).findViewById<View>(R.id.btnPhoneDeactivate).isShown)
    }

    @Test
    fun `Activate says so, reads the list again, and the button goes`() {
        val activity = screen().get()

        activity.findViewById<View>(R.id.btnPhonesActivate).performClick()
        idle()

        assertTrue("activate" in fake.calls)
        assertEquals(activity.getString(R.string.activate_done), ShadowToast.getTextOfLatestToast())
        assertEquals("Licensed on 2 of 2 phones", activity.text(R.id.tvPhonesCount))
        assertEquals(activity.getString(R.string.phones_this_active), activity.text(R.id.tvPhonesThis))
        assertFalse(activity.findViewById<View>(R.id.btnPhonesActivate).isShown)
    }

    @Test
    fun `opened to activate, it taps Activate once, not again after a rotation`() {
        fake.pending = "SEM-AB"
        val controller = screen(
            Intent(context, PhonesActivity::class.java).putExtra(ACTIVATE, true).putExtra(UPGRADE, true),
        )

        controller.recreate()
        idle()

        assertEquals(listOf("activate(upgrade)"), fake.calls.filter { it.startsWith("activate") })
    }

    @Test
    fun `a new licence waiting is named, and Activate moves to it`() {
        fake.pending = "SEM-AB"
        val activity = screen().get()

        assertEquals(activity.getString(R.string.phones_upgrade_ready_fmt, "SEM-AB"), activity.text(R.id.tvPhonesThis))
        assertEquals(activity.getString(R.string.phones_activate_upgrade), activity.text(R.id.btnPhonesActivate))
        activity.findViewById<View>(R.id.btnPhonesActivate).performClick()
        idle()
        assertTrue("activate(upgrade)" in fake.calls)
    }

    @Test
    fun `Deactivate asks first, the question survives a rotation, and Deactivate frees the phone`() {
        val controller = screen()
        controller.get().row(1).findViewById<View>(R.id.btnPhoneDeactivate).performClick()
        idle()
        assertNotNull(ShadowDialog.getLatestDialog())

        val rebuilt = controller.recreate().get()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        assertTrue("deactivate(and-other)" in fake.calls)
        assertFalse("refreshConfig" in fake.calls)
        assertEquals(rebuilt.getString(R.string.deactivate_done), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `deactivating this phone fetches the config again, so it is Demo now`() {
        fake.phones = listOf(here(activated = true), other(activated = true))
        fake.state = ActivationDto.STATE_ACTIVE
        val activity = screen().get()
        activity.row(0).findViewById<View>(R.id.btnPhoneDeactivate).performClick()
        idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        assertEquals(listOf("deactivate(and-here)", "refreshConfig"), fake.calls.filter { it != "list" })
    }

    @Test
    fun `deactivating this phone while the config fetch fails queues the retrying refresh`() {
        fake.phones = listOf(here(activated = true), other(activated = true))
        fake.state = ActivationDto.STATE_ACTIVE
        fake.fetch = ConfigFetch.FAILED
        val activity = screen().get()
        activity.row(0).findViewById<View>(R.id.btnPhoneDeactivate).performClick()
        idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        // The server has deactivated it: a refresh that did not land must not
        // leave the cached config licensed until the next periodic one.
        assertEquals(
            listOf("deactivate(and-here)", "refreshConfig", "queueRefresh"),
            fake.calls.filter { it != "list" },
        )
    }

    @Test
    fun `a cache that disagrees with the list is fetched again`() {
        fake.state = ActivationDto.STATE_ACTIVE
        screen()
        assertTrue("refreshConfig" in fake.calls)
    }

    private companion object {
        const val ACTIVATE = "com.sempermechanics.semper.extra.ACTIVATE"
        const val UPGRADE = "com.sempermechanics.semper.extra.ACTIVATE_UPGRADE"
    }
}
