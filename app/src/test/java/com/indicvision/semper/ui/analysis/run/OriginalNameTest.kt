package com.indicvision.semper.ui.analysis.run

import com.indicvision.semper.data.session.originalNameOr
import com.indicvision.semper.ui.analysis.wizard.WizardState
import com.indicvision.semper.ui.analysis.wizard.toDeformedFrames
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A frame keeps the name it was picked as; one with no name on record —
 * past the end of the list, or padded with "" by a restored draft — falls
 * back to its file's own name.
 */
class OriginalNameTest {

    @Test
    fun `a recorded name wins, a missing or blank one falls back`() {
        val names = listOf("IMG_1.png", "", " ")
        assertEquals("IMG_1.png", names.originalNameOr(0, "0000_a.png"))
        assertEquals("0001_b.png", names.originalNameOr(1, "0001_b.png"))
        assertEquals("0002_c.png", names.originalNameOr(2, "0002_c.png"))
        assertEquals("0003_d.png", names.originalNameOr(3, "0003_d.png"))
    }

    @Test
    fun `a restored draft with fewer names than frames still names every frame`() {
        val frames = WizardState.Frames(
            paths = listOf("/c/0000_a.png", "/c/0001_b.png"),
            names = listOf("IMG_1.png"),
            widths = listOf(4, 4),
            heights = listOf(3, 3),
        ).toDeformedFrames()
        val names = frames.map { it.name }

        val shown = frames.indices.map { names.originalNameOr(it, frames[it].path.baseName()) }
        assertEquals(listOf("IMG_1.png", "0001_b.png"), shown)
    }
}
