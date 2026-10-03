package com.sempermechanics.semper

import android.app.Application
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.sempermechanics.semper.data.account.DevAuth
import com.sempermechanics.semper.data.account.SeatHeartbeat
import com.sempermechanics.semper.data.prefs.DicSettings
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.data.session.StorageBudget
import com.sempermechanics.semper.diagnostics.CrashReportingTree
import com.sempermechanics.semper.diagnostics.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Plants the logging tree: verbose [Timber.DebugTree] in debug builds, and a
 * [CrashReportingTree] (Firebase Crashlytics) in release so field failures — and
 * the WARN breadcrumbs from the quota/upload gates — are no longer invisible.
 */
class SemperApp : Application() {

    /** Outlives every screen; only startup housekeeping runs here. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(CrashReportingTree(this))
        }
        installAppCheck()
        DicSettings.migrate(this)
        // Manifest disables Crashlytics/Analytics collection, so a fresh install
        // sends nothing until the user opts in. This re-applies their choice on
        // every launch — including turning collection back OFF after a withdrawal.
        Diagnostics.apply(this)

        // Startup is the one moment nothing is in flight, so it is where cache
        // leftovers can be reclaimed without racing an import or a share.
        appScope.launch {
            CacheJanitor.sweepOnStartup(this@SemperApp)
            StorageBudget.enforce(this@SemperApp)
        }
        // Floating-seat renew while this process is up. The slower background
        // config refresh is scheduled after a successful sign-in — WorkManager
        // is not always ready during Application.onCreate in unit tests.
        SeatHeartbeat.start(appScope, this)
    }

    /**
     * Lets the backend tell this binary apart from anything else holding a
     * valid sign-in. Attestation is an assertion the *server* checks, so a
     * failure here is not fatal: [com.sempermechanics.semper.data.net.AppCheckHeader]
     * omits the header and the request goes out on its other credentials, which
     * is what `APP_CHECK_MODE=off` and `monitor` exist to absorb while a fleet
     * that carries tokens is still rolling out.
     *
     * Skipped when there is no backend to attest to — an offline build, or the
     * emulator sign-in bypass — so neither pays for a Play Integrity handshake
     * nothing will read. See [wantsAppCheck].
     */
    private fun installAppCheck() {
        if (!wantsAppCheck(BuildConfig.SEMPER_API_BASE_URL, DevAuth.active)) return
        runCatching {
            FirebaseAppCheck.getInstance()
                .installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }.onFailure { Timber.w(it, "App Check provider unavailable") }
    }
}

/**
 * Whether this run should attest. Keyed on the bypass being *active*, not on
 * `BuildConfig.DEV_AUTH_BYPASS`: that flag is on in every debug build unless
 * local.properties turns it off, so testing it skipped App Check on real
 * phones too, where the bypass never applies and the app talks to the backend
 * like a release build.
 */
internal fun wantsAppCheck(apiBaseUrl: String, devAuthActive: Boolean): Boolean =
    apiBaseUrl.isNotBlank() && !devAuthActive
