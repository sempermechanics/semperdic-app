package com.indicvision.semper.capture

import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.indicvision.semper.R
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.ui.capture.CaptureSetupActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The setup screen when no rate on the ladder fits the plan.
 *
 * Flooring the ladder at 1 fps made "nothing fits" reachable for the first
 * time, and the screen's answer to it was a **Continue** button that stayed
 * enabled and did nothing: the option list was empty, the lookup returned null,
 * and the click handler returned without a word. A dead button is worse than a
 * refusal, because the user has no way to tell it apart from a slow one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureSetupActivityTest {

    @Test
    fun `a plan that needs more frames than the setting allows refuses to continue`() {
        // Ten frames is the floor of the setting, and at the 1 fps floor of the
        // ladder a minute of recording needs sixty. Nothing on the ladder fits,
        // whatever the camera underneath can do.
        setMaxFrames(DicSettings.MIN_MAX_FRAMES)

        val activity = launchWithDuration(LONG_RUN)

        val continueButton = activity.findViewById<MaterialButton>(R.id.btnCaptureContinue)
        assertFalse("Continue is dead, so it must not look alive", continueButton.isEnabled)
        assertEquals(
            activity.getString(R.string.capture_no_rate_title),
            activity.findViewById<TextView>(R.id.tvCaptureEstimate).text.toString(),
        )
    }

    @Test
    fun `the refusal names the limit that binds and how to lift it`() {
        setMaxFrames(DicSettings.MIN_MAX_FRAMES)

        val activity = launchWithDuration(LONG_RUN)

        // Max frames is the binding limit here, not the camera, so the line has
        // to say so — the fix is in Settings, and a message about the camera
        // would send the user to change the one thing that is not the problem.
        val said = activity.findViewById<TextView>(R.id.tvCaptureAssurance).text.toString()
        assertTrue("names the frames it needs: $said", said.contains(LONG_RUN.toString()))
        assertTrue(
            "names the cap it has: $said",
            said.contains(DicSettings.MIN_MAX_FRAMES.toString()),
        )
    }

    @Test
    fun `a plan that fits offers a rate and lets the run start`() {
        setMaxFrames(DicSettings.DEFAULT_MAX_FRAMES)

        val activity = launchWithDuration(SHORT_RUN)

        assertTrue(
            "Continue is alive when there is something to continue to",
            activity.findViewById<MaterialButton>(R.id.btnCaptureContinue).isEnabled,
        )
    }

    /** Type a duration into the field the way the user does, then let it settle. */
    private fun launchWithDuration(seconds: Int): CaptureSetupActivity {
        val activity = Robolectric.buildActivity(CaptureSetupActivity::class.java)
            .create()
            .start()
            .resume()
            .get()
        val minutes = seconds / SECONDS_PER_MINUTE
        val remainder = seconds % SECONDS_PER_MINUTE
        activity.findViewById<EditText>(R.id.etCaptureDuration)
            .setText("%d:%02d".format(minutes, remainder))
        return activity
    }

    private fun setMaxFrames(value: Int) {
        DicSettings.setMaxFrames(
            ApplicationProvider.getApplicationContext(),
            value,
            DicSettings.MAX_MAX_FRAMES,
        )
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60

        /** Sixty frames at the ladder's floor — more than the setting's own floor. */
        const val LONG_RUN = 60

        /** Well inside the default cap at every rate the ladder offers. */
        const val SHORT_RUN = 5
    }
}
