package kr.co.hanium.dreamup.walksafe.fieldlog

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession

/** One explicitly enabled test scope; this is not a RAW collection consent receipt. */
internal class GuidanceDiagnosticBinding(
    val session: GatewayFieldSession,
    val gatewayGeneration: Long,
    val accountGeneration: Long,
    val preferenceRevision: Long,
    val diagnosticSessionId: String,
    val buildId: String,
) {
    init {
        require(accountGeneration > 0 && preferenceRevision > 0)
        require(GuidanceDiagnosticPolicy.isUuid(diagnosticSessionId))
        require(Regex("[a-f0-9]{7,64}").matches(buildId))
    }
    /** Local queue partition only. It never appears in the diagnostic event payload. */
    val ownerKey: String = MessageDigest.getInstance("SHA-256")
        .digest((session.actorId + "\n" + accountGeneration).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal data class GuidanceDiagnosticEvent(
    val id: String,
    val sessionId: String,
    val buildId: String,
    val recordedAtMs: Long,
    val elapsedRealtimeMs: Long,
    val name: String,
    val fields: Map<String, Any>,
)

internal data class GuidanceDiagnosticBatch(val id: String, val events: List<GuidanceDiagnosticEvent>)
internal data class GuidanceDiagnosticAck(val batchId: String, val accepted: Int)
internal data class GuidanceDiagnosticPreference(val present: Boolean, val enabled: Boolean, val revision: Long)
internal data class GuidanceDiagnosticState(val queued: Int, val uploading: Boolean, val outcome: String?)

internal interface GuidanceDiagnosticAdmission {
    /** null unless DEBUG/test scope is explicitly enabled and current actor/deletion admission holds. */
    fun currentBinding(): GuidanceDiagnosticBinding?
    fun sameBindingCurrent(binding: GuidanceDiagnosticBinding): Boolean
    /** Existing connection/metered preference and withdrawal checks remain with the caller. */
    fun uploadAllowed(binding: GuidanceDiagnosticBinding): Boolean
}

internal interface GuidanceDiagnosticTransport {
    fun upload(binding: GuidanceDiagnosticBinding, batch: GuidanceDiagnosticBatch): CancellableNetworkCall<GuidanceDiagnosticAck>
}

/** Only fixed metadata names and bounded scalar codes. Messages, headers and media are never accepted. */
internal object GuidanceDiagnosticPolicy {
    const val QUEUE_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
    const val MAX_EVENTS = 2_000
    const val MAX_BATCH_EVENTS = 100
    private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val events = setOf("navigation_start", "route_request", "route_terminal", "position_quality", "direction_grace", "tts", "diagnostic_scope", "app_created")
    private val code = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val numeric = setOf("age_ms", "accuracy_m", "distance_m", "rate_hz", "elapsed_ms", "observed_ms",
        "grace_onset_ms", "grace_deadline_ms", "grace_remaining_ms", "episode_id", "request_generation",
        "heading_accuracy_deg", "retained_age_ms", "cue_revision", "http_status", "clock_hour", "range_start_hour", "range_end_hour")
    private val codes = setOf("stage", "reason", "gate_reason", "mode", "source", "tts_state", "expression_level", "authorization_kind")
    private val flags = setOf("navigation_allowed", "position_paused", "request_pending", "foreground", "current")
    fun isUuid(value: String) = uuid.matches(value)
    fun sanitize(name: String, input: Map<String, Any?>): Map<String, Any>? {
        if (name !in events) return null
        val result = linkedMapOf<String, Any>()
        for ((key, value) in input) when {
            key in numeric && value is Number -> value.toDouble().takeIf { it.isFinite() && it >= 0.0 && it <= 9_007_199_254_740_991.0 }
                ?.let { result[key] = if (value is Byte || value is Short || value is Int || value is Long) value.toLong() else value.toDouble() }
            key in codes && value is String && code.matches(value) -> result[key] = value
            key in flags && value is Boolean -> result[key] = value
            key == "request_id" && value is String && isUuid(value) -> result[key] = value
            key == "cue_id" && value is String && Regex("[0-9]{1,10}:[0-9]{1,10}").matches(value) -> result[key] = value
        }
        return result.toMap()
    }
}

/** Queue/transport lifetime only. No SDK, admission, clock or network call runs in construction. */
internal class GuidanceDiagnosticLog(
    private val admission: GuidanceDiagnosticAdmission,
    private val queue: GuidanceDiagnosticQueue,
    private val transport: GuidanceDiagnosticTransport,
    private val worker: Executor,
    private val wallTimeMs: () -> Long,
    private val elapsedRealtimeMs: () -> Long,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private var generation = 0L
    private var blocked = false
    private var pending: Ticket? = null
    private var queued = 0
    private var outcome: String? = null
    private data class Ticket(val generation: Long, val binding: GuidanceDiagnosticBinding,
        val batch: GuidanceDiagnosticBatch, val call: CancellableNetworkCall<GuidanceDiagnosticAck>)

    fun state(): GuidanceDiagnosticState = synchronized(lock) { GuidanceDiagnosticState(queued, pending != null, outcome) }

    fun record(name: String, fields: Map<String, Any?> = emptyMap()): Boolean = synchronized(lock) {
        if (blocked) return false
        val binding = admission.currentBinding() ?: return false
        if (!admission.sameBindingCurrent(binding)) return false
        val safe = GuidanceDiagnosticPolicy.sanitize(name, fields) ?: return false
        val now = wallTimeMs()
        val elapsed = elapsedRealtimeMs()
        val id = idFactory()
        if (now < 0 || elapsed < 0 || !GuidanceDiagnosticPolicy.isUuid(id)) return false
        val event = GuidanceDiagnosticEvent(id, binding.diagnosticSessionId, binding.buildId, now, elapsed, name, safe)
        return try {
            if (queue.bindPreference(binding.ownerKey, binding.preferenceRevision)) queued = 0
            queued = queue.append(binding.ownerKey, event, now)
            outcome = null
            true
        } catch (_: Exception) {
            outcome = "storage_unavailable"
            false
        }
    }

    /** Caller invokes this on connection recovery/timer; no new executor or automatic retry loop. */
    fun drainIfAdmitted(): Boolean = synchronized(lock) {
        if (blocked || pending != null) return false
        val binding = admission.currentBinding() ?: return false
        if (!admission.sameBindingCurrent(binding) || !admission.uploadAllowed(binding)) return false
        val events = try {
            if (queue.bindPreference(binding.ownerKey, binding.preferenceRevision)) queued = 0
            queue.peek(binding.ownerKey, wallTimeMs(), GuidanceDiagnosticPolicy.MAX_BATCH_EVENTS)
        }
            catch (_: Exception) { outcome = "storage_unavailable"; return false }
        if (events.isEmpty()) { queued = 0; return false }
        val batch = GuidanceDiagnosticBatch(batchId(events), events)
        val call = try { transport.upload(binding, batch) } catch (_: Exception) { outcome = "transport_unavailable"; return false }
        val ticket = Ticket(generation, binding, batch, call)
        pending = ticket
        try {
            worker.execute { run(ticket) }
            true
        } catch (_: Exception) {
            pending = null
            call.cancel()
            outcome = "worker_unavailable"
            false
        }
    }

    private fun run(ticket: Ticket) {
        val admitted = synchronized(lock) { current(ticket) && admission.uploadAllowed(ticket.binding) }
        if (!admitted) { finish(ticket, null, "admission_retired"); return }
        val result = try { ticket.call.execute() } catch (_: Exception) { null }
        finish(ticket, result, if (result == null) "upload_failed" else null)
    }

    private fun finish(ticket: Ticket, ack: GuidanceDiagnosticAck?, failure: String?) = synchronized(lock) {
        if (pending !== ticket) return
        pending = null
        if (!currentScope(ticket)) return
        if (ack?.batchId != ticket.batch.id || ack.accepted != ticket.batch.events.size) {
            outcome = failure ?: "invalid_ack"
            return
        }
        try {
            queued = queue.acknowledge(ticket.binding.ownerKey, ticket.batch.events.map { it.id }.toSet())
            outcome = "uploaded"
        } catch (_: Exception) { outcome = "storage_unavailable" }
    }

    private fun currentScope(ticket: Ticket) = !blocked && generation == ticket.generation &&
        admission.sameBindingCurrent(ticket.binding)
    private fun current(ticket: Ticket) = pending === ticket && currentScope(ticket) && !ticket.call.isCancelled()

    fun retireBinding() = synchronized(lock) {
        generation++
        pending?.call?.cancel()
        pending = null
        queued = 0
        outcome = "binding_retired"
    }

    fun blockForDeletion() = synchronized(lock) { blocked = true; retireBinding() }
    /** Exact actor-generation queue only, never another account's stored diagnostics. */
    fun purge(ownerKey: String): Boolean = synchronized(lock) {
        retireBinding()
        try { queue.purge(ownerKey); true } catch (_: Exception) { outcome = "storage_unavailable"; false }
    }

    private fun batchId(events: List<GuidanceDiagnosticEvent>): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(events.joinToString("\n") { it.id }.toByteArray())
        return UUID.nameUUIDFromBytes(hash).toString()
    }
}
