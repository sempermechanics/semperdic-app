package com.indicvision.semper.report

import com.indicvision.semper.field.Roi

/** The report's ROI row for this ROI: `RoiData(x, y, w, h)`, as the viewer and the upload bundler build it. */
fun Roi.toRoiData(): RoiData = RoiData(startX = x, startY = y, width = w, height = h)

/** This row as a [Roi]. */
fun RoiData.toRoi(): Roi = Roi(startX, startY, width, height)
