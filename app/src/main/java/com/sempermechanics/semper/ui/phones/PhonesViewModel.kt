package com.sempermechanics.semper.ui.phones

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.LicenseConfigWorker
import com.sempermechanics.semper.data.account.SeatLease
import com.sempermechanics.semper.data.account.entitlement.ConfigFetch
import com.sempermechanics.semper.data.account.phones.ActivateOutcome
import com.sempermechanics.semper.data.account.phones.DeactivateOutcome
import com.sempermechanics.semper.data.account.phones.DeviceActivation
import com.sempermechanics.semper.data.account.phones.PhonesOutcome
import com.sempermechanics.semper.data.net.AccountPhonesDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phones screen's list and its Activate and Deactivate taps, kept across
 * a rotation (ADR-016): a tap runs on [viewModelScope], and what it came to
 * is handed out once ([notes]) to whichever screen is current.
 */
@MainThread
class PhonesViewModel(app: Application) : AndroidViewModel(app) {

    /** What the screen shows. */
    sealed interface Screen {
        data object Loading : Screen

        /** The account's [phones]; [thisPhone] where this one stands; [pendingPrefix] a new licence waiting. */
        data class Loaded(
            val phones: AccountPhonesDto,
            val thisPhone: ThisPhone,
            val pendingPrefix: String?,
        ) : Screen

        /** The list could not be read; [note] says why, and the screen offers to try again. */
        data class Failed(val note: Note) : Screen
    }

    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** A tap is out: the buttons wait. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val noteChannel = Channel<Note>(Channel.UNLIMITED)

    /** Each tap's outcome once; buffered while no screen is there to take it. */
    val notes: Flow<Note> = noteChannel.receiveAsFlow()

    /** How the screen reaches the backend ([factory]). */
    private val phones: Phones = factory(app)

    /** Reads the list again. */
    fun load() {
        viewModelScope.launch { reload() }
    }

    /** Activate this phone, or with [upgrade] move to the new licence waiting and activate it there. */
    fun activate(upgrade: Boolean) = act {
        val outcome = phones.activate(upgrade)
        noteChannel.send(PhonesView.activated(outcome))
        if (outcome is ActivateOutcome.Activated || outcome is ActivateOutcome.LimitReached) reload()
    }

    /** Deactivate [deviceId] ([thisPhone] when it is this one). */
    fun deactivate(deviceId: String, thisPhone: Boolean) = act {
        val outcome = phones.deactivate(deviceId)
        // This phone is Demo now, not at its next refresh. The server has
        // said so; a fetch that failed is retried until it lands, or the
        // cached config kept this phone licensed until the next periodic one.
        if (thisPhone && outcome is DeactivateOutcome.Deactivated && phones.refreshConfig() == ConfigFetch.FAILED) {
            phones.queueRefresh()
        }
        noteChannel.send(PhonesView.deactivated(outcome))
        if (outcome is DeactivateOutcome.Deactivated || outcome is DeactivateOutcome.NotFound) reload()
    }

    /** One tap at a time. */
    private fun act(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun reload() {
        if (_screen.value !is Screen.Loaded) _screen.value = Screen.Loading
        _screen.value = when (val outcome = phones.list()) {
            is PhonesOutcome.Listed -> {
                val cached = phones.cachedState()
                val thisPhone = PhonesView.thisPhone(outcome.phones, cached)
                // The list is the server's word now: a cache that disagrees
                // (activated or deactivated elsewhere) is fetched again, so
                // every gate reads the same answer as this screen.
                if (PhonesView.thisPhone(cached) != thisPhone) phones.refreshConfig()
                Screen.Loaded(outcome.phones, thisPhone, phones.pendingPrefix())
            }
            PhonesOutcome.Offline -> Screen.Failed(Note(R.string.phones_offline))
            is PhonesOutcome.Refused -> Screen.Failed(Note(outcome.failure.userMessage(R.string.phones_load_failed)))
        }
    }

    override fun onCleared() {
        noteChannel.close()
        super.onCleared()
    }

    companion object {
        /** Builds each screen's [Phones]; tests stand in for it before the screen opens. */
        @VisibleForTesting
        internal var factory: (Application) -> Phones = { Phones.Live(it) }
    }

    /** The backend and the cached config, as the screen reaches them. */
    interface Phones {
        suspend fun list(): PhonesOutcome

        suspend fun activate(upgrade: Boolean): ActivateOutcome

        suspend fun deactivate(deviceId: String): DeactivateOutcome

        /** The cached `activation.state`. */
        suspend fun cachedState(): String

        /** The new licence's key prefix while one waits, else null. */
        suspend fun pendingPrefix(): String?

        /** Fetch `/v1/config` again; whether it answered, failed or was not made. */
        suspend fun refreshConfig(): ConfigFetch

        /** Queue the retrying `/v1/config` refresh ([LicenseConfigWorker.refreshNow]). */
        fun queueRefresh()

        class Live(private val app: Application) : Phones {
            override suspend fun list() = DeviceActivation.list(app)

            override suspend fun activate(upgrade: Boolean) = DeviceActivation.activate(app, upgrade)

            override suspend fun deactivate(deviceId: String) = DeviceActivation.deactivate(app, deviceId)

            override suspend fun cachedState() = withContext(Dispatchers.IO) { AppRemoteConfig.activation(app).state }

            override suspend fun pendingPrefix() =
                withContext(Dispatchers.IO) { AppRemoteConfig.pendingLicence(app)?.prefix }

            override suspend fun refreshConfig() = withContext(Dispatchers.IO) { SeatLease.fetchConfig(app) }

            override fun queueRefresh() = LicenseConfigWorker.refreshNow(app)
        }
    }
}
