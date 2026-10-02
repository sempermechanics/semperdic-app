package com.indicvision.semper.ui.common.media

import android.app.Application
import android.app.Dialog
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.indicvision.semper.R
import com.indicvision.semper.fixtures.idleUntil
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * The in-sheet gallery picker behind Home's **+** and the wizard dropzones:
 * what it lists per mode, the permission path, and what a tap means — one pick
 * for a reference (after the hint has had its second), a toggled multi-select
 * for deformed frames. MediaStore is a fake provider holding a few rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaPickerSheetTest {

    /** A stand-in for MediaStore's `media` authority; rows are set per test. */
    class FakeMediaProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            lastSelectionArgs = selectionArgs?.toList().orEmpty()
            queriedOnMain = Looper.myLooper() == Looper.getMainLooper()
            val cursor = MatrixCursor(
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                ),
            )
            rows.filter { it.type.toString() in lastSelectionArgs }
                .forEach { cursor.addRow(arrayOf<Any>(it.id, it.name, it.mime, it.type)) }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        data class Row(val id: Long, val name: String, val mime: String, val type: Int)

        companion object {
            var rows: List<Row> = emptyList()
            var lastSelectionArgs: List<String> = emptyList()
            var queriedOnMain: Boolean? = null
        }
    }

    private lateinit var activity: AppCompatActivity
    private var permissionRequests = 0
    private var safBrowses = 0
    private val picks = mutableListOf<List<Uri>>()

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        activity = built.setup().get()
        Robolectric.buildContentProvider(FakeMediaProvider::class.java).create("media")
        FakeMediaProvider.rows = listOf(
            FakeMediaProvider.Row(11, "ref.png", "image/png", IMAGE),
            FakeMediaProvider.Row(12, "def_01.png", "image/png", IMAGE),
            FakeMediaProvider.Row(13, "clip.mp4", "video/mp4", VIDEO),
        )
        FakeMediaProvider.lastSelectionArgs = emptyList()
        FakeMediaProvider.queriedOnMain = null
        // Inline, so each test sees the grid as soon as the main thread idles.
        MediaPickerSheet.queryDispatcher = Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        MediaPickerSheet.queryDispatcher = Dispatchers.IO
    }

    private fun grantGallery() {
        shadowOf(activity.application).grantPermissions("android.permission.READ_MEDIA_IMAGES")
    }

    private fun open(mode: MediaSourceChooser.Mode): Dialog {
        MediaPickerSheet.show(
            activity,
            mode,
            requestPermission = { permissionRequests++ },
            onBrowseSaf = { safBrowses++ },
            onPicked = { picks += it },
        )
        idle()
        return ShadowDialog.getLatestDialog()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun Dialog.adapter(): MediaGridAdapter =
        findViewById<RecyclerView>(R.id.listMedia).adapter as MediaGridAdapter

    /** Binds tile [position] the way the grid would and taps it. */
    private fun Dialog.tap(position: Int): MediaGridAdapter.Holder {
        val adapter = adapter()
        val holder = adapter.createViewHolder(findViewById(R.id.listMedia), 0)
        adapter.bindViewHolder(holder, position)
        holder.itemView.performClick()
        return holder
    }

    private fun Dialog.useButton(): MaterialButton = findViewById(R.id.btnMediaUse)

    private fun imageUri(id: Long) = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())

    // ── Title and permission ─────────────────────────────────────────────────

    @Test
    fun `each mode is titled for what it picks`() {
        assertEquals(
            activity.getString(R.string.new_analysis_title),
            open(MediaSourceChooser.Mode.HOME_REFERENCE).findViewById<TextView>(R.id.tvMediaTitle).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.reference_image),
            open(MediaSourceChooser.Mode.REFERENCE).findViewById<TextView>(R.id.tvMediaTitle).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.deformed_frames),
            open(MediaSourceChooser.Mode.DEFORMED).findViewById<TextView>(R.id.tvMediaTitle).text.toString(),
        )
    }

    @Test
    fun `without gallery access it asks for it instead of listing`() {
        val sheet = open(MediaSourceChooser.Mode.DEFORMED)

        assertEquals(View.VISIBLE, sheet.findViewById<View>(R.id.mediaEmpty).visibility)
        assertEquals(View.GONE, sheet.findViewById<View>(R.id.listMedia).visibility)
        assertEquals(
            activity.getString(R.string.media_need_permission),
            sheet.findViewById<TextView>(R.id.tvMediaEmpty).text.toString(),
        )
        sheet.findViewById<View>(R.id.btnMediaAllow).performClick()
        assertEquals(1, permissionRequests)
    }

    @Test
    fun `a granted permission reloads the grid`() {
        val picker = MediaPickerSheet.show(activity, MediaSourceChooser.Mode.DEFORMED, {}, {}, {})
        idle()
        val sheet = ShadowDialog.getLatestDialog()
        assertEquals(0, sheet.adapter().itemCount)

        grantGallery()
        picker.onPermissionResult()

        assertEquals(2, sheet.adapter().itemCount)
        assertEquals(View.VISIBLE, sheet.findViewById<View>(R.id.listMedia).visibility)
        assertEquals(View.GONE, sheet.findViewById<View>(R.id.btnMediaAllow).visibility)
    }

    @Test
    fun `an empty gallery points at Files`() {
        grantGallery()
        FakeMediaProvider.rows = emptyList()
        val sheet = open(MediaSourceChooser.Mode.REFERENCE)

        assertEquals(
            activity.getString(R.string.media_empty),
            sheet.findViewById<TextView>(R.id.tvMediaEmpty).text.toString(),
        )
        assertEquals(View.GONE, sheet.findViewById<View>(R.id.btnMediaAllow).visibility)
    }

    @Test
    fun `the gallery is queried off the main thread`() {
        MediaPickerSheet.queryDispatcher = Dispatchers.IO
        grantGallery()
        MediaPickerSheet.show(activity, MediaSourceChooser.Mode.HOME_REFERENCE, {}, {}, {})
        val sheet = ShadowDialog.getLatestDialog()

        idleUntil("the grid to fill") { sheet.adapter().itemCount > 0 }

        assertEquals(false, FakeMediaProvider.queriedOnMain)
        assertEquals(3, sheet.adapter().itemCount)
    }

    // ── What is listed ───────────────────────────────────────────────────────

    @Test
    fun `only Home's new-analysis picker lists videos`() {
        grantGallery()
        val home = open(MediaSourceChooser.Mode.HOME_REFERENCE)
        assertEquals(3, home.adapter().itemCount)
        assertTrue("the video tile is badged", home.tap(2).video.visibility == View.VISIBLE)

        val wizard = open(MediaSourceChooser.Mode.REFERENCE)
        assertEquals(listOf(IMAGE.toString()), FakeMediaProvider.lastSelectionArgs)
        assertEquals(2, wizard.adapter().itemCount)
    }

    // ── Files hand-off ───────────────────────────────────────────────────────

    @Test
    fun `choosing Files closes the sheet and hands off to the system picker`() {
        val sheet = open(MediaSourceChooser.Mode.DEFORMED)
        sheet.findViewById<MaterialButtonToggleGroup>(R.id.toggleMediaSource).check(R.id.btnMediaFiles)

        assertEquals(1, safBrowses)
        assertFalse(sheet.isShowing)
    }

    // ── Deformed: multi-select ───────────────────────────────────────────────

    @Test
    fun `deformed frames toggle, and Use counts and returns them in tap order`() {
        grantGallery()
        val sheet = open(MediaSourceChooser.Mode.DEFORMED)
        assertEquals("nothing picked, nothing to use", View.GONE, sheet.useButton().visibility)

        sheet.tap(1)
        sheet.tap(0)
        assertEquals(View.VISIBLE, sheet.useButton().visibility)
        assertEquals("Use 2 images", sheet.useButton().text.toString())

        sheet.tap(1) // untick
        assertEquals("Use 1 image", sheet.useButton().text.toString())
        sheet.tap(1)

        sheet.useButton().performClick()
        assertEquals(listOf(listOf(imageUri(11), imageUri(12))), picks)
        assertFalse(sheet.isShowing)
    }

    @Test
    fun `a ticked tile is shown ticked when it is rebound`() {
        grantGallery()
        val sheet = open(MediaSourceChooser.Mode.DEFORMED)
        sheet.tap(0)

        val adapter = sheet.adapter()
        val holder = adapter.createViewHolder(sheet.findViewById(R.id.listMedia), 0)
        adapter.bindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.check.visibility)
        adapter.bindViewHolder(holder, 1)
        assertEquals(View.GONE, holder.check.visibility)
    }

    // ── Reference: one pick, after the hint ──────────────────────────────────

    @Test
    fun `a reference tap is held off until the hint has been shown`() {
        grantGallery()
        val sheet = open(MediaSourceChooser.Mode.REFERENCE)

        sheet.tap(1)
        assertTrue("too early: the hint is still up", picks.isEmpty())
        assertTrue(sheet.isShowing)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        sheet.tap(1)
        assertEquals(listOf(listOf(imageUri(12))), picks)
        assertFalse("a single pick closes the sheet", sheet.isShowing)
    }

    private companion object {
        const val IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        const val VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
    }
}
