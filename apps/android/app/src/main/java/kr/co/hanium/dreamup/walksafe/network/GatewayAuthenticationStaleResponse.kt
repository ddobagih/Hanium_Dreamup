package kr.co.hanium.dreamup.walksafe.network

import java.util.ArrayDeque
import org.json.JSONObject

/** Capture before constructing the call, never when its delayed response arrives. */
internal fun captureGatewayAuthenticationRequest(
    session: GatewayFieldSession,
): GatewaySessionProcessSnapshot? =
    GatewaySessionProcessCoordinator.snapshot().takeIf { it.session === session }

/**
 * Only the original request generation may be cleared. This does not touch disk,
 * retry policy, or UI; the existing process subscribers retain those responsibilities.
 */
internal fun consumeGatewayAuthenticationStaleResponse(
    request: GatewaySessionProcessSnapshot?,
    session: GatewayFieldSession?,
    response: BoundedHttpResponse,
): GatewaySessionProcessSnapshot? {
    if (request == null || session == null || request.session !== session) return null
    if (response.statusCode != 401) return null
    if (!StrictGatewayJsonObject(response.body).isValid()) return null
    val code = runCatching {
        val detail = JSONObject(response.body).opt("detail") as? JSONObject
        detail?.opt("code")
    }.getOrNull()
    if (code !is String || code != "account_authentication_stale") return null
    return GatewaySessionProcessCoordinator.clear(expectedGeneration = request.generation)
}


/**
 * Grammar checks adapted from AdminStrictJson without an adminapp dependency.
 * An explicit stack replaces recursion; standard exponent syntax is accepted.
 */
private class StrictGatewayJsonObject(private val input: String) {
    private var offset = 0
    private val frames = ArrayDeque<Frame>()

    fun isValid(): Boolean {
        skipWhitespace()
        if (!consume('{')) return false
        frames.addLast(Frame(Phase.OBJECT_FIRST_KEY_OR_END, HashSet()))
        while (frames.isNotEmpty()) {
            skipWhitespace()
            val frame = frames.peekLast()
            when (frame.phase) {
                Phase.OBJECT_FIRST_KEY_OR_END -> {
                    if (consume('}')) frames.removeLast()
                    else frame.phase = Phase.OBJECT_KEY
                }
                Phase.OBJECT_KEY -> {
                    val key = readString() ?: return false
                    if (frame.keys?.add(key) != true) return false
                    frame.phase = Phase.OBJECT_COLON
                }
                Phase.OBJECT_COLON -> {
                    if (!consume(':')) return false
                    frame.phase = Phase.OBJECT_VALUE
                }
                Phase.OBJECT_VALUE -> {
                    frame.phase = Phase.OBJECT_SEPARATOR_OR_END
                    if (!readValue()) return false
                }
                Phase.OBJECT_SEPARATOR_OR_END -> {
                    when {
                        consume('}') -> frames.removeLast()
                        consume(',') -> frame.phase = Phase.OBJECT_KEY
                        else -> return false
                    }
                }
                Phase.ARRAY_FIRST_VALUE_OR_END -> {
                    if (consume(']')) frames.removeLast()
                    else frame.phase = Phase.ARRAY_VALUE
                }
                Phase.ARRAY_VALUE -> {
                    frame.phase = Phase.ARRAY_SEPARATOR_OR_END
                    if (!readValue()) return false
                }
                Phase.ARRAY_SEPARATOR_OR_END -> {
                    when {
                        consume(']') -> frames.removeLast()
                        consume(',') -> frame.phase = Phase.ARRAY_VALUE
                        else -> return false
                    }
                }
            }
        }
        skipWhitespace()
        return offset == input.length
    }

    private fun readValue(): Boolean {
        if (offset >= input.length) return false
        return when (input[offset]) {
            '{' -> {
                offset += 1
                frames.addLast(Frame(Phase.OBJECT_FIRST_KEY_OR_END, HashSet()))
                true
            }
            '[' -> {
                offset += 1
                frames.addLast(Frame(Phase.ARRAY_FIRST_VALUE_OR_END))
                true
            }
            '"' -> readString() != null
            't' -> readLiteral("true")
            'f' -> readLiteral("false")
            'n' -> readLiteral("null")
            '-', in '0'..'9' -> readNumber()
            else -> false
        }
    }

    private fun readString(): String? {
        if (!consume('"')) return null
        val decoded = StringBuilder()
        while (offset < input.length) {
            val current = input[offset++]
            when {
                current == '"' -> return decoded.toString()
                current < ' ' -> return null
                current != '\\' -> decoded.append(current)
                else -> {
                    if (offset >= input.length) return null
                    when (val escaped = input[offset++]) {
                        '"', '\\', '/' -> decoded.append(escaped)
                        'b' -> decoded.append('\b')
                        'f' -> decoded.append('\u000c')
                        'n' -> decoded.append('\n')
                        'r' -> decoded.append('\r')
                        't' -> decoded.append('\t')
                        'u' -> {
                            if (input.length - offset < 4) return null
                            var value = 0
                            repeat(4) {
                                val digit = when (val hex = input[offset++]) {
                                    in '0'..'9' -> hex - '0'
                                    in 'a'..'f' -> hex - 'a' + 10
                                    in 'A'..'F' -> hex - 'A' + 10
                                    else -> return null
                                }
                                value = (value shl 4) or digit
                            }
                            decoded.append(value.toChar())
                        }
                        else -> return null
                    }
                }
            }
        }
        return null
    }

    private fun readNumber(): Boolean {
        consume('-')
        if (consume('0')) {
            if (offset < input.length && input[offset] in '0'..'9') return false
        } else {
            if (offset >= input.length || input[offset] !in '1'..'9') return false
            offset += 1
            while (offset < input.length && input[offset] in '0'..'9') offset += 1
        }
        if (consume('.')) {
            if (!readDigits()) return false
        }
        if (consume('e') || consume('E')) {
            if (!consume('+')) consume('-')
            if (!readDigits()) return false
        }
        return true
    }

    private fun readDigits(): Boolean {
        val start = offset
        while (offset < input.length && input[offset] in '0'..'9') offset += 1
        return offset > start
    }

    private fun readLiteral(literal: String): Boolean {
        for (expected in literal) {
            if (!consume(expected)) return false
        }
        return true
    }

    private fun skipWhitespace() {
        while (offset < input.length) {
            when (input[offset]) {
                ' ', '\t', '\r', '\n' -> offset += 1
                else -> return
            }
        }
    }

    private fun consume(expected: Char): Boolean {
        if (offset >= input.length || input[offset] != expected) return false
        offset += 1
        return true
    }

    private class Frame(
        var phase: Phase,
        val keys: MutableSet<String>? = null,
    )

    private enum class Phase {
        OBJECT_FIRST_KEY_OR_END,
        OBJECT_KEY,
        OBJECT_COLON,
        OBJECT_VALUE,
        OBJECT_SEPARATOR_OR_END,
        ARRAY_FIRST_VALUE_OR_END,
        ARRAY_VALUE,
        ARRAY_SEPARATOR_OR_END,
    }
}
