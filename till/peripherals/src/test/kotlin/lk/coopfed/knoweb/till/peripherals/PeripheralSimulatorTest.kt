package lk.coopfed.knoweb.till.peripherals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeripheralSimulatorTest {

    @Test
    fun simulatorPeripheralsWorkWithoutHardware() {
        val scanner = SimulatorScanner()
        val printer = SimulatorPrinter()
        val drawer = SimulatorCashDrawer()

        assertEquals(
            "479000000001",
            scanner.scan()
        )

        printer.print("Test receipt")

        assertEquals(
            listOf("Test receipt"),
            printer.printedJobs()
        )

        drawer.open()

        assertTrue(drawer.opened)
    }
}