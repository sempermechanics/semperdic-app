package com.indicvision.semper.capture

import com.indicvision.semper.ui.analysis.DicGoodPractice
import com.indicvision.semper.ui.capture.CameraCapabilities
import com.indicvision.semper.ui.capture.CaptureSuitability
import com.indicvision.semper.ui.capture.ImageScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-recording resolution verdict.
 *
 * Every input is stated per case: a synthetic camera's size list, a measured
 * speckle, a test-shot size. No device's numbers are assumed, because the point
 * of this check is that the answer is different on every phone and every
 * specimen.
 */
class CaptureSuitabilityTest {

    @Test
    fun `a speckle already in the band raises nothing`() {
        // 24 px on a 4000 px test shot is 6 px on a 1000 px plan: inside 3-9,
        // so the user is not interrupted.
        assertNull(verdictFor(speckle = 24.0, testShot = 4000, plan = res(1000, 750)))
    }

    @Test
    fun `a plan too small to resolve the speckle is caught before recording`() {
        // 24 px on 4000 becomes 2.4 px on a 400 px plan — the failure the
        // device runs hit, now named before the specimen is spent.
        val verdict = verdictFor(speckle = 24.0, testShot = 4000, plan = res(400, 300))!!
        assertEquals(DicGoodPractice.Verdict.UNDER_RESOLVED, verdict.band)
        assertEquals(2.4, verdict.speckleOnPlanPx, 0.01)
        assertTrue("recommend larger", verdict.recommendedLongEdge > 400)
    }

    @Test
    fun `an oversampled plan is told to record smaller, not larger`() {
        // 24 px on 4000 is 24 px on a 4000 px plan: correlates fine and pays
        // for pixels it cannot use. The recommendation must go *down*.
        val verdict = verdictFor(speckle = 24.0, testShot = 4000, plan = res(4000, 3000))!!
        assertEquals(DicGoodPractice.Verdict.OVER_RESOLVED, verdict.band)
        assertTrue("recommend smaller", verdict.recommendedLongEdge < 4000)
    }

    @Test
    fun `the recommendation lands on a size the camera actually offers`() {
        // Naming a size the spinner cannot select would be advice the user
        // cannot take.
        val offered = listOf(res(4000, 3000), res(2000, 1500), res(1000, 750), res(640, 480))
        val verdict = verdictFor(24.0, 4000, res(400, 300), offered)!!
        assertNotNull(verdict.recommended)
        assertTrue(verdict.recommended in offered)
    }

    @Test
    fun `the recommendation puts the speckle back inside the band`() {
        // The whole purpose, stated as a round trip: record at the recommended
        // long edge and the speckle is usable.
        for (plan in listOf(res(400, 300), res(4000, 3000), res(800, 600))) {
            val verdict = verdictFor(24.0, 4000, plan) ?: continue
            val atRecommendation = 24.0 * verdict.recommendedLongEdge / 4000
            assertEquals(
                "from ${plan.label}",
                DicGoodPractice.Verdict.USABLE,
                DicGoodPractice.verdictFor(atRecommendation),
            )
        }
    }

    @Test
    fun `an orientation change does not change the verdict`() {
        // The test shot is landscape and the plan portrait, or the other way
        // round. Long edge against long edge, so the answer is the same.
        val landscape = verdictFor(24.0, 4000, res(400, 300))!!
        val portrait = verdictFor(24.0, 4000, res(300, 400))!!
        assertEquals(landscape.speckleOnPlanPx, portrait.speckleOnPlanPx, 1e-9)
        assertEquals(landscape.recommendedLongEdge, portrait.recommendedLongEdge)
    }

    @Test
    fun `an unmeasurable speckle says nothing rather than guessing`() {
        // A check that fires whenever it cannot see is a check users dismiss.
        assertNull(verdictFor(speckle = null, testShot = 4000, plan = res(400, 300)))
    }

    @Test
    fun `without a scale the verdict still stands, minus the millimetres`() {
        val verdict = verdictFor(24.0, 4000, res(400, 300), scale = ImageScale.Result.Unavailable)!!
        assertEquals(DicGoodPractice.Verdict.UNDER_RESOLVED, verdict.band)
        assertNull(verdict.speckleMm)
        assertNull(verdict.bandMm)
    }

    @Test
    fun `millimetre figures describe the specimen, not the frame size`() {
        // 0.05 mm/px on a 4000 px test shot is 0.5 mm/px on a 400 px plan, and
        // 24 test-shot pixels of speckle is 1.2 mm either way. The dot on the
        // specimen does not change size when the camera does; only the pixels
        // do, and the conversion has to survive that.
        val known = ImageScale.Result.Known(0.05)
        val small = verdictFor(24.0, 4000, res(400, 300), scale = known)!!
        val large = verdictFor(24.0, 4000, res(4000, 3000), scale = known)!!
        assertEquals(1.2, small.speckleMm!!, 1e-9)
        assertEquals(1.2, large.speckleMm!!, 1e-9)
    }

    @Test
    fun `the usable band in millimetres is quoted for the plan's own pixels`() {
        // 0.5 mm per plan pixel, so 3-9 px is 1.5-4.5 mm. That is the number
        // the user takes to the bench when no resolution can rescue the
        // pattern and the answer is to re-make it.
        val verdict = verdictFor(24.0, 4000, res(400, 300), scale = ImageScale.Result.Known(0.05))!!
        val (min, recommended, max) = verdict.bandMm!!
        assertEquals(1.5, min, 1e-9)
        assertEquals(2.5, recommended, 1e-9)
        assertEquals(4.5, max, 1e-9)
    }

    @Test
    fun `a camera with no sizes to offer still gives the pixel verdict`() {
        val verdict = verdictFor(24.0, 4000, res(400, 300), offered = emptyList())!!
        assertNull(verdict.recommended)
        assertTrue(verdict.recommendedLongEdge > 0)
    }

    private fun res(width: Int, height: Int) = CameraCapabilities.Resolution(width, height)

    private fun verdictFor(
        speckle: Double?,
        testShot: Int,
        plan: CameraCapabilities.Resolution,
        offered: List<CameraCapabilities.Resolution> = listOf(
            res(4000, 3000),
            res(2000, 1500),
            res(1000, 750),
            res(640, 480),
            res(400, 300),
        ),
        scale: ImageScale.Result = ImageScale.Result.Unavailable,
    ) = CaptureSuitability.of(
        speckleOnTestShotPx = speckle,
        testShotLongEdge = testShot,
        plan = plan,
        offered = offered,
        scale = scale,
    )
}
