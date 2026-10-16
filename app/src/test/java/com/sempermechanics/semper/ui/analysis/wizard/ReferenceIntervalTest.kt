package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.ui.analysis.rerun.RerunInputs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reference setting's values: the dropdown offers a fixed reference and a
 * few intervals, a saved value lands on one of them, and a Re-run's settings
 * written before the setting existed read as the fixed reference they ran with.
 */
class ReferenceIntervalTest {

    @Test
    fun `the choices start with the fixed reference, then every frame`() {
        assertEquals(listOf(0, 1, 2, 5, 10), WizardParamFields.REFERENCE_INTERVALS)
    }

    @Test
    fun `a saved interval lands on the largest choice not above it`() {
        assertEquals(0, WizardParamFields.snapInterval(-3))
        assertEquals(0, WizardParamFields.snapInterval(0))
        assertEquals(1, WizardParamFields.snapInterval(1))
        assertEquals(2, WizardParamFields.snapInterval(4))
        assertEquals(5, WizardParamFields.snapInterval(5))
        assertEquals(10, WizardParamFields.snapInterval(40))
    }

    @Test
    fun `re-run settings from before the setting read as the fixed reference`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"subset":41,"step":5,"strainWindow":21,"use6x6":true}"""

        assertEquals(0, json.decodeFromString(RerunInputs.Params.serializer(), old).refUpdateInterval)
    }

    @Test
    fun `re-run settings keep an interval`() {
        val params = RerunInputs.Params(41, 5, 21, use6x6 = false, refUpdateInterval = 2)
        val text = Json.encodeToString(RerunInputs.Params.serializer(), params)

        assertEquals(params, Json.decodeFromString(RerunInputs.Params.serializer(), text))
    }
}
