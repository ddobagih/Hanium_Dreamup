package kr.co.hanium.dreamup.walksafe.network

/** Validate the entire JSON document before Android JSONObject can normalize ambiguous input. */
internal fun strictJsonObjectDocument(value: String): Boolean =
    runCatching { StrictJsonDocumentReader(value).readObjectDocument() }.isSuccess

private class StrictJsonDocumentReader(
    private val value: String,
) {
    private var index = 0

    fun readObjectDocument() {
        skipWhitespace()
        require(peek() == '{')
        readObject(depth = 1)
        skipWhitespace()
        require(index == value.length)
    }

    private fun readValue(depth: Int) {
        require(depth <= STRICT_JSON_MAX_DEPTH)
        skipWhitespace()
        when (peek()) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> readString()
            't' -> readLiteral("true")
            'f' -> readLiteral("false")
            'n' -> readLiteral("null")
            '-', in '0'..'9' -> readNumber()
            else -> error("invalid JSON value")
        }
    }

    private fun readObject(depth: Int) {
        require(consume('{'))
        skipWhitespace()
        if (consume('}')) return
        val keys = mutableSetOf<String>()
        while (true) {
            require(peek() == '"')
            require(keys.add(readString()))
            skipWhitespace()
            require(consume(':'))
            readValue(depth + 1)
            skipWhitespace()
            if (consume('}')) return
            require(consume(','))
            skipWhitespace()
        }
    }

    private fun readArray(depth: Int) {
        require(consume('['))
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            readValue(depth + 1)
            skipWhitespace()
            if (consume(']')) return
            require(consume(','))
            skipWhitespace()
        }
    }

    private fun readString(): String {
        require(consume('"'))
        val decoded = StringBuilder()
        while (true) {
            require(index < value.length)
            when (val current = value[index++]) {
                '"' -> return decoded.toString()
                '\\' -> {
                    require(index < value.length)
                    when (val escaped = value[index++]) {
                        '"', '\\', '/' -> decoded.append(escaped)
                        'b' -> decoded.append('\b')
                        'f' -> decoded.append('\u000c')
                        'n' -> decoded.append('\n')
                        'r' -> decoded.append('\r')
                        't' -> decoded.append('\t')
                        'u' -> decoded.append(readUnicodeEscape())
                        else -> error("invalid JSON escape")
                    }
                }
                else -> {
                    require(current.code >= 0x20)
                    decoded.append(current)
                }
            }
        }
    }

    private fun readUnicodeEscape(): Char {
        require(index + 4 <= value.length)
        var decoded = 0
        repeat(4) {
            decoded = decoded * 16 + value[index++].digitToIntOrNull(16)
                .let(::requireNotNull)
        }
        return decoded.toChar()
    }

    private fun readLiteral(expected: String) {
        require(value.regionMatches(index, expected, 0, expected.length))
        index += expected.length
    }

    private fun readNumber() {
        consume('-')
        when (peek()) {
            '0' -> {
                index += 1
                require(!nextIsDigit())
            }
            in '1'..'9' -> readDigits()
            else -> error("invalid JSON number")
        }
        if (consume('.')) readRequiredDigits()
        if (peek() == 'e' || peek() == 'E') {
            index += 1
            if (peek() == '+' || peek() == '-') index += 1
            readRequiredDigits()
        }
    }

    private fun readRequiredDigits() {
        require(nextIsDigit())
        readDigits()
    }

    private fun readDigits() {
        while (nextIsDigit()) index += 1
    }

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') {
            index += 1
        }
    }

    private fun consume(expected: Char): Boolean {
        if (peek() != expected) return false
        index += 1
        return true
    }

    private fun peek(): Char? = value.getOrNull(index)

    private fun nextIsDigit(): Boolean = peek()?.let { it in '0'..'9' } == true
}

private const val STRICT_JSON_MAX_DEPTH = 16
