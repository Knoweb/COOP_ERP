package lk.coopfed.knoweb.till.core.id

import kotlin.random.Random

/** Makes the till's identifiers: UUIDv7 (time-ordered), as the sync contract asks for event and batch ids. */
fun interface IdGenerator {
    fun next(): String
}

/** UUIDv7 (RFC 9562): 48 bits of Unix milliseconds, version 7, 74 random bits. */
class UuidV7(
    private val millis: () -> Long,
    private val random: Random = Random.Default,
) : IdGenerator {

    override fun next(): String {
        val bytes = random.nextBytes(16)
        val ms = millis()
        for (i in 0 until 6) {
            bytes[i] = (ms ushr (40 - 8 * i)).toByte()
        }
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return format(bytes)
    }

    companion object {
        fun format(bytes: ByteArray): String {
            val hex = lk.coopfed.knoweb.till.core.crypto.Hex.encode(bytes)
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20)}"
        }
    }
}
