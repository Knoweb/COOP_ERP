package lk.coopfed.knoweb.till.peripherals

interface Scanner {
    fun scan(): String?
}

class SimulatorScanner : Scanner {

    override fun scan(): String =
        "479000000001"
}