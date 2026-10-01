package com.indicvision.semper.data.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** [AppRemoteConfig.record]: the one place a `/v1/config` fetch's outcome is stored. */
@RunWith(RobolectricTestRunner::class)
class AppRemoteConfigRecordTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clear() = AppRemoteConfig.clear(context)

    @Test
    fun `failures count towards the sync hint and an answer resets them`() {
        repeat(3) {
            assertFalse(AppRemoteConfig.record(context, Result.failure(IOException("offline"))))
        }
        assertTrue(AppRemoteConfig.shouldHintSyncBlocked(context))

        assertTrue(AppRemoteConfig.record(context, Result.success(AppConfigDto(maxSessions = 25, mode = "demo"))))

        assertEquals(25, AppRemoteConfig.maxSessions(context))
        assertTrue(AppRemoteConfig.isKnown(context))
        assertFalse(AppRemoteConfig.shouldHintSyncBlocked(context))
        assertTrue(AppRemoteConfig.fetchedAtMillis(context) > 0L)
    }
}
