package lk.coopfed.knoweb.till.core

import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.port.PinVerifier
import lk.coopfed.knoweb.till.core.port.UnreadablePinHash
import lk.coopfed.knoweb.till.core.sale.Basket
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow
import lk.coopfed.knoweb.till.core.snapshot.SnapshotVerifier

/** An enrolled till with a snapshot of two items and one operator, for the use-case tests. */
class TillFixture(
    operators: List<SnapshotRow> = listOf(Samples.operator("pin:1234")),
    private val trialCashier: Boolean = false,
    private val tokenEndpointSetLocally: Boolean = false,
) {
    val store = InMemoryStore()
    val clock = FixedClock()
    val rows = listOf(
        Samples.location(),
        Samples.sku(Samples.SKU_RICE, "Rice 5kg", "සහල් 5kg", "4790000000011"),
        Samples.sku(Samples.SKU_DHAL, "Dhal 1kg", "පරිප්පු 1kg", "4790000000028"),
        Samples.price(Samples.SKU_RICE, "1250.00"),
        Samples.price(Samples.SKU_DHAL, "385.50"),
    ) + operators
    val central = FakeCentral(Samples.snapshotAnswer(7, rows))

    /**
     * A PIN "hash" is "pin:" and the PIN; anything else is a record the till cannot read. The real
     * Argon2id check is tested on the JVM.
     */
    val pins = PinVerifier { pin, hash ->
        if (!hash.startsWith("pin:")) throw UnreadablePinHash("not a test PIN record")
        hash == "pin:$pin"
    }
    var service = newService()

    /** A till over the same database, as after closing and starting the application again. */
    fun newService() = TillService(
        store, central, SnapshotVerifier(FakeSignatures), pins, clock, CountingIds(), "0.1.0",
        trialCashierAllowed = trialCashier, tokenEndpointSetLocally = tokenEndpointSetLocally,
    )

    /** Closes the till and starts it again on the same database. */
    suspend fun restart(): TillService {
        service = newService()
        service.start()
        return service
    }

    suspend fun enrolled(): TillFixture {
        service.start()
        service.enrol("http://localhost:8080", Samples.DEVICE, "CODE", "DESKTOP-TRIAL-S01")
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
