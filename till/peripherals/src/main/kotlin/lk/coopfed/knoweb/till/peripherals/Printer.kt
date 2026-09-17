package lk.coopfed.knoweb.till.peripherals

interface Printer {
    fun print(text: String)
}

class SimulatorPrinter : Printer {

    private val jobs = mutableListOf<String>()

    override fun print(text: String) {
        jobs += text
    }

    fun printedJobs(): List<String> =
        jobs.toList()
}