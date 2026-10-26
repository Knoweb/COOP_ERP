package lk.coopfed.knoweb.till.peripherals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

class ScanLayerTest {

    private val scanLayer = ScanLayer(
        dedupeMillis = 700,
        referenceYear = { 2026 },
    )

    @Test
    fun ean13NormalizedToGtin14WithValidCheckDigit() {
        val res = scanLayer.parse("4791234567891", atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.EAN13, res.symbology)
        assertEquals("04791234567891", res.gtin)
        assertNull(res.weightGrams)
    }

    @Test
    fun ean13WithInvalidCheckDigitRejected() {
        // Last digit 0 is invalid for 479123456789 (expected 1)
        val res = scanLayer.parse("4791234567890", atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.EAN13, res.symbology)
        kotlin.test.assertTrue(res.isMalformed)
        assertNull(res.gtin)
    }

    @Test
    fun ean8NormalizedToGtin14WithValidCheckDigit() {
        val res = scanLayer.parse("47912342", atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.EAN8, res.symbology)
        assertEquals("00000047912342", res.gtin)
    }

    @Test
    fun ean8WithInvalidCheckDigitRejected() {
        val res = scanLayer.parse("47912345", atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.EAN8, res.symbology)
        kotlin.test.assertTrue(res.isMalformed)
        assertNull(res.gtin)
    }

    @Test
    fun upcaNormalizedToGtin14WithValidCheckDigit() {
        val res = scanLayer.parse("012345678905", atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.UPCA, res.symbology)
        assertEquals("00012345678905", res.gtin)
    }

    @Test
    fun upcaWithInvalidCheckDigitRejected() {
        val res = scanLayer.parse("012345678906", atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.UPCA, res.symbology)
        kotlin.test.assertTrue(res.isMalformed)
        assertNull(res.gtin)
    }

    @Test
    fun legacyNonGtinBarcodeRecognizedAsUnknownNotMalformed() {
        val res = scanLayer.parse("PROD-1234", atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.UNKNOWN, res.symbology)
        kotlin.test.assertFalse(res.isMalformed)
        assertNull(res.gtin)
    }

    @Test
    fun gs1128BracketedElementsExtracted() {
        val raw = "(01)04791234567891(10)LOT123(17)261231(3103)001500"
        val res = scanLayer.parse(raw, atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.GS1_DATAMATRIX, res.symbology)
        assertEquals("04791234567891", res.gtin)
        assertEquals("LOT123", res.lot)
        assertEquals(LocalDate(2026, 12, 31), res.expiry)
        assertEquals(1500L, res.weightGrams)
    }

    @Test
    fun gs1DataMatrixGroupSeparatorExtracted() {
        val raw = "010479123456789110LOT123\u001D172612313103001500"
        val res = scanLayer.parse(raw, atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.GS1_DATAMATRIX, res.symbology)
        assertEquals("04791234567891", res.gtin)
        assertEquals("LOT123", res.lot)
        assertEquals(LocalDate(2026, 12, 31), res.expiry)
        assertEquals(1500L, res.weightGrams)
    }

    @Test
    fun gs1DigitalLinkUriParsedPathAndQuery() {
        val url = "https://id.gs1.org/01/04791234567891/10/LOT123/17/261231?3103=001500"
        val res = scanLayer.parse(url, atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.GS1_QR, res.symbology)
        assertEquals("04791234567891", res.gtin)
        assertEquals("LOT123", res.lot)
        assertEquals(LocalDate(2026, 12, 31), res.expiry)
        assertEquals(1500L, res.weightGrams)
    }

    @Test
    fun ai17ExpiryDayZeroResolvedToEndOfMonth() {
        // Non-leap year 2026 February: 28 days
        val feb2026 = Gs1Parser.parseExpiry("260200", 2026)
        assertEquals(LocalDate(2026, 2, 28), feb2026)

        // Leap year 2024 February: 29 days
        val feb2024 = Gs1Parser.parseExpiry("240200", 2026)
        assertEquals(LocalDate(2024, 2, 29), feb2024)

        // April: 30 days
        val apr2026 = Gs1Parser.parseExpiry("260400", 2026)
        assertEquals(LocalDate(2026, 4, 30), apr2026)
    }

    @Test
    fun ai17InvalidDateFailsSafely() {
        val invalidMonth = Gs1Parser.parseExpiry("261301", 2026)
        assertNull(invalidMonth)

        val invalidDay = Gs1Parser.parseExpiry("260230", 2026)
        assertNull(invalidDay)
    }

    @Test
    fun weighedLabelPrefix20To24ExtractsSkuCodeAndWeightGrams() {
        // 2012345608504: prefix 20, sku 123456, weight 850g, check digit 4
        val res = scanLayer.parse("2012345608504", atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.INTERNAL_20_29, res.symbology)
        assertEquals("123456", res.code)
        assertEquals(850L, res.weightGrams)
        assertNull(res.batchRef)
    }

    @Test
    fun weighedLabelPrefix25To29ExtractsSkuCodeAndBatchRef() {
        // 2512345601234: prefix 25, sku 123456, batch 0123, check digit 4
        val res = scanLayer.parse("2512345601234", atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.INTERNAL_20_29, res.symbology)
        assertEquals("123456", res.code)
        assertEquals("0123", res.batchRef)
        assertNull(res.weightGrams)
    }

    @Test
    fun weighedLabelInvalidCheckDigitRejected() {
        val res = scanLayer.parse("2012345608502", atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.INTERNAL_20_29, res.symbology)
        kotlin.test.assertTrue(res.isMalformed)
        assertNull(res.code)
        assertNull(res.weightGrams)
    }

    @Test
    fun weighedLabelWithZeroWeightRejected() {
        // 2012345600008: prefix 20, sku 123456, weight 0g, check digit 8
        val res = scanLayer.parse("2012345600008", atMillis = 1000)
        assertNotNull(res)
        assertEquals(Symbology.INTERNAL_20_29, res.symbology)
        kotlin.test.assertTrue(res.isMalformed)
    }

    @Test
    fun gs1InvalidGtinCheckDigitRejected() {
        val raw = "(01)04791234567890(10)LOT123"
        val res = scanLayer.parse(raw, atMillis = 1000)
        assertNotNull(res)
        kotlin.test.assertTrue(res.isMalformed)
        assertNull(res.gtin)
    }

    @Test
    fun gs1InvalidNetWeightZeroRejected() {
        val raw = "(01)04791234567891(3103)000000"
        val res = scanLayer.parse(raw, atMillis = 1000)
        assertNotNull(res)
        kotlin.test.assertTrue(res.isMalformed)
    }

    @Test
    fun digitalLinkInvalidGtinRejected() {
        val url = "https://id.gs1.org/01/04791234567890"
        val res = scanLayer.parse(url, atMillis = 1000)
        assertNotNull(res)
        kotlin.test.assertTrue(res.isMalformed)
    }

    @Test
    fun digitalLinkMissingGtinRejected() {
        val url = "https://example.com/no-gtin"
        val res = scanLayer.parse(url, atMillis = 1000)
        assertNotNull(res)
        kotlin.test.assertTrue(res.isMalformed)
    }

    @Test
    fun unsupportedAimSymbologyRejected() {
        val res = scanLayer.parse("]Z912345", atMillis = 1000)
        assertNotNull(res)
        kotlin.test.assertTrue(res.isMalformed)
    }

    @Test
    fun aimPrefixIdentifiesSymbologyExplicitly() {
        val ean = scanLayer.parse("]E04791234567891", atMillis = 1000)
        assertNotNull(ean)
        assertEquals(Symbology.EAN13, ean.symbology)
        assertEquals("04791234567891", ean.gtin)

        val dm = scanLayer.parse("]d2(01)04791234567891(10)LOT1", atMillis = 2000)
        assertNotNull(dm)
        assertEquals(Symbology.GS1_DATAMATRIX, dm.symbology)
        assertEquals("04791234567891", dm.gtin)
    }

    @Test
    fun dualMarkDeduplicationDropsSecondSymbologyWithin700ms() {
        val layer = ScanLayer(dedupeMillis = 700)
        // First scan: 1D EAN-13
        val scan1 = layer.parse("4791234567891", atMillis = 1000)
        assertNotNull(scan1)
        assertEquals(Symbology.EAN13, scan1.symbology)

        // Second scan: 2D DataMatrix on same pack read 200ms later
        val scan2 = layer.parse("(01)04791234567891(10)LOT1", atMillis = 1200)
        assertNull(scan2, "Dual-marked pack read from different symbology within 700ms must be dropped")
    }

    @Test
    fun legitimateRepeatedScanAfter700msAccepted() {
        val layer = ScanLayer(dedupeMillis = 700)
        val scan1 = layer.parse("4791234567891", atMillis = 1000)
        assertNotNull(scan1)

        // Cashier scans a second identical item 1100ms later
        val scan2 = layer.parse("4791234567891", atMillis = 2100)
        assertNotNull(scan2, "Second identical item scanned after 700ms window must be accepted")
        assertEquals("04791234567891", scan2.gtin)
    }

    @Test
    fun rapidScansOfDifferentProductsAcceptedWithin700ms() {
        val layer = ScanLayer(dedupeMillis = 700)
        val scan1 = layer.parse("4791234567891", atMillis = 1000)
        assertNotNull(scan1)

        // Cashier immediately scans a different item 150ms later
        val scan2 = layer.parse("012345678905", atMillis = 1150)
        assertNotNull(scan2, "Different item scanned rapidly must never be dropped")
        assertEquals("00012345678905", scan2.gtin)
    }

    @Test
    fun twoIdenticalProductsScannedRapidlyWithin700msBothAccepted() {
        val layer = ScanLayer(dedupeMillis = 700)
        val scan1 = layer.parse("4791234567891", atMillis = 1000)
        assertNotNull(scan1)
        assertEquals(Symbology.EAN13, scan1.symbology)

        // Cashier scans a second identical can 300ms later (within 700ms window)
        val scan2 = layer.parse("4791234567891", atMillis = 1300)
        assertNotNull(scan2, "Second identical product scanned rapidly must be accepted, not dropped")
        assertEquals(Symbology.EAN13, scan2.symbology)
        assertEquals("04791234567891", scan2.gtin)
    }

    @Test
    fun dualMarkWithDigitalLinkAndEan13Within700msDeduplicated() {
        val layer = ScanLayer(dedupeMillis = 700)
        // First scan: 1D EAN-13
        val scan1 = layer.parse("4791234567891", atMillis = 1000)
        assertNotNull(scan1)
        assertEquals(Symbology.EAN13, scan1.symbology)

        // Second scan: GS1 Digital Link QR code on same pack read 250ms later
        val scan2 = layer.parse("https://id.gs1.org/01/04791234567891", atMillis = 1250)
        assertNull(scan2, "Dual-marked pack read from Digital Link QR within 700ms must be dropped")
    }
}
