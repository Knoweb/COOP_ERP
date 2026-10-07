package lk.coopfed.knoweb.till.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The till's limits and windows, with the defaults of the design. Two are configuration items that
 * central overwrites through the snapshot's `config` table ([withConfig]); the others are fixed by
 * the decisions they cite and are here, not in the code, so a test or a platform can name them.
 */
data class TillPolicy(
    /** 26A section 8: "5 failures -> 15-min lockout (local + audit)". */
    val pinFailuresBeforeLock: Int = 5,
    val pinLockFor: Duration = 15.minutes,
    /** S8, DR-3: acknowledged facts are kept 7 days (config sync.outbox.retention_days). */
    val outboxRetention: Duration = 7.days,
    /** Doc 32 section 5.2: warn after 3 days without a verified snapshot (config pos.snapshot_staleness_days). */
    val snapshotStaleness: Duration = 3.days,
    /** D-4: an offset central reports beyond this is refused, the PC's clock is kept. */
    val maxClockOffset: Duration = 7.days,
    /** D-4: the PC's clock moved by more than this beside real time: it was set by hand. */
    val clockJumpTolerance: Duration = 1.hours,
    /** D-4: no business date more than this after central's last server_time ... */
    val businessDateLead: Duration = 1.days,
    /** ... when the till synced within this. */
    val recentSync: Duration = 48.hours,
) {
    /** The policy with central's configuration items for this location applied over the defaults. */
    fun withConfig(config: Map<String, String>): TillPolicy = copy(
        outboxRetention = config["sync.outbox.retention_days"]?.toLongOrNull()?.takeIf { it >= 1 }?.days ?: outboxRetention,
        snapshotStaleness = config["pos.snapshot_staleness_days"]?.toLongOrNull()?.takeIf { it >= 1 }?.days ?: snapshotStaleness,
    )

    companion object {
        /** The permission (21A, shop-in-charge) that lets a supervisor correct the till's business date. */
        const val SUPERVISOR_PERMISSION = "pos.session.manage"
    }
}
