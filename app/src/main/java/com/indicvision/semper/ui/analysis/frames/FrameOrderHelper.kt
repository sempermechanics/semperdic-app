package com.indicvision.semper.ui.analysis.frames

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** How multi-picked deformed frames are ordered for analysis. */
enum class FrameOrderMode {
    /** System picker / import order. */
    PICKER,

    /** Alphabetical by display name. */
    NAME,

    /** Capture / creation / modified time. */
    DATE,

    /** User drag order. */
    MANUAL,
}

/** Sort direction for [FrameOrderMode.NAME] and [FrameOrderMode.DATE]. */
enum class FrameOrderDirection {
    ASCENDING,
    DESCENDING,
}

/**
 * Best-effort creation times for imported frame files, and reordering of the
 * parallel frame lists for the analysis wizard.
 */
object FrameOrderHelper {

    data class OrderedBatch(
        val paths: List<String>,
        val names: List<String>,
        val dates: List<Long>,
        val sizes: Map<String, Pair<Int, Int>>,
    )

    /**
     * Reorder parallel lists by [mode]. For MANUAL, [manualOrder] is the desired
     * permutation of indices into the current lists; ignored otherwise.
     * [direction] applies to NAME and DATE only.
     */
    @Suppress("LongParameterList") // the parallel frame lists plus the order they are put in
    fun reorder(
        paths: List<String>,
        names: List<String>,
        dates: List<Long>,
        sizes: Map<String, Pair<Int, Int>>,
        mode: FrameOrderMode,
        direction: FrameOrderDirection = FrameOrderDirection.ASCENDING,
        manualOrder: List<Int>? = null,
    ): OrderedBatch {
        val n = paths.size
        if (n == 0) {
            return OrderedBatch(emptyList(), emptyList(), emptyList(), emptyMap())
        }
        val ascending = direction == FrameOrderDirection.ASCENDING
        val indices = when (mode) {
            FrameOrderMode.PICKER -> paths.indices.toList()
            FrameOrderMode.NAME -> {
                val sorted = names.indices.sortedWith(
                    compareBy<Int> { names[it].lowercase(Locale.US) }.thenBy { it },
                )
                if (ascending) sorted else sorted.asReversed()
            }
            FrameOrderMode.DATE -> {
                val d = if (dates.size == n) dates else List(n) { Long.MAX_VALUE }
                val sorted = names.indices.sortedWith(
                    compareBy<Int> { d[it] }.thenBy { names[it].lowercase(Locale.US) },
                )
                if (ascending) sorted else sorted.asReversed()
            }
            FrameOrderMode.MANUAL -> {
                manualOrder?.takeIf { it.size == n } ?: paths.indices.toList()
            }
        }
        val newPaths = indices.map { paths[it] }
        return OrderedBatch(
            paths = newPaths,
            names = indices.map { names.getOrElse(it) { paths[it].substringAfterLast('/') } },
            dates = indices.map { dates.getOrElse(it) { Long.MAX_VALUE } },
            sizes = sizes.filterKeys { it in newPaths.toSet() },
        )
    }

    /**
     * Rename temp files to `%04d_…` matching [paths] order so lexicographic
     * path sort matches analysis order. Returns updated paths (and remapped sizes).
     */
    @Suppress("ReturnCount") // two nothing-to-do exits before the rename pass
    fun reprefixTempFiles(
        paths: List<String>,
        names: List<String>,
        sizes: Map<String, Pair<Int, Int>>,
    ): Pair<List<String>, Map<String, Pair<Int, Int>>> {
        if (paths.isEmpty()) return emptyList<String>() to emptyMap()
        val parent = File(paths.first()).parentFile ?: return paths to sizes
        val staging = paths.mapIndexed { index, oldPath ->
            val old = File(oldPath)
            val base = names.getOrElse(index) { old.name }
                .replace(Regex("^\\d{4}_"), "")
                .replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val staged = File(parent, String.format(Locale.US, "_ord_%04d_%s", index, base))
            if (old.absolutePath != staged.absolutePath) {
                if (staged.exists()) staged.delete()
                old.renameTo(staged)
            }
            staged
        }
        val newPaths = mutableListOf<String>()
        val newSizes = mutableMapOf<String, Pair<Int, Int>>()
        staging.forEachIndexed { index, staged ->
            val base = staged.name.removePrefix(String.format(Locale.US, "_ord_%04d_", index))
            val final = File(parent, String.format(Locale.US, "%04d_%s", index, base))
            if (staged.absolutePath != final.absolutePath) {
                if (final.exists()) final.delete()
                staged.renameTo(final)
            }
            newPaths.add(final.absolutePath)
            sizes[paths[index]]?.let { newSizes[final.absolutePath] = it }
                ?: sizes[staged.absolutePath]?.let { newSizes[final.absolutePath] = it }
        }
        return newPaths to newSizes
    }

    /**
     * Date for an already-imported frame file. Used when sort-by-date is chosen
     * after a fast import that skipped URI EXIF probes.
     */
    fun resolveDateMs(file: File): Long = runCatching {
        val exif = ExifInterface(file.absolutePath)
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return@runCatching null
        val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        fmt.parse(raw)?.time
    }.getOrNull()?.takeIf { it > 0L }
        ?: file.lastModified().takeIf { it > 0L }
        ?: Long.MAX_VALUE
}
