package com.sempermechanics.semper.ui.phones

import androidx.annotation.StringRes
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.phones.ActivateOutcome
import com.sempermechanics.semper.data.account.phones.DeactivateOutcome
import com.sempermechanics.semper.data.net.AccountPhoneDto
import com.sempermechanics.semper.data.net.AccountPhonesDto
import com.sempermechanics.semper.data.net.ActivationDto
import com.sempermechanics.semper.ui.common.Note

/** Where this phone stands on the account's licence for this app. */
enum class ThisPhone(@StringRes val statusRes: Int) {
    ACTIVE(R.string.phones_this_active),
    AVAILABLE(R.string.phones_this_available),
    FULL(R.string.phones_this_full),
    FLOATING(R.string.phones_this_floating),
    NONE(R.string.phones_this_none),
    ;

    /** Activate is offered: a slot is free, or the holder may free one first. */
    val offersActivate: Boolean get() = this == AVAILABLE || this == FULL
}

/**
 * What the phones screen, Home's card and Settings say (ADR-033), kept apart
 * from the views so each rule is a JVM test.
 */
object PhonesView {

    /** The [AccountPhonesDto.model] of an account with no live licence (or a floating one). */
    private const val MODEL_NONE = "none"

    /**
     * This phone's place on the licence, from the [list] the backend just
     * answered, with the cached `activation.state` [cachedState] to tell a
     * floating licence (listed as no licence) from none.
     */
    fun thisPhone(list: AccountPhonesDto, cachedState: String): ThisPhone = when {
        list.model == MODEL_NONE || list.model.isBlank() || list.maxDevices <= 0 ->
            if (cachedState == ActivationDto.STATE_FLOATING) ThisPhone.FLOATING else ThisPhone.NONE
        list.devices.any { it.thisDevice && it.activated } -> ThisPhone.ACTIVE
        list.devicesActive >= list.maxDevices -> ThisPhone.FULL
        else -> ThisPhone.AVAILABLE
    }

    /** This phone's place from the cached `activation.state` alone, before any list. */
    fun thisPhone(cachedState: String): ThisPhone = when (cachedState) {
        ActivationDto.STATE_ACTIVE -> ThisPhone.ACTIVE
        ActivationDto.STATE_AVAILABLE -> ThisPhone.AVAILABLE
        ActivationDto.STATE_FULL -> ThisPhone.FULL
        ActivationDto.STATE_FLOATING -> ThisPhone.FLOATING
        else -> ThisPhone.NONE
    }

    /** The count line: the licence's own, or an institution's pool; null with no count. */
    fun count(active: Int, max: Int, pool: Boolean): Note? = when {
        max <= 0 -> null
        pool -> Note(R.plurals.phones_pool_fmt, listOf(active, max), quantity = max)
        else -> Note(R.plurals.phones_count_fmt, listOf(active, max), quantity = max)
    }

    /** A row's second line: whether the licence covers it, and whether it is this phone. */
    @StringRes
    fun rowStatus(phone: AccountPhoneDto): Int = when {
        phone.thisDevice && phone.activated -> R.string.phones_row_this_activated
        phone.thisDevice -> R.string.phones_row_this_not_activated
        phone.activated -> R.string.phones_row_activated
        else -> R.string.phones_row_not_activated
    }

    /** What an Activate tap says. */
    fun activated(outcome: ActivateOutcome): Note = when (outcome) {
        is ActivateOutcome.Activated ->
            Note(if (outcome.already) R.string.activate_already else R.string.activate_done)
        is ActivateOutcome.LimitReached -> Note(R.string.activate_full_fmt, listOf(outcome.active, outcome.max))
        ActivateOutcome.NoLicence -> Note(R.string.activate_no_licence)
        ActivateOutcome.Floating -> Note(R.string.activate_floating)
        ActivateOutcome.NoPendingUpgrade -> Note(R.string.activate_no_upgrade)
        ActivateOutcome.Offline -> Note(R.string.phones_offline)
        is ActivateOutcome.Refused -> Note(outcome.failure.userMessage(R.string.activate_failed))
    }

    /** What a Deactivate tap says. */
    fun deactivated(outcome: DeactivateOutcome): Note = when (outcome) {
        is DeactivateOutcome.Deactivated -> Note(R.string.deactivate_done)
        DeactivateOutcome.NotFound -> Note(R.string.deactivate_gone)
        DeactivateOutcome.Offline -> Note(R.string.phones_offline)
        is DeactivateOutcome.Refused -> Note(outcome.failure.userMessage(R.string.deactivate_failed))
    }
}
