package lk.coopfed.knoweb.engine.model

import java.math.BigDecimal
import lk.coopfed.knoweb.engine.Money

data class Policy(
    val kind: PolicyKind,
    val gapAmount: Money?,
    val gapPercent: BigDecimal?
)
