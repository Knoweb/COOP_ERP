package lk.coopfed.knoweb.till.core

import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.port.PinVerifier
import lk.coopfed.knoweb.till.core.sale.Basket
import lk.coopfed.knoweb.till.core.snapshot.SnapshotVerifier

/** An enrolled till with a snapshot of two items and one operator, for the use-case tests. */
class TillFixture {
    val store = InMemoryStore()
    val clock = FixedClock()
    val central = FakeCentral(
        Samples.snapshotAnswer(
            7,
            listOf(
                Samples.location(),
                Samples.sku(Samples.SKU_RICE, "Rice 5kg", "සහල් 5kg", "4790000000011"),
                Samples.sku(Samples.SKU_DHAL, "Dhal 1kg", "පරිප්පු 1kg", "4790000000028"),
                Samples.price(Samples.SKU_RICE, "1250.00"),
                Samples.price(Samples.SKU_DHAL, "385.50"),
                Samples.operator("pin:1234"),
            ),
        ),
    )
    /** A PIN "hash" is "pin:" and the PIN; the real Argon2id check is tested on the JVM. */
    val pins = PinVerifier { pin, hash -> hash == "pin:$pin" }
    val service = newService()

    fun newService() = TillService(store, central, SnapshotVerifier(FakeSignatures), pins, clock, CountingIds(), "0.1.0")

    suspend fun enrolled(): TillFixture {
        service.start()
        service.enrol("http://localhost:8080", "0190f0de-0000-7000-8000-0000000c0001", "CODE", "DESKTOP-TRIAL-S01")
        service.refreshSnapshot()
        return this
    }

    suspend fun signedInWithSession(float: String = "2000.00"): TillFixture {
        enrolled()
        service.signIn(service.catalogue.operators.single(), "1234")
        service.openSession(Money.parse(float))
        return this
    }

    fun basket(vararg barcodes: String): Basket = Basket().apply {
        for (code in barcodes) {
            val item = service.catalogue.byBarcode(code)!!
            add(item, item.price!!)
        }
    }

    companion object {
        const val RICE = "4790000000011"
        const val DHAL = "4790000000028"
    }
}
