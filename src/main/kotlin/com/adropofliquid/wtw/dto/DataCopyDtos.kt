package com.adropofliquid.wtw.dto

/**
 * Counters for one target table in the data-copy report (brief 7.1, 7.9).
 * read = inserted + updated + unchanged + sum(skipped). In dry-run, inserted/updated are "would write".
 * Rows that end up in the new table = inserted + updated + unchanged + skipped["newer_on_server"].
 */
class TableStats {
    var oldCount: Long = 0
    var read: Long = 0
    var inserted: Long = 0
    var updated: Long = 0
    var unchanged: Long = 0
    var deleted: Long = 0
    val skipped: MutableMap<String, Long> = sortedMapOf()

    fun skip(reason: String, n: Long = 1) { if (n > 0) skipped.merge(reason, n, Long::plus) }
    val written: Long get() = inserted + updated + deleted
    val skippedTotal: Long get() = skipped.values.sum()
    /** Rows of this table that exist in the new DB after the copy (copied, already there, or newer from a phone). */
    val inTarget: Long get() = inserted + updated + unchanged + (skipped["newer_on_server"] ?: 0)

    fun toMap(): Map<String, Any> = linkedMapOf(
        "oldCount" to oldCount, "read" to read, "inserted" to inserted, "updated" to updated, "deleted" to deleted,
        "unchanged" to unchanged, "skipped" to skipped, "inTarget" to inTarget,
        "reconciles" to (read == inserted + updated + unchanged + skippedTotal),
    )
}

/** One line of `data-copy-users-{ts}.csv`: per table, old count, explained skips, expected new count, new count. */
class UserCounts(val uid: String) {
    val old = LinkedHashMap<String, Long>()
    val skipped = LinkedHashMap<String, Long>()
    val actual = LinkedHashMap<String, Long>()
    /** Tables where a phone pushed newer rows, so the new count may differ from the old one by design. */
    val phoneNewer = mutableSetOf<String>()
    var pullRows: Long = 0
    var pullBytes: Long = 0
    var error: String? = null

    fun expected(t: String) = (old[t] ?: 0) - (skipped[t] ?: 0)
    fun matches(t: String) = t in phoneNewer || actual[t] == expected(t)
}
