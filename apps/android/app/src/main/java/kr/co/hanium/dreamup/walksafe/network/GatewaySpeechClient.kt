package kr.co.hanium.dreamup.walksafe.network

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

internal data class GatewaySpeechAcoustic(
    val confidence: Double,
    val avgLogprob: Double?,
    val noSpeechProbability: Double?,
    val executionAllowed: Boolean,
)

internal data class GatewaySpeechTranscript(
    val requestId: String,
    val transcript: String,
    val acoustic: GatewaySpeechAcoustic,
    val modelRevision: String,
)

internal data class GatewaySpeechAudio(
    val requestId: String,
    val modelRevision: String,
    val temporaryFile: File,
)

internal data class GatewaySpeechCallbackFence(
    val actorId: String,
    val sessionGeneration: Long,
    val sessionInstanceId: String,
    val interactionGeneration: Long,
)

internal fun isGatewaySpeechCallbackCurrent(
    expected: GatewaySpeechCallbackFence,
    currentActorId: String?,
    currentSessionGeneration: Long,
    currentSessionInstanceId: String?,
    currentInteractionGeneration: Long,
    foreground: Boolean,
    featureGatePassed: Boolean,
): Boolean = foreground &&
    featureGatePassed &&
    expected.actorId == currentActorId &&
    expected.sessionGeneration == currentSessionGeneration &&
    expected.sessionInstanceId == currentSessionInstanceId &&
    expected.interactionGeneration == currentInteractionGeneration

internal class AndroidGatewaySpeechClient {
    fun transcribeCall(
        session: GatewayFieldSession,
        audioFile: File,
        requestId: String,
    ): CancellableNetworkCall<GatewaySpeechTranscript> {
        require(session.sessionScope == GatewaySessionScope.GENERAL)
        require(CANONICAL_UUID.matches(requestId))
        val length = audioFile.length()
        require(audioFile.isFile && length in 1..SPEECH_STT_MAX_REQUEST_BYTES.toLong())
        val endpoint = session.gatewayBaseUrl.trimEnd('/') + SPEECH_STT_PATH
        return cancellableHttpCall { cancellation ->
            val connection = openPost(session, endpoint, requestId).apply {
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "audio/mp4")
                setFixedLengthStreamingMode(length)
                readTimeout = SPEECH_STT_READ_TIMEOUT_MS
            }
            cancellation.attach(connection)
            try {
                connection.outputStream.use { output ->
                    cancellation.attach(output)
                    try {
                        FileInputStream(audioFile).use { input ->
                            val buffer = ByteArray(16 * 1024)
                            var written = 0L
                            while (true) {
                                cancellation.throwIfCancelled()
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                if (written > SPEECH_STT_MAX_REQUEST_BYTES) {
                                    throw GatewaySpeechProtocolException()
                                }
                                output.write(buffer, 0, read)
                            }
                            if (written != length) throw GatewaySpeechProtocolException()
                        }
                        output.flush()
                    } finally {
                        cancellation.detach(output)
                    }
                }
                val response = connection.readBoundedResponse(
                    SPEECH_STT_MAX_RESPONSE_BYTES,
                    cancellation,
                )
                if (response.statusCode != HttpURLConnection.HTTP_OK) {
                    throw GatewaySpeechHttpException(response.statusCode)
                }
                requireResponseHeaders(connection, "application/json")
                parseGatewaySpeechTranscript(response.body, requestId)
                    ?: throw GatewaySpeechProtocolException()
            } finally {
                cancellation.detach(connection)
                connection.disconnect()
            }
        }
    }

    fun synthesizeCall(
        session: GatewayFieldSession,
        text: String,
        requestId: String,
        cacheDirectory: File,
    ): CancellableNetworkCall<GatewaySpeechAudio> {
        require(session.sessionScope == GatewaySessionScope.GENERAL)
        require(CANONICAL_UUID.matches(requestId))
        require(validGatewaySpeechText(text))
        val requestBody = JSONObject()
            .put("schema_version", SPEECH_TTS_REQUEST_SCHEMA)
            .put("text", text)
            .put("request_id", requestId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(requestBody.size <= SPEECH_TTS_MAX_REQUEST_BYTES)
        require(cacheDirectory.isDirectory)
        val endpoint = session.gatewayBaseUrl.trimEnd('/') + SPEECH_TTS_PATH
        return cancellableHttpCall { cancellation ->
            val connection = openPost(session, endpoint, requestId).apply {
                setRequestProperty("Accept", "audio/wav")
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(requestBody.size)
                readTimeout = SPEECH_TTS_READ_TIMEOUT_MS
            }
            cancellation.attach(connection)
            var temporaryFile: File? = null
            try {
                connection.outputStream.use { output ->
                    cancellation.attach(output)
                    try {
                        output.write(requestBody)
                        output.flush()
                    } finally {
                        cancellation.detach(output)
                    }
                }
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    connection.errorStream?.close()
                    throw GatewaySpeechHttpException(status)
                }
                requireResponseHeaders(connection, "audio/wav")
                if (connection.getHeaderField("X-WalkSafe-Speech-Request-Id") != requestId) {
                    throw GatewaySpeechProtocolException()
                }
                val declaredLength = connection.getHeaderFieldLong("Content-Length", -1L)
                if (declaredLength !in -1L..SPEECH_TTS_MAX_RESPONSE_BYTES.toLong()) {
                    throw GatewaySpeechProtocolException()
                }
                val stream = connection.inputStream
                cancellation.attach(stream)
                val bytes = try {
                    stream.use {
                        it.readBoundedBytes(SPEECH_TTS_MAX_RESPONSE_BYTES, declaredLength)
                    }
                } finally {
                    cancellation.detach(stream)
                }
                if (!validGatewaySpeechWav(bytes)) throw GatewaySpeechProtocolException()
                val modelRevision = parseGatewaySpeechModelRevision(
                    connection.getHeaderField("X-WalkSafe-Voice-Model-Revision"),
                ) ?: throw GatewaySpeechProtocolException()
                val createdFile = File.createTempFile("walksafe-voice-", ".wav", cacheDirectory)
                temporaryFile = createdFile
                FileOutputStream(createdFile).use { it.write(bytes) }
                GatewaySpeechAudio(
                    requestId = requestId,
                    modelRevision = modelRevision,
                    temporaryFile = createdFile,
                ).also { temporaryFile = null }
            } finally {
                temporaryFile?.delete()
                cancellation.detach(connection)
                connection.disconnect()
            }
        }
    }

    private fun openPost(
        session: GatewayFieldSession,
        endpoint: String,
        requestId: String,
    ): HttpURLConnection {
        require(endpoint.startsWith(session.gatewayBaseUrl.trimEnd('/') + "/api/"))
        return (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = SPEECH_CONNECT_TIMEOUT_MS
            doInput = true
            doOutput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Cache-Control", "no-store")
            setRequestProperty("Connection", "close")
            setRequestProperty("X-Request-Id", requestId)
            session.requestHeaders().forEach(::setRequestProperty)
        }
    }

    private fun requireResponseHeaders(connection: HttpURLConnection, expectedType: String) {
        val contentType = connection.getHeaderField("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        val cacheControl = connection.getHeaderField("Cache-Control")
            ?.split(',')
            ?.map(String::trim)
            .orEmpty()
        val noSniff = connection.getHeaderField("X-Content-Type-Options")
        if (
            contentType != expectedType ||
            cacheControl.none { it.equals("no-store", ignoreCase = true) } ||
            !noSniff.equals("nosniff", ignoreCase = true)
        ) throw GatewaySpeechProtocolException()
    }
}

internal fun parseGatewaySpeechTranscript(
    body: String,
    expectedRequestId: String,
): GatewaySpeechTranscript? = runCatching {
    val root = JSONObject(body)
    if (root.exactKeys() != STT_RESPONSE_KEYS) return@runCatching null
    if (root.optString("schema_version") != SPEECH_STT_RESPONSE_SCHEMA) return@runCatching null
    val requestId = root.optString("request_id")
    if (requestId != expectedRequestId || !CANONICAL_UUID.matches(requestId)) return@runCatching null
    val transcript = root.opt("transcript") as? String ?: return@runCatching null
    if (!validGatewayTranscript(transcript)) return@runCatching null
    val acoustic = root.optJSONObject("acoustic") ?: return@runCatching null
    if (acoustic.exactKeys() != ACOUSTIC_KEYS) return@runCatching null
    val confidence = acoustic.strictFiniteDouble("confidence") ?: return@runCatching null
    if (confidence !in 0.0..1.0) return@runCatching null
    val avgLogprob = acoustic.strictFiniteDoubleOrNull("avg_logprob")
        ?: return@runCatching null
    val noSpeech = acoustic.strictFiniteDoubleOrNull("no_speech_probability")
        ?: return@runCatching null
    if (noSpeech.value != null && noSpeech.value !in 0.0..1.0) return@runCatching null
    val executionAllowed = acoustic.opt("execution_allowed") as? Boolean
        ?: return@runCatching null
    val modelRevision = root.opt("model_revision") as? String ?: return@runCatching null
    if (!MODEL_REVISION.matches(modelRevision)) return@runCatching null
    GatewaySpeechTranscript(
        requestId,
        transcript,
        GatewaySpeechAcoustic(
            confidence,
            avgLogprob.value,
            noSpeech.value,
            executionAllowed,
        ),
        modelRevision,
    )
}.getOrNull()

internal fun validGatewaySpeechText(value: String): Boolean =
    value.trim().isNotEmpty() &&
        value.codePointCount(0, value.length) <= SPEECH_TTS_MAX_TEXT_CODE_POINTS &&
        !value.hasUnicodeControlOrFormat()

internal fun parseGatewaySpeechModelRevision(value: String?): String? =
    value?.trim()?.takeIf(MODEL_REVISION::matches)

private fun validGatewayTranscript(value: String): Boolean =
    value.codePointCount(0, value.length) <= SPEECH_STT_MAX_TRANSCRIPT_CODE_POINTS &&
        !value.hasUnicodeControlOrFormat()

private fun String.hasUnicodeControlOrFormat(): Boolean = codePoints().anyMatch { codePoint ->
    Character.getType(codePoint) in setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
    )
}

private fun JSONObject.exactKeys(): Set<String> = keys().asSequence().toSet()

private fun JSONObject.strictFiniteDouble(name: String): Double? {
    val value = opt(name) as? Number ?: return null
    return value.toDouble().takeIf(Double::isFinite)
}

/** Distinguishes a valid JSON null from a missing or non-numeric value. */
private fun JSONObject.strictFiniteDoubleOrNull(name: String): StrictNullableDouble? {
    if (!has(name)) return null
    val value = opt(name)
    if (value == JSONObject.NULL) return StrictNullableDouble(null)
    val number = value as? Number ?: return null
    return number.toDouble().takeIf(Double::isFinite)?.let(::StrictNullableDouble)
}

private data class StrictNullableDouble(val value: Double?)

private fun InputStream.readBoundedBytes(maximumBytes: Int, contentLength: Long): ByteArray {
    val initialSize = contentLength
        .takeIf { it in 0..maximumBytes.toLong() }
        ?.toInt()
        ?: minOf(16 * 1024, maximumBytes)
    val output = ByteArrayOutputStream(initialSize)
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer, 0, minOf(buffer.size, maximumBytes - total + 1))
        if (read < 0) break
        total += read
        if (total > maximumBytes) throw GatewaySpeechProtocolException()
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun validGatewaySpeechWav(bytes: ByteArray): Boolean {
    if (bytes.size < 44 || bytes.ascii(0, 4) != "RIFF" || bytes.ascii(8, 4) != "WAVE") {
        return false
    }
    val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if ((view.getInt(4).toLong() and 0xffff_ffffL) != bytes.size.toLong() - 8L) return false
    var byteRate: Long? = null
    var dataBytes: Long? = null
    var offset = 12
    while (offset + 8 <= bytes.size) {
        val chunkId = bytes.ascii(offset, 4)
        val chunkSize = view.getInt(offset + 4).toLong() and 0xffff_ffffL
        val start = offset.toLong() + 8L
        val end = start + chunkSize
        if (end > bytes.size || end > Int.MAX_VALUE) return false
        if (chunkId == "fmt " && chunkSize >= 16L) {
            val format = view.getShort(start.toInt()).toInt() and 0xffff
            val channels = view.getShort(start.toInt() + 2).toInt() and 0xffff
            val sampleRate = view.getInt(start.toInt() + 4).toLong() and 0xffff_ffffL
            val candidateByteRate = view.getInt(start.toInt() + 8).toLong() and 0xffff_ffffL
            if (
                format !in setOf(1, 3, 65_534) ||
                channels !in 1..2 ||
                sampleRate !in 8_000L..192_000L ||
                candidateByteRate < 1L
            ) return false
            byteRate = candidateByteRate
        } else if (chunkId == "data") {
            dataBytes = chunkSize
        }
        offset = (end + (chunkSize % 2L)).toInt()
    }
    val rate = byteRate ?: return false
    val payload = dataBytes?.takeIf { it > 0L } ?: return false
    return payload.toDouble() / rate.toDouble() <= SPEECH_TTS_MAX_DURATION_SECONDS
}

private fun ByteArray.ascii(offset: Int, length: Int): String =
    copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

internal class GatewaySpeechHttpException(
    val statusCode: Int,
) : IllegalStateException("gateway speech request failed: $statusCode")

internal class GatewaySpeechProtocolException :
    IllegalStateException("gateway speech response is malformed")

internal const val SPEECH_STT_RESPONSE_SCHEMA = "walksafe.speech-stt-response.v1"
internal const val SPEECH_TTS_REQUEST_SCHEMA = "walksafe.speech-tts-request.v1"
internal const val SPEECH_STT_MAX_REQUEST_BYTES = 10 * 1024 * 1024
internal const val SPEECH_STT_MAX_RESPONSE_BYTES = 64 * 1024
internal const val SPEECH_TTS_MAX_REQUEST_BYTES = 2 * 1024
internal const val SPEECH_TTS_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
internal const val SPEECH_TTS_MAX_TEXT_CODE_POINTS = 180
private const val SPEECH_STT_MAX_TRANSCRIPT_CODE_POINTS = 500
private const val SPEECH_CONNECT_TIMEOUT_MS = 8_000
private const val SPEECH_STT_READ_TIMEOUT_MS = 45_000
private const val SPEECH_TTS_READ_TIMEOUT_MS = 75_000
private const val SPEECH_TTS_MAX_DURATION_SECONDS = 30.0
private const val SPEECH_STT_PATH = "/api/speech/stt"
private const val SPEECH_TTS_PATH = "/api/speech/tts"
private val CANONICAL_UUID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val MODEL_REVISION = Regex("^[0-9a-f]{40}$")
private val STT_RESPONSE_KEYS =
    setOf("schema_version", "request_id", "transcript", "acoustic", "model_revision")
private val ACOUSTIC_KEYS =
    setOf("confidence", "avg_logprob", "no_speech_probability", "execution_allowed")
