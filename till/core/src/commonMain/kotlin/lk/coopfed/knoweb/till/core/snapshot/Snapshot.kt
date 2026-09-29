package lk.coopfed.knoweb.till.core.snapshot

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One row of a snapshot table as central sent it; [data] null is a tombstone. */
data class SnapshotRow(
    val table: String,
    val rowId: String,
    val applyFrom: LocalDate?,
    val data: JsonObject?,
)

/** The upserts and tombstones of one table in a snapshot answer. */
data class SnapshotTableDelta(
    val upserts: List<SnapshotRow>,
    val tombstones: List<SnapshotRow>,
)

/** Central's snapshot answer (sync.yaml SnapshotDelta), read from its JSON. */
data class SnapshotDelta(
    val locationId: String,
    val since: Long?,
    val version: Long,
    val fullSnapshotRequired: Boolean,
    val urgent: Boolean,
    val tables: Map<String, SnapshotTableDelta>,
    val manifest: String,
    val signature: String,
    val keyId: String,
) {
    companion object {
        fun fromJson(json: JsonObject): SnapshotDelta {
            val tables = json.getValue("tables").jsonObject.mapValues { (name, value) ->
                val table = value.jsonObject
                SnapshotTableDelta(
                    upserts = table["upserts"]?.jsonArray.orEmpty().map { r ->
                        val row = r.jsonObject
                        SnapshotRow(name, row.text("row_id")!!, row.date("apply_from"), row.getValue("data").jsonObject)
                    },
                    tombstones = table["tombstones"]?.jsonArray.orEmpty().map { r ->
                        val row = r.jsonObject
                        SnapshotRow(name, row.text("row_id")!!, row.date("apply_from"), null)
                    },
                )
            }
            return SnapshotDelta(
                locationId = json.text("location_id")!!,
                since = json["since"]?.jsonPrimitive?.longOrNull,
                version = json.getValue("version").jsonPrimitive.longOrNull!!,
                fullSnapshotRequired = json.getValue("full_snapshot_required").jsonPrimitive.booleanOrNull!!,
                urgent = json["urgent"]?.jsonPrimitive?.booleanOrNull ?: false,
                tables = tables,
                manifest = json.text("manifest")!!,
                signature = json.text("signature")!!,
                keyId = json.text("key_id") ?: "",
            )
        }
    }
}

internal fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.date(name: String): LocalDate? = text(name)?.let { LocalDate.parse(it) }
