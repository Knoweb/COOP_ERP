package lk.coopfed.knoweb.till.core.sale

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import lk.coopfed.knoweb.till.core.crypto.Sha256
import lk.coopfed.knoweb.till.core.money.Decimals

/**
 * The content hash of a document bundle, by the kernel's rule (backend kernel ContentHash and
 * BundleHash; doc 18 "SHA-256 over the canonical header and lines; verified on sync"): the fields
 * below in this order, one per line as name=value, each line of the document as "line:" and its
 * fields, lines in line order; a null is empty, a decimal is rounded to its column's scale and
 * written with trailing zeros stripped. Central recomputes it and flags a bundle whose hash differs.
 */
object ContentHash {

    private val HEADER = listOf(
        "document_id" to null, "doc_type_code" to null, "series_id" to null, "doc_number" to null,
        "doc_number_display" to null, "owner_entity_id" to null, "counterparty_entity_id" to null,
        "location_id" to null, "till_position_id" to null, "device_id" to null, "issued_at" to null,
        "business_date" to null, "operator_user_id" to null, "currency" to null, "net_amount" to 2,
        "tax_amount" to 2, "gross_amount" to 2, "reference_document_id" to null, "origin" to null,
        "device_seq" to null,
    )

    private val LINE = listOf(
        "line_no" to null, "sku_id" to null, "batch_id" to null, "uom_code" to null, "qty" to 3,
        "unit_price" to 4, "mrp_applied" to 4, "control_price_applied" to 4, "cap_reason" to null,
        "discount_rule_id" to null, "discount_amount" to 2, "tax_rate_percent" to 3, "tax_amount" to 2,
        "line_total" to 2, "unit_cost_at_issue" to 4, "loss_category" to null, "reference_line_id" to null,
    )

    fun of(document: JsonObject, lines: JsonArray): String {
        val canon = StringBuilder(512)
        append(canon, document, HEADER)
        lines.map { it.jsonObject }
            .sortedBy { (it["line_no"] as JsonPrimitive).content.toInt() }
            .forEach { line ->
                canon.append("line:\n")
                append(canon, line, LINE)
            }
        return Sha256.hex(canon.toString())
    }

    private fun append(canon: StringBuilder, source: JsonObject, fields: List<Pair<String, Int?>>) {
        for ((name, scale) in fields) {
            val value = (source[name] as? JsonPrimitive)?.contentOrNull
            val text = when {
                value == null -> ""
                scale != null -> Decimals.canonical(Decimals.parse(value, scale), scale)
                else -> value
            }
            canon.append(name).append('=').append(text).append('\n')
        }
    }
}
