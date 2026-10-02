package com.indicvision.semper.ui.home

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.TransferPhase
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.fixtures.sessionRecord
import com.indicvision.semper.ui.common.transfer.TransferWorkObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One Home row: the subtitle that says how a run ended, the sync badge and
 * upload bar, the selection paint, and which rows get rebound when progress,
 * cloud presence or selection change — the list must never repaint rows
 * whose state did not move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionListAdapterTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var parent: FrameLayout

    private val selected = mutableSetOf<String>()
    private val clicked = mutableListOf<String>()
    private val longClicked = mutableListOf<String>()
    private val badgeClicked = mutableListOf<String>()
    private val adapter = SessionListAdapter(
        isSelected = { it in selected },
        onClick = { clicked += it.id },
        onLongClick = { longClicked += it.id },
        onBadgeClick = { badgeClicked += it.id },
    )

    /** Positions rebound through notifyItemChanged, and whole-list refreshes. */
    private val changed = mutableListOf<Int>()
    private var fullRefreshes = 0

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_Semper) // Material rows need the app theme
        activity = controller.setup().get()
        parent = FrameLayout(activity)
        adapter.registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    fullRefreshes++
                }

                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                    for (p in positionStart until positionStart + itemCount) changed += p
                }
            },
        )
    }

    @Suppress("LongParameterList") // named, defaulted knobs of one SessionRecord
    private fun record(
        id: String,
        frameCount: Int = 12,
        sync: SessionRecord.SyncState = SessionRecord.SyncState.LOCAL_ONLY,
        headline: String = "",
        stopCode: Int = 0,
        planned: Int = 0,
        sweep: Boolean = false,
    ) = sessionRecord(
        id = id,
        name = "Specimen $id",
        createdAt = 0L,
        frameCount = frameCount,
        refPath = "/nonexistent/$id/ref.png",
        sessionDir = "/nonexistent/$id",
        syncState = sync,
    ).copy(
        headline = headline,
        stopCode = stopCode,
        plannedFrameCount = planned,
        sweepSteps = if (sweep) listOf(5, 7) else emptyList(),
    )

    private fun bind(position: Int): SessionListAdapter.Holder =
        adapter.createViewHolder(parent, 0).also { adapter.bindViewHolder(it, position) }

    private val SessionListAdapter.Holder.badge get() = row.sessionBadge
    private val SessionListAdapter.Holder.subtitle get() = row.sessionSubtitle
    private val SessionListAdapter.Holder.thumb get() = row.sessionThumb
    private val SessionListAdapter.Holder.card get() = row.sessionCard
    private val SessionListAdapter.Holder.check get() = row.sessionCheck
    private val SessionListAdapter.Holder.progressBar get() = row.sessionProgress

    private fun submit(vararg records: SessionRecord, withoutLocalData: Set<String> = emptySet()) {
        adapter.submit(records.toList(), withoutLocalData)
        changed.clear()
        fullRefreshes = 0
    }

    // ── Subtitle ─────────────────────────────────────────────────────────────

    @Test
    fun `a finished run reads as its frame count and headline`() {
        submit(record("a", frameCount = 12, headline = "97.5% converged"))
        val subtitle = bind(0).subtitle.text.toString()

        assertTrue(subtitle, subtitle.endsWith(" · 12 frames · 97.5% converged"))
    }

    @Test
    fun `a run cut short says how far it got and why`() {
        val code = 3
        submit(record("a", frameCount = 39, stopCode = code, planned = 50))
        val subtitle = bind(0).subtitle.text.toString()

        assertTrue(subtitle, subtitle.contains(" · 39 of 50 frames"))
        assertTrue(subtitle, subtitle.endsWith(" · Unknown engine error (code 3)"))
    }

    @Test
    fun `a record with no planned count falls back to the plain count`() {
        submit(record("a", frameCount = 39, stopCode = 3, planned = 0))
        val subtitle = bind(0).subtitle.text.toString()

        assertTrue(subtitle, subtitle.contains(" · 39 frames"))
        assertFalse(subtitle, subtitle.contains(" of "))
    }

    @Test
    fun `a parameter sweep is named as one, not counted in frames`() {
        submit(record("a", frameCount = 9, sweep = true))
        val subtitle = bind(0).subtitle.text.toString()

        assertTrue(subtitle, subtitle.contains(activity.getString(R.string.session_sweep_kind)))
        assertFalse(subtitle, subtitle.contains("frames"))
    }

    // ── Sync badge and upload bar ────────────────────────────────────────────

    @Test
    fun `each sync state gets its badge`() {
        submit(
            record("s", sync = SessionRecord.SyncState.SYNCED),
            record("p", sync = SessionRecord.SyncState.PENDING),
            record("l", sync = SessionRecord.SyncState.LOCAL_ONLY),
            record("f", sync = SessionRecord.SyncState.FAILED),
        )
        val badges = (0 until 4).map { bind(it).badge.text.toString() }

        assertEquals(
            listOf(R.string.badge_synced, R.string.badge_pending, R.string.badge_local, R.string.badge_not_backed_up)
                .map(activity::getString),
            badges,
        )
    }

    @Test
    fun `only a failed backup is painted as a danger`() {
        submit(record("f", sync = SessionRecord.SyncState.FAILED), record("s", sync = SessionRecord.SyncState.SYNCED))
        assertEquals(activity.getColor(R.color.semantic_danger), bind(0).badge.currentTextColor)
        assertEquals(activity.getColor(R.color.sky_on_container), bind(1).badge.currentTextColor)
    }

    @Test
    fun `a synced row with no local frames says it is only in the cloud`() {
        submit(record("s", sync = SessionRecord.SyncState.SYNCED), withoutLocalData = setOf("s"))
        assertEquals(activity.getString(R.string.badge_cloud_only), bind(0).badge.text.toString())
    }

    @Test
    fun `a row without frames that is not backed up keeps its own badge`() {
        submit(
            record("p", sync = SessionRecord.SyncState.PENDING),
            record("s", sync = SessionRecord.SyncState.SYNCED),
            withoutLocalData = setOf("p"),
        )
        assertEquals(activity.getString(R.string.badge_pending), bind(0).badge.text.toString())
        assertEquals("frames on the phone", activity.getString(R.string.badge_synced), bind(1).badge.text.toString())
    }

    @Test
    fun `phone presence is what the list was submitted with, not a disk read`() {
        // Every record here points at a directory that does not exist; the
        // adapter must answer from the IO-computed set alone.
        submit(record("a"), record("b"), withoutLocalData = setOf("b"))
        assertTrue(adapter.hasLocalData("a"))
        assertFalse(adapter.hasLocalData("b"))
    }

    @Test
    fun `a running upload shows its percent and a determinate bar`() {
        submit(record("a"))
        adapter.setUploadProgress(mapOf("a" to TransferWorkObserver.RowProgress(TransferPhase.UPLOAD, 42)))
        val row = bind(0)

        assertEquals("uploading 42%", row.badge.text.toString())
        assertTrue(row.progressBar.isVisible())
        assertFalse(row.progressBar.isIndeterminate)
        assertEquals(42, row.progressBar.progress)
    }

    @Test
    fun `a bundle download at zero percent spins instead of sitting empty`() {
        submit(record("a"))
        adapter.setUploadProgress(mapOf("a" to TransferWorkObserver.RowProgress(TransferPhase.DOWNLOAD, 0)))
        val row = bind(0)

        assertTrue(row.progressBar.isIndeterminate)
        assertEquals("downloading 0%", row.badge.text.toString())
    }

    @Test
    fun `a demo account shows no badge, no bar and no badge action`() {
        submit(record("a", sync = SessionRecord.SyncState.SYNCED))
        adapter.setUploadProgress(mapOf("a" to TransferWorkObserver.RowProgress(TransferPhase.UPLOAD, 42)))
        adapter.setSyncVisible(false)
        val row = bind(0)

        assertFalse(row.badge.isVisible())
        assertFalse(row.progressBar.isVisible())
        assertFalse(row.badge.hasOnClickListeners())
    }

    // ── Selection paint and clicks ───────────────────────────────────────────

    @Test
    fun `a selected row is ticked and outlined`() {
        submit(record("a"), record("b"))
        selected += "b"

        val plain = bind(0)
        val picked = bind(1)
        assertFalse(plain.check.isVisible())
        assertTrue(picked.check.isVisible())
        assertEquals(activity.getColor(R.color.sky_primary), picked.card.strokeColor)
        assertEquals(activity.getColor(R.color.surface_outline), plain.card.strokeColor)
        assertEquals(activity.getColor(R.color.sky_container), picked.card.cardBackgroundColor.defaultColor)
    }

    @Test
    fun `taps, long presses and badge taps reach the owner with the row's record`() {
        submit(record("a"), record("b"))
        val row = bind(1)

        row.itemView.performClick()
        assertTrue(row.itemView.performLongClick())
        row.badge.performClick()

        assertEquals(listOf("b"), clicked)
        assertEquals(listOf("b"), longClicked)
        assertEquals(listOf("b"), badgeClicked)
    }

    @Test
    fun `a row whose reference image is gone has no thumbnail`() {
        submit(record("a"))
        assertNull(bind(0).thumb.drawable)
    }

    // ── What gets rebound ────────────────────────────────────────────────────

    @Test
    fun `progress rebinds only the rows whose progress moved`() {
        submit(record("a"), record("b"), record("c"))
        adapter.setUploadProgress(mapOf("b" to TransferWorkObserver.RowProgress(TransferPhase.UPLOAD, 10)))
        assertEquals(listOf(1), changed)

        changed.clear()
        adapter.setUploadProgress(
            mapOf(
                "b" to TransferWorkObserver.RowProgress(TransferPhase.UPLOAD, 10),
                "c" to TransferWorkObserver.RowProgress(TransferPhase.PREPARE, 0),
            ),
        )
        assertEquals("b unchanged, c new", listOf(2), changed)

        changed.clear()
        adapter.setUploadProgress(mapOf("b" to TransferWorkObserver.RowProgress(TransferPhase.UPLOAD, 10)))
        assertEquals("c finished", listOf(2), changed)
        assertEquals(0, fullRefreshes)
    }

    @Test
    fun `the same progress again rebinds nothing`() {
        submit(record("a"))
        val progress = mapOf("a" to TransferWorkObserver.RowProgress(TransferPhase.UPLOAD, 50))
        adapter.setUploadProgress(progress)
        changed.clear()

        adapter.setUploadProgress(progress.toMap())
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `rebinding an id that is not listed is a no-op`() {
        submit(record("a"))
        adapter.rebindRow("gone")
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `hiding sync repaints the list once, and only when it changes`() {
        submit(record("a"))
        adapter.setSyncVisible(true)
        assertEquals(0, fullRefreshes)
        adapter.setSyncVisible(false)
        assertEquals(1, fullRefreshes)
    }

    @Test
    fun `records for a selection come back in list order`() {
        submit(record("a"), record("b"), record("c"))
        assertEquals(listOf("a", "c"), adapter.recordsFor(linkedSetOf("c", "a", "zzz")).map { it.id })
        assertEquals(listOf("a", "b", "c"), adapter.allIds())
    }

    private fun View.isVisible() = visibility == View.VISIBLE
}
