package dev.lucasdone.tvremote.agent.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed class JsonValue {
    data class ObjectValue(val fields: LinkedHashMap<String, JsonValue>) : JsonValue() {
        operator fun get(name: String): JsonValue? = fields[name]
    }

    data class ArrayValue(val values: List<JsonValue>) : JsonValue()
    data class StringValue(val value: String) : JsonValue()
    data class NumberValue(val source: String) : JsonValue()
    data class BooleanValue(val value: Boolean) : JsonValue()
    data object NullValue : JsonValue()
}

class JsonParseException(message: String) : IllegalArgumentException(message)

object StrictJson {
    const val MAX_DOCUMENT_BYTES = 64 * 1024
    const val MAX_STRING_LENGTH = 4 * 1024
    const val MAX_DEPTH = 12
    private const val MAX_CONTAINER_ENTRIES = 256
    private const val MAX_VALUES = 2_048

    fun parseObject(bytes: ByteArray): JsonValue.ObjectValue {
        if (bytes.size > MAX_DOCUMENT_BYTES) throw JsonParseException("JSON document exceeds 64 KiB")
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val source = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw JsonParseException("JSON document is not valid UTF-8")
        }
        return Parser(source).parseRootObject()
    }

    fun encode(value: JsonValue): ByteArray = buildString { appendValue(value) }.toByteArray(StandardCharsets.UTF_8)

    private fun StringBuilder.appendValue(value: JsonValue) {
        when (value) {
            is JsonValue.ObjectValue -> {
                append('{')
                value.fields.entries.forEachIndexed { index, entry ->
                    if (index != 0) append(',')
                    appendQuoted(entry.key)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is JsonValue.ArrayValue -> {
                append('[')
                value.values.forEachIndexed { index, child ->
                    if (index != 0) append(',')
                    appendValue(child)
                }
                append(']')
            }
            is JsonValue.StringValue -> appendQuoted(value.value)
            is JsonValue.NumberValue -> {
                if (!NUMBER_PATTERN.matches(value.source)) throw IllegalArgumentException("invalid JSON number")
                append(value.source)
            }
            is JsonValue.BooleanValue -> append(if (value.value) "true" else "false")
            JsonValue.NullValue -> append("null")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        require(value.length <= MAX_STRING_LENGTH) { "JSON string is too long" }
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var offset = 0
        private var valueCount = 0

        fun parseRootObject(): JsonValue.ObjectValue {
            skipWhitespace()
            val result = parseValue(0) as? JsonValue.ObjectValue
                ?: throw error("root value must be an object")
            skipWhitespace()
            if (offset != source.length) throw error("trailing JSON data")
            return result
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw error("JSON nesting is too deep")
            valueCount += 1
            if (valueCount > MAX_VALUES) throw error("JSON document has too many values")
            skipWhitespace()
            if (offset >= source.length) throw error("unexpected end of JSON")
            return when (source[offset]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonValue.StringValue(parseString())
                't' -> parseLiteral("true", JsonValue.BooleanValue(true))
                'f' -> parseLiteral("false", JsonValue.BooleanValue(false))
                'n' -> parseLiteral("null", JsonValue.NullValue)
                '-', in '0'..'9' -> parseNumber()
                else -> throw error("unexpected JSON token")
            }
        }

        private fun parseObject(depth: Int): JsonValue.ObjectValue {
            expect('{')
            skipWhitespace()
            val fields = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonValue.ObjectValue(fields)
            while (true) {
                skipWhitespace()
                if (offset >= source.length || source[offset] != '"') throw error("object key must be a string")
                val name = parseString()
                if (fields.containsKey(name)) throw error("duplicate object field: $name")
                skipWhitespace()
                expect(':')
                fields[name] = parseValue(depth)
                if (fields.size > MAX_CONTAINER_ENTRIES) throw error("JSON object has too many fields")
                skipWhitespace()
                if (consume('}')) break
                expect(',')
            }
            return JsonValue.ObjectValue(fields)
        }

        private fun parseArray(depth: Int): JsonValue.ArrayValue {
            expect('[')
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (consume(']')) return JsonValue.ArrayValue(values)
            while (true) {
                values += parseValue(depth)
                if (values.size > MAX_CONTAINER_ENTRIES) throw error("JSON array has too many entries")
                skipWhitespace()
                if (consume(']')) break
                expect(',')
            }
            return JsonValue.ArrayValue(values)
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (offset < source.length) {
                val character = source[offset++]
                when {
                    character == '"' -> {
                        if (result.length > MAX_STRING_LENGTH) throw error("JSON string is too long")
                        validateSurrogates(result)
                        return result.toString()
                    }
                    character == '\\' -> result.append(parseEscape())
                    character < ' ' -> throw error("unescaped control character in string")
                    else -> result.append(character)
                }
                if (result.length > MAX_STRING_LENGTH) throw error("JSON string is too long")
            }
            throw error("unterminated JSON string")
        }

        private fun parseEscape(): Char {
            if (offset >= source.length) throw error("unterminated JSON escape")
            return when (val escaped = source[offset++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (offset + 4 > source.length) throw error("short Unicode escape")
                    val code = source.substring(offset, offset + 4).toIntOrNull(16)
                        ?: throw error("invalid Unicode escape")
                    offset += 4
                    code.toChar()
                }
                else -> throw error("invalid JSON escape")
            }
        }

        private fun validateSurrogates(value: CharSequence) {
            var index = 0
            while (index < value.length) {
                val character = value[index]
                when {
                    Character.isHighSurrogate(character) -> {
                        if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                            throw error("unpaired Unicode surrogate")
                        }
                        index += 2
                    }
                    Character.isLowSurrogate(character) -> throw error("unpaired Unicode surrogate")
                    else -> index += 1
                }
            }
        }

        private fun parseNumber(): JsonValue.NumberValue {
            val start = offset
            if (consume('-') && offset >= source.length) throw error("invalid JSON number")
            when {
                consume('0') -> if (offset < source.length && source[offset].isDigit()) throw error("leading zero in JSON number")
                offset < source.length && source[offset] in '1'..'9' -> while (offset < source.length && source[offset].isDigit()) offset += 1
                else -> throw error("invalid JSON number")
            }
            if (consume('.')) {
                val fractionStart = offset
                while (offset < source.length && source[offset].isDigit()) offset += 1
                if (fractionStart == offset) throw error("invalid JSON fraction")
            }
            if (offset < source.length && (source[offset] == 'e' || source[offset] == 'E')) {
                offset += 1
                if (offset < source.length && (source[offset] == '+' || source[offset] == '-')) offset += 1
                val exponentStart = offset
                while (offset < source.length && source[offset].isDigit()) offset += 1
                if (exponentStart == offset) throw error("invalid JSON exponent")
            }
            val raw = source.substring(start, offset)
            if (!NUMBER_PATTERN.matches(raw)) throw error("invalid JSON number")
            return JsonValue.NumberValue(raw)
        }

        private fun <T : JsonValue> parseLiteral(text: String, value: T): T {
            if (!source.regionMatches(offset, text, 0, text.length)) throw error("invalid JSON literal")
            offset += text.length
            return value
        }

        private fun skipWhitespace() {
            while (offset < source.length && source[offset] in JSON_WHITESPACE) offset += 1
        }

        private fun expect(character: Char) {
            if (!consume(character)) throw error("expected '$character'")
        }

        private fun consume(character: Char): Boolean {
            if (offset >= source.length || source[offset] != character) return false
            offset += 1
            return true
        }

        private fun error(message: String) = JsonParseException("$message at offset $offset")
    }

    private val JSON_WHITESPACE = charArrayOf(' ', '\t', '\n', '\r')
    private val NUMBER_PATTERN = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}

fun jsonObject(vararg fields: Pair<String, JsonValue>): JsonValue.ObjectValue =
    JsonValue.ObjectValue(linkedMapOf(*fields))

fun jsonString(value: String): JsonValue = JsonValue.StringValue(value)
fun jsonLong(value: Long): JsonValue = JsonValue.NumberValue(value.toString())
fun jsonBoolean(value: Boolean): JsonValue = JsonValue.BooleanValue(value)

fun JsonValue.ObjectValue.requireString(name: String, maxLength: Int = StrictJson.MAX_STRING_LENGTH): String {
    val value = (this[name] as? JsonValue.StringValue)?.value
        ?: throw JsonParseException("field '$name' must be a string")
    if (value.length > maxLength) throw JsonParseException("field '$name' is too long")
    return value
}

fun JsonValue.ObjectValue.optionalString(name: String, maxLength: Int = StrictJson.MAX_STRING_LENGTH): String? {
    val raw = this[name] ?: return null
    val value = (raw as? JsonValue.StringValue)?.value
        ?: throw JsonParseException("field '$name' must be a string")
    if (value.length > maxLength) throw JsonParseException("field '$name' is too long")
    return value
}

fun JsonValue.ObjectValue.requireLong(name: String): Long {
    val source = (this[name] as? JsonValue.NumberValue)?.source
        ?: throw JsonParseException("field '$name' must be an integer")
    if ('.' in source || 'e' in source.lowercase()) throw JsonParseException("field '$name' must be an integer")
    return source.toLongOrNull() ?: throw JsonParseException("field '$name' is outside the signed 64-bit range")
}

fun JsonValue.ObjectValue.requireObject(name: String): JsonValue.ObjectValue =
    this[name] as? JsonValue.ObjectValue ?: throw JsonParseException("field '$name' must be an object")
