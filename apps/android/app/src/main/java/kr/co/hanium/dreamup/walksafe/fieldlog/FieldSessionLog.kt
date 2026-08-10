package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import kr.co.hanium.dreamup.walksafe.debuglog.MetadataLogJsonEncoder
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONObject

/** Runtime values needed for field diagnosis, intentionally excluding coordinates and user input. */
data class FieldRuntimeSnapshot(
    val stepCount: Int,
    val stepLengthM: Float,
    val routeActive: Boolean,
    val navigationState: String,
    val deviceGateAllowsAlerts: Boolean,
    val deviceGateAllowsReports: Boolean,
    val reportCandidateState: String,
    val trustedLocationAvailable: Boolean,
    val locationAccuracyM: Float?,
    val locationAgeMs: Long?,
    val headingDeg: Float?,
)

data class FieldSessionDeviceInfo(
    val model: String,
    val androidVersion: String,
    val appVersionName: String,
    val sourceCommit: String,
    val apkSha256: String? = null,
    val modelConfigSha256: String? = null,
)

data class CameraNonMetricFieldSample(
    val elapsedRealtimeMs: Long,
    val inferenceMs: Long,
    val detectionCount: Int,
    val capabilityTier: String,
    val cameraPermissionGranted: Boolean,
    val cameraFallbackRunning: Boolean,
    val detectorAvailable: Boolean,
    val imuFresh: Boolean,
    val tmapRouteActive: Boolean,
)

interface FieldSessionLog : Closeable {
    fun start(): String?
    fun startAfterUserConfirmation(): String?
    fun stop(): String?
    fun isActive(): Boolean
    fun activeSessionId(): String?
    fun appendTelemetry(entry: MetadataCaptureLogEntry, runtime: FieldRuntimeSnapshot)
    fun appendCameraNonMetricSample(sample: CameraNonMetricFieldSample)
    fun recordEvent(name: String, fields: Map<String, Any?> = emptyMap())
    fun statusText(): String
    fun purgeAll(): Boolean
    fun blockActiveSessionRestore(): Boolean = true
    /** Admission-only privacy fence. Implementations must not perform file or crypto I/O. */
    fun blockNewProcessingForAccountDeletion() = Unit
    fun blockForAccountDeletion(): Boolean = true
    fun blockForRawSourceWithdrawal(): Boolean = true
    fun resetRawSourceAfterConfirmedConsent(): Boolean = true
    fun resetForNewEnrollment(): Boolean = true
}

class NoopFieldSessionLog : FieldSessionLog {
    override fun start(): String? = null
    override fun startAfterUserConfirmation(): String? = null
    override fun stop(): String? = null
    override fun isActive(): Boolean = false
    override fun activeSessionId(): String? = null
    override fun appendTelemetry(entry: MetadataCaptureLogEntry, runtime: FieldRuntimeSnapshot) = Unit
    override fun appendCameraNonMetricSample(sample: CameraNonMetricFieldSample) = Unit
    override fun recordEvent(name: String, fields: Map<String, Any?>) = Unit
    override fun statusText(): String = "field-log=unavailable"
    override fun purgeAll(): Boolean = true
    override fun blockActiveSessionRestore(): Boolean = true
    override fun blockForAccountDeletion(): Boolean = true
    override fun blockForRawSourceWithdrawal(): Boolean = true
    override fun resetRawSourceAfterConfirmedConsent(): Boolean = true
    override fun resetForNewEnrollment(): Boolean = true
    override fun close() = Unit
}

/**
 * Debug field evidence stored under app-private files/field_sessions.
 *
 * Records are synchronous. Telemetry and CameraX inference streams are independently capped at one
 * sample per second; the field gate checks sparse continuity rather than claiming an exact 1 Hz rate.
 * Each line is flushed by closing its append stream, so a process kill loses at most the record being
 * written. An active session pointer survives process restarts until the tester explicitly stops it,
 * unless a fail-closed restore block is committed first.
 */
internal class PersistentFieldSessionLog(
    private val rootDirectory: File,
    private val deviceInfo: FieldSessionDeviceInfo,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { "field-${UUID.randomUUID()}" },
    private val maxSegmentBytes: Long = DEFAULT_MAX_SEGMENT_BYTES,
    private val telemetryIntervalMs: Long = DEFAULT_TELEMETRY_INTERVAL_MS,
    private val maxSessionBytes: Long = DEFAULT_MAX_SESSION_BYTES,
    private val maxRetainedSessions: Int = DEFAULT_MAX_RETAINED_SESSIONS,
    private val maxSessionAgeMs: Long = DEFAULT_MAX_SESSION_AGE_MS,
    private val maxActiveSessionAgeMs: Long = DEFAULT_MAX_ACTIVE_SESSION_AGE_MS,
    private val initiallyBlockedForAccountDeletion: Boolean = false,
    private val initiallyBlockedForRawSourceCollection: Boolean = false,
    private val aead: LocalAead = AndroidKeyStoreAead(FIELD_KEY_POLICY),
    private val directorySync: (File) -> Boolean = ::syncDirectory,
) : FieldSessionLog {
    private var active: SessionState? = null
    private var lastTelemetryAtMs = Long.MIN_VALUE
    private var lastCameraNonMetricSampleElapsedRealtimeMs = Long.MIN_VALUE
    private var recordBytesWritten = 0L
    private var storageLimitReached = false
    private var accountDeletionBlocked = initiallyBlockedForAccountDeletion
    private var rawSourceCollectionBlocked = initiallyBlockedForRawSourceCollection
    private var activeSessionRestoreBlocked = false
    private var cryptoBlocked = false

    init {
        synchronized(PROCESS_LOCK) {
            require(maxSegmentBytes > 0L) { "maxSegmentBytes must be positive" }
            require(telemetryIntervalMs >= 0L) { "telemetryIntervalMs must not be negative" }
            require(maxSessionBytes > 0L) { "maxSessionBytes must be positive" }
            require(maxRetainedSessions >= 1) { "maxRetainedSessions must be positive" }
            require(maxSessionAgeMs > 0L) { "maxSessionAgeMs must be positive" }
            require(maxActiveSessionAgeMs > 0L) { "maxActiveSessionAgeMs must be positive" }
            rootDirectory.mkdirs()
            if (initiallyBlockedForAccountDeletion) persistFence(accountDeletionBlockFile())
            if (initiallyBlockedForRawSourceCollection) persistFence(rawSourceCollectionBlockFile())
            refreshPersistentFencesAndDropStaleActive()
            pruneCompletedSessions()
            if (accountDeletionBlocked || rawSourceCollectionBlocked) {
                finalizePersistedActiveSessionForRestoreBlock(
                    terminationReason = if (accountDeletionBlocked) {
                        "account_deletion_blocked"
                    } else {
                        "raw_source_collection_withdrawn"
                    },
                )
            } else if (activeSessionRestoreBlocked) {
                finalizePersistedActiveSessionForRestoreBlock()
            } else {
                restoreActiveSession()
            }
        }
    }

    @Synchronized
    override fun start(): String? = synchronized(PROCESS_LOCK) {
        if (accountDeletionBlocked) return@synchronized null
        refreshPersistentFencesAndDropStaleActive()
        startLocked()
    }

    private fun startLocked(): String? {
        if (
            accountDeletionBlocked || rawSourceCollectionBlocked ||
            activeSessionRestoreBlocked || cryptoBlocked
        ) return null
        expireActiveSessionIfNeeded(nowMillis())
        active?.let { return it.id }
        if (hasActivePointerArtifact()) return null
        pruneCompletedSessions()
        val now = nowMillis()
        val id = idFactory().takeIf { SAFE_SESSION_ID.matches(it) }
            ?: throw IllegalArgumentException("field session id must contain only safe filename characters")
        val directory = File(rootDirectory, id)
        check(directory.mkdirs()) { "field session directory already exists: $id" }
        active = SessionState(
            id = id,
            startedAtMs = now,
            segmentIndex = 1,
            nextRecordOrdinal = 0L,
            recordTailSha256 = null,
        )
        lastCameraNonMetricSampleElapsedRealtimeMs = Long.MIN_VALUE
        recordBytesWritten = 0L
        storageLimitReached = false
        if (
            !writeManifest(status = STATUS_ACTIVE, endedAtMs = null) ||
            !writeEncryptedFile(activePointerFile(), id, activePointerAad(), POINTER_LIMITS)
        ) {
            active = null
            directory.deleteRecursively()
            blockCryptoLocked()
            return null
        }
        appendEventRecord("session_started", emptyMap(), now)
        return id
    }

    @Synchronized
    override fun startAfterUserConfirmation(): String? = synchronized(PROCESS_LOCK) {
        if (accountDeletionBlocked) return@synchronized null
        refreshPersistentFencesAndDropStaleActive()
        if (accountDeletionBlocked || rawSourceCollectionBlocked) return@synchronized null
        if (activeSessionRestoreBlocked && !finalizePersistedActiveSessionForRestoreBlock()) {
            return@synchronized null
        }
        if (!clearFence(activeSessionRestoreBlockFile())) return@synchronized null
        activeSessionRestoreBlocked = false
        startLocked()
    }

    @Synchronized
    override fun stop(): String? = synchronized(PROCESS_LOCK) {
        refreshPersistentFencesAndDropStaleActive()
        stopLocked()
    }

    private fun stopLocked(): String? {
        val now = nowMillis()
        if (expireActiveSessionIfNeeded(now)) return null
        val state = active ?: return null
        appendEventRecord("session_stopped", emptyMap(), now)
        if (!writeManifest(status = STATUS_COMPLETED, endedAtMs = now)) {
            blockCryptoLocked()
            return null
        }
        activePointerFile().delete()
        active = null
        lastTelemetryAtMs = Long.MIN_VALUE
        lastCameraNonMetricSampleElapsedRealtimeMs = Long.MIN_VALUE
        recordBytesWritten = 0L
        storageLimitReached = false
        return state.id
    }

    @Synchronized
    override fun blockActiveSessionRestore(): Boolean = synchronized(PROCESS_LOCK) {
        refreshPersistentFencesAndDropStaleActive()
        activeSessionRestoreBlocked = true
        val persisted = persistFence(activeSessionRestoreBlockFile())
        stopActiveSessionForRestoreBlock()
        persisted
    }

    @Synchronized
    override fun purgeAll(): Boolean = synchronized(PROCESS_LOCK) {
        refreshPersistentFencesAndDropStaleActive()
        accountDeletionBlocked = true
        val preserveRawSourceBlock = rawSourceCollectionBlocked
        active = null
        lastTelemetryAtMs = Long.MIN_VALUE
        lastCameraNonMetricSampleElapsedRealtimeMs = Long.MIN_VALUE
        recordBytesWritten = 0L
        storageLimitReached = false
        val keyDeleted = aead.destroyKnownVersions()
        val rootExisted = rootDirectory.exists()
        val deleted = !rootDirectory.exists() || rootDirectory.deleteRecursively()
        val deletionDurable = deleted && (
            !rootExisted || rootDirectory.parentFile?.let(directorySync) == true
        )
        val recreated = rootDirectory.mkdirs() || rootDirectory.isDirectory
        val recreationDurable = recreated && rootDirectory.parentFile?.let(directorySync) == true
        val accountFencePersisted = recreated && persistFence(accountDeletionBlockFile())
        val rawFencePersisted =
            !preserveRawSourceBlock || (
                recreated && persistFence(rawSourceCollectionBlockFile())
            )
        val purgePrerequisites = keyDeleted && deleted && deletionDurable &&
            recreationDurable && accountFencePersisted && rawFencePersisted
        val purgeVerified = purgePrerequisites && atomicWrite(
            fieldKeyPurgeVerifiedFile(),
            FIELD_KEY_PURGE_VERIFIED_VALUE,
        )
        val succeeded = purgePrerequisites && purgeVerified
        cryptoBlocked = !succeeded
        if (!succeeded) {
            fieldKeyPurgeVerifiedFile().delete()
            atomicTemporaryFile(fieldKeyPurgeVerifiedFile()).delete()
            persistFence(cryptoBlockFile())
        }
        succeeded
    }

    @Synchronized
    override fun blockNewProcessingForAccountDeletion() = synchronized(PROCESS_LOCK) {
        accountDeletionBlocked = true
        active = null
        lastTelemetryAtMs = Long.MIN_VALUE
        lastCameraNonMetricSampleElapsedRealtimeMs = Long.MIN_VALUE
        recordBytesWritten = 0L
        storageLimitReached = false
    }

    @Synchronized
    override fun blockForAccountDeletion(): Boolean = synchronized(PROCESS_LOCK) {
        refreshPersistentFencesAndDropStaleActive()
        accountDeletionBlocked = true
        val persisted = persistFence(accountDeletionBlockFile())
        stopActiveSessionForPrivacyBlock()
        persisted
    }

    @Synchronized
    override fun blockForRawSourceWithdrawal(): Boolean = synchronized(PROCESS_LOCK) {
        refreshPersistentFencesAndDropStaleActive()
        rawSourceCollectionBlocked = true
        val persisted = persistFence(rawSourceCollectionBlockFile())
        stopActiveSessionForPrivacyBlock()
        persisted
    }

    @Synchronized
    override fun resetRawSourceAfterConfirmedConsent(): Boolean = synchronized(PROCESS_LOCK) {
        refreshPersistentFencesAndDropStaleActive()
        if (accountDeletionBlocked) return@synchronized false
        val changed = rawSourceCollectionBlocked
        if (!clearFence(rawSourceCollectionBlockFile())) return@synchronized false
        rawSourceCollectionBlocked = false
        changed
    }

    @Synchronized
    override fun resetForNewEnrollment(): Boolean = synchronized(PROCESS_LOCK) {
        refreshPersistentFencesAndDropStaleActive()
        if (!hasVerifiedFieldKeyPurge() || !hasOnlyVerifiedPurgeArtifacts()) {
            blockCryptoLocked()
            return@synchronized false
        }
        val accountCleared = clearFence(accountDeletionBlockFile())
        val rawCleared = clearFence(rawSourceCollectionBlockFile())
        val cryptoCleared = clearFence(cryptoBlockFile())
        if (!accountCleared || !rawCleared || !cryptoCleared || !hasOnlyExactPurgeMarker()) {
            blockCryptoLocked()
            return@synchronized false
        }
        if (!aead.createFreshAfterVerifiedPurge()) {
            blockCryptoLocked()
            return@synchronized false
        }
        val marker = fieldKeyPurgeVerifiedFile()
        if (!marker.delete() || marker.exists() || !directorySync(rootDirectory)) {
            blockCryptoLocked()
            return@synchronized false
        }
        accountDeletionBlocked = false
        rawSourceCollectionBlocked = false
        cryptoBlocked = false
        rootDirectory.mkdirs()
        true
    }

    @Synchronized
    override fun isActive(): Boolean = synchronized(PROCESS_LOCK) {
        if (accountDeletionBlocked) return@synchronized false
        refreshPersistentFencesAndDropStaleActive()
        expireActiveSessionIfNeeded(nowMillis())
        active != null
    }

    @Synchronized
    override fun activeSessionId(): String? = synchronized(PROCESS_LOCK) {
        if (accountDeletionBlocked) return@synchronized null
        refreshPersistentFencesAndDropStaleActive()
        expireActiveSessionIfNeeded(nowMillis())
        active?.id
    }

    @Synchronized
    override fun appendTelemetry(
        entry: MetadataCaptureLogEntry,
        runtime: FieldRuntimeSnapshot,
    ) = synchronized(PROCESS_LOCK) {
        if (accountDeletionBlocked) return@synchronized
        refreshPersistentFencesAndDropStaleActive()
        if (accountDeletionBlocked || rawSourceCollectionBlocked) return@synchronized
        val now = nowMillis()
        if (expireActiveSessionIfNeeded(now) || active == null) return@synchronized
        if (lastTelemetryAtMs != Long.MIN_VALUE && now - lastTelemetryAtMs < telemetryIntervalMs) {
            return@synchronized
        }
        lastTelemetryAtMs = now
        val runtimeJson = JSONObject()
            .put("step_count", runtime.stepCount)
            .put("step_length_m", runtime.stepLengthM)
            .put("route_active", runtime.routeActive)
            .put("navigation_state", runtime.navigationState.take(MAX_STATUS_LENGTH))
            .put("device_gate_allows_alerts", runtime.deviceGateAllowsAlerts)
            .put("device_gate_allows_reports", runtime.deviceGateAllowsReports)
            .put("report_candidate_state", runtime.reportCandidateState.substringBefore(' ').take(MAX_STATUS_LENGTH))
            .put("trusted_location_available", runtime.trustedLocationAvailable)
        runtime.locationAccuracyM?.let { runtimeJson.put("location_accuracy_m", it) }
        runtime.locationAgeMs?.let { runtimeJson.put("location_age_ms", it) }
        runtime.headingDeg?.let { runtimeJson.put("heading_deg", it) }
        appendRecord(
            JSONObject()
                .put("schema_version", RECORD_SCHEMA_VERSION)
                .put("record_type", "telemetry")
                .put("recorded_at_epoch_ms", now)
                .put("runtime", runtimeJson)
                .put("depth_debug", JSONObject(MetadataLogJsonEncoder.encodeEntry(entry))),
        )
    }

    @Synchronized
    override fun appendCameraNonMetricSample(
        sample: CameraNonMetricFieldSample,
    ) = synchronized(PROCESS_LOCK) {
        if (accountDeletionBlocked) return@synchronized
        refreshPersistentFencesAndDropStaleActive()
        if (accountDeletionBlocked || rawSourceCollectionBlocked) return@synchronized
        val now = nowMillis()
        if (expireActiveSessionIfNeeded(now) || active == null) return@synchronized
        if (sample.elapsedRealtimeMs < 0L) return@synchronized
        if (lastCameraNonMetricSampleElapsedRealtimeMs != Long.MIN_VALUE) {
            if (sample.elapsedRealtimeMs < lastCameraNonMetricSampleElapsedRealtimeMs) return@synchronized
            val elapsedSinceLastSampleMs =
                sample.elapsedRealtimeMs - lastCameraNonMetricSampleElapsedRealtimeMs
            if (elapsedSinceLastSampleMs < CAMERA_NON_METRIC_SAMPLE_INTERVAL_MS) return@synchronized
        }
        lastCameraNonMetricSampleElapsedRealtimeMs = sample.elapsedRealtimeMs
        appendEventRecord(
            "camera_non_metric_inference_sample",
            mapOf(
                "elapsed_realtime_ms" to sample.elapsedRealtimeMs,
                "inference_ms" to sample.inferenceMs,
                "detection_count" to sample.detectionCount,
                "capability_tier" to sample.capabilityTier,
                "camera_permission_granted" to sample.cameraPermissionGranted,
                "camera_fallback_running" to sample.cameraFallbackRunning,
                "detector_available" to sample.detectorAvailable,
                "imu_fresh" to sample.imuFresh,
                "tmap_route_active" to sample.tmapRouteActive,
                "metric" to false,
                "reports_allowed" to false,
                "state" to "detector_succeeded",
            ),
            now,
        )
    }

    @Synchronized
    override fun recordEvent(
        name: String,
        fields: Map<String, Any?>,
    ) = synchronized(PROCESS_LOCK) {
        if (accountDeletionBlocked) return@synchronized
        refreshPersistentFencesAndDropStaleActive()
        if (accountDeletionBlocked || rawSourceCollectionBlocked) return@synchronized
        val now = nowMillis()
        if (expireActiveSessionIfNeeded(now) || active == null) return@synchronized
        appendEventRecord(name, fields, now)
    }

    @Synchronized
    override fun statusText(): String = synchronized(PROCESS_LOCK) {
        if (accountDeletionBlocked) return@synchronized "field-log=blocked:account-deletion"
        refreshPersistentFencesAndDropStaleActive()
        if (cryptoBlocked) return@synchronized "field-log=blocked:crypto"
        expireActiveSessionIfNeeded(nowMillis())
        val state = active ?: return@synchronized "field-log=off"
        val storage = if (storageLimitReached) " storage=limit" else ""
        "field-log=on session=${state.id.takeLast(16)} segment=${state.segmentIndex}$storage"
    }

    /** Closing the Activity keeps the explicit field session active for process-restart recovery. */
    @Synchronized
    override fun close() = synchronized(PROCESS_LOCK) {
        if (accountDeletionBlocked) return@synchronized
        refreshPersistentFencesAndDropStaleActive()
        val now = nowMillis()
        if (!expireActiveSessionIfNeeded(now) && active != null) {
            appendEventRecord("app_process_closed", emptyMap(), now)
        }
    }

    private fun stopActiveSessionForPrivacyBlock() {
        try {
            if (active != null) stopLocked()
        } catch (_: Exception) {
            // Privacy blocking is already committed; session cleanup is best effort.
        } finally {
            if (hasActivePointerArtifact()) {
                finalizePersistedActiveSessionForRestoreBlock(
                    terminationReason = if (accountDeletionBlocked) {
                        "account_deletion_blocked"
                    } else {
                        "raw_source_collection_withdrawn"
                    },
                )
            }
            active = null
            lastTelemetryAtMs = Long.MIN_VALUE
            lastCameraNonMetricSampleElapsedRealtimeMs = Long.MIN_VALUE
            recordBytesWritten = 0L
            storageLimitReached = false
        }
    }

    private fun stopActiveSessionForRestoreBlock() {
        try {
            if (active != null) stopLocked()
        } catch (_: Exception) {
            // The restore block is already committed; session cleanup is best effort.
        } finally {
            finalizePersistedActiveSessionForRestoreBlock()
            active = null
            lastTelemetryAtMs = Long.MIN_VALUE
            lastCameraNonMetricSampleElapsedRealtimeMs = Long.MIN_VALUE
            recordBytesWritten = 0L
            storageLimitReached = false
        }
    }

    private fun finalizePersistedActiveSessionForRestoreBlock(
        terminationReason: String = "active_session_restore_blocked",
    ): Boolean {
        val pointer = activePointerFile()
        val id = readActivePointerId()
        var manifestFinalized = true
        if (id != null) {
            val manifestFile = File(File(rootDirectory, id), MANIFEST_NAME)
            manifestFinalized = runCatching {
                val manifest = readManifest(id) ?: return@runCatching false
                when (manifest.optString("status")) {
                    STATUS_ACTIVE -> {
                        manifest
                            .put("status", STATUS_COMPLETED)
                            .put("ended_at_epoch_ms", nowMillis())
                            .put("termination_reason", terminationReason)
                        writeEncryptedFile(
                            manifestFile,
                            manifest.toString(2),
                            manifestAad(id),
                            MANIFEST_LIMITS,
                        )
                    }
                    STATUS_COMPLETED -> true
                    else -> false
                }
            }.getOrDefault(false)
            if (!manifestFinalized) blockCryptoLocked()
        }
        val finalPointerCleared = !pointer.exists() || pointer.delete()
        val temporaryPointer = atomicTemporaryFile(pointer)
        val temporaryPointerCleared = !temporaryPointer.exists() || temporaryPointer.delete()
        return manifestFinalized &&
            finalPointerCleared &&
            temporaryPointerCleared &&
            !pointer.exists() &&
            !temporaryPointer.exists()
    }

    private fun refreshPersistentFencesAndDropStaleActive() {
        accountDeletionBlocked = accountDeletionBlocked || hasFence(accountDeletionBlockFile())
        rawSourceCollectionBlocked =
            rawSourceCollectionBlocked || hasFence(rawSourceCollectionBlockFile())
        activeSessionRestoreBlocked =
            activeSessionRestoreBlocked || hasFence(activeSessionRestoreBlockFile())
        cryptoBlocked = cryptoBlocked || hasFence(cryptoBlockFile())
        val activeState = active
        val pointerMatchesActive = activeState == null || readActivePointerId() == activeState.id
        if (
            accountDeletionBlocked ||
            rawSourceCollectionBlocked ||
            activeSessionRestoreBlocked ||
            cryptoBlocked ||
            !pointerMatchesActive
        ) {
            active = null
            lastTelemetryAtMs = Long.MIN_VALUE
            lastCameraNonMetricSampleElapsedRealtimeMs = Long.MIN_VALUE
            recordBytesWritten = 0L
            storageLimitReached = false
        }
    }

    private fun readActivePointerId(): String? {
        val pointer = activePointerFile().takeIf(File::isFile) ?: return null
        val id = readEncryptedFile(pointer, activePointerAad(), POINTER_LIMITS)?.trim()
        if (id == null || !SAFE_SESSION_ID.matches(id)) {
            blockCryptoLocked()
            return null
        }
        return id
    }

    private fun hasActivePointerArtifact(): Boolean =
        activePointerFile().exists() || atomicTemporaryFile(activePointerFile()).exists()

    private fun persistFence(marker: File): Boolean {
        return atomicWrite(marker, RESTORE_BLOCK_MARKER_VALUE) && hasFence(marker)
    }

    private fun clearFence(marker: File): Boolean {
        val hadArtifact = marker.exists() || atomicTemporaryFile(marker).exists()
        val finalCleared = !marker.exists() || marker.delete()
        val temporary = atomicTemporaryFile(marker)
        val temporaryCleared = !temporary.exists() || temporary.delete()
        val deletionDurable = !hadArtifact || marker.parentFile?.let(directorySync) == true
        return finalCleared && temporaryCleared && !hasFence(marker) && deletionDurable
    }

    private fun hasFence(marker: File): Boolean =
        marker.exists() || atomicTemporaryFile(marker).exists()

    private fun atomicTemporaryFile(target: File): File =
        File(target.parentFile, "${target.name}.tmp")

    private fun accountDeletionBlockFile(): File = File(rootDirectory, ACCOUNT_DELETION_BLOCK_MARKER_NAME)

    private fun rawSourceCollectionBlockFile(): File =
        File(rootDirectory, RAW_SOURCE_COLLECTION_BLOCK_MARKER_NAME)

    private fun cryptoBlockFile(): File = File(rootDirectory, CRYPTO_BLOCK_MARKER_NAME)

    private fun fieldKeyPurgeVerifiedFile(): File =
        File(rootDirectory, FIELD_KEY_PURGE_VERIFIED_MARKER_NAME)

    private fun hasVerifiedFieldKeyPurge(): Boolean = runCatching {
        val marker = fieldKeyPurgeVerifiedFile()
        val path = marker.toPath()
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!attributes.isRegularFile) return@runCatching false
        val linkCount = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)
            as? Number ?: return@runCatching false
        if (linkCount.toLong() != 1L) return@runCatching false
        val expected = FIELD_KEY_PURGE_VERIFIED_VALUE.toByteArray(StandardCharsets.UTF_8)
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != expected.size.toLong()) return@runCatching false
            val buffer = ByteBuffer.allocate(expected.size)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) return@runCatching false
            }
            MessageDigest.isEqual(buffer.array(), expected)
        }
    }.getOrDefault(false)

    private fun hasOnlyVerifiedPurgeArtifacts(): Boolean {
        val names = rootDirectory.listFiles()?.map(File::getName)?.toSet() ?: return false
        return FIELD_KEY_PURGE_VERIFIED_MARKER_NAME in names &&
            names.all {
                it == FIELD_KEY_PURGE_VERIFIED_MARKER_NAME ||
                    it == ACCOUNT_DELETION_BLOCK_MARKER_NAME ||
                    it == RAW_SOURCE_COLLECTION_BLOCK_MARKER_NAME ||
                    it == CRYPTO_BLOCK_MARKER_NAME
            }
    }

    private fun hasOnlyExactPurgeMarker(): Boolean =
        rootDirectory.listFiles()?.map(File::getName)?.toSet() ==
            setOf(FIELD_KEY_PURGE_VERIFIED_MARKER_NAME) &&
            hasVerifiedFieldKeyPurge()

    private fun restoreActiveSession() {
        val pointer = activePointerFile()
        if (!pointer.isFile) return
        val id = readActivePointerId()
        if (id == null || !SAFE_SESSION_ID.matches(id)) {
            blockCryptoLocked()
            return
        }
        val manifestFile = File(File(rootDirectory, id), MANIFEST_NAME)
        val manifest = readManifest(id)
        if (manifest == null || manifest.optString("status") != STATUS_ACTIVE) {
            blockCryptoLocked()
            return
        }
        val directory = File(rootDirectory, id)
        val restoredRecords = restoreRecordState(directory, manifest) ?: run {
            blockCryptoLocked()
            return
        }
        if (provenanceChanged(manifest)) {
            val now = nowMillis()
            manifest
                .put("status", STATUS_COMPLETED)
                .put("ended_at_epoch_ms", now)
                .put("termination_reason", "app_or_model_provenance_changed")
            if (
                !writeEncryptedFile(
                    manifestFile,
                    manifest.toString(2),
                    manifestAad(id),
                    MANIFEST_LIMITS,
                )
            ) {
                blockCryptoLocked()
                return
            }
            pointer.delete()
            start()
            recordEvent("session_restarted_for_provenance_change")
            return
        }
        val startedAtMs = manifest.optLong("started_at_epoch_ms", 0L)
        active = SessionState(
            id = id,
            startedAtMs = startedAtMs,
            segmentIndex = restoredRecords.lastSegmentIndex,
            nextRecordOrdinal = restoredRecords.nextOrdinal,
            recordTailSha256 = restoredRecords.recordTailSha256,
        )
        if (expireActiveSessionIfNeeded(nowMillis())) return
        lastCameraNonMetricSampleElapsedRealtimeMs = restoredRecords.lastCameraElapsedRealtimeMs
        recordBytesWritten = recordFilesSize(directory)
        storageLimitReached = recordBytesWritten >= maxSessionBytes
        appendEventRecord("session_resumed_after_app_restart", emptyMap(), nowMillis())
    }

    private fun restoreRecordState(
        directory: File,
        manifest: JSONObject,
    ): RestoredRecordState? {
        var lastElapsedRealtimeMs = Long.MIN_VALUE
        var ordinal = 0L
        var recordTailSha256: String? = null
        val recordFiles = directory
            .listFiles { file -> RECORD_FILE_REGEX.matches(file.name) }
            .orEmpty()
            .sortedBy(File::getName)
        val expectedSegmentCount = strictNonNegativeLong(manifest.opt("record_segment_count"))
            ?: return null
        val expectedRecordCount = strictNonNegativeLong(manifest.opt("record_count"))
            ?: return null
        val expectedTailSha256 = manifest.opt("record_tail_sha256") as? String ?: return null
        if (expectedSegmentCount != recordFiles.size.toLong()) return null
        if (expectedRecordCount == 0L && expectedTailSha256.isNotEmpty()) return null
        if (expectedRecordCount > 0L && !SHA256_HEX.matches(expectedTailSha256)) return null
        for ((fileIndex, recordFile) in recordFiles.withIndex()) {
            if (!recordFile.isFile) return null
            val segmentIndex = RECORD_FILE_REGEX.matchEntire(recordFile.name)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (segmentIndex != fileIndex + 1) return null
            val lines = runCatching { recordFile.readLines() }.getOrElse { return null }
            for (line in lines) {
                if (line.isBlank()) return null
                val plaintext = openRecord(
                    envelope = line,
                    sessionId = directory.name,
                    segmentIndex = segmentIndex,
                    ordinal = ordinal,
                ) ?: return null
                val record = runCatching { JSONObject(plaintext) }.getOrElse { return null }
                if (record.optString("schema_version") != RECORD_SCHEMA_VERSION) return null
                recordTailSha256 = sha256Hex(line)
                ordinal += 1L
                if (record.optString("event_name") != "camera_non_metric_inference_sample") continue
                val fields = record.optJSONObject("fields") ?: return null
                val elapsedRealtimeMs = when (val value = fields.opt("elapsed_realtime_ms")) {
                    is Int -> value.toLong()
                    is Long -> value
                    else -> return null
                }
                if (elapsedRealtimeMs < 0L) return null
                if (lastElapsedRealtimeMs != Long.MIN_VALUE) {
                    if (elapsedRealtimeMs < lastElapsedRealtimeMs) return null
                    if (elapsedRealtimeMs - lastElapsedRealtimeMs < CAMERA_NON_METRIC_SAMPLE_INTERVAL_MS) {
                        return null
                    }
                }
                lastElapsedRealtimeMs = elapsedRealtimeMs
            }
        }
        if (ordinal != expectedRecordCount || (recordTailSha256 ?: "") != expectedTailSha256) {
            return null
        }
        return RestoredRecordState(
            lastCameraElapsedRealtimeMs = lastElapsedRealtimeMs,
            nextOrdinal = ordinal,
            lastSegmentIndex = recordFiles.size.coerceAtLeast(1),
            recordTailSha256 = recordTailSha256,
        )
    }

    private fun provenanceChanged(manifest: JSONObject): Boolean {
        val currentApkSha = deviceInfo.apkSha256 ?: return false
        val currentConfigSha = deviceInfo.modelConfigSha256 ?: return false
        val previous = manifest.optJSONObject("provenance") ?: return true
        return previous.optString("apk_sha256") != currentApkSha ||
            previous.optString("model_config_sha256") != currentConfigSha
    }

    private fun sealRecord(record: JSONObject, state: SessionState): String? {
        val plaintext = record.toString().toByteArray(StandardCharsets.UTF_8)
        val sealed = aead.seal(
            plaintext = plaintext,
            domainAad = recordAad(state.id, state.segmentIndex, state.nextRecordOrdinal),
            limits = RECORD_LIMITS,
        )
        plaintext.fill(0)
        return (sealed as? AeadSealResult.Sealed)?.envelope
    }

    private fun openRecord(
        envelope: String,
        sessionId: String,
        segmentIndex: Int,
        ordinal: Long,
    ): String? {
        val opened = aead.open(
            envelope = envelope,
            domainAad = recordAad(sessionId, segmentIndex, ordinal),
            limits = RECORD_LIMITS,
        ) as? AeadOpenResult.Opened ?: return null
        val plaintext = String(opened.plaintext, StandardCharsets.UTF_8)
        opened.plaintext.fill(0)
        return plaintext
    }

    private fun readManifest(sessionId: String): JSONObject? {
        if (!SAFE_SESSION_ID.matches(sessionId)) return null
        val file = File(File(rootDirectory, sessionId), MANIFEST_NAME)
        val plaintext = readEncryptedFile(file, manifestAad(sessionId), MANIFEST_LIMITS)
            ?: return null
        return runCatching { JSONObject(plaintext) }.getOrNull()?.takeIf { manifest ->
            manifest.optString("schema_version") == MANIFEST_SCHEMA_VERSION &&
                manifest.optString("session_id") == sessionId
        }
    }

    private fun writeEncryptedFile(
        target: File,
        plaintext: String,
        aad: ByteArray,
        limits: AeadLimits,
    ): Boolean {
        val bytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        val sealed = aead.seal(bytes, aad, limits)
        bytes.fill(0)
        val envelope = (sealed as? AeadSealResult.Sealed)?.envelope ?: return false
        return atomicWrite(target, envelope)
    }

    private fun readEncryptedFile(
        target: File,
        aad: ByteArray,
        limits: AeadLimits,
    ): String? {
        val envelope = runCatching { target.readText() }.getOrNull() ?: return null
        val opened = aead.open(envelope, aad, limits) as? AeadOpenResult.Opened ?: return null
        val plaintext = String(opened.plaintext, StandardCharsets.UTF_8)
        if (opened.needsRewrap) {
            val rewrapped = (aead.seal(opened.plaintext, aad, limits)
                as? AeadSealResult.Sealed)?.envelope
            if (rewrapped == null || !atomicWrite(target, rewrapped)) {
                opened.plaintext.fill(0)
                return null
            }
        }
        opened.plaintext.fill(0)
        return plaintext
    }

    private fun activePointerAad(): ByteArray =
        "$AAD_PREFIX|field-log|active-pointer|storage=1".toByteArray(StandardCharsets.UTF_8)

    private fun manifestAad(sessionId: String): ByteArray =
        "$AAD_PREFIX|field-log|session=$sessionId|manifest|storage=1"
            .toByteArray(StandardCharsets.UTF_8)

    private fun recordAad(
        sessionId: String,
        segmentIndex: Int,
        ordinal: Long,
    ): ByteArray =
        (
            "$AAD_PREFIX|field-log|session=$sessionId|segment=" +
                String.format(Locale.US, "%04d", segmentIndex) +
                "|ordinal=$ordinal|record|storage=1"
        ).toByteArray(StandardCharsets.UTF_8)

    private fun blockCryptoLocked() {
        cryptoBlocked = true
        active = null
        lastTelemetryAtMs = Long.MIN_VALUE
        lastCameraNonMetricSampleElapsedRealtimeMs = Long.MIN_VALUE
        recordBytesWritten = 0L
        storageLimitReached = false
        persistFence(cryptoBlockFile())
    }

    private fun appendEventRecord(name: String, fields: Map<String, Any?>, now: Long) {
        require(SAFE_EVENT_NAME.matches(name)) { "invalid field event name" }
        val safeFields = JSONObject()
        fields.forEach { (key, value) ->
            if (key in ALLOWED_EVENT_FIELDS && SAFE_FIELD_NAME.matches(key)) {
                when (value) {
                    is Boolean -> safeFields.put(key, value)
                    is Number -> safeFields.put(key, value)
                    is String -> value.take(MAX_STATUS_LENGTH)
                        .takeIf(SAFE_FIELD_VALUE::matches)
                        ?.let { safeFields.put(key, it) }
                }
            }
        }
        appendRecord(
            JSONObject()
                .put("schema_version", RECORD_SCHEMA_VERSION)
                .put("record_type", "event")
                .put("recorded_at_epoch_ms", now)
                .put("event_name", name)
                .put("fields", safeFields),
        )
    }

    private fun appendRecord(record: JSONObject) {
        if (cryptoBlocked) return
        var state = active ?: return
        val directory = sessionDirectory(state)
        val observedBytes = recordFilesSize(directory)
        if (observedBytes != recordBytesWritten) {
            val manifest = readManifest(state.id) ?: run {
                blockCryptoLocked()
                return
            }
            val restored = restoreRecordState(directory, manifest) ?: run {
                blockCryptoLocked()
                return
            }
            state = state.copy(
                segmentIndex = restored.lastSegmentIndex,
                nextRecordOrdinal = restored.nextOrdinal,
                recordTailSha256 = restored.recordTailSha256,
            )
            active = state
            recordBytesWritten = observedBytes
        }
        var envelope = sealRecord(record, state) ?: run {
            blockCryptoLocked()
            return
        }
        var bytes = (envelope + "\n").toByteArray(StandardCharsets.UTF_8)
        if (recordBytesWritten + bytes.size > maxSessionBytes) {
            storageLimitReached = true
            return
        }
        var segment = segmentFile(state)
        if (segment.isFile && segment.length() > 0L && segment.length() + bytes.size > maxSegmentBytes) {
            state = state.copy(segmentIndex = state.segmentIndex + 1)
            active = state
            segment = segmentFile(state)
            envelope = sealRecord(record, state) ?: run {
                blockCryptoLocked()
                return
            }
            bytes = (envelope + "\n").toByteArray(StandardCharsets.UTF_8)
        }
        segment.parentFile?.mkdirs()
        val appended = runCatching {
            FileOutputStream(segment, true).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }.isSuccess
        if (!appended) {
            blockCryptoLocked()
            return
        }
        recordBytesWritten += bytes.size
        active = state.copy(
            nextRecordOrdinal = state.nextRecordOrdinal + 1L,
            recordTailSha256 = sha256Hex(envelope),
        )
        if (!writeManifest(status = STATUS_ACTIVE, endedAtMs = null)) {
            blockCryptoLocked()
        }
    }

    private fun writeManifest(status: String, endedAtMs: Long?): Boolean {
        val state = active ?: return false
        val manifest = JSONObject()
            .put("schema_version", MANIFEST_SCHEMA_VERSION)
            .put("session_id", state.id)
            .put("status", status)
            .put("started_at_epoch_ms", state.startedAtMs)
            .put(
                "record_segment_count",
                sessionDirectory(state)
                    .listFiles { file -> file.isFile && RECORD_FILE_REGEX.matches(file.name) }
                    .orEmpty()
                    .size,
            )
            .put("record_count", state.nextRecordOrdinal)
            .put("record_tail_sha256", state.recordTailSha256 ?: "")
            .put(
                "device",
                JSONObject()
                    .put("model", deviceInfo.model)
                    .put("android_version", deviceInfo.androidVersion)
                    .put("app_version_name", deviceInfo.appVersionName),
            )
            .put(
                "privacy",
                JSONObject()
                    .put("exact_coordinates", false)
                    .put("camera_or_depth_files", false)
                    .put("audio_or_recognized_text", false)
                    .put("reporter_user_id", false)
                    .put("destination_or_search_query", false),
            )
        val provenance = JSONObject()
        provenance.put("source_commit", deviceInfo.sourceCommit)
        deviceInfo.apkSha256?.let { provenance.put("apk_sha256", it) }
        deviceInfo.modelConfigSha256?.let { provenance.put("model_config_sha256", it) }
        manifest.put("provenance", provenance)
        endedAtMs?.let { manifest.put("ended_at_epoch_ms", it) }
        return writeEncryptedFile(
            target = File(sessionDirectory(state), MANIFEST_NAME),
            plaintext = manifest.toString(2),
            aad = manifestAad(state.id),
            limits = MANIFEST_LIMITS,
        )
    }

    private fun existingSegmentIndex(directory: File): Int {
        return directory.listFiles()
            .orEmpty()
            .mapNotNull { RECORD_FILE_REGEX.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()
            ?: 1
    }

    private fun recordFilesSize(directory: File): Long = directory
        .listFiles { file -> RECORD_FILE_REGEX.matches(file.name) }
        .orEmpty()
        .sumOf(File::length)

    private fun expireActiveSessionIfNeeded(now: Long): Boolean {
        val state = active ?: return false
        val ageMs = now - state.startedAtMs
        if (ageMs < 0L || ageMs < maxActiveSessionAgeMs) return false
        activePointerFile().delete()
        sessionDirectory(state).deleteRecursively()
        active = null
        lastTelemetryAtMs = Long.MIN_VALUE
        lastCameraNonMetricSampleElapsedRealtimeMs = Long.MIN_VALUE
        recordBytesWritten = 0L
        storageLimitReached = false
        return true
    }

    private fun pruneCompletedSessions() {
        val protectedActiveId = readActivePointerId()
        if (cryptoBlocked) return
        val now = nowMillis()
        val completed = rootDirectory.listFiles(File::isDirectory)
            .orEmpty()
            .mapNotNull { directory ->
                if (directory.name == protectedActiveId) return@mapNotNull null
                val manifest = readManifest(directory.name) ?: run {
                    blockCryptoLocked()
                    return@mapNotNull null
                }
                if (manifest.optString("status") != STATUS_COMPLETED) {
                    blockCryptoLocked()
                    return@mapNotNull null
                }
                val endedAt = manifest.optLong("ended_at_epoch_ms", directory.lastModified())
                RetainedSession(directory, endedAt)
            }
            .sortedByDescending(RetainedSession::endedAtMs)
        completed.forEachIndexed { index, session ->
            val expired = now >= session.endedAtMs && now - session.endedAtMs > maxSessionAgeMs
            if (expired || index >= maxRetainedSessions) session.directory.deleteRecursively()
        }
    }

    private fun segmentFile(state: SessionState): File = File(
        sessionDirectory(state),
        String.format(Locale.US, "records-%04d.jsonl", state.segmentIndex),
    )

    private fun sessionDirectory(state: SessionState): File = File(rootDirectory, state.id)

    private fun activePointerFile(): File = File(rootDirectory, ACTIVE_POINTER_NAME)

    private fun activeSessionRestoreBlockFile(): File = File(rootDirectory, RESTORE_BLOCK_MARKER_NAME)

    private fun atomicWrite(target: File, value: String): Boolean = runCatching {
        val parent = target.parentFile ?: return false
        if (!parent.mkdirs() && !parent.isDirectory) return false
        val temporary = atomicTemporaryFile(target)
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
        check(directorySync(parent)) { "parent directory fsync failed: ${parent.path}" }
        true
    }.getOrDefault(false)

    private data class SessionState(
        val id: String,
        val startedAtMs: Long,
        val segmentIndex: Int,
        val nextRecordOrdinal: Long,
        val recordTailSha256: String?,
    )

    private data class RestoredRecordState(
        val lastCameraElapsedRealtimeMs: Long,
        val nextOrdinal: Long,
        val lastSegmentIndex: Int,
        val recordTailSha256: String?,
    )

    private data class RetainedSession(val directory: File, val endedAtMs: Long)

    private companion object {
        fun strictNonNegativeLong(value: Any?): Long? = when (value) {
            is Int -> value.toLong().takeIf { it >= 0L }
            is Long -> value.takeIf { it >= 0L }
            else -> null
        }

        fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

        fun syncDirectory(directory: File): Boolean = runCatching {
            check(directory.isDirectory) { "not a directory: ${directory.path}" }
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        }.isSuccess

        const val RECORD_SCHEMA_VERSION = "android.field_record.v1"
        val SHA256_HEX = Regex("[0-9a-f]{64}")
        const val MANIFEST_SCHEMA_VERSION = "android.field_session.v1"
        const val STATUS_ACTIVE = "active"
        const val STATUS_COMPLETED = "completed"
        const val MANIFEST_NAME = "manifest.json"
        const val ACTIVE_POINTER_NAME = "active_session.txt"
        const val RESTORE_BLOCK_MARKER_NAME = "active_session_restore_blocked.txt"
        const val ACCOUNT_DELETION_BLOCK_MARKER_NAME = "account_deletion_blocked.txt"
        const val RAW_SOURCE_COLLECTION_BLOCK_MARKER_NAME = "raw_source_collection_blocked.txt"
        const val CRYPTO_BLOCK_MARKER_NAME = "field_log_crypto_blocked.txt"
        const val FIELD_KEY_PURGE_VERIFIED_MARKER_NAME = "field_key_purge_verified.txt"
        const val RESTORE_BLOCK_MARKER_VALUE = "blocked\n"
        const val FIELD_KEY_PURGE_VERIFIED_VALUE = "verified\n"
        const val DEFAULT_MAX_SEGMENT_BYTES = 4L * 1024L * 1024L
        const val DEFAULT_MAX_SESSION_BYTES = 32L * 1024L * 1024L
        const val DEFAULT_MAX_RETAINED_SESSIONS = 20
        const val DEFAULT_MAX_SESSION_AGE_MS = 14L * 24L * 60L * 60L * 1_000L
        const val DEFAULT_MAX_ACTIVE_SESSION_AGE_MS = 14L * 24L * 60L * 60L * 1_000L
        const val DEFAULT_TELEMETRY_INTERVAL_MS = 1_000L
        const val CAMERA_NON_METRIC_SAMPLE_INTERVAL_MS = 1_000L
        const val MAX_STATUS_LENGTH = 160
        const val AAD_PREFIX = "kr.co.hanium.dreamup.walksafe|USER"
        val SAFE_SESSION_ID = Regex("[A-Za-z0-9._-]{1,96}")
        val SAFE_EVENT_NAME = Regex("[a-z][a-z0-9_]{0,63}")
        val SAFE_FIELD_NAME = Regex("[a-z][a-z0-9_]{0,63}")
        val SAFE_FIELD_VALUE = Regex("[A-Za-z0-9_.:=+\\-]{0,160}")
        val RECORD_FILE_REGEX = Regex("records-(\\d{4})\\.jsonl")
        val ALLOWED_EVENT_FIELDS = setOf(
            "activity_permission",
            "audio_permission",
            "camera_fallback_running",
            "camera_permission",
            "camera_permission_granted",
            "capability_tier",
            "closed",
            "detection_count",
            "depth_supported",
            "detector_available",
            "direction",
            "elapsed_realtime_ms",
            "imu_fresh",
            "inference_ms",
            "level",
            "loaded_model",
            "location_permission",
            "metric",
            "model_fallback_used",
            "reason",
            "reports_allowed",
            "state",
            "tmap_authoritative",
            "tmap_route_active",
        )
        val PROCESS_LOCK = Any()
        val FIELD_KEY_POLICY = AeadKeyPolicy(
            aliasPrefix = "walksafe.user.field_log.aead.v",
            currentVersion = 1,
            readableVersions = setOf(1),
        )
        val POINTER_LIMITS = AeadLimits(
            maxPlaintextBytes = 128,
            maxCiphertextBytes = 144,
            maxEnvelopeChars = 512,
        )
        val MANIFEST_LIMITS = AeadLimits(
            maxPlaintextBytes = 64 * 1_024,
            maxCiphertextBytes = 64 * 1_024 + 16,
            maxEnvelopeChars = 96 * 1_024,
        )
        val RECORD_LIMITS = AeadLimits(
            maxPlaintextBytes = 512 * 1_024,
            maxCiphertextBytes = 512 * 1_024 + 16,
            maxEnvelopeChars = 704 * 1_024,
        )
    }
}
