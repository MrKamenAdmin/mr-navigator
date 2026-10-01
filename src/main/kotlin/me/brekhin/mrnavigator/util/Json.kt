package me.brekhin.mrnavigator.util

/**
 * Minimal JSON reader/writer (no external dependencies).
 * Objects → LinkedHashMap<String, Any?>, arrays → List<Any?>, numbers → Long or Double.
 */
object Json {
    fun parse(text: String): Any? = Parser(text).parseDocument()

    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value)
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> sb.append(if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString())
            is Float -> writeTo(sb, value.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, v)
                }
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    class JsonException(message: String) : RuntimeException(message)

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            skipWs()
            val v = parseValue()
            skipWs()
            if (i != s.length) fail("Unexpected trailing characters")
            return v
        }

        private fun fail(msg: String): Nothing = throw JsonException("$msg at $i")

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun parseValue(): Any? {
            if (i >= s.length) fail("Unexpected end")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else fail("Unexpected '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Expected $word")
            i += word.length
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (s.getOrNull(i) == '}') { i++; return map }
            while (true) {
                skipWs()
                if (s.getOrNull(i) != '"') fail("Expected key")
                val key = parseString()
                skipWs()
                if (s.getOrNull(i) != ':') fail("Expected ':'")
                i++
                skipWs()
                map[key] = parseValue()
                skipWs()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (s.getOrNull(i) == ']') { i++; return list }
            while (true) {
                skipWs()
                list.add(parseValue())
                skipWs()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val text = s.substring(start, i)
            return if (text.any { it in ".eE" }) text.toDouble() else text.toLongOrNull() ?: text.toDouble()
        }
    }
}

// Convenience accessors for parsed JSON
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

@Suppress("UNCHECKED_CAST")
fun Any?.arr(): List<Any?> = this as? List<Any?> ?: emptyList()

fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: false
fun Map<String, Any?>.o(key: String): Map<String, Any?>? = this[key].let { if (it is Map<*, *>) it.obj() else null }
fun Map<String, Any?>.a(key: String): List<Any?> = this[key].arr()
