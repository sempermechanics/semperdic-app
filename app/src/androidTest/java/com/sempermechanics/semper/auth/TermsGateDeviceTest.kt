package com.sempermechanics.semper.auth

import android.graphics.Bitmap
import android.widget.Button
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.ui.auth.TermsActivity
import com.sempermechanics.semper.ui.home.HomeActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The clickwrap gate on a real device: the required box starts unticked with
 * a dead button and is the only thing that unlocks it; the improvement box is
 * pre-ticked and irrelevant to the button. Writes screenshots to the app's external
 * files dir so a human can look at the screen too.
 */
@RunWith(AndroidJUnit4::class)
class TermsGateDeviceTest {

    @Test
    fun gateStartsUntickedAndUnlocksOnTheRequiredBox() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TokenStore.clear(context)
        val intent = TermsActivity.intent(context, HomeActivity::class.java)
        ActivityScenario.launch<TermsActivity>(intent).use { scenario ->
            Thread.sleep(SETTLE_MS)
            screenshot("terms_1_initial")
            scenario.onActivity { a ->
                assertFalse(a.findViewById<CheckBox>(R.id.cbAgreeTerms).isChecked)
                assertTrue(a.findViewById<CheckBox>(R.id.cbImprovementConsent).isChecked)
                assertFalse(a.findViewById<Button>(R.id.btnAgree).isEnabled)
            }
            scenario.onActivity { a -> a.findViewById<CheckBox>(R.id.cbImprovementConsent).isChecked = false }
            Thread.sleep(SETTLE_MS)
            screenshot("terms_2_optional_off")
            scenario.onActivity { a ->
                assertFalse("optional box must not affect the button", a.findViewById<Button>(R.id.btnAgree).isEnabled)
            }
            scenario.onActivity { a -> a.findViewById<CheckBox>(R.id.cbAgreeTerms).isChecked = true }
            Thread.sleep(SETTLE_MS)
            screenshot("terms_3_required_ticked")
            scenario.onActivity { a ->
                assertTrue(a.findViewById<Button>(R.id.btnAgree).isEnabled)
            }
        }
    }

    private fun screenshot(name: String) {
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = ApplicationProvider.getApplicationContext<android.content.Context>().getExternalFilesDir(null)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val SETTLE_MS = 800L
    }
}
