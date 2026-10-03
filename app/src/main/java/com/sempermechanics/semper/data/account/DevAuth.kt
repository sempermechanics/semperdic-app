package com.sempermechanics.semper.data.account

import android.content.Context
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.prefs.DicSettings
import timber.log.Timber

/**
 * Debug-only sign-in bypass for emulator runs.
 *
 * Working on the UI in an emulator otherwise means a real Google/Firebase
 * sign-in against an approved backend account — awkward on a machine with no
 * Play Services account, and pointless for screens that never touch the cloud.
 * When [active], the app boots straight to Home as a local-only dev account.
 *
 * Three independent conditions must all hold, so this can never reach a user:
 *  1. `BuildConfig.DEBUG` — release builds are compiled without it,
 *  2. `BuildConfig.DEV_AUTH_BYPASS` — hardcoded false for the release build
 *     type, and switchable off with `SEMPER_DEV_AUTH_BYPASS=false` in
 *     local.properties to test the real sign-in flow on an emulator,
 *  3. the app is running on an emulator, not a physical device.
 *
 * While it is active, [com.sempermechanics.semper.data.net.SemperApi.enabled] reports
 * the cloud as disabled: with no Firebase user there is no ID token, so every
 * backend call would fail anyway (and the upload worker would retry forever).
 * The bypass therefore gives a purely offline app — cloud backup, restore and
 * account screens behave exactly as they do with no SEMPER_API_BASE_URL set.
 */
object DevAuth {

    /** Identity seeded for the bypassed session; visible in the account sheet. */
    const val DEV_EMAIL = "dev@emulator.local"
    private const val DEV_UID = "dev-emulator"

    /** Session quota for the dev account — high enough never to gate local work. */
    private const val DEV_QUOTA_MAX = 999

    /** True when sign-in should be skipped for this run. */
    val active: Boolean by lazy {
        BuildConfig.DEBUG && BuildConfig.DEV_AUTH_BYPASS && DeviceEnv.isEmulator()
    }

    /**
     * Seeds the local session cache so the Home screen, account sheet and quota
     * gate have something coherent to show. No-op unless the bypass is [active].
     */
    fun install(context: Context) {
        if (!active) return
        TokenStore.saveIdentity(context, DEV_UID, DEV_EMAIL)
        TokenStore.setStatus(context, AccessStatus.APPROVED)
        TokenStore.setRole(context, "user")
        AppRemoteConfig.apply(
            context,
            AppConfigDto(
                maxSessions = DEV_QUOTA_MAX,
                maxFilesPerSession = 600,
                maxFrames = DicSettings.MAX_MAX_FRAMES,
                mode = LicenseEntitlements.MODE_LICENSED,
                plan = "professional",
                cloudBackupEnabled = true,
                shareEnabled = true,
            ),
        )
        TokenStore.setSessionLimitReached(context, false)
        Timber.w("DEV AUTH BYPASS active (debug build on an emulator) — cloud is off")
    }
}
