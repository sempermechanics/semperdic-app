package com.sempermechanics.semper.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.DicSettings
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionRepository
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.StorageBudget
import com.sempermechanics.semper.diagnostics.EngineDebug
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A long batch of large photos used to leave two full copies of every image on
 * disk plus an unbounded debug dump, which is what made storage the bottleneck.
 * These pin the three behaviours that fixed it: images are moved rather than
 * copied, cache leftovers are reclaimed, and the auto-free budget only ever
 * touches sessions the cloud already has.
 */
@RunWith(RobolectricTestRunner::class)
class LocalStorageFootprintTest {

    @get:Rule
    val clean = CleanAppState()

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        DicSettings.setAutoFreeBudgetGb(ctx, DicSettings.AUTO_FREE_OFF)
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        // Eviction presumes the cloud copy can be pulled back, which is the
        // licensed half of cloud; the demo case has its own test below.
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", cloudBackupEnabled = true))
    }

    @After
    fun tearDown() {
        AppRemoteConfig.clear(ctx)
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    // ── Images move rather than duplicate ────────────────────────────────

    @Test
    fun `persisting a deformed frame moves it out of the import cache`() {
        val staged = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        val source = File(staged, "0000_specimen.png").apply { writeText("image-bytes") }
        val batchDir = SessionStore.dirFor(ctx, "s1")

        val name = SessionRepository().persistRawDeformed(
            batchDir,
            frameIndex = 0,
            defFilePaths = listOf(source.absolutePath),
            defOriginalNames = listOf("specimen.png"),
        )

        assertEquals("specimen.png", name)
        val persisted = File(File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR), "specimen.png")
        assertEquals("image-bytes", persisted.readText())
        // The point of the change: one copy on disk, not two.
        assertFalse("staged copy should have been moved, not duplicated", source.exists())
    }

    @Test
    fun `a frame with a blank recorded name is persisted under its file name`() {
        val staged = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        val source = File(staged, "0000_specimen.png").apply { writeText("image-bytes") }
        val batchDir = SessionStore.dirFor(ctx, "s4")

        // A restored draft pads a frame with no recorded name with "".
        val name = SessionRepository().persistRawDeformed(
            batchDir,
            frameIndex = 0,
            defFilePaths = listOf(source.absolutePath),
            defOriginalNames = listOf(""),
        )

        assertEquals("0000_specimen.png", name)
        val persisted = File(File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR), name)
        assertEquals("image-bytes", persisted.readText())
    }

    @Test
    fun `re-running over an already persisted frame keeps it`() {
        val batchDir = SessionStore.dirFor(ctx, "s2")
        val rawDir = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }
        val alreadyThere = File(rawDir, "specimen.png").apply { writeText("image-bytes") }

        val name = SessionRepository().persistRawDeformed(
            batchDir,
            frameIndex = 0,
            defFilePaths = listOf(alreadyThere.absolutePath),
            defOriginalNames = listOf("specimen.png"),
        )

        // The old code wiped raw_deformed/ before writing, which on a re-run
        // destroyed the very file it was about to read.
        assertEquals("specimen.png", name)
        assertTrue(alreadyThere.exists())
        assertEquals("image-bytes", alreadyThere.readText())
    }

    @Test
    fun `persisting a frame drops an earlier run's leftovers`() {
        val batchDir = SessionStore.dirFor(ctx, "s3")
        val rawDir = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }
        val stale = File(rawDir, "old.png").apply { writeText("old") }
        val staged = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        val source = File(staged, "0000_new.png").apply { writeText("new") }

        SessionRepository().persistRawDeformed(
            batchDir,
            frameIndex = 0,
            defFilePaths = listOf(source.absolutePath),
            defOriginalNames = listOf("new.png"),
        )

        assertFalse(stale.exists())
        assertTrue(File(rawDir, "new.png").exists())
    }

    // ── Cache leftovers ─────────────────────────────────────────────────

    @Test
    fun `startup sweep reclaims the debug dump and import leftovers`() {
        val debug = File(ctx.cacheDir, EngineDebug.DIR_NAME).apply { mkdirs() }
        File(debug, "correlation_heatmap.png").writeText("x".repeat(64))
        File(ctx.cacheDir, "${FrameImportHelper.STAGING_DIR_PREFIX}123").apply { mkdirs() }
        File(ctx.cacheDir, FrameImportHelper.PREVIOUS_DIR_NAME).apply { mkdirs() }
        val committed = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        File(committed, "frame.png").writeText("bytes")
        val keep = File(ctx.cacheDir, "roi_mask_cache.bin").apply { writeText("mask") }

        val freed = CacheJanitor.sweepOnStartup(ctx)

        assertFalse(debug.exists())
        assertFalse(File(ctx.cacheDir, "${FrameImportHelper.STAGING_DIR_PREFIX}123").exists())
        assertFalse(File(ctx.cacheDir, FrameImportHelper.PREVIOUS_DIR_NAME).exists())
        assertFalse(committed.exists())
        assertTrue("unrelated cache files must survive", keep.exists())
        assertTrue(freed > 0)
    }

    @Test
    fun `user-requested sweep leaves a freshly imported batch alone`() {
        val committed = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        File(committed, "frame.png").writeText("bytes")
        val debug = File(ctx.cacheDir, EngineDebug.DIR_NAME).apply { mkdirs() }

        CacheJanitor.sweepUserRequested(ctx)

        // Another screen may be holding these paths; only startup can assume not.
        assertTrue(committed.exists())
        assertFalse(debug.exists())
    }

    @Test
    fun `recent worker scratch survives a sweep`() {
        val scratch = File(ctx.cacheDir, "restore_abc_bundle.zip").apply { writeText("in-flight") }

        CacheJanitor.sweepOnStartup(ctx)

        assertTrue("a running worker's scratch file must not be pulled out from under it", scratch.exists())
    }

    @Test
    fun `user clear reclaims stale transfer scratch and regenerable files`() {
        val scratch = File(ctx.cacheDir, "restore_abc_bundle.zip").apply { writeText("x".repeat(2048)) }
        // Older than the 2-minute user grace window.
        scratch.setLastModified(System.currentTimeMillis() - java.util.concurrent.TimeUnit.MINUTES.toMillis(5))
        val mask = File(ctx.cacheDir, "roi_mask_cache.bin").apply { writeText("mask") }
        val committed = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        File(committed, "frame.png").writeText("keep-me")

        val clearable = CacheJanitor.clearableUserBytes(ctx)
        assertTrue("meter should count scratch + mask", clearable >= 2048)
        val freed = CacheJanitor.sweepUserRequested(ctx)

        assertFalse(scratch.exists())
        assertFalse(mask.exists())
        assertTrue(committed.exists())
        assertTrue(freed >= 2048)
        assertEquals(0L, CacheJanitor.clearableUserBytes(ctx))
    }

    @Test
    fun `user clear leaves mid-write scratch alone`() {
        val scratch = File(ctx.cacheDir, "upload_xyz_frame.pdf").apply { writeText("hot") }
        scratch.setLastModified(System.currentTimeMillis())

        assertEquals(0L, CacheJanitor.clearableUserBytes(ctx))
        CacheJanitor.sweepUserRequested(ctx)

        assertTrue(scratch.exists())
    }

    // ── Auto-free budget ────────────────────────────────────────────────

    @Test
    fun `freeing space drops backed-up sessions and spares the rest`() {
        val synced = seedSession("synced", SessionRecord.SyncState.SYNCED)
        val localOnly = seedSession("local", SessionRecord.SyncState.LOCAL_ONLY)
        val pending = seedSession("pending", SessionRecord.SyncState.PENDING)

        val outcome = StorageBudget.freeAllBackedUp(ctx)

        assertEquals(1, outcome.sessionsDropped)
        assertTrue(outcome.freedBytes > 0)
        assertFalse(File(synced, "frame_0000.dat").exists())
        // This device holds the only copy of these two — dropping them would be
        // data loss, not eviction.
        assertTrue(File(localOnly, "frame_0000.dat").exists())
        assertTrue(File(pending, "frame_0000.dat").exists())
        // The reference survives so the Home row still renders as "only in cloud".
        assertTrue(File(synced, "reference.png").exists())
    }

    @Test
    fun `budget of off never drops anything`() {
        seedSession("synced", SessionRecord.SyncState.SYNCED)
        DicSettings.setAutoFreeBudgetGb(ctx, DicSettings.AUTO_FREE_OFF)

        val outcome = StorageBudget.enforce(ctx)

        assertEquals(0, outcome.sessionsDropped)
        assertTrue(SessionStore.get(ctx, "synced")!!.hasLocalData())
    }

    @Test
    fun `a demo account never gives up a local copy it cannot restore`() {
        // Demo analyses are recorded, so they do reach SYNCED — but demo has
        // no restore, so this phone still holds the only reachable copy.
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "demo", cloudBackupEnabled = false))
        val synced = seedSession("synced", SessionRecord.SyncState.SYNCED)
        DicSettings.setAutoFreeBudgetGb(ctx, 1)

        assertEquals(0L, StorageBudget.reclaimableBytes(ctx))
        assertEquals(0, StorageBudget.freeAllBackedUp(ctx).sessionsDropped)
        assertEquals(0, StorageBudget.enforce(ctx).sessionsDropped)
        assertTrue(File(synced, "frame_0000.dat").exists())
    }

    @Test
    fun `reclaimable bytes counts only backed-up sessions`() {
        seedSession("synced", SessionRecord.SyncState.SYNCED)
        seedSession("local", SessionRecord.SyncState.LOCAL_ONLY)

        val reclaimable = StorageBudget.reclaimableBytes(ctx)

        // The .dat frame and the raw image; reference.png stays behind.
        assertEquals(512L + 2048L, reclaimable)
    }

    @Test
    fun `the free-up preview promises exactly what freeing drops`() {
        val synced = seedSession("synced", SessionRecord.SyncState.SYNCED)
        // Small files a drop keeps: they used to be counted in the preview.
        File(synced, "metadata.json").writeText("m".repeat(300))

        val preview = StorageBudget.reclaimableBytes(ctx)
        val sizeBefore = SessionStore.sizeOf(ctx, "synced")
        val outcome = StorageBudget.freeAllBackedUp(ctx)

        assertEquals(preview, outcome.freedBytes)
        assertTrue(preview < sizeBefore)
        assertTrue(File(synced, "metadata.json").exists())
    }

    /** A session directory with the artifacts a real run leaves behind. */
    private fun seedSession(id: String, state: SessionRecord.SyncState): File {
        val dir = SessionStore.dirFor(ctx, id)
        File(dir, "frame_0000.dat").writeText("d".repeat(512))
        File(dir, "reference.png").writeText("ref")
        File(dir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }
            .let { File(it, "specimen.png").writeText("i".repeat(2048)) }
        SessionStore.upsert(
            ctx,
            sessionRecord(
                id = id,
                refPath = File(dir, "reference.png").absolutePath,
                refName = "reference.png",
                sessionDir = dir.absolutePath,
                syncState = state,
            ),
        )
        return dir
    }
}
