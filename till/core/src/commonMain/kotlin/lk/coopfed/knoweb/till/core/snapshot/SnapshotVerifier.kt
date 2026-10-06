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
open class SnapshotRejected(message: String) : Exception(message)

/**
 * A signed delta that does not start at the version the till holds (TWK-01): applying it would skip
 * the versions in between. The till asks for a full snapshot instead.
 */
class SnapshotNotContinuous(message: String) : SnapshotRejected(message)

/** What the signed manifest says, once [SnapshotVerifier.verify] has checked it. */
data class VerifiedManifest(val since: Long, val version: Long, val full: Boolean)

/**
 * Checks a snapshot answer before anything of it is applied (doc 32 section 5; sync.yaml
 * SnapshotDelta.manifest): it is signed with the key this till enrolled with (the answer's key_id,
 * then Ed25519 over the manifest's UTF-8 bytes), the manifest describes this snapshot, and every
 * table hashes to what the manifest says. [continuity] then checks it follows the version the till
 * holds.
 */
class SnapshotVerifier(private val signatures: SignatureVerifier) {

    /**
     * @param keyId the signing key's id from the enrolment answer; an answer signed with another
     *   key is refused with a clearer reason than a failed signature
     */
    fun verify(delta: SnapshotDelta, locationId: String, publicKey: String, keyId: String? = null): VerifiedManifest {
        if (keyId != null && delta.keyId != keyId) {
            throw SnapshotRejected("The snapshot is signed with key \"${delta.keyId}\", not with the key this till enrolled with (\"$keyId\")")
        }
        if (!signedByCentral(delta.manifest, delta.signature, publicKey)) {
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
        // The signed since, never the answer's unsigned field.
        val since = manifest["since"]?.jsonPrimitive?.longOrNull ?: throw SnapshotRejected("The manifest carries no since")
        return VerifiedManifest(since, delta.version, delta.fullSnapshotRequired)
    }

    /**
     * Whether a verified answer may follow the version the till holds (TWK-01; doc 32 section 5.2,
     * one monotonic version per location): never a version below it, and a delta (not a full
     * snapshot) only from exactly that version. A full snapshot may carry any since.
     *
     * @throws SnapshotRejected for an older version
     * @throws SnapshotNotContinuous for a delta from another version: ask for since=0
     */
    fun continuity(manifest: VerifiedManifest, currentVersion: Long) {
        if (manifest.version < currentVersion) {
            throw SnapshotRejected("The snapshot is version ${manifest.version}, older than the version $currentVersion this till holds")
        }
        if (!manifest.full && manifest.since != currentVersion) {
            throw SnapshotNotContinuous("The snapshot delta starts at version ${manifest.since}, but this till holds version $currentVersion")
        }
    }

    /** Whether [signature] (base64) is central's Ed25519 signature of [text] with [publicKey]. */
    fun signedByCentral(text: String, signature: String, publicKey: String): Boolean {
        val bytes = try {
            Base64.decode(signature)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return signatures.verifyEd25519(publicKey, text.encodeToByteArray(), bytes)
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
