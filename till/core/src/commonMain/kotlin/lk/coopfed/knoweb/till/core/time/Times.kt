package lk.coopfed.knoweb.till.core.time

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * How the till writes times into facts. A fact's instant is kept to the whole second, and written
 * as "2026-09-29T04:30:15Z": central's hash rule reads it back with java.time.Instant and writes it
 * again, and at whole seconds both sides print the same text.
 */
object Times {

    fun wholeSecond(instant: Instant): Instant = Instant.fromEpochSeconds(instant.epochSeconds)

    fun iso(instant: Instant): String = format(wholeSecond(instant).toLocalDateTime(TimeZone.UTC)) + "Z"

    /** The local reading, "yyyy-MM-ddTHH:mm:ss" (TillEvent.occurred_local). */
    fun local(instant: Instant, zone: TimeZone): String = format(wholeSecond(instant).toLocalDateTime(zone))

    fun date(instant: Instant, zone: TimeZone): LocalDate = instant.toLocalDateTime(zone).date

    /** "29/09/2026 10:00" for the paper. */
    fun printed(instant: Instant, zone: TimeZone): String {
        val t = instant.toLocalDateTime(zone)
        return "${two(t.day)}/${two(t.month.ordinal + 1)}/${t.year} ${two(t.hour)}:${two(t.minute)}"
    }

    private fun format(t: LocalDateTime): String =
        "${t.year.toString().padStart(4, '0')}-${two(t.month.ordinal + 1)}-${two(t.day)}" +
            "T${two(t.hour)}:${two(t.minute)}:${two(t.second)}"

    private fun two(n: Int) = n.toString().padStart(2, '0')
}
