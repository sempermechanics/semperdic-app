package com.sempermechanics.semper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app installs its App Check provider. A debug build on a real phone
 * signs in and backs up for real, so it must attest like a release build.
 */
class AppCheckInstallTest {

    private val api = "https://semper-gw-xxxx.an.gateway.dev"

    @Test
    fun `a build with a backend attests unless the emulator bypass is running`() {
        assertTrue(wantsAppCheck(api, devAuthActive = false))
        assertFalse(wantsAppCheck(api, devAuthActive = true))
    }

    @Test
    fun `an offline build never attests`() {
        assertFalse(wantsAppCheck("", devAuthActive = false))
        assertFalse(wantsAppCheck("  ", devAuthActive = false))
    }
}
