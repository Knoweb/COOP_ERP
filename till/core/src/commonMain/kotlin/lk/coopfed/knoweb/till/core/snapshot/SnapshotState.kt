package lk.coopfed.knoweb.till.core.snapshot

import kotlinx.datetime.LocalDate

/**
 * The reference data a till sells with, at one version: the live rows per table and the rows held
 * for a later business date (apply_from). Immutable; [apply] returns the next state, which the
 * store saves in one transaction, so a power cut half-way leaves the old version live.
 */
data class SnapshotState(
    val version: Long,
    val live: Map<String, Map<String, SnapshotRow>>,
    val held: List<SnapshotRow>,
) {
    fun table(name: String): Collection<SnapshotRow> = live[name]?.values.orEmpty()

    /** Applies a verified answer: a full snapshot replaces every table, a delta changes the live copy. */
    fun apply(delta: SnapshotDelta, businessDate: LocalDate): SnapshotState {
        val staging = if (delta.fullSnapshotRequired) {
            mutableMapOf()
        } else {
            live.mapValuesTo(mutableMapOf()) { (_, rows) -> rows.toMutableMap() }
        }
        val stagedHeld = if (delta.fullSnapshotRequired) mutableListOf() else held.toMutableList()
        for ((name, table) in delta.tables) {
            val rows = staging.getOrPut(name) { mutableMapOf() }
            for (row in table.upserts + table.tombstones) {
                if (row.applyFrom != null && row.applyFrom > businessDate) {
                    stagedHeld += row
                } else if (row.data == null) {
                    rows.remove(row.rowId)
                } else {
                    rows[row.rowId] = row
                }
            }
        }
        return SnapshotState(delta.version, staging, stagedHeld)
    }

    /** A new business day opens: held rows dated on or before it go live. */
    fun dayOpen(date: LocalDate): SnapshotState {
        val next = live.mapValuesTo(mutableMapOf()) { (_, rows) -> rows.toMutableMap() }
        val stillHeld = mutableListOf<SnapshotRow>()
        for (row in held) {
            if (row.applyFrom != null && row.applyFrom > date) {
                stillHeld += row
            } else if (row.data == null) {
                next[row.table]?.remove(row.rowId)
            } else {
                next.getOrPut(row.table) { mutableMapOf() }[row.rowId] = row
            }
        }
        return SnapshotState(version, next, stillHeld)
    }

    companion object {
        val EMPTY = SnapshotState(0, emptyMap(), emptyList())
    }
}
