package com.sempermechanics.semper.ui.analysis.frames

import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection.ASCENDING
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection.DESCENDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The frame-order menu ticks the order in use, and each item picks its order. */
class AnalysisFrameOrderMenuHelperTest {

    private val helper = AnalysisFrameOrderMenuHelper

    @Test
    fun `the menu ticks the order in use`() {
        assertEquals(R.id.menu_frame_order_name_asc, helper.checkedItem(FrameOrderMode.NAME, ASCENDING))
        assertEquals(R.id.menu_frame_order_name_desc, helper.checkedItem(FrameOrderMode.NAME, DESCENDING))
        assertEquals(R.id.menu_frame_order_date_asc, helper.checkedItem(FrameOrderMode.DATE, ASCENDING))
        assertEquals(R.id.menu_frame_order_date_desc, helper.checkedItem(FrameOrderMode.DATE, DESCENDING))
        assertEquals(R.id.menu_frame_order_manual, helper.checkedItem(FrameOrderMode.MANUAL, DESCENDING))
        // The picker's own order has no item.
        assertNull(helper.checkedItem(FrameOrderMode.PICKER, ASCENDING))
    }

    @Test
    fun `each item picks its order, and manual keeps the direction`() {
        assertEquals(FrameOrderMode.NAME to ASCENDING, helper.orderFor(R.id.menu_frame_order_name_asc, DESCENDING))
        assertEquals(FrameOrderMode.NAME to DESCENDING, helper.orderFor(R.id.menu_frame_order_name_desc, ASCENDING))
        assertEquals(FrameOrderMode.DATE to ASCENDING, helper.orderFor(R.id.menu_frame_order_date_asc, DESCENDING))
        assertEquals(FrameOrderMode.DATE to DESCENDING, helper.orderFor(R.id.menu_frame_order_date_desc, ASCENDING))
        assertEquals(FrameOrderMode.MANUAL to DESCENDING, helper.orderFor(R.id.menu_frame_order_manual, DESCENDING))
        assertNull(helper.orderFor(R.id.btnNext, ASCENDING))
    }
}
