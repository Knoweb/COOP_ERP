package lk.coopfed.knoweb.till.peripherals

interface CustomerDisplay {
    fun show(message: String)
}

class SimulatorCustomerDisplay : CustomerDisplay {

    var lastMessage: String? = null
        private set

    override fun show(message: String) {
        lastMessage = message
    }
}