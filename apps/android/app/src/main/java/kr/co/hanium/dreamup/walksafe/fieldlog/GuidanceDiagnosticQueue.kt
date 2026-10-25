package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONArray
import org.json.JSONObject

internal interface GuidanceDiagnosticQueue {
    /** Returns true only when old or unconfirmed preference data was discarded. */
    fun bindPreference(ownerKey: String, revision: Long): Boolean
    fun append(ownerKey: String, event: GuidanceDiagnosticEvent, nowMs: Long): Int
    fun peek(ownerKey: String, nowMs: Long, limit: Int): List<GuidanceDiagnosticEvent>
    fun acknowledge(ownerKey: String, eventIds: Set<String>): Int
    fun purge(ownerKey: String)
}

/** Separate from field/raw archives. No plaintext fallback, key generation or filesystem I/O in init. */
internal class EncryptedGuidanceDiagnosticQueue(
    private val directory: File,
    private val aead: LocalAead,
    private val syncDirectory: (File) -> Boolean,
) : GuidanceDiagnosticQueue {
    private val lock = Any()
    private val boundRevisions = mutableMapOf<String, Long>()
    override fun bindPreference(ownerKey: String, revision: Long): Boolean = synchronized(lock) {
        require(revision > 0)
        if (boundRevisions[ownerKey] == revision) return false
        val document = readDocument(ownerKey)
        val discard = document != null && document.first != revision
        if (document == null || discard) write(ownerKey, emptyList(), revision)
        boundRevisions[ownerKey] = revision
        discard
    }
    override fun append(ownerKey: String, event: GuidanceDiagnosticEvent, nowMs: Long): Int = synchronized(lock) {
        val revision = requireNotNull(boundRevisions[ownerKey]) { "diagnostic preference not bound" }
        val events = retained(read(ownerKey), nowMs).toMutableList()
        if (events.none { it.id == event.id }) events += event
        var remaining = 1_048_576 - 128
        val bounded = events.asReversed().take(GuidanceDiagnosticPolicy.MAX_EVENTS).takeWhile {
            remaining -= toJson(it).toString().toByteArray(Charsets.UTF_8).size + 1
            remaining >= 0
        }.asReversed()
        write(ownerKey, bounded, revision)
        bounded.size
    }
    override fun peek(ownerKey: String, nowMs: Long, limit: Int): List<GuidanceDiagnosticEvent> = synchronized(lock) {
        val old = read(ownerKey)
        val events = retained(old, nowMs)
        if (old.size != events.size) write(ownerKey, events, requireNotNull(boundRevisions[ownerKey]))
        val first = events.firstOrNull() ?: return emptyList()
        // Old same-account sessions can finish uploading; never mix APK provenance in one batch.
        events.filter { it.sessionId == first.sessionId && it.buildId == first.buildId }.take(limit.coerceIn(1, 100))
    }
    override fun acknowledge(ownerKey: String, eventIds: Set<String>): Int = synchronized(lock) {
        val retained = read(ownerKey).filter { it.id !in eventIds }
        write(ownerKey, retained, requireNotNull(boundRevisions[ownerKey]))
        retained.size
    }
    override fun purge(ownerKey: String) = synchronized(lock) {
        val file = path(ownerKey)
        boundRevisions.remove(ownerKey)
        require(!Files.isSymbolicLink(file.toPath()))
        if (file.exists()) check(file.delete() && syncDirectory(directory)) { "diagnostic purge failed" }
    }

    private fun retained(events: List<GuidanceDiagnosticEvent>, nowMs: Long) = events.filter {
        nowMs >= it.recordedAtMs && nowMs - it.recordedAtMs <= GuidanceDiagnosticPolicy.QUEUE_RETENTION_MS
    }
    private fun path(ownerKey: String): File {
        require(Regex("[a-f0-9]{64}").matches(ownerKey))
        require(!Files.isSymbolicLink(directory.toPath()))
        return File(directory, "$ownerKey.aead")
    }
    private fun aad(ownerKey: String) = "walksafe.guidance-diagnostic.queue.v1|owner=$ownerKey".toByteArray()

    private fun readDocument(ownerKey: String): Pair<Long?, List<GuidanceDiagnosticEvent>>? {
        val file = path(ownerKey)
        if (!file.exists()) return null
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        require(file.length() <= LIMITS.maxEnvelopeChars)
        check(file.length() <= 1_500_000) { "diagnostic envelope too large" }
        val opened = aead.open(file.readText(), aad(ownerKey), LIMITS) as? AeadOpenResult.Opened
            ?: error("diagnostic queue authentication failed")
        try {
            val document = JSONObject(opened.plaintext.toString(Charsets.UTF_8))
            check(document.getString("schema") == "guidance-diagnostic.queue.v1")
            val records = document.getJSONArray("events")
            check(records.length() <= GuidanceDiagnosticPolicy.MAX_EVENTS)
            val revision = if (document.has("preference_revision")) {
                val value = document.get("preference_revision")
                check(value is Number && value.toLong() > 0)
                value.toLong()
            } else null
            return revision to (0 until records.length()).map { parse(records.getJSONObject(it)) }
        } finally { opened.plaintext.fill(0) }
    }

    private fun read(ownerKey: String): List<GuidanceDiagnosticEvent> {
        val revision = requireNotNull(boundRevisions[ownerKey]) { "diagnostic preference not bound" }
        val document = readDocument(ownerKey) ?: return emptyList()
        check(document.first == revision) { "diagnostic preference changed" }
        return document.second
    }

    private fun write(ownerKey: String, events: List<GuidanceDiagnosticEvent>, revision: Long) {
        val file = path(ownerKey)
        check(directory.isDirectory || directory.mkdirs())
        require(!Files.isSymbolicLink(file.toPath()))
        val records = JSONArray().apply { events.forEach { put(toJson(it)) } }
        val bytes = JSONObject().put("schema", "guidance-diagnostic.queue.v1").put("events", records).put("preference_revision", revision)
            .toString().toByteArray(Charsets.UTF_8)
        val sealed = try { aead.seal(bytes, aad(ownerKey), LIMITS) as? AeadSealResult.Sealed }
            finally { bytes.fill(0) }
        check(sealed != null) { "diagnostic queue encryption failed" }
        val temporary = File.createTempFile(".diagnostic-", ".tmp", directory)
        try {
            check(temporary.setReadable(false, false) && temporary.setWritable(false, false))
            check(temporary.setReadable(true, true) && temporary.setWritable(true, true))
            FileOutputStream(temporary).use { it.write(sealed.envelope.toByteArray()); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            check(syncDirectory(directory)) { "diagnostic queue commit not durable" }
        } finally { temporary.delete() }
    }

    companion object {
        val KEY_POLICY = AeadKeyPolicy("walksafe.user.guidance_diagnostics.aead.v", 1, setOf(1))
        private val LIMITS = AeadLimits(1_048_576, 1_048_592, 1_500_000)
        fun toJson(event: GuidanceDiagnosticEvent) = JSONObject()
            .put("id", event.id).put("session_id", event.sessionId).put("build_id", event.buildId)
            .put("recorded_at_ms", event.recordedAtMs).put("elapsed_realtime_ms", event.elapsedRealtimeMs)
            .put("name", event.name).put("fields", JSONObject(event.fields))
        private fun parse(value: JSONObject): GuidanceDiagnosticEvent {
            val fields = value.getJSONObject("fields")
            val map = fields.keys().asSequence().associateWith { fields.get(it) }
            val name = value.getString("name")
            val safe = requireNotNull(GuidanceDiagnosticPolicy.sanitize(name, map))
            check(safe.size == map.size)
            val id = value.getString("id"); val session = value.getString("session_id")
            check(GuidanceDiagnosticPolicy.isUuid(id) && GuidanceDiagnosticPolicy.isUuid(session))
            val build = value.getString("build_id"); check(Regex("[a-f0-9]{7,64}").matches(build))
            val wall = value.getLong("recorded_at_ms"); val elapsed = value.getLong("elapsed_realtime_ms")
            check(wall >= 0 && elapsed >= 0)
            return GuidanceDiagnosticEvent(id, session, build, wall, elapsed, name, safe)
        }
    }
}
