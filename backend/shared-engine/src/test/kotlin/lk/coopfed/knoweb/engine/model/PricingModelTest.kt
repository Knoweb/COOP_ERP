package lk.coopfed.knoweb.engine.model

import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import lk.coopfed.knoweb.engine.Money
import lk.coopfed.knoweb.engine.Quantity

class PricingModelTest {

    @Test
    fun retailLineDefaultsToZeroTierQuantity() {
        val line =
            RetailLine(
                skuId = UUID.randomUUID(),
                uom = "EA",
                price = Money.of("100.00")
            )

        assertEquals(
            Quantity.zero(),
            line.tierFromQty
        )
    }

    @Test
    fun batchCandidateAllowsMissingPrintedMrpAndExpiry() {
        val batch =
            BatchCandidate(
                batchId = UUID.randomUUID(),
                batchNo = "B-001",
                printedMrp = null,
                expiry = null,
                onHand = Quantity.of("5.000")
            )

        assertNull(batch.printedMrp)
        assertNull(batch.expiry)
    }

    @Test
    fun policyCarriesPickerThresholds() {
        val policy =
            Policy(
                kind = PolicyKind.PICKER,
                gapAmount = Money.of("20.00"),
                gapPercent = BigDecimal("5.00")
            )

        assertEquals(
            PolicyKind.PICKER,
            policy.kind
        )

        assertEquals(
            BigDecimal("5.00"),
            policy.gapPercent
        )
    }

    @Test
    fun capReasonsMatchPricingContract() {
        assertEquals(
            listOf(
                "NONE",
                "MRP_LOWEST",
                "MRP_BARCODE",
                "MRP_PICKED",
                "CONTROL_PRICE"
            ),
            CapReason.entries.map { it.name }
        )
    }
}
