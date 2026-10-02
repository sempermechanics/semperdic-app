package com.indicvision.semper.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.fixtures.idleUntil
import com.indicvision.semper.fixtures.launchViewer
import com.indicvision.semper.fixtures.viewerArgs
import com.indicvision.semper.fixtures.viewerController
import com.indicvision.semper.fixtures.writeGridBatch
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import com.indicvision.semper.ui.viewer.ResultViewerViewModel
import com.indicvision.semper.ui.viewer.ViewerSweepArgs
import com.indicvision.semper.ui.viewer.share.ShareCenter
import com.indicvision.semper.ui.viewer.share.ShareExportBuilder
import com.indicvision.semper.ui.viewer.share.ShareKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.Executor

/**
 * The viewer's "save to Files" export, end to end from a real viewer: the CSV
 * it writes to the picked document, and the per-frame pitch every sweep export
 * renders with. The PDF kinds need the platform's PdfDocument and are covered
 * on a device (PdfReportDeviceTest).
 */
@RunWith(RobolectricTestRunner::class)
class ShareCenterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 3
        const val GRID = 4
        const val STEP = 4
    }

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, FRAMES, GRID, STEP)
    }

    private fun viewer(frameNames: List<String> = emptyList()): ResultViewerActivity {
        val activity = launchViewer(viewerArgs(batchDir, GRID, STEP, frameNames))
        idleUntil("the viewer") { activity.buildShareSnapshot() != null }
        return activity
    }

    /** The in-app pill CrispToast adds over the content, or null when none is up. */
    private fun pillText(activity: ResultViewerActivity): String? =
        activity.findViewById<ViewGroup>(android.R.id.content)
            .findViewById<TextView>(R.id.tvToast)?.text?.toString()

    @Test
    fun `save to Files writes the whole batch as one csv`() {
        val activity = viewer()
        val dest = File(temp.root, "picked.csv")
        ShareCenter(activity).writeKindToUri("csv", Uri.fromFile(dest))
        idleUntil("the save toast") { ShadowToast.getLatestToast() != null }

        assertEquals(activity.getString(R.string.save_success), ShadowToast.getTextOfLatestToast())
        val lines = dest.readLines()
        val header = lines.indexOfFirst { it.startsWith("image,") }
        assertTrue("one point header", header >= 0 && lines.count { it.startsWith("image,") } == 1)
        assertTrue("field stats all sit above the points", lines.drop(header).none { it.startsWith("# ") })
        for (f in 1..FRAMES) {
            assertEquals(GRID * GRID, lines.count { it.startsWith("Frame_$f,") })
        }
    }

    /** The share CSV's point rows, keyed by their image column. */
    private fun csvRowsByImage(activity: ResultViewerActivity): Map<String, Int> {
        val dest = File(temp.root, "picked_${System.nanoTime()}.csv")
        ShareCenter(activity).writeKindToUri("csv", Uri.fromFile(dest))
        idleUntil("the save toast") { ShadowToast.getLatestToast() != null }
        val lines = dest.readLines()
        val header = lines.indexOfFirst { it.startsWith("image,") }
        return lines.drop(header + 1).filter { it.isNotBlank() }.groupingBy { it.substringBefore(',') }.eachCount()
    }

    @Test
    fun `past a skipped frame every frame keeps its own name`() {
        // The batch skipped frame 2 (b.png): frame_001 was never written.
        File(batchDir, "frame_001.dat").delete()
        val activity = viewer(listOf("a.png", "b.png", "c.png"))

        assertEquals("c.png", activity.frameDisplayName(1))
        val snapshot = activity.buildShareSnapshot()!!
        assertEquals(2, snapshot.plannedAt(1))
        assertEquals("c.png", snapshot.nameAt(1))
        // Same rows as the cloud bundle's CSV (SessionUploadBundlerTest).
        assertEquals(mapOf("a.png" to GRID * GRID, "c.png" to GRID * GRID), csvRowsByImage(activity))
    }

    @Test
    fun `an unnamed frame past a skipped one is numbered as planned`() {
        File(batchDir, "frame_001.dat").delete()
        val activity = viewer()

        assertEquals("Frame 3", activity.frameDisplayName(1))
        assertEquals(mapOf("Frame_1" to GRID * GRID, "Frame_3" to GRID * GRID), csvRowsByImage(activity))
    }

    @Test
    fun `an unknown kind reports failure instead of saving`() {
        val activity = viewer()
        val dest = File(temp.root, "picked.bin")
        ShareCenter(activity).writeKindToUri("bogus", Uri.fromFile(dest))
        idleUntil("the failure pill") { pillText(activity) != null }

        assertEquals(activity.getString(R.string.share_failed), pillText(activity))
        assertTrue(!dest.exists() || dest.length() == 0L)
    }

    /** The viewer, resumed and its frames listed; the caller keeps it to recreate it. */
    private fun controller(): ActivityController<ResultViewerActivity> {
        val controller = viewerController(viewerArgs(batchDir, GRID, STEP))
        idleUntil("the viewer") { controller.get().buildShareSnapshot() != null }
        return controller
    }

    /**
     * The viewer, not yet created, with its frame listing held in [held]: a
     * viewer the system just restored has not read its frames yet.
     */
    private fun heldController(held: MutableList<Runnable>): ActivityController<ResultViewerActivity> {
        val intent = viewerArgs(batchDir, GRID, STEP).toIntent(ApplicationProvider.getApplicationContext())
        val controller = Robolectric.buildActivity(ResultViewerActivity::class.java, intent)
        controller.get().frameSetDispatcher = Executor { held += it }.asCoroutineDispatcher()
        return controller
    }

    /** The CSV export's point rows per image column, from a file it saved. */
    private fun rowsByImage(csv: File): Map<String, Int> {
        val lines = csv.readLines()
        val header = lines.indexOfFirst { it.startsWith("image,") }
        return lines.drop(header + 1).filter { it.isNotBlank() }.groupingBy { it.substringBefore(',') }.eachCount()
    }

    @Test
    fun `an export the user is waiting on survives a rotation`() {
        val controller = controller()
        val activity = controller.get()
        val dest = File(temp.root, "picked.csv")

        ShareCenter(activity).writeKindToUri("csv", Uri.fromFile(dest))
        // Rotate before the job can report back to the main thread.
        val rebuilt = controller.recreate().get()
        idleUntil("the save toast") { ShadowToast.getLatestToast() != null || pillText(rebuilt) != null }

        assertEquals(rebuilt.getString(R.string.save_success), ShadowToast.getTextOfLatestToast())
        assertEquals((1..FRAMES).associate { "Frame_$it" to GRID * GRID }, rowsByImage(dest))
    }

    @Test
    fun `a save-as picked across a rotation saves from the rebuilt viewer`() {
        val controller = controller()
        val activity = controller.get()
        activity.pickShareDocument(ShareKind.CSV, "picked.csv")
        val picker = shadowOf(activity).nextStartedActivityForResult

        // Rotated while the picker was up: its answer reaches the new viewer.
        val rebuilt = controller.recreate().get()
        val dest = File(temp.root, "picked.csv")
        rebuilt.activityResultRegistry.dispatchResult(
            picker.requestCode,
            Activity.RESULT_OK,
            Intent().setData(Uri.fromFile(dest)),
        )
        idleUntil("the save toast") { ShadowToast.getLatestToast() != null || pillText(rebuilt) != null }

        assertEquals(rebuilt.getString(R.string.save_success), ShadowToast.getTextOfLatestToast())
        assertEquals((1..FRAMES).associate { "Frame_$it" to GRID * GRID }, rowsByImage(dest))
    }

    @Test
    fun `a save-as answer that lands before the frames are read still saves`() {
        val held = mutableListOf<Runnable>()
        val activity = heldController(held).setup().get()
        activity.pickShareDocument(ShareKind.CSV, "picked.csv")
        val picker = shadowOf(activity).nextStartedActivityForResult
        val dest = File(temp.root, "picked.csv")

        activity.activityResultRegistry.dispatchResult(
            picker.requestCode,
            Activity.RESULT_OK,
            Intent().setData(Uri.fromFile(dest)),
        )
        shadowOf(activity.mainLooper).idle()
        assertNull("no 'share failed' for a viewer still loading", pillText(activity))
        assertEquals(null, activity.rawData)

        held.toList().forEach { it.run() }
        idleUntil("the save toast") { ShadowToast.getLatestToast() != null || pillText(activity) != null }

        assertEquals(activity.getString(R.string.save_success), ShadowToast.getTextOfLatestToast())
        assertEquals((1..FRAMES).associate { "Frame_$it" to GRID * GRID }, rowsByImage(dest))
    }

    @Test
    fun `a save-as waiting on the frames survives a rotation and starts once`() {
        val held = mutableListOf<Runnable>()
        val controller = heldController(held)
        val activity = controller.setup().get()
        activity.pickShareDocument(ShareKind.CSV, "picked.csv")
        val picker = shadowOf(activity).nextStartedActivityForResult
        val dest = File(temp.root, "picked.csv")
        activity.activityResultRegistry.dispatchResult(
            picker.requestCode,
            Activity.RESULT_OK,
            Intent().setData(Uri.fromFile(dest)),
        )

        // Rotated before the frames were listed: the rebuilt viewer, which
        // reads them, starts the export the old one was waiting to.
        val rebuilt = controller.recreate().get()
        idleUntil("the save toast") { ShadowToast.getLatestToast() != null || pillText(rebuilt) != null }
        assertEquals(rebuilt.getString(R.string.save_success), ShadowToast.getTextOfLatestToast())
        assertEquals((1..FRAMES).associate { "Frame_$it" to GRID * GRID }, rowsByImage(dest))

        // The save was handed out once: neither a later rotation nor the old
        // viewer's frame listing finishing starts a second export.
        dest.delete()
        held.toList().forEach { it.run() }
        val again = controller.recreate().get()
        repeat(5) { shadowOf(again.mainLooper).idle() }
        assertEquals(1, ShadowToast.shownToastCount())
        assertTrue("no second export", !dest.exists())
    }

    /**
     * Starts a share job on [activity] that waits for [release], then hands
     * back a staged CSV for the "Send to" sheet. Returns the job's id.
     */
    private fun heldShareJob(activity: ResultViewerActivity, release: CompletableDeferred<Unit>): String {
        val app = activity.applicationContext
        activity.shareExports.start(ShareKind.CSV, "Exporting", destUri = null, direct = false) {
            release.await()
            val file = File(ShareExportBuilder.newJobDir(app.cacheDir), "held.csv").apply { writeText("a,b\n1,2\n") }
            file to "text/csv"
        }
        shadowOf(activity.mainLooper).idle()
        return ViewModelProvider(activity)[ResultViewerViewModel::class.java].exports.running.value.keys.single()
    }

    /** How many "Send to" sheets (SendToSheet) any viewer has shown in this test. */
    private fun sendToSheetsShown(): Int =
        ShadowDialog.getShownDialogs().count { it.findViewById<View>(R.id.rowSendSave) != null }

    /** Releases the held job and waits for its result, then for anything else that follows. */
    private fun releaseAndSettle(rebuilt: ResultViewerActivity, release: CompletableDeferred<Unit>) {
        release.complete(Unit)
        idleUntil("the Send-to sheet") { sendToSheetsShown() > 0 }
        repeat(5) { shadowOf(rebuilt.mainLooper).idle() }
    }

    @Test
    fun `a share watched in its dialog across a rotation is offered once`() {
        val controller = controller()
        val activity = controller.get()
        val release = CompletableDeferred<Unit>()
        heldShareJob(activity, release)
        assertTrue("progress dialog up", ShadowDialog.getLatestDialog()?.isShowing == true)

        val rebuilt = controller.recreate().get()
        shadowOf(rebuilt.mainLooper).idle()
        assertTrue("the rebuilt viewer shows the dialog again", ShadowDialog.getLatestDialog()?.isShowing == true)

        releaseAndSettle(rebuilt, release)
        assertEquals("the result reaches the Send-to sheet exactly once", 1, sendToSheetsShown())
    }

    @Test
    fun `a share sent to the banner across a rotation is offered once`() {
        val controller = controller()
        val activity = controller.get()
        val release = CompletableDeferred<Unit>()
        val id = heldShareJob(activity, release)
        ViewModelProvider(activity)[ResultViewerViewModel::class.java].exports.sendToBackground(id)
        shadowOf(activity.mainLooper).idle()
        assertTrue(activity.shareBanner.contains(id))

        val rebuilt = controller.recreate().get()
        shadowOf(rebuilt.mainLooper).idle()
        assertTrue("the rebuilt viewer's banner shows the job", rebuilt.shareBanner.contains(id))

        releaseAndSettle(rebuilt, release)
        assertEquals("the result reaches the Send-to sheet exactly once", 1, sendToSheetsShown())
        assertTrue("off the banner once delivered", !rebuilt.shareBanner.contains(id))
    }

    @Test
    fun `two exports of the same file name each keep their own bytes`() {
        val activity = viewer()
        val whole = activity.buildShareSnapshot()!!
        val oneFrame = whole.copy(batchFiles = whole.batchFiles.take(1))
        val app = activity.applicationContext

        // Same base name, so both write "<base>_data.csv". One shared directory
        // let the second job truncate the file the first was still handing over.
        val (first, _) = runBlocking {
            ShareExportBuilder(whole, app.resources, ShareExportBuilder.newJobDir(app.cacheDir))
                .produce(ShareKind.CSV) { _, _ -> }
        }
        val (second, _) = runBlocking {
            ShareExportBuilder(oneFrame, app.resources, ShareExportBuilder.newJobDir(app.cacheDir))
                .produce(ShareKind.CSV) { _, _ -> }
        }

        assertEquals(first.name, second.name)
        assertNotEquals(first.absolutePath, second.absolutePath)
        // Inside cacheDir/share, which the FileProvider serves and CacheJanitor sweeps.
        val shareDir = CacheJanitor.shareDir(app.cacheDir).canonicalFile
        assertTrue(first.canonicalPath.startsWith(shareDir.path + File.separator))
        assertEquals((1..FRAMES).associate { "Frame_$it" to GRID * GRID }, rowsByImage(first))
        assertEquals(mapOf("Frame_1" to GRID * GRID), rowsByImage(second))
    }

    @Test
    fun `an export's snapshot does not reach the viewer`() {
        val activity = viewer()
        val snapshot = activity.buildShareSnapshot()!!

        // A job keeps its snapshot until it ends, past a rotation; anything in it
        // that reaches the Activity keeps the destroyed viewer (and its views) alive.
        val leak = reachableFrom(snapshot).firstOrNull { it is Context }
        assertNull("snapshot reaches $leak", leak)
    }

    /** Every object [root]'s instance fields lead to, java.* and android.* internals aside. */
    private fun reachableFrom(root: Any): Sequence<Any> = sequence {
        val seen = java.util.IdentityHashMap<Any, Unit>()
        val queue = ArrayDeque<Any>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (seen.put(node, Unit) == null) {
                yield(node)
                queue.addAll(referencesOf(node))
            }
        }
    }

    /** What [node] points at: a container's elements, else its own instance fields. */
    private fun referencesOf(node: Any): List<Any> = when (node) {
        is Array<*> -> node.filterNotNull()
        is Collection<*> -> node.filterNotNull()
        is Map<*, *> -> node.keys.filterNotNull() + node.values.filterNotNull()
        else -> generateSequence<Class<*>>(node.javaClass) { it.superclass }
            .takeWhile { !it.name.startsWith("java.") && !it.name.startsWith("android.") }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
            .mapNotNull { field ->
                field.isAccessible = true
                field.get(node)
            }
            .toList()
    }

    @Test
    fun `a sweep renders each frame at its own pitch`() {
        val base = viewer().buildShareSnapshot()!!
        val source = base.reportSource
        val sweepArgs = ViewerSweepArgs(
            subsets = emptyList(),
            steps = listOf(3, 5),
            strainWindows = emptyList(),
            lineCutHorizontal = true,
            skippedJson = "[]",
        )
        val sweep = base.copy(reportSource = source.copy(args = source.args.copy(sweep = sweepArgs)))
        assertEquals(3, sweep.stepAt(0))
        assertEquals(5, sweep.stepAt(1))
        assertEquals("past the sweep's list, the shared step", STEP, sweep.stepAt(2))
        assertEquals(STEP, base.stepAt(1))
    }
}
