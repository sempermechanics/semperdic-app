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
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `the refusal is one line, not a lecture`() {
        setMaxFrames(DicSettings.MIN_MAX_FRAMES)

        val activity = launchWithDuration(LONG_RUN)

        // The line used to carry the reasoning behind the 1 fps floor as well,
        // which is a paragraph the user has to read past to reach the sentence
        // telling them what to change. A dead end wants an exit, not a lesson.
        val said = activity.findViewById<TextView>(R.id.tvCaptureAssurance).text.toString()
        assertTrue("stays short: $said", said.length <= MAX_REFUSAL_CHARS)
        assertFalse("no correlation lecture: $said", said.contains("subset"))
    }

    @Test
    fun `a disabled Continue does not look like an enabled one`() {
        setMaxFrames(DicSettings.MIN_MAX_FRAMES)

        val activity = launchWithDuration(LONG_RUN)

        // The style set backgroundTint to a flat colour, which replaced the
        // state list wholesale: the button was disabled and still painted the
        // full-strength primary fill, so the only way to discover it was dead
        // was to press it. Both halves of the affordance have to dim.
        val button = activity.findViewById<MaterialButton>(R.id.btnCaptureContinue)
        val fill = requireNotNull(button.backgroundTintList) { "a tint list" }
        val label = button.textColors
        assertNotEquals(
            "the fill dims when disabled",
            fill.getColorForState(ENABLED, 0),
            fill.getColorForState(DISABLED, 0),
        )
        assertNotEquals(
            "the label dims with it",
            label.getColorForState(ENABLED, 0),
            label.getColorForState(DISABLED, 0),
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

        /**
         * Long enough for the number, the cap and both ways out; short enough
         * that a second explanatory paragraph cannot hide inside it.
         */
        const val MAX_REFUSAL_CHARS = 120

        val ENABLED = intArrayOf(android.R.attr.state_enabled)
        val DISABLED = intArrayOf(-android.R.attr.state_enabled)
    }
}
