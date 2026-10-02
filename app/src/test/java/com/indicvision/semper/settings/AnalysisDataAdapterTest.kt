package com.indicvision.semper.settings

import android.app.Application
import com.indicvision.semper.fixtures.idleUntil
import com.indicvision.semper.ui.settings.AnalysisDataAdapter
import com.indicvision.semper.ui.settings.AnalysisEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The settings list drops a deleted row at once, however fast the deletes come. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnalysisDataAdapterTest {

    private val adapter = AnalysisDataAdapter(
        stateLine = { "" },
        backupLabel = { null },
        onOpen = {},
        onBackup = {},
        onLocalDownload = {},
        onCloudRestore = {},
        onDelete = {},
    )

    private fun entry(name: String) = AnalysisEntry(name = name, record = null, cloud = null)

    private fun shown() = adapter.currentList.map { it.name }

    @Test
    fun `two deletes back to back both drop their rows`() {
        adapter.submit(listOf(entry("a"), entry("b"), entry("c")))
        idleUntil("the list to show") { shown() == listOf("a", "b", "c") }

        // The second comes before the first one's diff has landed.
        adapter.remove(entry("a").downloadKey())
        adapter.remove(entry("b").downloadKey())
        // Before, the second removal started from the stale list and put "a" back.
        idleUntil("only c to be left") { shown() == listOf("c") }
        assertEquals(listOf("c"), shown())
    }

    @Test
    fun `removing a row that is not listed changes nothing`() {
        adapter.submit(listOf(entry("a")))
        idleUntil("the list to show") { shown() == listOf("a") }

        adapter.remove("gone")
        adapter.remove(entry("a").downloadKey())
        idleUntil("the removal to land") { shown().isEmpty() }

        assertEquals(emptyList<String>(), shown())
    }
}
