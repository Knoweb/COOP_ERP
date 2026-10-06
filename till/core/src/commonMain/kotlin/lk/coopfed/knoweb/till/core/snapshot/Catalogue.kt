package lk.coopfed.knoweb.till.core.snapshot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import lk.coopfed.knoweb.till.core.money.Money

/** The till's three languages; a shop has one of them for its receipts. */
enum class Language(val code: String) {
    EN("en"), SI("si"), TA("ta");

    companion object {
        fun of(code: String?): Language = entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: EN
    }
}

/** A name in the three languages, with English as the fallback (doc 26 section 3.4). */
data class Trilingual(val en: String, val si: String?, val ta: String?) {
    fun get(language: Language): String = when (language) {
        Language.EN -> en
        Language.SI -> si?.takeIf { it.isNotBlank() } ?: en
        Language.TA -> ta?.takeIf { it.isNotBlank() } ?: en
    }

    /** True when [language] has no text of its own and falls back to English (the receipt tags it "EN"). */
    fun fallsBack(language: Language): Boolean = language != Language.EN && get(language) == en && en.isNotEmpty() &&
        (if (language == Language.SI) si.isNullOrBlank() else ta.isNullOrBlank())
}

/** An item the till can sell (the snapshot's sku table, M2). */
data class Item(
    val skuId: String,
    val skuCode: String,
    val name: Trilingual,
    val baseUom: String,
    val soldByWeight: Boolean,
    val barcodes: List<String>,
    /** The shelf price, when the snapshot carries one (see [Catalogue]); null asks the cashier. */
    val price: Money?,
)

/** An operator who may sign in at this shop (the snapshot's operator table, M1). */
data class Operator(
    val userId: String,
    val displayName: String,
    val language: Language,
    val pinHash: String?,
    val permissions: List<String>,
)

/** The shop itself (the snapshot's location table, M1). */
data class Shop(
    val locationId: String,
    val code: String,
    val name: Trilingual,
    val language: Language,
)

/**
 * The typed view of the live snapshot the sell screen works with: items by barcode and by name,
 * operators, the shop.
 *
 * Prices: central's snapshot has no price table yet (the M3 contributor is not built; doc 32 section
 * 5.1 lists prices). The till reads a table named `price` (row: sku_id, unit_price) when central
 * sends one, then a local price book (a file the shop can be given for the trial), and otherwise
 * asks the cashier to key the price. See the till README, "Deviations".
 */
class Catalogue(snapshot: SnapshotState, localPrices: Map<String, Money> = emptyMap()) {

    val items: List<Item>
    val operators: List<Operator>
    val shop: Shop?
    /**
     * The location's configuration items (doc 32 section 5.1, "configuration items for the
     * location"), key to value, from a table `config` (row: key, value) when central sends one;
     * the kernel's contributor for it is not built yet, so the till keeps its defaults until then.
     */
    val config: Map<String, String>
    private val byBarcode: Map<String, Item>

    init {
        config = snapshot.table("config").mapNotNull { row ->
            val d = row.data ?: return@mapNotNull null
            val key = d.str("key") ?: return@mapNotNull null
            val value = d.str("value") ?: return@mapNotNull null
            key to value
        }.toMap()
        val centralPrices = snapshot.table("price").mapNotNull { row ->
            val data = row.data ?: return@mapNotNull null
            val sku = data.str("sku_id") ?: return@mapNotNull null
            val price = data.str("unit_price") ?: return@mapNotNull null
            sku to Money.parse(price)
        }.toMap()
        items = snapshot.table("sku").mapNotNull { row ->
            val d = row.data ?: return@mapNotNull null
            if (d.str("status") == "RETIRED") return@mapNotNull null
            val barcodes = (d["barcodes"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.str("barcode")
            }
            Item(
                skuId = row.rowId,
                skuCode = d.str("sku_code") ?: "",
                name = Trilingual(d.str("short_name_en") ?: d.str("sku_code") ?: "?", d.str("short_name_si"), d.str("short_name_ta")),
                baseUom = d.str("base_uom_code") ?: "EA",
                soldByWeight = d["sold_by_weight"]?.jsonPrimitive?.booleanOrNull ?: false,
                barcodes = barcodes,
                price = centralPrices[row.rowId] ?: barcodes.firstNotNullOfOrNull { localPrices[it] }
                    ?: localPrices[row.rowId] ?: localPrices[d.str("sku_code") ?: ""],
            )
        }.sortedBy { it.name.en }
        byBarcode = buildMap { items.forEach { item -> item.barcodes.forEach { put(it, item) } } }
        operators = snapshot.table("operator").mapNotNull { row ->
            val d = row.data ?: return@mapNotNull null
            Operator(
                userId = row.rowId,
                displayName = d.str("display_name") ?: row.rowId,
                language = Language.of(d.str("language")),
                pinHash = d.str("pin_hash"),
                permissions = (d["permissions"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            )
        }.sortedBy { it.displayName }
        shop = snapshot.table("location").firstOrNull()?.let { row ->
            val d = row.data!!
            Shop(
                locationId = row.rowId,
                code = d.str("location_code") ?: "",
                name = Trilingual(d.str("name_en") ?: "", d.str("name_si"), d.str("name_ta")),
                language = Language.of(d.str("language")),
            )
        }
    }

    fun byBarcode(code: String): Item? = byBarcode[code.trim()]

    /** Items whose name (any language), code or barcode contains [query]. */
    fun search(query: String, limit: Int = 20): List<Item> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return items.asSequence().filter { item ->
            item.name.en.lowercase().contains(q) || item.name.si?.contains(q) == true ||
                item.name.ta?.contains(q) == true || item.skuCode.lowercase().contains(q) ||
                item.barcodes.any { it.contains(q) }
        }.take(limit).toList()
    }

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
