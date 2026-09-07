package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Comparator
import java.util.Locale
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.POSITION_TRACE_EXPORT_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.POSITION_TRACE_STORAGE_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositioningTraceCheckpoint
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositioningTraceRecord
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositioningTraceStartMetadata
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.toCanonicalJsonLine
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.toExportRecordJson
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.toStoredTraceJson
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONObject

/** A random local-only scope. It must never be derived from an actor, email or device identifier. */
internal data class RecorderBinding(
    val localAccountScopeId: String,
    val walkEpoch: Long,
) {
    internal fun isValid(): Boolean = isCanonicalUuid(localAccountScopeId) && walkEpoch >= 0L

    companion object {
        fun random(walkEpoch: Long): RecorderBinding =
            RecorderBinding(UUID.randomUUID().toString(), walkEpoch)
    }
}

internal data class PositionFieldPurgeDurability(
    val markerFenceStored: Boolean,
    val preferenceFenceStored: Boolean,
    val purgeSucceeded: Boolean = false,
    val replacementScopeStored: Boolean = false,
    val markerCleared: Boolean = false,
) {
    val mayAttemptPurge: Boolean get() = markerFenceStored || preferenceFenceStored
    val mayUnblock: Boolean get() =
        mayAttemptPurge && purgeSucceeded && replacementScopeStored && markerCleared
}

internal data class PositionFieldSessionLease(
    val sessionId: String,
    val localAccountScopeSha256: String,
    val generation: Long,
    val walkEpoch: Long,
)

internal enum class PositionFieldSessionStatus(val wireValue: String) {
    ACTIVE("active"),
    COMPLETED("completed"),
    INTERRUPTED("interrupted"),
}

internal data class PositionFieldSessionSummary(
    val sessionId: String,
    val status: PositionFieldSessionStatus,
    val routeId: String,
    val walkEpoch: Long,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val recordCount: Long,
)

internal enum class PositionFieldExportStatus {
    EXPORTED,
    NOT_FOUND,
    NOT_COMPLETED,
    CORRUPT_SOURCE,
    DESTINATION_WRITE_FAILED,
}

internal data class PositionFieldExportResult(
    val status: PositionFieldExportStatus,
    val recordCount: Long,
    val partialDestinationMustBeDeleted: Boolean,
) {
    val success: Boolean get() = status == PositionFieldExportStatus.EXPORTED
}

/** App-private no-backup positioning trace storage with non-resumable process leases. */
internal class PositionFieldSessionRecorder(
    noBackupRootDirectory: File,
    private val aead: LocalAead,
    private val elapsedRealtimeNsClock: () -> Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val generationFactory: () -> Long = ::randomPositiveLong,
    private val maxSessionAgeMs: Long = DEFAULT_MAX_SESSION_AGE_MS,
    private val maxSessionCount: Int = DEFAULT_MAX_SESSION_COUNT,
    private val maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES,
) {
    private val rootDirectory = File(noBackupRootDirectory, ROOT_DIRECTORY_NAME)
    private val maxSessionAgeNs = maxSessionAgeMs * NANOS_PER_MILLISECOND
    private var active: ActiveSession? = null

    init {
        require(maxSessionAgeMs > 0L && maxSessionAgeMs <= Long.MAX_VALUE / NANOS_PER_MILLISECOND)
        require(maxSessionCount > 0)
        require(maxStorageBytes >= MIN_STORAGE_BYTES)
        synchronized(PROCESS_LOCK) {
            if (ensureDirectory(rootDirectory)) {
                interruptOrphanedSessions()
                enforceRetention()
            }
        }
    }

    fun start(
        metadata: PositioningTraceStartMetadata,
        binding: RecorderBinding,
    ): PositionFieldSessionLease? = synchronized(PROCESS_LOCK) {
        if (!metadata.isValid() || !binding.isValid() || active != null) return@synchronized null
        if (!ensureDirectory(rootDirectory)) return@synchronized null
        val reserve = storageWriteReserve()
        enforceRetention(reservedSessionSlots = 1, reservedBytes = reserve)
        if (scanManifests().size >= maxSessionCount || storedBytes() + reserve > maxStorageBytes) {
            return@synchronized null
        }
        val sessionId = nextSessionId() ?: return@synchronized null
        val generation = generationFactory().takeIf { it > 0L } ?: return@synchronized null
        val startedAt = nowMillis().takeIf { it >= 0L } ?: return@synchronized null
        val startedElapsed = elapsedRealtimeNsClock().takeIf { it >= 0L } ?: return@synchronized null
        val scopeSha256 = sha256Hex(binding.localAccountScopeId)
        val directory = sessionDirectory(sessionId)
        if (!directory.mkdir()) return@synchronized null
        syncDirectory(rootDirectory)
        val state = ActiveSession(
            sessionId = sessionId,
            scopeSha256 = scopeSha256,
            generation = generation,
            walkEpoch = binding.walkEpoch,
            metadata = metadata,
            startedAtEpochMs = startedAt,
            startedElapsedRealtimeNs = startedElapsed,
            lastObservedEpochMs = startedAt,
            lastObservedElapsedRealtimeNs = startedElapsed,
            nextSequence = 0L,
            nextCheckpointOrdinal = 0,
            lastRecordElapsedRealtimeNs = null,
        )
        if (!writeManifest(state, PositionFieldSessionStatus.ACTIVE)) {
            deleteSessionArtifact(directory)
            return@synchronized null
        }
        active = state
        state.toLease()
    }

    fun append(lease: PositionFieldSessionLease, record: PositioningTraceRecord): Boolean =
        synchronized(PROCESS_LOCK) {
            val state = activeFor(lease) ?: return@synchronized false
            if (!isNextRecordTime(state, record.elapsedRealtimeNs)) return@synchronized false
            appendRecord(
                state,
                record.toStoredTraceJson(
                    state.sessionId,
                    state.scopeSha256,
                    state.generation,
                    state.walkEpoch,
                    state.nextSequence,
                ),
                record.elapsedRealtimeNs,
                checkpoint = false,
            )
        }

    fun markCheckpoint(
        lease: PositionFieldSessionLease,
        checkpoint: PositioningTraceCheckpoint,
    ): Boolean = synchronized(PROCESS_LOCK) {
        val state = activeFor(lease) ?: return@synchronized false
        if (
            !checkpoint.isValid() ||
            checkpoint.ordinal != state.nextCheckpointOrdinal + 1 ||
            !isNextRecordTime(state, checkpoint.elapsedRealtimeNs)
        ) return@synchronized false
        appendRecord(
            state,
            checkpoint.toStoredTraceJson(
                state.sessionId,
                state.scopeSha256,
                state.generation,
                state.walkEpoch,
                state.nextSequence,
            ),
            checkpoint.elapsedRealtimeNs,
            checkpoint = true,
        )
    }

    fun stop(lease: PositionFieldSessionLease): Boolean = synchronized(PROCESS_LOCK) {
        val state = activeFor(lease) ?: return@synchronized false
        val endedElapsed = maxOf(
            state.lastObservedElapsedRealtimeNs,
            state.lastRecordElapsedRealtimeNs ?: state.startedElapsedRealtimeNs,
        )
        if (!writeManifest(
                state,
                PositionFieldSessionStatus.COMPLETED,
                endedAtEpochMs = state.lastObservedEpochMs,
                endedElapsedRealtimeNs = endedElapsed,
            )
        ) return@synchronized false
        active = null
        enforceRetention()
        true
    }

    fun list(binding: RecorderBinding): List<PositionFieldSessionSummary> = synchronized(PROCESS_LOCK) {
        if (!binding.isValid()) return@synchronized emptyList()
        enforceRetention()
        val scopeSha256 = sha256Hex(binding.localAccountScopeId)
        scanManifests()
            .filter { it.scopeSha256 == scopeSha256 && it.walkEpoch == binding.walkEpoch }
            .sortedByDescending(StoredManifest::startedAtEpochMs)
            .map(StoredManifest::toSummary)
    }

    /** The caller must delete its partial destination when the returned flag is true. */
    fun export(
        sessionId: String,
        binding: RecorderBinding,
        output: OutputStream,
    ): PositionFieldExportResult =
        synchronized(PROCESS_LOCK) {
            if (!isCanonicalUuid(sessionId) || !binding.isValid()) {
                return@synchronized exportResult(PositionFieldExportStatus.NOT_FOUND)
            }
            val directory = sessionDirectory(sessionId)
            if (!directory.exists()) return@synchronized exportResult(PositionFieldExportStatus.NOT_FOUND)
            val manifest = readManifest(sessionId) ?: run {
                deleteSessionArtifact(directory)
                return@synchronized exportResult(PositionFieldExportStatus.CORRUPT_SOURCE)
            }
            if (
                manifest.scopeSha256 != sha256Hex(binding.localAccountScopeId) ||
                manifest.walkEpoch != binding.walkEpoch
            ) return@synchronized exportResult(PositionFieldExportStatus.NOT_FOUND)
            if (manifest.status != PositionFieldSessionStatus.COMPLETED) {
                return@synchronized exportResult(PositionFieldExportStatus.NOT_COMPLETED)
            }
            val recordLines = readAndValidateExportRecords(manifest)
                ?: return@synchronized exportResult(PositionFieldExportStatus.CORRUPT_SOURCE)
            val precedingLines = ArrayList<String>(recordLines.size + 1)
            precedingLines += manifest.metadata.toHeaderJson(manifest.sessionId).toCanonicalJsonLine()
            precedingLines += recordLines
            val contentSha256 = sha256CanonicalLines(precedingLines)
            val footer = JSONObject()
                .put("schema_version", POSITION_TRACE_EXPORT_SCHEMA_VERSION)
                .put("record_type", "footer")
                .put("record_count", manifest.recordCount)
                .put("content_sha256", contentSha256)
                .toCanonicalJsonLine()
            val writeSucceeded = runCatching {
                (precedingLines + footer).forEach { line ->
                    output.write(line.toByteArray(StandardCharsets.UTF_8))
                    output.write(LINE_FEED)
                }
                output.flush()
            }.isSuccess
            if (writeSucceeded) {
                exportResult(PositionFieldExportStatus.EXPORTED, manifest.recordCount)
            } else {
                PositionFieldExportResult(
                    PositionFieldExportStatus.DESTINATION_WRITE_FAILED,
                    0L,
                    partialDestinationMustBeDeleted = true,
                )
            }
        }

    fun delete(sessionId: String): Boolean = synchronized(PROCESS_LOCK) {
        if (!isCanonicalUuid(sessionId) || active?.sessionId == sessionId) return@synchronized false
        val directory = sessionDirectory(sessionId)
        if (!directory.exists()) return@synchronized false
        if (readManifest(sessionId) == null) {
            deleteSessionArtifact(directory)
            return@synchronized false
        }
        deleteSessionArtifact(directory)
    }

    fun purge(): Boolean = synchronized(PROCESS_LOCK) {
        if (!deleteTree(rootDirectory)) return@synchronized false
        active = null
        ensureDirectory(rootDirectory)
    }

    private fun activeFor(lease: PositionFieldSessionLease): ActiveSession? {
        val current = active ?: return null
        if (lease != current.toLease()) return null
        val manifest = readManifest(current.sessionId) ?: run {
            active = null
            return null
        }
        if (
            manifest.status != PositionFieldSessionStatus.ACTIVE ||
            manifest.scopeSha256 != current.scopeSha256 ||
            manifest.generation != current.generation ||
            manifest.walkEpoch != current.walkEpoch ||
            manifest.recordCount != current.nextSequence ||
            manifest.checkpointCount != current.nextCheckpointOrdinal ||
            manifest.lastRecordElapsedRealtimeNs != current.lastRecordElapsedRealtimeNs
        ) {
            active = null
            return null
        }
        val now = nowMillis()
        val elapsed = elapsedRealtimeNsClock()
        val rollback = now < current.lastObservedEpochMs || elapsed < current.lastObservedElapsedRealtimeNs
        val wallExpired = now >= current.startedAtEpochMs &&
            now - current.startedAtEpochMs >= maxSessionAgeMs
        val monotonicExpired = elapsed >= current.startedElapsedRealtimeNs &&
            elapsed - current.startedElapsedRealtimeNs >= maxSessionAgeNs
        if (now < 0L || elapsed < 0L || rollback || wallExpired || monotonicExpired) {
            interrupt(current)
            active = null
            return null
        }
        return current.copy(
            lastObservedEpochMs = now,
            lastObservedElapsedRealtimeNs = elapsed,
        ).also { active = it }
    }

    private fun isNextRecordTime(state: ActiveSession, elapsedRealtimeNs: Long): Boolean =
        elapsedRealtimeNs >= state.startedElapsedRealtimeNs &&
            elapsedRealtimeNs <= state.lastObservedElapsedRealtimeNs &&
            (state.lastRecordElapsedRealtimeNs == null || elapsedRealtimeNs > state.lastRecordElapsedRealtimeNs)

    private fun appendRecord(
        state: ActiveSession,
        record: JSONObject,
        elapsedRealtimeNs: Long,
        checkpoint: Boolean,
    ): Boolean {
        val plaintext = record.toCanonicalJsonLine().toByteArray(StandardCharsets.UTF_8)
        val sealed = aead.seal(
            plaintext,
            recordAad(state.binding(), state.sessionId, state.nextSequence),
            RECORD_LIMITS,
        )
        plaintext.fill(0)
        val envelope = (sealed as? AeadSealResult.Sealed)?.envelope ?: return false
        val lineBytes = envelope.toByteArray(StandardCharsets.UTF_8).size.toLong() + 1L
        val reserve = storageWriteReserve()
        enforceRetention(lineBytes + reserve, protectedSessionId = state.sessionId)
        if (storedBytes() + lineBytes + reserve > maxStorageBytes) return false
        val recordsFile = File(sessionDirectory(state.sessionId), RECORDS_FILE_NAME)
        val oldLength = recordsFile.takeIf(File::exists)?.length() ?: 0L
        if (!appendLine(recordsFile, envelope)) return false
        val next = state.copy(
            nextSequence = state.nextSequence + 1L,
            nextCheckpointOrdinal = state.nextCheckpointOrdinal + if (checkpoint) 1 else 0,
            lastRecordElapsedRealtimeNs = elapsedRealtimeNs,
        )
        if (!writeManifest(next, PositionFieldSessionStatus.ACTIVE)) {
            if (!truncate(recordsFile, oldLength)) active = null
            return false
        }
        active = next
        return true
    }

    private fun interruptOrphanedSessions() {
        scanManifests().filter { it.status == PositionFieldSessionStatus.ACTIVE }.forEach { manifest ->
            interrupt(manifest.toActiveSession())
        }
    }

    private fun interrupt(state: ActiveSession): Boolean = writeManifest(
        state,
        PositionFieldSessionStatus.INTERRUPTED,
        endedAtEpochMs = maxOf(state.startedAtEpochMs, state.lastObservedEpochMs),
        endedElapsedRealtimeNs = maxOf(
            state.startedElapsedRealtimeNs,
            state.lastObservedElapsedRealtimeNs,
            state.lastRecordElapsedRealtimeNs ?: 0L,
        ),
    )

    private fun enforceRetention(
        reservedBytes: Long = 0L,
        reservedSessionSlots: Int = 0,
        protectedSessionId: String? = active?.sessionId,
    ) {
        var sessions = scanManifests().toMutableList()
        val now = nowMillis()
        sessions.filter {
            it.sessionId != protectedSessionId &&
                it.status != PositionFieldSessionStatus.ACTIVE &&
                now >= it.retentionEpochMs &&
                now - it.retentionEpochMs >= maxSessionAgeMs
        }.forEach { manifest ->
            if (deleteSessionArtifact(sessionDirectory(manifest.sessionId))) sessions.remove(manifest)
        }
        fun oldest(): StoredManifest? = sessions
            .filter { it.sessionId != protectedSessionId && it.status != PositionFieldSessionStatus.ACTIVE }
            .minWithOrNull(compareBy(StoredManifest::retentionEpochMs, StoredManifest::sessionId))
        while (sessions.size + reservedSessionSlots > maxSessionCount) {
            val candidate = oldest() ?: break
            if (!deleteSessionArtifact(sessionDirectory(candidate.sessionId))) break
            sessions.remove(candidate)
        }
        while (storedBytes() + reservedBytes > maxStorageBytes) {
            val candidate = oldest() ?: break
            if (!deleteSessionArtifact(sessionDirectory(candidate.sessionId))) break
            sessions.remove(candidate)
        }
    }

    /** Invalid and missing-manifest artifacts are removed from this dedicated root. */
    private fun scanManifests(): List<StoredManifest> {
        val manifests = mutableListOf<StoredManifest>()
        rootDirectory.listFiles().orEmpty().forEach { artifact ->
            val isSessionDirectory = Files.isDirectory(artifact.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                isCanonicalUuid(artifact.name)
            val manifest = if (isSessionDirectory) readManifest(artifact.name) else null
            if (manifest == null) {
                deleteSessionArtifact(artifact)
            } else {
                manifests += manifest
            }
        }
        return manifests
    }

    private fun readManifest(sessionId: String): StoredManifest? {
        if (!isCanonicalUuid(sessionId)) return null
        val file = File(sessionDirectory(sessionId), MANIFEST_FILE_NAME)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        if (file.length() !in 1..MANIFEST_LIMITS.maxEnvelopeChars.toLong() * 2L) return null
        val wrapper = runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }.getOrNull() ?: return null
        val binding = ManifestBinding(
            scopeSha256 = wrapper.optString("scope_sha256"),
            generation = wrapper.strictLong("generation") ?: return null,
            walkEpoch = wrapper.strictLong("walk_epoch") ?: return null,
        )
        if (!binding.isValid()) return null
        val envelope = wrapper.optString("envelope").takeIf(String::isNotBlank) ?: return null
        val plaintext = openEnvelope(envelope, manifestAad(binding, sessionId), MANIFEST_LIMITS) ?: return null
        val json = runCatching { JSONObject(plaintext) }.getOrNull() ?: return null
        if (
            json.optString("schema_version") != MANIFEST_SCHEMA_VERSION ||
            json.optString("session_id") != sessionId ||
            json.optString("scope_sha256") != binding.scopeSha256 ||
            json.strictLong("generation") != binding.generation ||
            json.strictLong("walk_epoch") != binding.walkEpoch
        ) return null
        val status = PositionFieldSessionStatus.values()
            .firstOrNull { it.wireValue == json.optString("status") } ?: return null
        val metadataJson = json.optJSONObject("metadata") ?: return null
        val metadata = PositioningTraceStartMetadata(
            routeId = metadataJson.optString("route_id"),
            scenario = metadataJson.optString("scenario"),
            environment = metadataJson.optString("environment"),
            direction = metadataJson.optString("direction"),
            deviceModel = metadataJson.optString("device_model"),
            androidApi = metadataJson.strictLong("android_api")?.toInt() ?: return null,
            mount = metadataJson.optString("mount"),
            sourceKind = metadataJson.optString("source_kind"),
            syntheticContractOnly = metadataJson.opt("synthetic_contract_only") as? Boolean ?: return null,
            timebase = metadataJson.optString("timebase"),
        )
        if (!metadata.isValid()) return null
        val startedAt = json.nonNegativeLong("started_at_epoch_ms") ?: return null
        val startedElapsed = json.nonNegativeLong("started_elapsed_realtime_ns") ?: return null
        val lastObservedAt = json.nonNegativeLong("last_observed_epoch_ms") ?: return null
        val lastObservedElapsed = json.nonNegativeLong("last_observed_elapsed_realtime_ns") ?: return null
        val count = json.nonNegativeLong("record_count") ?: return null
        val checkpointCount = json.nonNegativeLong("checkpoint_count")
            ?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        if (json.strictLong("last_seq") != count - 1L) return null
        val lastRecordElapsed = json.strictNullableLong("last_record_elapsed_realtime_ns")
        if ((count == 0L) != (lastRecordElapsed == null)) return null
        val endedAt = json.strictNullableLong("ended_at_epoch_ms")
        val endedElapsed = json.strictNullableLong("ended_elapsed_realtime_ns")
        if (status == PositionFieldSessionStatus.ACTIVE && (endedAt != null || endedElapsed != null)) return null
        if (status != PositionFieldSessionStatus.ACTIVE && (endedAt == null || endedElapsed == null)) return null
        return StoredManifest(
            sessionId,
            binding.scopeSha256,
            binding.generation,
            binding.walkEpoch,
            status,
            metadata,
            startedAt,
            startedElapsed,
            lastObservedAt,
            lastObservedElapsed,
            endedAt,
            endedElapsed,
            count,
            checkpointCount,
            lastRecordElapsed,
        )
    }

    private fun writeManifest(
        state: ActiveSession,
        status: PositionFieldSessionStatus,
        endedAtEpochMs: Long? = null,
        endedElapsedRealtimeNs: Long? = null,
    ): Boolean {
        val metadata = state.metadata.toHeaderJson(state.sessionId).apply {
            remove("schema_version")
            remove("record_type")
            remove("session_id")
        }
        val json = JSONObject()
            .put("schema_version", MANIFEST_SCHEMA_VERSION)
            .put("session_id", state.sessionId)
            .put("scope_sha256", state.scopeSha256)
            .put("generation", state.generation)
            .put("walk_epoch", state.walkEpoch)
            .put("status", status.wireValue)
            .put("metadata", metadata)
            .put("started_at_epoch_ms", state.startedAtEpochMs)
            .put("started_elapsed_realtime_ns", state.startedElapsedRealtimeNs)
            .put("last_observed_epoch_ms", state.lastObservedEpochMs)
            .put("last_observed_elapsed_realtime_ns", state.lastObservedElapsedRealtimeNs)
            .put("record_count", state.nextSequence)
            .put("checkpoint_count", state.nextCheckpointOrdinal)
            .put("last_seq", state.nextSequence - 1L)
        state.lastRecordElapsedRealtimeNs?.let { json.put("last_record_elapsed_realtime_ns", it) }
        endedAtEpochMs?.let { json.put("ended_at_epoch_ms", it) }
        endedElapsedRealtimeNs?.let { json.put("ended_elapsed_realtime_ns", it) }
        val binding = state.binding()
        val plaintext = json.toCanonicalJsonLine().toByteArray(StandardCharsets.UTF_8)
        val sealed = aead.seal(plaintext, manifestAad(binding, state.sessionId), MANIFEST_LIMITS)
        plaintext.fill(0)
        val envelope = (sealed as? AeadSealResult.Sealed)?.envelope ?: return false
        val wrapper = JSONObject()
            .put("scope_sha256", binding.scopeSha256)
            .put("generation", binding.generation)
            .put("walk_epoch", binding.walkEpoch)
            .put("envelope", envelope)
            .toCanonicalJsonLine()
        return atomicWrite(File(sessionDirectory(state.sessionId), MANIFEST_FILE_NAME), wrapper)
    }

    private fun readAndValidateExportRecords(manifest: StoredManifest): List<String>? {
        val file = File(sessionDirectory(manifest.sessionId), RECORDS_FILE_NAME)
        if (manifest.recordCount == 0L) return if (!file.exists()) emptyList() else null
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val exported = mutableListOf<String>()
        var sequence = 0L
        var checkpointOrdinal = 0
        var previousElapsed: Long? = null
        val binding = manifest.binding()
        val valid = runCatching {
            file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val envelope = reader.readLine() ?: break
                    if (envelope.isEmpty()) return@runCatching false
                    val plaintext = openEnvelope(
                        envelope,
                        recordAad(binding, manifest.sessionId, sequence),
                        RECORD_LIMITS,
                    ) ?: return@runCatching false
                    val json = runCatching { JSONObject(plaintext) }.getOrNull() ?: return@runCatching false
                    val elapsed = json.strictLong("elapsed_realtime_ns") ?: return@runCatching false
                    val type = json.optString("record_type")
                    if (
                        json.optString("storage_schema_version") != POSITION_TRACE_STORAGE_SCHEMA_VERSION ||
                        json.optString("session_id") != manifest.sessionId ||
                        json.optString("scope_sha256") != manifest.scopeSha256 ||
                        json.strictLong("generation") != manifest.generation ||
                        json.strictLong("walk_epoch") != manifest.walkEpoch ||
                        json.strictLong("seq") != sequence ||
                        type !in ALLOWED_RECORD_TYPES ||
                        elapsed < manifest.startedElapsedRealtimeNs ||
                        (previousElapsed != null && elapsed <= previousElapsed!!)
                    ) return@runCatching false
                    if (type == "checkpoint") {
                        if (
                            json.strictLong("ordinal") != checkpointOrdinal.toLong() + 1L ||
                            json.optString("checkpoint_id").isBlank() ||
                            json.optString("stationary_state") != "stationary" ||
                            (json.nonNegativeLong("stationary_duration_ms")
                                ?: return@runCatching false) < 1_500L
                        ) return@runCatching false
                        checkpointOrdinal += 1
                    }
                    val exportJson = json.toExportRecordJson() ?: return@runCatching false
                    exported += exportJson.toCanonicalJsonLine()
                    previousElapsed = elapsed
                    sequence += 1L
                }
            }
            sequence == manifest.recordCount &&
                checkpointOrdinal == manifest.checkpointCount &&
                previousElapsed == manifest.lastRecordElapsedRealtimeNs
        }.getOrDefault(false)
        return exported.takeIf { valid }
    }

    private fun openEnvelope(envelope: String, aad: ByteArray, limits: AeadLimits): String? {
        val opened = aead.open(envelope, aad, limits) as? AeadOpenResult.Opened ?: return null
        val plaintext = String(opened.plaintext, StandardCharsets.UTF_8)
        opened.plaintext.fill(0)
        return plaintext
    }

    private fun atomicWrite(target: File, value: String): Boolean = runCatching {
        val parent = target.parentFile ?: return@runCatching false
        if (!ensureDirectory(parent)) return@runCatching false
        if (target.exists() && !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return@runCatching false
        }
        val temporary = File.createTempFile(".${target.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(value.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(parent)
            true
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }.getOrDefault(false)

    private fun appendLine(target: File, envelope: String): Boolean = runCatching {
        val parent = target.parentFile ?: return@runCatching false
        if (!Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching false
        if (target.exists() && !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return@runCatching false
        }
        FileOutputStream(target, true).use { output ->
            output.write(envelope.toByteArray(StandardCharsets.UTF_8))
            output.write(LINE_FEED)
            output.fd.sync()
        }
        syncDirectory(parent)
        true
    }.getOrDefault(false)

    private fun truncate(target: File, length: Long): Boolean = runCatching {
        FileChannel.open(target.toPath(), StandardOpenOption.WRITE).use { channel ->
            channel.truncate(length)
            channel.force(true)
        }
        syncDirectory(requireNotNull(target.parentFile))
        true
    }.getOrDefault(false)

    private fun storedBytes(): Long = runCatching {
        if (!rootDirectory.exists()) return@runCatching 0L
        Files.walk(rootDirectory.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .mapToLong(Files::size)
                .sum()
        }
    }.getOrDefault(maxStorageBytes)

    private fun deleteSessionArtifact(artifact: File): Boolean = runCatching {
        if (artifact.parentFile?.canonicalFile != rootDirectory.canonicalFile) return@runCatching false
        deleteTree(artifact)
    }.getOrDefault(false)

    private fun deleteTree(target: File): Boolean = runCatching {
        if (!target.exists()) return@runCatching true
        Files.walk(target.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        target.parentFile?.takeIf(File::isDirectory)?.let(::syncDirectory)
        true
    }.getOrDefault(false)

    private fun ensureDirectory(directory: File): Boolean {
        if (directory.exists()) return Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
        val parent = directory.parentFile
        if (parent != null && !ensureDirectory(parent)) return false
        if (!directory.mkdir()) return false
        parent?.let(::syncDirectory)
        return true
    }

    private fun nextSessionId(): String? {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = sessionIdFactory().lowercase(Locale.ROOT)
            if (isCanonicalUuid(candidate) && !sessionDirectory(candidate).exists()) return candidate
        }
        return null
    }

    private fun sessionDirectory(sessionId: String) = File(rootDirectory, sessionId)

    private fun storageWriteReserve(): Long =
        minOf(MAX_WRITE_RESERVE_BYTES, maxStorageBytes / 8L).coerceAtLeast(1L)

    private fun manifestAad(binding: ManifestBinding, sessionId: String): ByteArray =
        (
            "$AAD_PREFIX|scope=${binding.scopeSha256}|generation=${binding.generation}|" +
                "walk_epoch=${binding.walkEpoch}|session=$sessionId|manifest|schema=1"
        ).toByteArray(StandardCharsets.UTF_8)

    private fun recordAad(binding: ManifestBinding, sessionId: String, sequence: Long): ByteArray =
        (
            "$AAD_PREFIX|scope=${binding.scopeSha256}|generation=${binding.generation}|" +
                "walk_epoch=${binding.walkEpoch}|session=$sessionId|seq=$sequence|record|schema=1"
        ).toByteArray(StandardCharsets.UTF_8)

    private data class ManifestBinding(
        val scopeSha256: String,
        val generation: Long,
        val walkEpoch: Long,
    ) {
        fun isValid(): Boolean = SHA256_HEX.matches(scopeSha256) && generation > 0L && walkEpoch >= 0L
    }

    private data class ActiveSession(
        val sessionId: String,
        val scopeSha256: String,
        val generation: Long,
        val walkEpoch: Long,
        val metadata: PositioningTraceStartMetadata,
        val startedAtEpochMs: Long,
        val startedElapsedRealtimeNs: Long,
        val lastObservedEpochMs: Long,
        val lastObservedElapsedRealtimeNs: Long,
        val nextSequence: Long,
        val nextCheckpointOrdinal: Int,
        val lastRecordElapsedRealtimeNs: Long?,
    ) {
        fun binding() = ManifestBinding(scopeSha256, generation, walkEpoch)
        fun toLease() = PositionFieldSessionLease(sessionId, scopeSha256, generation, walkEpoch)
    }

    private data class StoredManifest(
        val sessionId: String,
        val scopeSha256: String,
        val generation: Long,
        val walkEpoch: Long,
        val status: PositionFieldSessionStatus,
        val metadata: PositioningTraceStartMetadata,
        val startedAtEpochMs: Long,
        val startedElapsedRealtimeNs: Long,
        val lastObservedEpochMs: Long,
        val lastObservedElapsedRealtimeNs: Long,
        val endedAtEpochMs: Long?,
        val endedElapsedRealtimeNs: Long?,
        val recordCount: Long,
        val checkpointCount: Int,
        val lastRecordElapsedRealtimeNs: Long?,
    ) {
        val retentionEpochMs get() = endedAtEpochMs ?: startedAtEpochMs
        fun binding() = ManifestBinding(scopeSha256, generation, walkEpoch)
        fun toActiveSession() = ActiveSession(
            sessionId,
            scopeSha256,
            generation,
            walkEpoch,
            metadata,
            startedAtEpochMs,
            startedElapsedRealtimeNs,
            lastObservedEpochMs,
            lastObservedElapsedRealtimeNs,
            recordCount,
            checkpointCount,
            lastRecordElapsedRealtimeNs,
        )
        fun toSummary() = PositionFieldSessionSummary(
            sessionId,
            status,
            metadata.routeId,
            walkEpoch,
            startedAtEpochMs,
            endedAtEpochMs,
            recordCount,
        )
    }

    private companion object {
        val PROCESS_LOCK = Any()
        val SECURE_RANDOM = SecureRandom()
        val SHA256_HEX = Regex("[0-9a-f]{64}")
        val ALLOWED_RECORD_TYPES = setOf("position", "checkpoint")
        const val ROOT_DIRECTORY_NAME = "position_field_sessions_v1"
        const val MANIFEST_FILE_NAME = "manifest.aead"
        const val RECORDS_FILE_NAME = "records.jsonl"
        const val MANIFEST_SCHEMA_VERSION = "android.positioning_trace_manifest.v1"
        const val AAD_PREFIX = "kr.co.hanium.dreamup.walksafe|positioning-field-v1"
        const val DEFAULT_MAX_SESSION_AGE_MS = 14L * 24L * 60L * 60L * 1_000L
        const val DEFAULT_MAX_SESSION_COUNT = 20
        const val DEFAULT_MAX_STORAGE_BYTES = 32L * 1_024L * 1_024L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MIN_STORAGE_BYTES = 4L * 1_024L
        const val MAX_WRITE_RESERVE_BYTES = 64L * 1_024L
        const val MAX_ID_ATTEMPTS = 8
        const val LINE_FEED = 10
        val MANIFEST_LIMITS = AeadLimits(64 * 1_024, 64 * 1_024 + 16, 96 * 1_024)
        val RECORD_LIMITS = AeadLimits(256 * 1_024, 256 * 1_024 + 16, 352 * 1_024)

        fun randomPositiveLong(): Long {
            var value: Long
            do value = SECURE_RANDOM.nextLong() and Long.MAX_VALUE while (value == 0L)
            return value
        }

        fun exportResult(status: PositionFieldExportStatus, count: Long = 0L) =
            PositionFieldExportResult(status, count, partialDestinationMustBeDeleted = false)

        fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

        fun sha256CanonicalLines(lines: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            lines.forEach { line ->
                digest.update(line.toByteArray(StandardCharsets.UTF_8))
                digest.update(LINE_FEED.toByte())
            }
            return digest.digest().joinToString("") {
                "%02x".format(Locale.ROOT, it.toInt() and 0xff)
            }
        }

        fun syncDirectory(directory: File) {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}

private val CANONICAL_UUID = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
)

private fun isCanonicalUuid(value: String): Boolean =
    CANONICAL_UUID.matches(value) &&
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

private fun JSONObject.strictLong(name: String): Long? = when (val value = opt(name)) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

private fun JSONObject.nonNegativeLong(name: String): Long? = strictLong(name)?.takeIf { it >= 0L }

private fun JSONObject.strictNullableLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else strictLong(name)
