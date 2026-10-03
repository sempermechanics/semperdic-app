package com.sempermechanics.semper.fixtures

import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import com.sempermechanics.semper.ui.viewer.ViewerArgs
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import java.io.File

/**
 * Viewer arguments for a [writeGridBatch] batch in [batchDir]: the image is the
 * whole [grid] × [step] square, and the viewer opens on the first frame rather
 * than the summary.
 */
fun viewerArgs(batchDir: File, grid: Int, step: Int, frameNames: List<String> = emptyList()) = ViewerArgs.ofFrames(
    batchDir.absolutePath,
    grid * step,
    grid * step,
    step,
    frameNames = frameNames,
    startFrame = 0,
)

/** The viewer for [args], created through resume; the caller keeps it to recreate it. */
fun viewerController(args: ViewerArgs): ActivityController<ResultViewerActivity> {
    val intent = args.toIntent(ApplicationProvider.getApplicationContext())
    return Robolectric.buildActivity(ResultViewerActivity::class.java, intent).setup()
}

/** The viewer for [args], resumed. Its frames are still loading off the main thread. */
fun launchViewer(args: ViewerArgs): ResultViewerActivity = viewerController(args).get()
