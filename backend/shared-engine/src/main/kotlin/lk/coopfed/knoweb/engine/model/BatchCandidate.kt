package lk.coopfed.knoweb.engine.model

import java.time.LocalDate
import java.util.UUID
import lk.coopfed.knoweb.engine.Money
import lk.coopfed.knoweb.engine.Quantity

data class BatchCandidate(
    val batchId: UUID,
    val batchNo: String,
    val printedMrp: Money?,
    val expiry: LocalDate?,
    val onHand: Quantity
)
