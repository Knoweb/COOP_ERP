package lk.coopfed.knoweb.till.core.crypto

import kotlin.io.encoding.Base64 as KotlinBase64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Standard Base64 (RFC 4648 with padding), as central writes signatures and keys. */
@OptIn(ExperimentalEncodingApi::class)
object Base64 {
    fun decode(text: String): ByteArray = KotlinBase64.decode(text)

    fun encode(bytes: ByteArray): String = KotlinBase64.encode(bytes)
}
