package lk.coopfed.knoweb.till.sync

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import lk.coopfed.knoweb.till.core.port.UnreadablePinHash
import lk.coopfed.knoweb.till.core.snapshot.SnapshotDelta
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRejected
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow
import lk.coopfed.knoweb.till.core.snapshot.SnapshotTableDelta
import lk.coopfed.knoweb.till.core.snapshot.SnapshotVerifier
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

class JvmCryptoTest {

    private val location = "0190f0de-0000-7000-8000-000000000132"
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)

    /** A snapshot answer signed with a real Ed25519 key, as central's kernel signs it. */
    private fun signedSnapshot(name: String): JsonObject {
        val row = SnapshotRow("sku", "0190f0de-0000-7000-8000-0000000a0001", null, buildJsonObject {
            put("short_name_en", name)
            put("short_name_si", "සහල්")
            put("short_name_ta", "அரிசி")
        })
        val hash = SnapshotVerifier.tableHash(SnapshotTableDelta(listOf(row), emptyList()))
        val manifest = """{"full":true,"location_id":"$location","since":0,"tables":{"sku":{"sha256":"$hash","tombstones":0,"upserts":1}},"version":5}"""
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(manifest.toByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
        return buildJsonObject {
            put("location_id", location)
            put("version", 5)
            put("full_snapshot_required", true)
            put("urgent", false)
            putJsonObject("tables") {
                putJsonObject("sku") {
                    put("upserts", buildJsonArray { add(buildJsonObject { put("row_id", row.rowId); put("data", row.data!!) }) })
                    put("tombstones", JsonArray(emptyList()))
                }
            }
            put("manifest", manifest)
            put("signature", signature)
            put("key_id", "dev")
        }
    }

    @Test
    fun aSnapshotSignedWithCentralsEd25519KeyVerifies() {
        SnapshotVerifier(JdkEd25519Verifier).verify(SnapshotDelta.fromJson(signedSnapshot("Rice 5kg")), location, publicKey)
    }

    @Test
    fun aSnapshotSignedWithAnotherKeyIsRefused() {
        val other = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)
        assertFailsWith<SnapshotRejected> {
            SnapshotVerifier(JdkEd25519Verifier).verify(SnapshotDelta.fromJson(signedSnapshot("Rice 5kg")), location, other)
        }
    }

    @Test
    fun aSnapshotChangedAfterSigningIsRefused() {
        val signed = signedSnapshot("Rice 5kg").toString().replace("Rice 5kg", "Rice 9kg")
        val tampered = kotlinx.serialization.json.Json.parseToJsonElement(signed) as JsonObject
        val refusal = assertFailsWith<SnapshotRejected> {
            SnapshotVerifier(JdkEd25519Verifier).verify(SnapshotDelta.fromJson(tampered), location, publicKey)
        }
        assertEquals("The hash of table sku does not match its manifest", refusal.message)
    }

    private val salt = "fixed-test-salt!".toByteArray()
    private val b64 = Base64.getEncoder().withoutPadding()

    /** A PHC string for PIN 1234 with these parameters. */
    private fun phc(memoryKb: Int = 8192, iterations: Int = 1, parallelism: Int = 1): String {
        val hash = ByteArray(32)
        Argon2BytesGenerator().apply {
            init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(19).withMemoryAsKB(memoryKb)
                .withIterations(iterations).withParallelism(parallelism).withSalt(salt).build())
        }.generateBytes("1234".toByteArray(), hash)
        return "\$argon2id\$v=19\$m=$memoryKb,t=$iterations,p=$parallelism\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    @Test
    fun aPinIsCheckedAgainstCentralsArgon2idString() {
        // The smallest parameters the till accepts keep the test fast; central's are m=65536,t=3,p=2,
        // read from the string the same way.
        val phc = phc()

        assertTrue(Argon2PinVerifier.verify("1234", phc))
        assertFalse(Argon2PinVerifier.verify("4321", phc))
    }

    @Test
    fun aPinRecordTheTillCannotReadIsNotAWrongPin() {
        val good = phc()
        val unreadable = listOf(
            "\$bcrypt\$not-argon",
            "",
            good.replace("m=8192", "m=1024"), // below 8 MB
            good.replace("m=8192", "m=2097152"), // above 1 GB
            good.replace("t=1", "t=0"),
            good.replace("t=1", "t=101"),
            good.replace("p=1", "p=17"),
            good.replace("m=8192,", ""), // memory missing
            good.replace("t=1", "t=x"), // not a number
            good.replace("v=19", "v=99"),
            good.substringBeforeLast('$') + "\$not*base64",
        )
        for (hash in unreadable) {
            assertFailsWith<UnreadablePinHash>(hash) { Argon2PinVerifier.verify("1234", hash) }
        }
    }

    @Test
    fun theBatchBodyGzipsAndInflatesBackToTheSameText() {
        val text = """{"batch_id":"b1","events":[${"{\"payload\":\"සහල් 5kg\"},".repeat(50).trimEnd(',')}]}"""
        val packed = JvmGzip(text.toByteArray())

        assertEquals(0x1f, packed[0].toInt() and 0xff)
        assertEquals(0x8b, packed[1].toInt() and 0xff)
        assertTrue(packed.size < text.toByteArray().size)
        assertEquals(text, GZIPInputStream(packed.inputStream()).readBytes().decodeToString())
    }
}
