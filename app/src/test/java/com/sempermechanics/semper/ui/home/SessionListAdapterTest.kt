package com.sempermechanics.semper.ui.home

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.dynamicanimation.animation.SpringFrames
import androidx.recyclerview.widget.RecyclerView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.TransferPhase
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.ui.common.transfer.TransferWorkObserver
import org.junit.After
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One Home row: the subtitle that says how a run ended, the state icon and
 * transfer bar, the selection paint, and which rows get rebound when progress,
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

    /** Every fixture row was created at 0: the date its subtitle ends with. */
    private val date = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(0L))

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

    /** A bound row's bar springs to its percent, and no frame ends it here (TD-200). */
    @After
    fun tearDown() = SpringFrames.endAll()

    @Suppress("LongParameterList") // named, defaulted knobs of one SessionRecord
    private fun record(
        id: String,
        frameCount: Int = 12,
        sync: SessionRecord.SyncState = SessionRecord.SyncState.LOCAL_ONLY,
        headline: String = "",
        stopCode: Int = 0,
        planned: Int = 0,
        sweep: Boolean = false,
        convergence: Float? = null,
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
        engineStats = convergence?.let { c ->
            List(EngineStats.SLOT_CONVERGENCE + 1) { if (it == EngineStats.SLOT_CONVERGENCE) c else 0f }
        }.orEmpty(),
    )

    private fun bind(position: Int): SessionListAdapter.Holder =
        adapter.createViewHolder(parent, 0).also { adapter.bindViewHolder(it, position) }

    private val SessionListAdapter.Holder.state get() = row.sessionState
    private val SessionListAdapter.Holder.subtitle get() = row.sessionSubtitle
    private val SessionListAdapter.Holder.thumb get() = row.sessionThumb
    private val SessionListAdapter.Holder.resultThumb get() = row.sessionResultThumb
    private val SessionListAdapter.Holder.check get() = row.sessionCheck
    private val SessionListAdapter.Holder.progressBar get() = row.sessionProgress
    private val SessionListAdapter.Holder.stateWords get() = state.contentDescription.toString()
    private val SessionListAdapter.Holder.stateTint get() = state.imageTintList?.defaultColor

    private fun submit(vararg records: SessionRecord, withoutLocalData: Set<String> = emptySet()) {
        adapter.submit(records.toList(), withoutLocalData)
        changed.clear()
        fullRefreshes = 0
    }

    private fun progress(phase: TransferPhase, percent: Int, done: Long? = null, total: Long? = null) =
        TransferWorkObserver.RowProgress(phase, percent, done, total)

    /** The [ForegroundColorSpan]s of [text], as (covered text, colour). */
    private fun colouredParts(text: CharSequence): List<Pair<String, Int>> {
        val spanned = text as? Spanned ?: return emptyList()
        return spanned.getSpans(0, text.length, ForegroundColorSpan::class.java).map {
            text.subSequence(spanned.getSpanStart(it), spanned.getSpanEnd(it)).toString() to it.foregroundColor
        }
    }

    // ── Subtitle ─────────────────────────────────────────────────────────────

    @Test
    fun `a finished run reads as frames, convergence and date`() {
        submit(record("a", frameCount = 40, convergence = 96.34f))
        val subtitle = bind(0).subtitle.text

        assertEquals("40 frames · 96.3% converged · $date", subtitle.toString())
        assertTrue("above 85% is not flagged", colouredParts(subtitle).isEmpty())
    }

    @Test
    fun `a convergence under 85 percent paints only its figure amber`() {
        submit(record("low", convergence = 84.9f), record("edge", convergence = 85f))

        val low = bind(0).subtitle.text
        assertEquals("12 frames · 84.9% converged · $date", low.toString())
        assertEquals(listOf("84.9%" to activity.getColor(R.color.semantic_warning)), colouredParts(low))
        assertTrue("85.0 exactly is fine", colouredParts(bind(1).subtitle.text).isEmpty())
    }

    @Test
    fun `a row without engine stats keeps its stored headline`() {
        submit(record("a", frameCount = 12, headline = "97.5% converged on frame 1"))
        val subtitle = bind(0).subtitle.text.toString()

        assertEquals("12 frames · 97.5% converged on frame 1 · $date", subtitle)
    }

    @Test
    fun `a run cut short says how far it got and why, before the date`() {
        val code = 3
        submit(record("a", frameCount = 39, stopCode = code, planned = 50, convergence = 90f))
        val subtitle = bind(0).subtitle.text.toString()

        assertEquals("39 of 50 frames · 90.0% converged · Unknown engine error (code 3) · $date", subtitle)
    }

    @Test
    fun `a record with no planned count falls back to the plain count`() {
        submit(record("a", frameCount = 39, stopCode = 3, planned = 0))
        val subtitle = bind(0).subtitle.text.toString()

        assertTrue(subtitle, subtitle.startsWith("39 frames · "))
        assertFalse(subtitle, subtitle.contains(" of "))
    }

    @Test
    fun `a parameter sweep is named as one with its headline, not counted in frames`() {
        submit(record("a", frameCount = 9, sweep = true, headline = "plate · 9 of 9 solved", convergence = 50f))
        val subtitle = bind(0).subtitle.text.toString()

        assertEquals("${activity.getString(R.string.session_sweep_kind)} · plate · 9 of 9 solved · $date", subtitle)
    }

    // ── State icon and transfer bar ──────────────────────────────────────────

    @Test
    fun `each sync state gets its icon words`() {
        submit(
            record("s", sync = SessionRecord.SyncState.SYNCED),
            record("p", sync = SessionRecord.SyncState.PENDING),
            record("l", sync = SessionRecord.SyncState.LOCAL_ONLY),
            record("f", sync = SessionRecord.SyncState.FAILED),
        )
        val words = (0 until 4).map { bind(it).stateWords }

        assertEquals(listOf("Backed up", "Upload pending", "Not backed up", "Not backed up"), words)
    }

    @Test
    fun `only a failed backup is painted as a danger`() {
        submit(
            record("f", sync = SessionRecord.SyncState.FAILED),
            record("l", sync = SessionRecord.SyncState.LOCAL_ONLY),
        )
        assertEquals(activity.getColor(R.color.semantic_danger), bind(0).stateTint)
        assertEquals(activity.getColor(R.color.text_secondary), bind(1).stateTint)
    }

    @Test
    fun `a synced row with no local frames says it is only in the cloud`() {
        submit(record("s", sync = SessionRecord.SyncState.SYNCED), withoutLocalData = setOf("s"))
        assertEquals("Only in cloud", bind(0).stateWords)
    }

    @Test
    fun `a row without frames that is not backed up keeps its own state`() {
        submit(
            record("p", sync = SessionRecord.SyncState.PENDING),
            record("s", sync = SessionRecord.SyncState.SYNCED),
            withoutLocalData = setOf("p"),
        )
        assertEquals("Upload pending", bind(0).stateWords)
        assertEquals("frames on the phone", "Backed up", bind(1).stateWords)
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
    fun `a running upload reads its percent to one decimal over a determinate bar`() {
        submit(record("a"))
        adapter.setUploadProgress(mapOf("a" to progress(TransferPhase.UPLOAD, 42)))
        val row = bind(0)

        assertEquals("Backing up · 42.0%", row.subtitle.text.toString())
        assertEquals("Backing up", row.stateWords)
        assertEquals(activity.getColor(R.color.sky_primary), row.stateTint)
        assertTrue(row.progressBar.isVisible())
        assertFalse(row.progressBar.isIndeterminate)
        assertEquals(42, row.progressBar.progress)
    }

    @Test
    fun `a running upload that reports bytes reads them instead of a percent`() {
        val mb = 1024L * 1024L
        submit(record("a"), record("b"))
        adapter.setUploadProgress(
            mapOf(
                "a" to progress(TransferPhase.UPLOAD, 35, done = 42 * mb / 10, total = 12 * mb),
                "b" to progress(TransferPhase.UPLOAD, 50, done = 512L * 1024L, total = 900L * 1024L),
            ),
        )

        assertEquals("Backing up · 4.2 of 12 MB", bind(0).subtitle.text.toString())
        assertEquals("Backing up · 512 of 900 KB", bind(1).subtitle.text.toString())
    }

    @Test
    fun `byte counts with no total fall back to the percent`() {
        submit(record("a"))
        adapter.setUploadProgress(mapOf("a" to progress(TransferPhase.PREPARE, 7, done = 100L, total = null)))

        assertEquals("Preparing backup · 7.0%", bind(0).subtitle.text.toString())
    }

    @Test
    fun `a bundle download at zero percent spins and names its phase alone`() {
        submit(record("a"))
        adapter.setUploadProgress(mapOf("a" to progress(TransferPhase.DOWNLOAD, 0)))
        val row = bind(0)

        assertTrue(row.progressBar.isIndeterminate)
        assertEquals("Restoring", row.subtitle.text.toString())
        assertEquals("Restoring", row.stateWords)
    }

    @Test
    fun `a demo account shows no state icon, no bar, no transfer text and no tap action`() {
        submit(record("a", sync = SessionRecord.SyncState.SYNCED, convergence = 99f))
        adapter.setUploadProgress(mapOf("a" to progress(TransferPhase.UPLOAD, 42)))
        adapter.setSyncVisible(false)
        val row = bind(0)

        assertFalse(row.state.isVisible())
        assertFalse(row.progressBar.isVisible())
        assertFalse(row.state.hasOnClickListeners())
        assertTrue(row.subtitle.text.toString(), row.subtitle.text.startsWith("12 frames"))
    }

    // ── Selection paint and clicks ───────────────────────────────────────────

    @Test
    fun `a selected row is ticked and tinted`() {
        submit(record("a"), record("b"))
        selected += "b"

        val plain = bind(0)
        val picked = bind(1)
        assertFalse(plain.check.isVisible())
        assertTrue(picked.check.isVisible())
        assertEquals(activity.getColor(R.color.sky_container), picked.background())
        assertEquals(Color.TRANSPARENT, plain.background())
    }

    @Test
    fun `taps, long presses and state icon taps reach the owner with the row's record`() {
        submit(record("a"), record("b"))
        val row = bind(1)

        row.itemView.performClick()
        assertTrue(row.itemView.performLongClick())
        row.state.performClick()

        assertEquals(listOf("b"), clicked)
        assertEquals(listOf("b"), longClicked)
        assertEquals(listOf("b"), badgeClicked)
    }

    @Test
    fun `a row whose reference image and frames are gone has no thumbnail`() {
        submit(record("a"))
        val row = bind(0)
        assertNull(row.thumb.drawable)
        assertNull(row.resultThumb.drawable)
    }

    // ── What gets rebound ────────────────────────────────────────────────────

    @Test
    fun `progress rebinds only the rows whose progress moved`() {
        submit(record("a"), record("b"), record("c"))
        adapter.setUploadProgress(mapOf("b" to progress(TransferPhase.UPLOAD, 10)))
        assertEquals(listOf(1), changed)

        changed.clear()
        adapter.setUploadProgress(
            mapOf(
                "b" to progress(TransferPhase.UPLOAD, 10),
                "c" to progress(TransferPhase.PREPARE, 0),
            ),
        )
        assertEquals("b unchanged, c new", listOf(2), changed)

        changed.clear()
        adapter.setUploadProgress(mapOf("b" to progress(TransferPhase.UPLOAD, 10)))
        assertEquals("c finished", listOf(2), changed)
        assertEquals(0, fullRefreshes)
    }

    @Test
    fun `new byte counts at the same percent rebind the row`() {
        submit(record("a"))
        adapter.setUploadProgress(mapOf("a" to progress(TransferPhase.UPLOAD, 10, 1L, 10L)))
        changed.clear()

        adapter.setUploadProgress(mapOf("a" to progress(TransferPhase.UPLOAD, 10, 2L, 10L)))
        assertEquals(listOf(0), changed)
    }

    @Test
    fun `the same progress again rebinds nothing`() {
        submit(record("a"))
        val progress = mapOf("a" to progress(TransferPhase.UPLOAD, 50))
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

    private fun SessionListAdapter.Holder.background(): Int = (row.sessionRow.background as ColorDrawable).color

    private fun View.isVisible() = visibility == View.VISIBLE
}
