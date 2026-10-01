package com.indicvision.semper.settings

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.prefs.DicSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Prefs schema upgrades must drop retired keys once and stamp the current schema.
 */
@RunWith(RobolectricTestRunner::class)
class DicSettingsMigrateTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("dic_settings", Context.MODE_PRIVATE).edit { clear() }
    }

    @Test
    fun `migrate removes retired keep_every_rerun and stamps schema`() {
        ctx.getSharedPreferences("dic_settings", Context.MODE_PRIVATE).edit {
            putBoolean("keep_every_rerun", true)
            putInt("schema", 0)
        }

        DicSettings.migrate(ctx)

        val prefs = ctx.getSharedPreferences("dic_settings", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("keep_every_rerun"))
        assertEquals(1, prefs.getInt("schema", 0))
    }

    @Test
    fun `migrate is a no-op when already at current schema`() {
        ctx.getSharedPreferences("dic_settings", Context.MODE_PRIVATE).edit {
            putBoolean("keep_every_rerun", true)
            putInt("schema", 1)
        }

        DicSettings.migrate(ctx)

        val prefs = ctx.getSharedPreferences("dic_settings", Context.MODE_PRIVATE)
        // Already stamped — do not re-run the remove (legacy key may linger only
        // if someone hand-wrote schema=1; migrate must not touch it again).
        assertEquals(1, prefs.getInt("schema", 0))
    }
}
