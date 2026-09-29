package lk.coopfed.knoweb.till.peripherals

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import lk.coopfed.knoweb.till.core.receipt.MonoBitmap

class PeripheralsTest {

    @Test
    fun aRasterGoesAsGsV0InBandsWithTheWidthInBytesAndTheHeightInDots() {
        val image = MonoBitmap(16, 3)
        image[0, 0] = true
        image[15, 2] = true

        val bytes = EscPos.raster(image, bandHeight = 2)

        assertContentEquals(
            byteArrayOf(
                0x1D, 0x76, 0x30, 0x00, 2, 0, 2, 0, 0x80.toByte(), 0, 0, 0,
                0x1D, 0x76, 0x30, 0x00, 2, 0, 1, 0, 0, 0x01,
            ),
            bytes,
        )
    }

    @Test
    fun aCashSaleJobOpensTheDrawerAfterTheCut() {
        val job = EscPos.job(MonoBitmap(8, 1), kickDrawer = true)
        assertContentEquals(EscPos.INITIALISE, job.copyOfRange(0, 2))
        assertContentEquals(EscPos.CUT + EscPos.KICK_DRAWER, job.copyOfRange(job.size - 8, job.size))
    }

    @Test
    fun aScannerIsToldFromAPersonByHowFastTheKeysCome() {
        val reader = ScanReader()
        "4790000000011".forEachIndexed { i, c -> reader.key(c, 1000L + i * 5) }
        assertEquals(ScanReader.Scan("4790000000011", byScanner = true), reader.enter(1070))

        "4790000000028".forEachIndexed { i, c -> reader.key(c, 5000L + i * 300) }
        assertEquals(ScanReader.Scan("4790000000028", byScanner = false), reader.enter(9000))
    }

    @Test
    fun theSamePackReadTwiceAtOnceCountsOnce() {
        val reader = ScanReader()
        "4790000000011".forEach { reader.key(it, 1000) }
        reader.enter(1000)
        "4790000000011".forEach { reader.key(it, 1300) }
        assertNull(reader.enter(1300))
        "4790000000011".forEach { reader.key(it, 2500) }
        assertEquals("4790000000011", reader.enter(2500)?.code)
    }

    @Test
    fun simulatorPeripheralsWorkWithoutHardware() {
        val drawer = SimulatorCashDrawer()
        drawer.open()
        assertTrue(drawer.opened)
        val display = SimulatorCustomerDisplay()
        display.show("LKR 1,250.00")
        assertEquals("LKR 1,250.00", display.lastMessage)
    }
}
