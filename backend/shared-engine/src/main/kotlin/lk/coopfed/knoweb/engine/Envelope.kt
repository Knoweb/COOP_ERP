package lk.coopfed.knoweb.engine

import java.time.Instant
import java.util.UUID

data class Envelope<T>(
    val eventId: UUID,
    val occurredAt: Instant,
    val payload: T
)