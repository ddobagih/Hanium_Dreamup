package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import org.json.JSONArray
import org.json.JSONObject

/** Ordinary current-user Gateway cookie only. No diagnostic body, token or server text is logged. */
internal class GuidanceDiagnosticHttpTransport(
    private val wallTimeMs: () -> Long,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : GuidanceDiagnosticTransport {
    override fun upload(binding: GuidanceDiagnosticBinding, batch: GuidanceDiagnosticBatch): CancellableNetworkCall<GuidanceDiagnosticAck> {
        val first = requireNotNull(batch.events.firstOrNull())
        require(batch.events.all { it.sessionId == first.sessionId && it.buildId == first.buildId })
        val body = JSONObject().put("batch_id", batch.id).put("preference_revision", binding.preferenceRevision)
            .put("events", JSONArray().apply { batch.events.forEach { put(EncryptedGuidanceDiagnosticQueue.toJson(it)) } })
        return request(binding.session, "POST", "/api/field-diagnostics/sessions/${first.sessionId}/batches", body) {
            GuidanceDiagnosticAck(it.getString("batch_id"), it.getInt("accepted"))
        }
    }

    fun preference(session: GatewayFieldSession): CancellableNetworkCall<GuidanceDiagnosticPreference> =
        request(session, "GET", "/api/field-diagnostics/preference", null, ::parsePreference)

    /** Creates an authorized test preference only when absent; explicit disabled can never be re-enabled here. */
    fun authorizeTestScopeIfAbsent(session: GatewayFieldSession): CancellableNetworkCall<GuidanceDiagnosticPreference> =
        request(session, "POST", "/api/field-diagnostics/preference", JSONObject().put("authorize_if_absent", true), ::parsePreference)

    fun setPreference(session: GatewayFieldSession, enabled: Boolean, expectedRevision: Long): CancellableNetworkCall<GuidanceDiagnosticPreference> =
        request(session, "PUT", "/api/field-diagnostics/preference",
            JSONObject().put("enabled", enabled).put("expected_revision", expectedRevision), ::parsePreference)

    fun ownSession(session: GatewayFieldSession, sessionId: String): CancellableNetworkCall<JSONObject> {
        require(GuidanceDiagnosticPolicy.isUuid(sessionId))
        return request(session, "GET", "/api/field-diagnostics/sessions/$sessionId", null) { it }
    }

    private fun parsePreference(json: JSONObject) = GuidanceDiagnosticPreference(
        json.getBoolean("present"), json.getBoolean("enabled"), json.getLong("revision"))

    private fun <T> request(session: GatewayFieldSession, method: String, path: String, body: JSONObject?, decode: (JSONObject) -> T): CancellableNetworkCall<T> {
        val connection = AtomicReference<HttpURLConnection?>()
        lateinit var call: CancellableNetworkCall<T>
        call = CancellableNetworkCall(executeBlock = {
            check(session.sessionScope == GatewaySessionScope.GENERAL)
            val headers = session.requestHeaders(wallTimeMs())
            check(!call.isCancelled())
            val url = URL(session.gatewayBaseUrl.trimEnd('/') + path)
            val channel = openConnection(url)
            connection.set(channel)
            try {
                check(!call.isCancelled())
                channel.requestMethod = method
                channel.connectTimeout = 8_000
                channel.readTimeout = 8_000
                channel.instanceFollowRedirects = false
                channel.setRequestProperty("Accept", "application/json")
                headers.forEach(channel::setRequestProperty)
                if (body != null) {
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 262_144)
                    channel.doOutput = true
                    channel.setRequestProperty("Content-Type", "application/json")
                    channel.setFixedLengthStreamingMode(bytes.size)
                    channel.outputStream.use { it.write(bytes) }
                }
                val status = channel.responseCode
                if (status !in 200..299) throw GuidanceDiagnosticHttpFailure(status)
                val output = ByteArrayOutputStream()
                channel.inputStream.use { input ->
                    val buffer = ByteArray(2_048)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (output.size() + size > 262_144) throw IOException("diagnostic response too large")
                        output.write(buffer, 0, size)
                    }
                }
                decode(JSONObject(output.toString("UTF-8")))
            } finally {
                connection.compareAndSet(channel, null)
                channel.disconnect()
            }
        }, cancelBlock = { connection.getAndSet(null)?.disconnect() }, rejectLateResult = true)
        return call
    }
}

internal class GuidanceDiagnosticHttpFailure(val status: Int) : IOException("diagnostic HTTP failure")
