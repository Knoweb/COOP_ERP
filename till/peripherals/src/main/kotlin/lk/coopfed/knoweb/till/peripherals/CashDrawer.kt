package lk.coopfed.knoweb.till.peripherals

interface CashDrawer {
    fun open()
}

class SimulatorCashDrawer : CashDrawer {

    var opened: Boolean = false
        private set

    override fun open() {
        opened = true
    }
}