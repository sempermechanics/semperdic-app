package com.sempermechanics.semper.data.session

/**
 * Names that come from a backup: the name a restore gives its row, the labels
 * a list of backups reads, and the repair of rows an earlier build named.
 * They follow [SessionNaming]'s rule (the reference's name without its image
 * extension, ` (2)`, ` (3)` … on a clash), so a restored analysis reads like
 * one made on this phone. [SessionStore.save] makes every saved name unique.
 */
object CloudNaming {

    /** The auto-name before 2026-10-08: `steel_00 · Oct 9, 17:57:31`. Home's rows date themselves now. */
    private val DATED = Regex("""^(.+) · [A-Z][a-z]{2} \d{1,2}, \d{2}:\d{2}:\d{2}$""")

    /** What a restore names a row with no name of its own. */
    private const val FALLBACK = "Restored"

    /**
     * The name a backup restores under, from its `metadata.json`'s [name] and
     * [specimen] (the reference's file name). The stored name is kept unless it
     * is an auto-name of an older shape, which reads as today's: the reference's
     * file name (`pmma_32.png` → `pmma_32`) or the reference's name and a date
     * (`steel_00 · Oct 9, 17:57:31` → `steel_00`). No name falls back to the
     * specimen's, then "Restored".
     */
    fun restoredName(name: String?, specimen: String?): String {
        val reference = specimen.orEmpty().trim()
        val base = SessionNaming.withoutMediaExtension(reference).trim()
        val stored = name.orEmpty().trim()
        return when {
            stored.isEmpty() -> base.ifEmpty { FALLBACK }
            stored == reference -> base.ifEmpty { stored }
            base.isNotEmpty() && DATED.matchEntire(stored)?.groupValues?.get(1) == base -> base
            else -> stored
        }
    }

    /**
     * Labels for a list of backups, one per [specimens] entry (the reference's
     * file name, which is all the backend lists): the name without its image
     * extension, made unique against [taken] (the names on the phone) and the
     * labels before it, so no two read alike. A blank specimen stays blank for
     * the caller's own wording.
     */
    fun backupNames(specimens: List<String?>, taken: Collection<String>): List<String> {
        val used = HashSet(taken)
        return specimens.map { specimen ->
            val base = SessionNaming.withoutMediaExtension(specimen.orEmpty().trim()).trim()
            if (base.isEmpty()) "" else SessionNaming.uniqueName(base, used).also { used += it }
        }
    }

    /**
     * [rows] with the names earlier builds left behind put right, or null when
     * none needs it. A row the user never renamed whose name is still its
     * reference's file name (a restore named it from the backup list, or an
     * old build named it so) takes [storedName] of it (the name its restored
     * `metadata.json` gives, when there is one), else the file name without its
     * extension. Then each name more than one row has stays with the oldest
     * row, and the others take the next free ` (n)`. Ids, directories and every
     * other field are left as they are.
     */
    fun repaired(rows: List<SessionRecord>, storedName: (SessionRecord) -> String?): List<SessionRecord>? {
        val named = rows.filter { it.name.isNotBlank() }.map { it.name }
        if (rows.none(::namedAfterFile) && named.size == named.toSet().size) return null
        val renamed = rows.map { row ->
            if (namedAfterFile(row)) {
                row.copy(name = storedName(row) ?: SessionNaming.withoutMediaExtension(row.name))
            } else {
                row
            }
        }
        val distinct = withDistinctNames(renamed)
        return distinct.takeIf { it != rows }
    }

    /** Whether [row] is an auto-name that kept its reference's extension. */
    private fun namedAfterFile(row: SessionRecord): Boolean =
        !row.renamedByUser &&
            row.name.isNotBlank() &&
            row.name == row.refName &&
            SessionNaming.withoutMediaExtension(row.name) != row.name

    /** [rows] where only the oldest holder of a name keeps it; blank names are left alone. */
    private fun withDistinctNames(rows: List<SessionRecord>): List<SessionRecord> {
        val used = rows.mapTo(HashSet()) { it.name }
        val holders = HashSet<String>()
        val newNames = HashMap<Int, String>()
        rows.indices.sortedWith(compareBy({ rows[it].createdAt }, { rows[it].id })).forEach { i ->
            val name = rows[i].name
            if (name.isNotBlank() && !holders.add(name)) {
                newNames[i] = SessionNaming.uniqueName(name, used).also { used += it }
            }
        }
        return rows.mapIndexed { i, row -> newNames[i]?.let { row.copy(name = it) } ?: row }
    }
}
