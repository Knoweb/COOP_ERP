package lk.coopfed.knoweb.till.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The "data" of the snapshot hash rule (sync.yaml, SnapshotDelta.manifest): JSON with keys sorted
 * at every level and no whitespace, written the way central's JSON writer (Jackson) writes it, so
 * that the till and central hash the same bytes. Written here by hand rather than trusting a
 * library's default escaping.
 */
object CanonicalJson {

    fun write(element: JsonElement): String = buildString { writeTo(this, element) }

    private fun writeTo(out: StringBuilder, element: JsonElement) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                element.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) out.append(',')
                    string(out, key)
                    out.append(':')
                    writeTo(out, element.getValue(key))
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    writeTo(out, item)
                }
                out.append(']')
            }
            is JsonPrimitive -> if (element.isString) string(out, element.content) else out.append(element.content)
        }
    }

    private const val HEX = "0123456789ABCDEF"

    /** Jackson's escaping: quote, backslash, the short forms of five controls, \u00XX for the rest. */
    private fun string(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\t' -> out.append("\\t")
                c == '\n' -> out.append("\\n")
                c == '\u000C' -> out.append("\\f")
                c == '\r' -> out.append("\\r")
                c.code < 0x20 -> out.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xf])
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
