package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import lk.coopfed.knoweb.till.core.money.Decimals
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty
import lk.coopfed.knoweb.till.core.sale.ContentHash

class ContentHashTest {

    /**
     * The expected value was computed by the backend's own rule (kernel BundleHash.of, run with
     * the backend's classes on this bundle): if the kernel's canonical form changes, this test
     * says the till must change with it.
     */
    @Test
    fun theTillHashesAReceiptBundleExactlyAsCentralDoes() {
        val document = Json.parseToJsonElement(
            """
            {"document_id":"0190f0de-0000-7000-8000-0000000f0001","doc_type_code":"RCT",
             "series_id":"0190f0de-0000-7000-8000-0000000e0001","doc_number":41,"doc_number_display":"S01-T2-41",
             "owner_entity_id":"0190f0de-0000-7000-8000-0000000000e3","location_id":"0190f0de-0000-7000-8000-000000000132",
             "till_position_id":"0190f0de-0000-7000-8000-0000000d0002","device_id":"0190f0de-0000-7000-8000-0000000c0001",
             "issued_at":"2026-09-29T04:30:00Z","business_date":"2026-09-29","operator_user_id":"0190f0de-0000-7000-8000-0000000b0001",
             "currency":"LKR","net_amount":"2021.00","tax_amount":"0.00","gross_amount":"2021.00","origin":"OFFLINE","device_seq":7}
            """,
        ).jsonObject
        val lines = Json.parseToJsonElement(
            """
            [{"line_no":1,"sku_id":"0190f0de-0000-7000-8000-0000000a0001","uom_code":"EA","qty":"1.000","unit_price":"1250.00","tax_amount":"0.00","line_total":"1250.00"},
             {"line_no":2,"sku_id":"0190f0de-0000-7000-8000-0000000a0002","uom_code":"EA","qty":"2.000","unit_price":"385.50","tax_amount":"0.00","line_total":"771.00"}]
            """,
        ).jsonArray

        assertEquals("7446f0f4b4e5dabb19115e422a4fd936ccdee53e7d7527c175f172bf3e7f3f38", ContentHash.of(document, lines))
    }

    @Test
    fun decimalsRoundHalfAwayFromZeroAndPrintLikeTheKernel() {
        assertEquals(1235, Decimals.parse("12.345", 2))
        assertEquals(-1235, Decimals.parse("-12.345", 2))
        assertEquals("10", Decimals.canonical(Decimals.parse("10.00", 2), 2))
        assertEquals("0", Decimals.canonical(0, 3))
        assertEquals("385.5", Decimals.canonical(38550, 2))
        assertEquals(Money.parse("771.00"), Qty.of(2).times(Money.parse("385.50")))
        assertEquals(Money.parse("96.38"), Qty.parse("0.25").times(Money.parse("385.50")))
        assertEquals("1,250.00", Money.parse("1250").display())
    }
}
