package lk.coopfed.knoweb.till.peripherals

import java.math.BigDecimal

interface Scale {
    fun weightKg(): BigDecimal
}

class SimulatorScale : Scale {

    override fun weightKg(): BigDecimal =
        BigDecimal("1.000")
}