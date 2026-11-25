package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.snapshot.Catalogue
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow
import lk.coopfed.knoweb.till.core.snapshot.SnapshotState

class CatalogueBarcodeLookupTest {

    private fun makeSku(
        id: String,
        skuCode: String,
        name: String,
        barcodes: List<String>,
        soldByWeight: Boolean = false,
    ): SnapshotRow = SnapshotRow(
        "sku", id, null,
        buildJsonObject {
            put("sku_code", skuCode)
            put("status", "ACTIVE")
            put("short_name_en", name)
            put("base_uom_code", if (soldByWeight) "KG" else "EA")
            put("sold_by_weight", soldByWeight)
            putJsonArray("barcodes") {
                barcodes.forEach { bc ->
                    add(buildJsonObject { put("barcode", bc) })
                }
            }
        },
    )

    private fun catalogueWith(vararg rows: SnapshotRow): Catalogue {
        val live = mapOf("sku" to rows.associateBy { it.rowId })
        val state = SnapshotState(1, live, emptyList())
        val prices = mapOf(
            "sku-rice" to Money.parse("150.00"),
            "sku-dhal" to Money.parse("80.00"),
            "sku-veg" to Money.parse("200.00"),
        )
        return Catalogue(state, prices)
    }

    @Test
    fun lookupByExactBarcode() {
        val cat = catalogueWith(
            makeSku("sku-rice", "RICE", "Rice 5kg", listOf("4791234567891")),
        )
        val item = cat.byBarcode("4791234567891")
        assertNotNull(item)
        assertEquals("sku-rice", item.skuId)
    }

    @Test
    fun lookupByGtin14MatchesItemRegisteredAsEan13() {
        val cat = catalogueWith(
            makeSku("sku-rice", "RICE", "Rice 5kg", listOf("4791234567891")),
        )
        // Scanned as GTIN-14 (04791234567891) matching registered EAN-13 (4791234567891)
        val item = cat.byGtin("04791234567891")
        assertNotNull(item)
        assertEquals("sku-rice", item.skuId)
    }

    @Test
    fun lookupByGtin14MatchesItemRegisteredAsUpcA() {
        val cat = catalogueWith(
            makeSku("sku-dhal", "DHAL", "Dhal 1kg", listOf("012345678905")),
        )
        // Scanned as GTIN-14 (00012345678905) matching registered UPC-A (012345678905)
        val item = cat.byGtin("00012345678905")
        assertNotNull(item)
        assertEquals("sku-dhal", item.skuId)
    }

    @Test
    fun lookupBySkuCodeMatchesItem() {
        val cat = catalogueWith(
            makeSku("sku-veg", "123456", "Fresh Carrots", emptyList(), soldByWeight = true),
        )
        val item = cat.bySkuCode("123456")
        assertNotNull(item)
        assertEquals("sku-veg", item.skuId)
    }

    @Test
    fun lookupBySkuCodeWithLeadingZerosMatchesStripped() {
        val cat = catalogueWith(
            makeSku("sku-veg", "123456", "Fresh Carrots", emptyList(), soldByWeight = true),
        )
        val item = cat.bySkuCode("000123456")
        assertNotNull(item)
        assertEquals("sku-veg", item.skuId)
    }

    @Test
    fun unknownBarcodeReturnsNull() {
        val cat = catalogueWith(
            makeSku("sku-rice", "RICE", "Rice 5kg", listOf("4791234567891")),
        )
        assertNull(cat.byBarcode("9999999999999"))
        assertNull(cat.byGtin("99999999999999"))
        assertNull(cat.bySkuCode("NONEXISTENT"))
    }
}
