package com.indicvision.semper.ui.home

import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.SessionDeletes
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.fixtures.sessionRecord
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.UUID

/**
 * The screen and controller every SessionSelection test drives: a bare
 * activity, a [SessionListAdapter], the bar's views, and what the
 * controller's callbacks were handed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
abstract class SessionSelectionFixture {

    @get:Rule
    val temp = TemporaryFolder()

    protected lateinit var activity: AppCompatActivity
    protected lateinit var controller: SessionSelectionController
    protected lateinit var adapter: SessionListAdapter

    protected lateinit var topBar: View
    protected lateinit var selectionBar: View
    protected lateinit var count: TextView
    protected lateinit var rename: ImageButton
    protected lateinit var restore: ImageButton
    protected lateinit var selectAll: MaterialCheckBox
    protected lateinit var fab: ImageButton
    protected lateinit var close: ImageButton
    protected lateinit var delete: ImageButton
    protected val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }
    protected var refreshes = 0
    protected var deviceOnlyDeletes = 0
    protected val queuedDeletes = mutableListOf<List<SessionDeletes.Item>>()
    protected val announced = mutableListOf<Pair<UUID, Int>>()
    protected val restored = mutableListOf<List<String>>()
    protected var restoreAllowed = true

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper) // Material dialogs need the app theme
        activity = built.setup().get()
        adapter = SessionListAdapter(isSelected = { controller.isSelected(it) }, onClick = {}, onLongClick = {})
        topBar = View(activity)
        selectionBar = View(activity).apply { visibility = View.GONE }
        count = TextView(activity)
        rename = ImageButton(activity)
        restore = ImageButton(activity).apply { visibility = View.GONE }
        selectAll = MaterialCheckBox(activity)
        fab = ImageButton(activity)
        close = ImageButton(activity)
        delete = ImageButton(activity)
        controller = SessionSelectionController(
            activity = activity,
            adapter = adapter,
            topBar = topBar,
            selectionBar = selectionBar,
            selectionCount = count,
            btnSelectionRename = rename,
            btnSelectionRestore = restore,
            selectAllBox = selectAll,
            fab = fab,
            backCallback = back,
            onRefresh = { refreshes++ },
            onDeviceOnlyDeleted = { deviceOnlyDeletes++ },
            restoreEnabled = { restoreAllowed },
            onRestore = { records -> restored += records.map { it.id } },
            onDeleteQueued = { id, items -> announced += id to items.size },
            enqueueDelete = { items ->
                queuedDeletes += items
                UUID(0L, queuedDeletes.size.toLong())
            },
        )
        controller.bindBarActions(btnClose = close, btnDelete = delete)
    }

    /** [local] gives the record a session dir holding a `.dat`, so it has phone data. */
    protected fun record(id: String, local: Boolean = true, cloud: Boolean = false): SessionRecord {
        val dir = File(temp.root, id).apply { mkdirs() }
        if (local) File(dir, "frame_0000.dat").writeBytes(ByteArray(32))
        return sessionRecord(
            id = id,
            name = "Specimen $id",
            createdAt = 0L,
            refPath = File(dir, "ref.png").path,
            sessionDir = dir.path,
            cloudSessionId = if (cloud) "cloud-$id" else "",
            syncState = if (cloud) SessionRecord.SyncState.SYNCED else SessionRecord.SyncState.LOCAL_ONLY,
        )
    }

    protected val a by lazy { record("a") }
    protected val b by lazy { record("b") }
    protected val c by lazy { record("c") }

    protected fun list(vararg records: SessionRecord) = submit(records.toList())

    /** Submits as Home does: phone presence read from disk once, up front. */
    protected fun submit(records: List<SessionRecord>) =
        adapter.submit(records, records.filterNot { it.hasLocalData() }.map { it.id }.toSet())

    protected fun latestDialog(): Dialog = ShadowDialog.getLatestDialog()

    protected fun dialogMessage(): String? =
        latestDialog().findViewById<TextView>(android.R.id.message)?.text?.toString()

    /** The labels of the choice dialog's buttons, top to bottom. */
    protected fun choices(): List<MaterialButton> {
        val box = latestDialog().findViewById<ViewGroup>(R.id.deleteChoices)
        return (0 until box.childCount).map { box.getChildAt(it) as MaterialButton }
    }

    protected fun pick(labelRes: Int) {
        val label = activity.getString(labelRes)
        choices().single { it.text.toString() == label }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    protected fun confirmPositive() {
        (latestDialog() as androidx.appcompat.app.AlertDialog)
            .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
