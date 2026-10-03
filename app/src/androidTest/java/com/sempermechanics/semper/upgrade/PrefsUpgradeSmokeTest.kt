package com.sempermechanics.semper.upgrade

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.data.prefs.DicSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke: prefs migrate when SemperApp would call [DicSettings.migrate].
 */
@RunWith(AndroidJUnit4::class)
class PrefsUpgradeSmokeTest {

    @Test
    fun migrateDropsRetiredKeysOnDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("dic_settings", 0).edit {
            clear()
            putBoolean("keep_every_rerun", true)
            putInt("schema", 0)
        }

        DicSettings.migrate(ctx)

        val prefs = ctx.getSharedPreferences("dic_settings", 0)
        assertFalse(prefs.contains("keep_every_rerun"))
        assertEquals(1, prefs.getInt("schema", 0))
    }
}
