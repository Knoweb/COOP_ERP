package lk.coopfed.knoweb.engine.model

import lk.coopfed.knoweb.engine.Money

data class Ceiling(
    val price: Money,
    val uom: String,
    val ref: String
)
