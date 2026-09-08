package com.indicvision.semper.ui.capture

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import timber.log.Timber

/**
 * Catalogue of back-camera output sizes from Camera2, with the sensor's own
 * floor on how fast each size can be read out.
 *
 * That floor is per-resolution, not per-device: [StreamConfigurationMap]
 * reports a minimum frame duration and a stall duration for each (format,
 * size) pair, and a full-sensor read is slower than a binned one on the same
 * hardware. A single device-wide "max fps" — which is what the AE target-fps
 * ranges give you — cannot express that, and reading it at full resolution
 * promised rates the sensor was never going to deliver.
 *
 * Durations are queried for YUV_420_888 because that is the format the locked
 * session actually requests (see [LockedCameraSession]); the JPEG figures
 * describe the hardware JPEG encoder, which this path does not use.
 *
 * [Info.yuvSizes] keeps only 4:3-shaped sizes (see [preferFourByThree]) and is
 * bounded by [sustainableCeiling] — DIC decisions, not hardware ones; see
 * those.
 */
object CameraCapabilities {

    data class Resolution(val width: Int, val height: Int) {
        val label: String get() = "$width×$height"
        val pixels: Long get() = width.toLong() * height

        /** Width over height. Zero height cannot happen from Camera2, but a
         *  divide-by-zero on the capture path is not worth the risk. */
        val aspect: Float get() = if (height == 0) 0f else width.toFloat() / height
    }

    data class Info(
        val cameraId: String,
        /** YUV_420_888-supported sizes — what the locked-session still ImageReader
         *  actually requests (lossless PNG path). Often capped lower than the vendor
         *  JPEG sizes by ISP/memory bandwidth limits on real hardware; matching
         *  against JPEG sizes instead can request an unsupported stream config and
         *  fail session creation entirely. */
        val yuvSizes: List<Resolution>,
        /** Shortest gap the sensor will allow between two stills at a given size,
         *  in ms. Absent when the device does not report one (LEGACY hardware
         *  level returns zero), in which case only the measured encode cost
         *  bounds the plan. */
        val minFrameMs: Map<Resolution, Long>,
        /** Sizes valid for a [SurfaceTexture] target — the preview stream. A
         *  size the camera does not list here can fail session configuration
         *  outright, so the preview size is chosen from this and not simply
         *  scaled down from the capture size. */
        val previewSizes: List<Resolution>,
        /** Physical long edge of the active sensor area, in millimetres, or
         *  null when the device does not report one. The only ingredient of
         *  [ImageScale] that is not already read somewhere else, and the one
         *  that turns a speckle measured in pixels into a size at the bench. */
        val sensorLongEdgeMm: Float? = null,
    ) {
        /** Sensor floor for [res] in ms, or 0 when this device reports none. */
        fun sensorFloorMs(res: Resolution): Long = minFrameMs[res] ?: 0L

        /**
         * Preview size to frame [capture] with: the largest supported size at
         * or under [maxLongEdge] that shares [capture]'s aspect ratio.
         *
         * Aspect ratio is the part that cannot be compromised. Clamping width
         * and height independently — which is what the old code did — lands on
         * a differently-shaped frame, and then the preview shows a field of
         * view the capture will not have. Since the user frames against this
         * preview, that is not a cosmetic difference.
         */
        /**
         * The catalogued size closest to [width]x[height].
         *
         * Aspect ratio first: a plain |dw| + |dh| metric can land on a
         * differently-shaped size, and a frame whose shape the user never chose
         * is the failure [previewSizeFor] exists to avoid. Only within the
         * closest shape does total pixel count decide.
         */
        fun nearest(width: Int, height: Int): Resolution? {
            if (yuvSizes.isEmpty()) return null
            val wanted = Resolution(width, height).aspect
            val closestShape = yuvSizes.minOf { kotlin.math.abs(it.aspect - wanted) }
            val target = width.toLong() * height
            return yuvSizes
                .filter { kotlin.math.abs(it.aspect - wanted) <= closestShape + ASPECT_EPSILON }
                .minByOrNull { kotlin.math.abs(it.pixels - target) }
        }

        fun previewSizeFor(capture: Resolution, maxLongEdge: Int): Resolution {
            if (previewSizes.isEmpty()) return capture
            val wanted = capture.aspect
            val sameShape = previewSizes.filter { kotlin.math.abs(it.aspect - wanted) <= ASPECT_EPSILON }
            val pool = sameShape.ifEmpty {
                // No exact match: the closest shape still beats a stretch.
                listOfNotNull(previewSizes.minByOrNull { kotlin.math.abs(it.aspect - wanted) })
            }
            val small = pool.filter { maxOf(it.width, it.height) <= maxLongEdge }
            return small.maxByOrNull { it.pixels }
                ?: pool.minByOrNull { it.pixels }
                ?: capture
        }
    }

    private const val FALLBACK_MAX_WIDTH = 4032
    private const val FALLBACK_MAX_HEIGHT = 3024
    private const val RES_1080P_WIDTH = 1920
    private const val RES_1080P_HEIGHT = 1080
    private const val RES_720P_WIDTH = 1280
    private const val RES_720P_HEIGHT = 720
    private const val NANOS_PER_MILLI = 1_000_000L

    /** Aspect ratios within this of each other are the same shape in practice —
     *  4:3 catalogues routinely list 1440x1080 alongside 2048x1536. */
    private const val ASPECT_EPSILON = 0.02f

    /**
     * The shape offered for capture: 4:3.
     *
     * It is the sensor's native full-array readout on essentially every
     * phone. The other shapes a stream configuration map routinely lists
     * alongside it — 16:9, and on some sensors a square 1:1 binned mode — are
     * crops or a different, often pre-processed readout path, neither of
     * which helps a correlation that wants the most complete pixel data the
     * sensor can give it. Square is excluded by construction: nothing within
     * [FOUR_BY_THREE_TOLERANCE] of 4:3 (≈1.333) is anywhere near 1:1.
     */
    private const val ASPECT_4_3 = 4f / 3f
    private const val FOUR_BY_THREE_TOLERANCE = 0.05f

    /**
     * Longest edge every device offers regardless of memory: 2K.
     *
     * Not a hardware limit — every phone here shoots larger. It is the
     * known-good DIC floor: [sustainableCeiling] only ever adds larger sizes
     * on top of this set when the device has room for them, never removes
     * from it, so lifting the ceiling on a capable phone cannot regress a
     * modest one.
     */
    const val CAPTURE_MAX_LONG_EDGE = 2048

    /**
     * Share of free RAM a candidate resolution's own working buffers
     * ([CaptureBudget.ramRequired]) may claim before it is left off the
     * catalogue entirely. Deliberately generous — this only keeps the
     * picker from ever *offering* a size that would almost certainly fail;
     * [CaptureBudget.check] still gates the resolution and frame count the
     * user actually picks against its own tighter, run-specific numbers.
     */
    private const val CATALOGUE_RAM_FRACTION = 0.25
    private const val MIN_USABLE_WIDTH = 640L
    private const val MIN_USABLE_HEIGHT = 480L

    /**
     * Size to fall back on when a camera reports no usable output at all. 720p
     * because every Camera2 device is required to support it; reaching for it
     * means something is badly wrong, and a working picker beats a crash.
     */
    val LAST_RESORT = Resolution(RES_720P_WIDTH, RES_720P_HEIGHT)

    /** What the still and preview streams fall back to when Camera2 says nothing. */
    private val FALLBACK_STREAM_SIZES = listOf(
        Resolution(RES_1080P_WIDTH, RES_1080P_HEIGHT),
        Resolution(RES_720P_WIDTH, RES_720P_HEIGHT),
    )

    private val FALLBACK_SIZES = listOf(
        Resolution(FALLBACK_MAX_WIDTH, FALLBACK_MAX_HEIGHT),
        Resolution(RES_1080P_WIDTH, RES_1080P_HEIGHT),
        Resolution(RES_720P_WIDTH, RES_720P_HEIGHT),
    )

    @Volatile
    private var cached: Info? = null

    /**
     * The back camera's catalogue, read once per process.
     *
     * Not cheap to build: it opens characteristics for every camera id the
     * device exposes (a binder round trip each, and modern phones list six or
     * more), then asks for min-frame and stall durations per size. Three
     * separate screens want it during one capture, and it cannot change while
     * the app is in the foreground.
     */
    fun query(context: Context): Info = cached ?: synchronized(this) {
        cached ?: runCatching { queryCamera2(context) }.getOrElse {
            Timber.w(it, "Camera2 catalogue unavailable; using fallbacks")
            fallback()
        }.also { cached = it }
    }

    private fun fallback(): Info = Info(
        cameraId = "0",
        yuvSizes = FALLBACK_STREAM_SIZES,
        minFrameMs = emptyMap(),
        previewSizes = FALLBACK_STREAM_SIZES,
        sensorLongEdgeMm = null,
    )

    @Suppress("ReturnCount")
    private fun queryCamera2(context: Context): Info {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return fallback()
        val id = pickBackCameraId(manager) ?: return fallback()
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return fallback()

        val jpeg = map.getOutputSizes(ImageFormat.JPEG)
            ?.map { Resolution(it.width, it.height) }
            ?.let { distinctLargestFirst(it) }
            .orEmpty()
            .ifEmpty { FALLBACK_SIZES }

        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.map { Resolution(it.width, it.height) }
            ?.let { distinctLargestFirst(it) }
            .orEmpty()
            .ifEmpty { jpeg }
            .let { preferFourByThree(it) }
            .let { sustainableCeiling(it, CaptureResources.availRamBytes(context)) }

        val previews = map.getOutputSizes(SurfaceTexture::class.java)
            ?.map { Resolution(it.width, it.height) }
            ?.let { distinctLargestFirst(it) }
            .orEmpty()
            .ifEmpty { yuv }

        // Best-effort: absent on some devices, and absent is a state
        // [ImageScale] is built to report rather than paper over.
        val sensorMm = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?.let { maxOf(it.width, it.height) }
            ?.takeIf { it.isFinite() && it > 0f }

        return Info(id, yuv, minFrameDurations(map, yuv), previews, sensorMm)
    }

    /**
     * Sensor read-out floor per size, from the stream configuration map.
     *
     * Min frame duration and stall duration are added because they are
     * consecutive costs, not alternatives: the stall is time the pipeline
     * spends before it can accept the next request for that stream. Sizes the
     * device declines to answer for are left out rather than defaulted, so an
     * unknown floor reads as "unknown" downstream and never as "fast".
     */
    private fun minFrameDurations(
        map: StreamConfigurationMap,
        sizes: List<Resolution>,
    ): Map<Resolution, Long> = sizes.mapNotNull { res ->
        val size = Size(res.width, res.height)
        val ns = runCatching {
            map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size) +
                map.getOutputStallDuration(ImageFormat.YUV_420_888, size)
        }.getOrDefault(0L)
        if (ns <= 0L) null else res to (ns / NANOS_PER_MILLI).coerceAtLeast(1L)
    }.toMap()

    /**
     * The back camera to build the catalogue from: the longest lens among
     * the back-facing physical cameras Camera2 lists.
     *
     * Out-of-plane error scales as Δz/z, so a longer focal length shrinks it
     * proportionally for the same standoff — the same reasoning Part 4 of
     * the precision plan applies to lens choice generally. Read purely from
     * [CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS] on each
     * back-facing id, never from a model or vendor name, so it lands
     * correctly on whatever lenses this device happens to have.
     *
     * This is the static half of that decision only: it does not yet know
     * whether the chosen lens can still frame the ROI at the standoff the
     * user set up, which needs the live preview to answer and belongs with
     * that on-device verification. Falls back to the first back-facing id
     * when none report a focal length, and to the first id of any facing
     * when the device reports no back camera at all — the same last-resort
     * behaviour this function always had.
     */
    private fun pickBackCameraId(manager: CameraManager): String? {
        val backIds = manager.cameraIdList.filter { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        val longestLens = backIds
            .mapNotNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.maxOrNull()
                    ?.let { id to it }
            }
            .maxByOrNull { it.second }
            ?.first
        return longestLens ?: backIds.firstOrNull() ?: manager.cameraIdList.firstOrNull()
    }

    /**
     * Keeps only 4:3-shaped sizes, falling back to the unfiltered list when a
     * device reports nothing in that shape at all — the "last link is always
     * today's behaviour" rule: a device with no 4:3 output still has to offer
     * something rather than an empty catalogue.
     */
    internal fun preferFourByThree(sizes: List<Resolution>): List<Resolution> {
        val fourByThree = sizes.filter { kotlin.math.abs(it.aspect - ASPECT_4_3) <= FOUR_BY_THREE_TOLERANCE }
        return fourByThree.ifEmpty { sizes }
    }

    /**
     * [CAPTURE_MAX_LONG_EDGE] and below, always — plus whatever larger sizes
     * this device has room for.
     *
     * "Room for" means a candidate's own working buffers
     * ([CaptureBudget.ramRequired]) fit inside [CATALOGUE_RAM_FRACTION] of
     * [availRamBytes]. A 200MP sensor and an 8MP one both end up offered the
     * largest frame they can sustain, with no constant naming either — and a
     * phone that cannot report free RAM ([availRamBytes] ≤ 0) gets exactly
     * today's known-good ceiling and nothing more, since there is nothing to
     * check the larger sizes against.
     *
     * If a camera reports nothing at or under the floor either — no phone
     * does, but a fixed-function sensor might — the smallest it does offer is
     * kept, because an empty picker is worse than an over-sized frame.
     */
    internal fun sustainableCeiling(sizes: List<Resolution>, availRamBytes: Long): List<Resolution> {
        val floor = sizes.filter { maxOf(it.width, it.height) <= CAPTURE_MAX_LONG_EDGE }
            .ifEmpty { listOfNotNull(sizes.minByOrNull { it.pixels }) }
        if (availRamBytes <= 0L) return floor
        val budget = (availRamBytes * CATALOGUE_RAM_FRACTION).toLong()
        val larger = sizes.filter {
            maxOf(it.width, it.height) > CAPTURE_MAX_LONG_EDGE &&
                CaptureBudget.ramRequired(it.width, it.height) <= budget
        }
        return (floor + larger).distinct().sortedByDescending { it.pixels }
    }

    private fun distinctLargestFirst(sizes: List<Resolution>): List<Resolution> {
        val minPixels = MIN_USABLE_WIDTH * MIN_USABLE_HEIGHT
        return sizes
            .filter { it.pixels >= minPixels }
            .distinctBy { it.width to it.height }
            .sortedByDescending { it.pixels }
    }
}
