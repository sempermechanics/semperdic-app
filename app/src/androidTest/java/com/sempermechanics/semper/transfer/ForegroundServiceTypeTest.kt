package com.sempermechanics.semper.transfer

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.data.cloud.TransferNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device check that the foreground-service contract holds end to end.
 *
 * A typeless foreground service is fatal from Android 14 on
 * (`InvalidForegroundServiceTypeException`), and satisfying it takes two
 * separate pieces that can drift apart:
 *
 *  1. `ForegroundInfo` must carry a type — covered off-device by
 *     `TransferNotificationsTest`.
 *  2. The **merged manifest** must declare that type on WorkManager's
 *     `SystemForegroundService`, which ships without one.
 *
 * A JVM unit test cannot see (2) — it reads app sources, not the merged
 * manifest — so this asserts it against the installed package, and then
 * asserts the two agree, which is the condition the platform actually
 * enforces: the type requested at runtime must be a subset of the declared one.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundServiceTypeTest {

    private val workManagerService = "androidx.work.impl.foreground.SystemForegroundService"

    @Suppress("DEPRECATION") // the ComponentInfoFlags overload is API 33+; minSdk is 24
    private fun serviceInfo(className: String): ServiceInfo {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.packageManager.getServiceInfo(
            ComponentName(context.packageName, className),
            PackageManager.GET_META_DATA,
        )
    }

    @Test
    fun workManagerForegroundServiceIsDeclaredWithAType() {
        val declared = serviceInfo(workManagerService).foregroundServiceType
        assertNotEquals(
            "SystemForegroundService reached the merged manifest with no " +
                "foregroundServiceType; Android 14+ will refuse to start it",
            TYPE_NONE,
            declared,
        )
    }

    @Test
    fun workManagerForegroundServiceDeclaresDataSync() {
        val declared = serviceInfo(workManagerService).foregroundServiceType
        assertTrue(
            "expected dataSync in the declared types, got $declared",
            declared and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0,
        )
    }

    /**
     * The platform requires the runtime type to be a subset of the declared
     * type. This is the assertion neither half can make on its own.
     */
    @Test
    fun everyTransferTypeIsCoveredByTheManifestDeclaration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val declared = serviceInfo(workManagerService).foregroundServiceType

        val runtimeTypes = listOf(
            TransferNotifications.uploadForeground(context).foregroundServiceType,
            TransferNotifications.restoreForeground(context).foregroundServiceType,
            TransferNotifications.downloadForeground(context).foregroundServiceType,
        )

        runtimeTypes.forEach { requested ->
            assertNotEquals(
                "a transfer worker requested foreground service type none",
                TYPE_NONE,
                requested,
            )
            assertEquals(
                "requested type $requested is not covered by declared type $declared",
                requested,
                requested and declared,
            )
        }
    }

    private companion object {
        // ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE (0) is deprecated; the
        // platform's "no type" is still the empty bit set.
        const val TYPE_NONE = 0
    }
}
