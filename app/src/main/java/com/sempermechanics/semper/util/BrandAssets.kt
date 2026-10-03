@file:Suppress("ReturnCount")

package com.sempermechanics.semper.util

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Punch a solid black plate out of a brand bitmap so the remaining ink can sit
 * on whatever surface the caller draws (login, PDF, launcher).
 */
object BrandAssets {

    fun punchBlackPlate(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val w = out.width
        val h = out.height
        if (w <= 0 || h <= 0) return out
        val row = IntArray(w)
        for (y in 0 until h) {
            out.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                // Plate is #000000. Keep ~#171719 glyphs and cyan DIC.
                if (r <= PLATE_MAX && g <= PLATE_MAX && b <= PLATE_MAX) {
                    row[x] = Color.TRANSPARENT
                }
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    private const val PLATE_MAX = 8
}
