package lk.coopfed.knoweb.engine.snapshot

import java.time.LocalDate
import java.util.UUID
import lk.coopfed.knoweb.engine.model.BatchCandidate
import lk.coopfed.knoweb.engine.model.Ceiling
import lk.coopfed.knoweb.engine.model.Policy
import lk.coopfed.knoweb.engine.model.RetailLine
import lk.coopfed.knoweb.engine.model.Rule
import lk.coopfed.knoweb.engine.model.SkuFacts
import lk.coopfed.knoweb.engine.model.StackingPolicy

/**
 * Everything the engine reads, indexed once per snapshot version (23A section 6, "Index").
 * The till builds it from its snapshot rows, central from its tables; building it is O(rows).
 *
 * Ceilings are given per SKU and per tag in the unit they apply to. Unit conversion of a per-kg
 * ceiling to a 400 g pack (23A section 11) is the caller's work until M3-06 builds the control
 * price; the index matches a ceiling to a line only when the units are the same.
 */
class PricingSnapshotIndex(
    val snapshotVersion: Long,
    val stackingPolicy: StackingPolicy,
    skus: Collection<SkuFacts>,
    retailLines: Collection<RetailLine>,
    batches: Map<UUID, List<BatchCandidate>>,
    policies: Map<UUID, Policy> = emptyMap(),
    skuCeilings: Map<UUID, List<Ceiling>> = emptyMap(),
    tagCeilings: Map<String, List<Ceiling>> = emptyMap(),
    rules: Collection<Rule> = emptyList()
) {
    private val skus: Map<UUID, SkuFacts> = skus.associateBy { it.skuId }
    private val retail: Map<Pair<UUID, String>, List<RetailLine>> = retailLines.groupBy { it.skuId to it.uom }
    private val batches: Map<UUID, List<BatchCandidate>> = batches
    private val batchById: Map<UUID, BatchCandidate> = batches.values.flatten().associateBy { it.batchId }
    private val policies: Map<UUID, Policy> = policies
    private val skuCeilings: Map<UUID, List<Ceiling>> = skuCeilings
    private val tagCeilings: Map<String, List<Ceiling>> = tagCeilings
    private val lineRules: List<Rule> = rules.filter { it.lineLevel }
    private val billRules: List<Rule> = rules.filter { !it.lineLevel }

    fun sku(skuId: UUID): SkuFacts? = skus[skuId]

    /** The retail line in force on the date; the highest tier when a list carries tiers. */
    fun retailLine(skuId: UUID, uom: String, date: LocalDate): RetailLine? =
        retail[skuId to uom].orEmpty().filter { it.inForce(date) }.maxByOrNull { it.tierFromQty }

    fun inStockBatches(skuId: UUID): List<BatchCandidate> = batches[skuId].orEmpty()

    fun batch(batchId: UUID): BatchCandidate? = batchById[batchId]

    fun policyFor(skuId: UUID): Policy = policies[skuId] ?: Policy.DEFAULT

    /** The lowest ceiling in force of the SKU and of its tags (doc 23 DR-2: the lower applies). */
    fun ceilingFor(skuId: UUID, uom: String, date: LocalDate): Ceiling? {
        val tags = skus[skuId]?.tags.orEmpty()
        val all = skuCeilings[skuId].orEmpty() + tags.flatMap { tagCeilings[it].orEmpty() }
        return all.filter { it.uom == uom && it.inForce(date) }.minByOrNull { it.price }
    }

    /** Line-level rules valid on the date whose scope is the SKU or one of its tags. */
    fun lineRulesFor(skuId: UUID, date: LocalDate): List<Rule> {
        val tags = skus[skuId]?.tags.orEmpty()
        return lineRules.filter { it.validOn(date) && (it.skuId == skuId || (it.tag != null && it.tag in tags)) }
    }

    fun billRules(date: LocalDate): List<Rule> = billRules.filter { it.validOn(date) }
}
