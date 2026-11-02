package com.sempermechanics.semper.ui.phones

import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.phones.ActivateOutcome
import com.sempermechanics.semper.data.account.phones.DeactivateOutcome
import com.sempermechanics.semper.data.net.AccountPhoneDto
import com.sempermechanics.semper.data.net.AccountPhonesDto
import com.sempermechanics.semper.data.net.ActivationDto
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.ui.common.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** What the phones screen, Home's card and Settings say ([PhonesView], ADR-033). */
class PhonesViewTest {

    private fun phone(id: String, activated: Boolean = false, here: Boolean = false) =
        AccountPhoneDto(id, "Pixel", activated = activated, thisDevice = here)

    private fun list(active: Int, max: Int, vararg phones: AccountPhoneDto, model: String = "activation") =
        AccountPhonesDto("lic-1", active, max, model = model, devices = phones.toList())

    @Test
    fun `this phone is active when the list says so, whatever the count`() {
        val full = list(2, 2, phone("a", activated = true, here = true), phone("b", activated = true))
        assertEquals(ThisPhone.ACTIVE, PhonesView.thisPhone(full, ActivationDto.STATE_FULL))
    }

    @Test
    fun `a phone off the licence may activate while a slot is free, else the licence is full`() {
        assertEquals(
            ThisPhone.AVAILABLE,
            PhonesView.thisPhone(list(1, 2, phone("a", here = true), phone("b", activated = true)), ""),
        )
        assertEquals(
            ThisPhone.FULL,
            PhonesView.thisPhone(list(1, 1, phone("a", here = true), phone("b", activated = true)), ""),
        )
        // A licence from before the split counts one phone too.
        assertEquals(ThisPhone.FULL, PhonesView.thisPhone(list(1, 1, phone("a", here = true), model = "lock"), ""))
    }

    @Test
    fun `no licence is none, or floating when the config says the seat is shared`() {
        val none = list(0, 0, phone("a", here = true), model = "none")
        assertEquals(ThisPhone.NONE, PhonesView.thisPhone(none, ActivationDto.STATE_NONE))
        assertEquals(ThisPhone.FLOATING, PhonesView.thisPhone(none, ActivationDto.STATE_FLOATING))
        assertEquals(ThisPhone.NONE, PhonesView.thisPhone(list(0, 0, model = ""), ""))
    }

    @Test
    fun `the cached state alone maps each wire value`() {
        assertEquals(ThisPhone.ACTIVE, PhonesView.thisPhone(ActivationDto.STATE_ACTIVE))
        assertEquals(ThisPhone.AVAILABLE, PhonesView.thisPhone(ActivationDto.STATE_AVAILABLE))
        assertEquals(ThisPhone.FULL, PhonesView.thisPhone(ActivationDto.STATE_FULL))
        assertEquals(ThisPhone.FLOATING, PhonesView.thisPhone(ActivationDto.STATE_FLOATING))
        assertEquals(ThisPhone.NONE, PhonesView.thisPhone("something new"))
    }

    @Test
    fun `only a phone that may take or free a slot offers Activate`() {
        assertTrue(ThisPhone.AVAILABLE.offersActivate)
        assertTrue(ThisPhone.FULL.offersActivate)
        assertFalse(ThisPhone.ACTIVE.offersActivate)
        assertFalse(ThisPhone.FLOATING.offersActivate)
        assertFalse(ThisPhone.NONE.offersActivate)
    }

    @Test
    fun `the count line is the licence's, an institution's pool, or none`() {
        assertEquals(Note(R.plurals.phones_count_fmt, listOf(1, 2), quantity = 2), PhonesView.count(1, 2, pool = false))
        assertEquals(Note(R.plurals.phones_pool_fmt, listOf(3, 5), quantity = 5), PhonesView.count(3, 5, pool = true))
        assertNull(PhonesView.count(0, 0, pool = false))
    }

    @Test
    fun `each row says whether the licence covers it and which is this phone`() {
        assertEquals(R.string.phones_row_this_activated, PhonesView.rowStatus(phone("a", true, here = true)))
        assertEquals(R.string.phones_row_this_not_activated, PhonesView.rowStatus(phone("a", here = true)))
        assertEquals(R.string.phones_row_activated, PhonesView.rowStatus(phone("a", activated = true)))
        assertEquals(R.string.phones_row_not_activated, PhonesView.rowStatus(phone("a")))
    }

    @Test
    fun `each Activate outcome has its line`() {
        assertEquals(Note(R.string.activate_done), PhonesView.activated(ActivateOutcome.Activated(false, 1, 2)))
        assertEquals(Note(R.string.activate_already), PhonesView.activated(ActivateOutcome.Activated(true, 1, 2)))
        assertEquals(
            Note(R.string.activate_full_fmt, listOf(2, 2)),
            PhonesView.activated(ActivateOutcome.LimitReached(2, 2)),
        )
        assertEquals(Note(R.string.activate_no_licence), PhonesView.activated(ActivateOutcome.NoLicence))
        assertEquals(Note(R.string.activate_floating), PhonesView.activated(ActivateOutcome.Floating))
        assertEquals(Note(R.string.activate_no_upgrade), PhonesView.activated(ActivateOutcome.NoPendingUpgrade))
        assertEquals(Note(R.string.phones_offline), PhonesView.activated(ActivateOutcome.Offline))
        val refused = ActivateOutcome.Refused(HttpFailure.classify(ApiException(400, "{}")))
        assertEquals(Note(R.string.activate_failed), PhonesView.activated(refused))
        val down = ActivateOutcome.Refused(HttpFailure.classify(ApiException(503, "{}")))
        assertEquals(Note(R.string.error_cloud_unavailable), PhonesView.activated(down))
    }

    @Test
    fun `each Deactivate outcome has its line`() {
        assertEquals(Note(R.string.deactivate_done), PhonesView.deactivated(DeactivateOutcome.Deactivated(0, 1)))
        assertEquals(Note(R.string.deactivate_gone), PhonesView.deactivated(DeactivateOutcome.NotFound))
        assertEquals(Note(R.string.phones_offline), PhonesView.deactivated(DeactivateOutcome.Offline))
        val refused = DeactivateOutcome.Refused(HttpFailure.classify(IllegalStateException("x")))
        assertEquals(Note(R.string.deactivate_failed), PhonesView.deactivated(refused))
        val offline = DeactivateOutcome.Refused(HttpFailure.classify(IOException("x")))
        assertEquals(Note(R.string.error_offline), PhonesView.deactivated(offline))
    }
}
