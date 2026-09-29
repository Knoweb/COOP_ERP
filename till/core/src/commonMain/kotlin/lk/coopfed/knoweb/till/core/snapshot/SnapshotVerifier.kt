package lk.coopfed.knoweb.till.core.snapshot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import lk.coopfed.knoweb.till.core.crypto.Base64
import lk.coopfed.knoweb.till.core.crypto.Sha256
import lk.coopfed.knoweb.till.core.json.CanonicalJson
import lk.coopfed.knoweb.till.core.port.SignatureVerifier

/** The till refuses a snapshot it cannot trust; it keeps selling with the one it has. */
class SnapshotRejected(message: String) : Exception(message)

/**
 * Checks a snapshot answer before anything of it is applied (doc 32 section 5; sync.yaml
 * SnapshotDelta.manifest): the manifest is signed by central's key from the enrolment answer
 * (Ed25519 over its UTF-8 bytes), it describes this snapshot, and every table hashes to what the
 * manifest says.
 */
class SnapshotVerifier(private val signatures: SignatureVerifier) {

    fun verify(delta: SnapshotDelta, locationId: String, publicKey: String) {
        val signature = try {
            Base64.decode(delta.signature)
        } catch (e: IllegalArgumentException) {
            throw SnapshotRejected("The snapshot signature is not base64")
        }
        if (!signatures.verifyEd25519(publicKey, delta.manifest.encodeToByteArray(), signature)) {
            throw SnapshotRejected("The snapshot manifest is not signed by central")
        }
        val manifest = Json.parseToJsonElement(delta.manifest).jsonObject
        if (manifest["version"]?.jsonPrimitive?.longOrNull != delta.version ||
            manifest["location_id"]?.jsonPrimitive?.contentOrNull != locationId ||
            delta.locationId != locationId ||
            manifest["full"]?.jsonPrimitive?.booleanOrNull != delta.fullSnapshotRequired
        ) {
            throw SnapshotRejected("The manifest does not describe this snapshot")
        }
        val tables = manifest["tables"]?.jsonObject ?: JsonObject(emptyMap())
        if (tables.keys != delta.tables.keys) {
            throw SnapshotRejected("The manifest and the snapshot name different tables")
        }
        for ((name, table) in delta.tables) {
            val expected = tables.getValue(name).jsonObject["sha256"]?.jsonPrimitive?.contentOrNull
            if (expected != tableHash(table)) {
                throw SnapshotRejected("The hash of table $name does not match its manifest")
            }
        }
    }

    companion object {
        /**
         * SHA-256 (hex) over the table's lines joined by a newline: the upserts sorted by the text
         * of row_id, "U <row_id> <apply_from or -> <canonical data>", then the tombstones likewise,
         * "D <row_id> <apply_from or ->".
         */
        fun tableHash(table: SnapshotTableDelta): String {
            val lines = mutableListOf<String>()
            table.upserts.sortedBy { it.rowId }.forEach {
                lines += "U ${it.rowId} ${it.applyFrom ?: "-"} ${CanonicalJson.write(it.data!!)}"
            }
            table.tombstones.sortedBy { it.rowId }.forEach {
                lines += "D ${it.rowId} ${it.applyFrom ?: "-"}"
            }
            return Sha256.hex(lines.joinToString("\n"))
        }
    }
}
