package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [EngineFailure.shortReason] fills in the code. Home, the viewer's settings
 * sheet and the lattice used to call `getString(shortReasonRes(code))`, which
 * showed the raw "Unknown engine error (code %1$d)".
 */
@RunWith(RobolectricTestRunner::class)
class EngineFailureShortReasonTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an unknown code is named in the label`() {
        assertEquals("Unknown engine error (code 42)", EngineFailure.shortReason(ctx, 42))
    }

    @Test
    fun `a known cause reads its label unchanged`() {
        assertEquals(ctx.getString(R.string.sweep_reason_vsg), EngineFailure.shortReason(ctx, 0))
        assertEquals(
            ctx.getString(R.string.sweep_reason_decorrelated),
            EngineFailure.shortReason(ctx, EngineFailure.ENGINE_ERROR_FEATURES),
        )
    }
}
