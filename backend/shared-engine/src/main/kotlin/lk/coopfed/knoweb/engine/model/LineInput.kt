package lk.coopfed.knoweb.engine.model

import java.util.UUID
import lk.coopfed.knoweb.engine.Quantity

data class LineInput(
    val skuId: UUID,
    val uom: String,
    val qty: Quantity,
    val scannedBatchId: UUID?,
    val pickedBatchId: UUID?
)
