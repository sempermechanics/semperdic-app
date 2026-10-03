// Pixel-layout constants (DWORD row alignment, BGR component order, the 4:2:2
// byte pairs) are the formats themselves; naming each one would obscure them.
// A payload that is too short for its stated layout is refused where it is
// found, so the several early returns per function are the point.
@file:Suppress("MagicNumber", "ReturnCount")

package com.sempermechanics.semper.imaging

/**
 * Turns an uncompressed AVI frame payload into the luma plane the DIC engine
 * correlates on, in the same [GrayPngEncoder.Luma] shape the hardware decoder
 * produces, so both import paths write byte-identical grayscale PNGs.
 *
 * Machine-vision and UTM cameras write AVI in exactly these layouts: 8-bit
 * gray, packed 4:2:2, planar 4:2:0, or a plain bottom-up DIB. For every one of
 * them the luminance is already in the file, so nothing here is a conversion
 * except the RGB case.
 *
 * Pure JVM: zero Android dependencies, 100% unit-testable.
 */
internal object AviLuma {

    /** Uncompressed rows in a `BITMAPINFOHEADER` layout: DWORD-aligned, bottom-up by default. */
    private val DIB_FOURCCS = setOf("DIB ", "RAW ", "    ")

    /** 8 bits of luminance per pixel, packed. */
    private val GRAY8_FOURCCS = setOf("Y800", "Y8  ", "GREY", "GRAY", "Y1  ")

    /** 16 bits of luminance per pixel, little-endian: the high byte is the 8-bit image. */
    private val GRAY16_FOURCCS = setOf("Y16 ", "Y160")

    /** Planar YUV: the full-resolution Y plane comes first. */
    private val PLANAR_FOURCCS = setOf("I420", "IYUV", "YV12", "YV16", "NV12", "NV21", "422P")

    /** Packed 4:2:2 beginning with Y. */
    private val YUY2_FOURCCS = setOf("YUY2", "YUYV", "YUNV", "V422")

    /** Packed 4:2:2 beginning with U, so Y sits on the odd bytes. */
    private val UYVY_FOURCCS = setOf("UYVY", "UYNV", "Y422", "HDYC")

    /**
     * One pixel's luminance, full-range Rec.601. Full range rather than the
     * 16–235 studio swing because the weights then sum to 256: a grayscale image
     * stored as RGB comes back bit-exact instead of being squeezed.
     */
    fun luminance(r: Int, g: Int, b: Int): Int = ((77 * r + 150 * g + 29 * b + 128) shr 8).coerceIn(0, 255)

    /** True when [video] carries its pixels uncompressed, so [toLuma] can read them. */
    fun isSupported(video: AviReader.Video): Boolean = when (video.fourcc) {
        in DIB_FOURCCS -> video.bitCount == 8 || video.bitCount == 24 || video.bitCount == 32
        in GRAY8_FOURCCS, in GRAY16_FOURCCS, in PLANAR_FOURCCS, in YUY2_FOURCCS, in UYVY_FOURCCS -> true
        else -> false
    }

    /** The luma plane of one frame, or null when [payload] is short or the layout is not ours. */
    fun toLuma(payload: ByteArray, video: AviReader.Video): GrayPngEncoder.Luma? {
        val width = video.width
        val height = video.height
        if (width <= 0 || height <= 0) return null
        return when (video.fourcc) {
            in DIB_FOURCCS -> fromDib(payload, video)
            in GRAY8_FOURCCS -> strided(payload, video, width, 1, 0)
            in GRAY16_FOURCCS -> strided(payload, video, width * 2, 2, 1)
            in PLANAR_FOURCCS -> strided(payload, video, width, 1, 0)
            in YUY2_FOURCCS -> strided(payload, video, width * 2, 2, 0)
            in UYVY_FOURCCS -> strided(payload, video, width * 2, 2, 1)
            else -> null
        }
    }

    /**
     * A top-down plane already inside [payload]. [offset] skips the leading
     * chroma byte of a UYVY pair or the low byte of a 16-bit sample; the slice
     * it needs is one copy, which beats re-deriving the stride downstream.
     */
    private fun strided(
        payload: ByteArray,
        video: AviReader.Video,
        rowStride: Int,
        pixelStride: Int,
        offset: Int,
    ): GrayPngEncoder.Luma? {
        val width = video.width
        val height = video.height
        // The bound is the last byte actually read, not a whole trailing row:
        // a 4:2:2 payload ends on its final chroma byte, one short of a row.
        val last = offset.toLong() + (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride
        if (last >= payload.size) return null
        val bytes = if (offset == 0) payload else payload.copyOfRange(offset, payload.size)
        return GrayPngEncoder.Luma(bytes, width, height, rowStride, pixelStride)
    }

    private fun fromDib(payload: ByteArray, video: AviReader.Video): GrayPngEncoder.Luma? {
        val width = video.width
        val height = video.height
        val rowStride = ((width * video.bitCount + 31) / 32) * 4
        if (rowStride.toLong() * height > payload.size) return null
        return when (video.bitCount) {
            8 -> fromIndexed(payload, video, rowStride)
            24, 32 -> fromRgb(payload, video, rowStride)
            else -> null
        }
    }

    /**
     * 8-bit DIB rows through the file's own palette. A grayscale camera writes a
     * gray ramp, so the mapping is the identity and the bytes pass through
     * unchanged; a file with a real palette is still read correctly.
     */
    private fun fromIndexed(payload: ByteArray, video: AviReader.Video, rowStride: Int): GrayPngEncoder.Luma {
        val width = video.width
        val height = video.height
        val lut = paletteLut(video.codecPrivate)
        if (lut == null && video.topDown) {
            return GrayPngEncoder.Luma(payload, width, height, rowStride, 1)
        }
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val src = sourceRow(y, height, video.topDown) * rowStride
            val dst = y * width
            for (x in 0 until width) {
                val index = payload[src + x].toInt() and 0xFF
                out[dst + x] = (lut?.get(index) ?: index.toByte())
            }
        }
        return GrayPngEncoder.Luma(out, width, height, width, 1)
    }

    /** BGR(A) rows to [luminance]; the alpha byte of a 32-bit row is ignored. */
    private fun fromRgb(payload: ByteArray, video: AviReader.Video, rowStride: Int): GrayPngEncoder.Luma {
        val width = video.width
        val height = video.height
        val bytesPerPixel = video.bitCount / 8
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val src = sourceRow(y, height, video.topDown) * rowStride
            val dst = y * width
            for (x in 0 until width) {
                val at = src + x * bytesPerPixel
                val b = payload[at].toInt() and 0xFF
                val g = payload[at + 1].toInt() and 0xFF
                val r = payload[at + 2].toInt() and 0xFF
                out[dst + x] = luminance(r, g, b).toByte()
            }
        }
        return GrayPngEncoder.Luma(out, width, height, width, 1)
    }

    /** DIB rows run bottom-up unless `biHeight` was negative. */
    private fun sourceRow(y: Int, height: Int, topDown: Boolean): Int = if (topDown) y else height - 1 - y

    /**
     * The palette that follows the bitmap header, as a 256-entry luma table.
     * Null when there is none, or when it is the gray ramp that needs no lookup.
     */
    private fun paletteLut(codecPrivate: ByteArray?): ByteArray? {
        val palette = codecPrivate ?: return null
        val entries = palette.size / 4
        if (entries < 2) return null
        val lut = ByteArray(256)
        var identity = true
        for (i in 0 until 256) {
            if (i >= entries) {
                lut[i] = i.toByte()
                continue
            }
            val at = i * 4
            val b = palette[at].toInt() and 0xFF
            val g = palette[at + 1].toInt() and 0xFF
            val r = palette[at + 2].toInt() and 0xFF
            val y = luminance(r, g, b)
            lut[i] = y.toByte()
            if (y != i) identity = false
        }
        return if (identity) null else lut
    }
}
