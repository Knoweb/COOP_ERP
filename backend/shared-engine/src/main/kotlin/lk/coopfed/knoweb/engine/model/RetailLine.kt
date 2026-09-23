package lk.coopfed.knoweb.engine.model

import java.util.UUID
import lk.coopfed.knoweb.engine.Money
import lk.coopfed.knoweb.engine.Quantity

data class RetailLine(
    val skuId: UUID,
    val uom: String,
    val price: Money,
    val tierFromQty: Quantity = Quantity.zero()
)
