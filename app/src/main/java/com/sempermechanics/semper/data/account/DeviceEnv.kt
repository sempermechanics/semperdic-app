package com.sempermechanics.semper.data.account

import android.os.Build

/**
 * Emulator detection shared by debug-only bypasses. Covers the Android Studio
 * emulator (goldfish/ranchu), older generic images, and common third-party
 * emulators. Physical devices report a real manufacturer/hardware and fail
 * every branch.
 */
object DeviceEnv {

    private val EMULATOR_HARDWARE = setOf("goldfish", "ranchu", "vbox86", "android_x86")

    fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val hardware = Build.HARDWARE.orEmpty().lowercase()
        val product = Build.PRODUCT.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty()
        val device = Build.DEVICE.orEmpty()

        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            hardware in EMULATOR_HARDWARE ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            product.contains("simulator") ||
            (brand.startsWith("generic") && device.startsWith("generic"))
    }
}
