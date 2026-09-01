package kr.co.hanium.dreamup.walksafe.report

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionVerificationState
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONArray
import org.json.JSONObject

internal interface UserReportDeletionTracker {
    fun track(session: GatewayFieldSession, requestId: String): Boolean
    fun trackedRequestIds(session: GatewayFieldSession): List<String>
    fun trackRequest(
        session: GatewayFieldSession,
        reference: UserReportRequestReference,
    ): Boolean
    fun trackedRequestReferences(session: GatewayFieldSession): List<UserReportRequestReference>
}

/**
 * Persists only deletion request UUIDs. Each ciphertext is bound to one v7
 * Backend account actor and generation and lives below noBackupFilesDir.
 */
internal class AndroidReportDeletionTrackerStore private constructor(
    private val storage: ReportDeletionTrackerStorage,
    private val aead: LocalAead,
) : UserReportDeletionTracker {
    constructor(
        context: Context,
        aead: LocalAead = AndroidKeyStoreAead(KEY_POLICY),
    ) : this(
        storage = AtomicReportDeletionTrackerStorage(
            File(context.noBackupFilesDir, DIRECTORY_NAME),
        ),
        aead = aead,
    )

    internal constructor(
        storage: ReportDeletionTrackerStorage,
        aead: LocalAead,
        testOnly: Unit = Unit,
    ) : this(storage, aead)

    @Synchronized
    override fun track(session: GatewayFieldSession, requestId: String): Boolean {
        val binding = session.bindingOrNull() ?: return false
        if (!validCanonicalUserReportUuid(requestId)) return false
        val loaded = read(binding)
        val current = when (loaded) {
            TrackerRead.Absent -> TrackerSnapshot()
            is TrackerRead.Loaded -> loaded.snapshot
            TrackerRead.Blocked -> return false
        }
        if (current.requestReferences.any {
            it.requestId == requestId && it.requestType != UserReportRequestType.DELETE
        }) return false
        if (requestId in current.deletionRequestIds) return true
        if (current.distinctRequestIdCount() >= MAX_TRACKED_REQUESTS) return false
        return write(
            binding,
            current.copy(deletionRequestIds = current.deletionRequestIds + requestId),
        )
    }

    @Synchronized
    override fun trackedRequestIds(session: GatewayFieldSession): List<String> {
        val binding = session.bindingOrNull() ?: return emptyList()
        return (read(binding) as? TrackerRead.Loaded)?.snapshot?.deletionRequestIds.orEmpty()
    }

    @Synchronized
    override fun trackRequest(
        session: GatewayFieldSession,
        reference: UserReportRequestReference,
    ): Boolean {
        val binding = session.bindingOrNull() ?: return false
        val current = when (val loaded = read(binding)) {
            TrackerRead.Absent -> TrackerSnapshot()
            is TrackerRead.Loaded -> loaded.snapshot
            TrackerRead.Blocked -> return false
        }
        val existing = current.requestReferences.singleOrNull {
            it.requestId == reference.requestId
        }
        if (existing != null && existing != reference) return false
        if (
            reference.requestType != UserReportRequestType.DELETE &&
            reference.requestId in current.deletionRequestIds
        ) return false
        if (
            existing == null &&
            reference.requestId !in current.deletionRequestIds &&
            current.distinctRequestIdCount() >= MAX_TRACKED_REQUESTS
        ) return false
        val nextReferences = if (existing == null) {
            current.requestReferences + reference
        } else {
            current.requestReferences
        }
        val nextDeletionIds = if (
            reference.requestType == UserReportRequestType.DELETE &&
            reference.requestId !in current.deletionRequestIds
        ) {
            current.deletionRequestIds + reference.requestId
        } else {
            current.deletionRequestIds
        }
        if (existing != null && nextDeletionIds == current.deletionRequestIds) return true
        return write(
            binding,
            TrackerSnapshot(
                deletionRequestIds = nextDeletionIds,
                requestReferences = nextReferences,
            ),
        )
    }

    @Synchronized
    override fun trackedRequestReferences(
        session: GatewayFieldSession,
    ): List<UserReportRequestReference> {
        val binding = session.bindingOrNull() ?: return emptyList()
        return (read(binding) as? TrackerRead.Loaded)?.snapshot?.requestReferences.orEmpty()
    }

    private fun read(binding: TrackerBinding): TrackerRead {
        val envelope = try {
            storage.read(binding.storageKey)
        } catch (_: Exception) {
            return TrackerRead.Blocked
        } ?: return TrackerRead.Absent
        val opened = runCatching { aead.open(envelope, binding.aad, LIMITS) }
            .getOrNull() as? AeadOpenResult.Opened
            ?: run {
                runCatching { storage.delete(binding.storageKey) }
                return TrackerRead.Blocked
            }
        val decoded = decode(opened.plaintext)
        opened.plaintext.fill(0)
        if (decoded == null) {
            runCatching { storage.delete(binding.storageKey) }
            return TrackerRead.Blocked
        }
        if (opened.needsRewrap) {
            if (!write(binding, decoded.snapshot)) return TrackerRead.Blocked
        } else if (decoded.needsSchemaUpgrade) {
            write(binding, decoded.snapshot)
        }
        return TrackerRead.Loaded(decoded.snapshot)
    }

    private fun write(binding: TrackerBinding, snapshot: TrackerSnapshot): Boolean {
        if (
            snapshot.distinctRequestIdCount() > MAX_TRACKED_REQUESTS ||
            snapshot.deletionRequestIds.distinct().size != snapshot.deletionRequestIds.size ||
            snapshot.deletionRequestIds.any { !validCanonicalUserReportUuid(it) } ||
            snapshot.requestReferences.map(UserReportRequestReference::requestId)
                .distinct().size != snapshot.requestReferences.size ||
            snapshot.requestReferences.any {
                it.requestType == UserReportRequestType.DELETE &&
                    it.requestId !in snapshot.deletionRequestIds
            } ||
            snapshot.requestReferences.any {
                it.requestType != UserReportRequestType.DELETE &&
                    it.requestId in snapshot.deletionRequestIds
            }
        ) return false
        val references = JSONArray().apply {
            snapshot.requestReferences.forEach { reference ->
                put(
                    JSONObject()
                        .put("report_id", reference.reportId)
                        .put("request_id", reference.requestId)
                        .put("request_type", reference.requestType.wireValue),
                )
            }
        }
        val plaintext = JSONObject()
            .put("schema_version", SCHEMA_VERSION_V2)
            .put("request_ids", JSONArray(snapshot.deletionRequestIds))
            .put("request_references", references)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val sealed = runCatching { aead.seal(plaintext, binding.aad, LIMITS) }.getOrNull()
        plaintext.fill(0)
        val envelope = (sealed as? AeadSealResult.Sealed)?.envelope ?: return false
        return runCatching { storage.write(binding.storageKey, envelope) }.getOrDefault(false)
    }

    private fun decode(plaintext: ByteArray): DecodedTrackerSnapshot? = runCatching {
        val root = JSONObject(String(plaintext, Charsets.UTF_8))
        when (root.getString("schema_version")) {
            SCHEMA_VERSION_V1 -> {
                require(root.keysAsSet() == setOf("schema_version", "request_ids"))
                DecodedTrackerSnapshot(
                    snapshot = TrackerSnapshot(
                        deletionRequestIds = root.strictRequestIds(
                            "request_ids",
                            MAX_TRACKED_REQUESTS,
                        ),
                    ),
                    needsSchemaUpgrade = true,
                )
            }
            SCHEMA_VERSION_V2 -> {
                require(
                    root.keysAsSet() ==
                        setOf("schema_version", "request_ids", "request_references"),
                )
                val deletionRequestIds = root.strictRequestIds(
                    "request_ids",
                    MAX_TRACKED_REQUESTS,
                )
                val rawReferences = root.get("request_references") as? JSONArray
                    ?: error("request_references must be an array")
                require(rawReferences.length() <= MAX_TRACKED_REQUESTS)
                val requestReferences = buildList {
                    repeat(rawReferences.length()) { index ->
                        val item = rawReferences.get(index) as? JSONObject
                            ?: error("request reference must be an object")
                        require(
                            item.keysAsSet() ==
                                setOf("report_id", "request_id", "request_type"),
                        )
                        add(
                            UserReportRequestReference(
                                reportId = item.get("report_id") as? String
                                    ?: error("report_id must be a string"),
                                requestId = item.get("request_id") as? String
                                    ?: error("request_id must be a string"),
                                requestType = UserReportRequestType.fromWireOrNull(
                                    item.get("request_type") as? String
                                        ?: error("request_type must be a string"),
                                ) ?: error("request_type is unknown"),
                            ),
                        )
                    }
                }
                require(
                    requestReferences.map(UserReportRequestReference::requestId)
                        .distinct().size == requestReferences.size,
                )
                require(
                    requestReferences.none {
                        it.requestType == UserReportRequestType.DELETE &&
                            it.requestId !in deletionRequestIds
                    },
                )
                require(
                    requestReferences.none {
                        it.requestType != UserReportRequestType.DELETE &&
                            it.requestId in deletionRequestIds
                    },
                )
                val snapshot = TrackerSnapshot(
                    deletionRequestIds = deletionRequestIds,
                    requestReferences = requestReferences,
                )
                require(snapshot.distinctRequestIdCount() <= MAX_TRACKED_REQUESTS)
                DecodedTrackerSnapshot(
                    snapshot = snapshot,
                    needsSchemaUpgrade = false,
                )
            }
            else -> error("unknown report request tracker schema")
        }
    }.getOrNull()

    private fun GatewayFieldSession.bindingOrNull(): TrackerBinding? {
        val generation = backendAccountGeneration ?: return null
        if (
            sessionScope != GatewaySessionScope.GENERAL ||
            verificationState != GatewaySessionVerificationState.VERIFIED ||
            !isBackendAccountDeviceBound ||
            generation <= 0L ||
            !ACTOR_ID.matches(actorId)
        ) return null
        val canonical = "$actorId\u0000$generation"
        val storageKey = MessageDigest.getInstance("SHA-256")
            .digest("$BINDING_DOMAIN\u0000$canonical".toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return TrackerBinding(
            storageKey = storageKey,
            aad = "$BINDING_DOMAIN|actor=$actorId|generation=$generation"
                .toByteArray(Charsets.UTF_8),
        )
    }

    private data class TrackerBinding(
        val storageKey: String,
        val aad: ByteArray,
    )

    private sealed interface TrackerRead {
        data object Absent : TrackerRead
        data object Blocked : TrackerRead
        data class Loaded(val snapshot: TrackerSnapshot) : TrackerRead
    }

    private data class TrackerSnapshot(
        val deletionRequestIds: List<String> = emptyList(),
        val requestReferences: List<UserReportRequestReference> = emptyList(),
    ) {
        fun distinctRequestIdCount(): Int =
            (deletionRequestIds + requestReferences.map(UserReportRequestReference::requestId))
                .toSet().size
    }

    private data class DecodedTrackerSnapshot(
        val snapshot: TrackerSnapshot,
        val needsSchemaUpgrade: Boolean,
    )

    private companion object {
        const val DIRECTORY_NAME = "report-deletion-trackers"
        const val SCHEMA_VERSION_V1 = "walksafe.android-report-deletion-tracker.v1"
        const val SCHEMA_VERSION_V2 = "walksafe.android-report-deletion-tracker.v2"
        const val BINDING_DOMAIN =
            "kr.co.hanium.dreamup.walksafe|USER|report-deletion-tracker|schema=1"
        const val MAX_TRACKED_REQUESTS = 256
        val ACTOR_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")
        val KEY_POLICY = AeadKeyPolicy(
            aliasPrefix = "walksafe.user.report_deletion_tracker.aead.v",
            currentVersion = 1,
            readableVersions = setOf(1),
        )
        val LIMITS = AeadLimits(
            maxPlaintextBytes = 64 * 1_024,
            maxCiphertextBytes = 64 * 1_024 + 16,
            maxEnvelopeChars = 128 * 1_024,
        )
    }
}

private fun JSONObject.strictRequestIds(name: String, maximumCount: Int): List<String> {
    val values = get(name) as? JSONArray ?: error("$name must be an array")
    require(values.length() <= maximumCount)
    val requestIds = buildList {
        repeat(values.length()) { index ->
            val requestId = values.get(index) as? String
                ?: error("request id must be a string")
            require(validCanonicalUserReportUuid(requestId))
            add(requestId)
        }
    }
    require(requestIds.distinct().size == requestIds.size)
    return requestIds
}

private fun JSONObject.keysAsSet(): Set<String> = buildSet {
    val iterator = keys()
    while (iterator.hasNext()) add(iterator.next())
}

internal interface ReportDeletionTrackerStorage {
    fun read(storageKey: String): String?
    fun write(storageKey: String, envelope: String): Boolean
    fun delete(storageKey: String): Boolean
}

private class AtomicReportDeletionTrackerStorage(
    private val directory: File,
) : ReportDeletionTrackerStorage {
    override fun read(storageKey: String): String? {
        val file = target(storageKey)
        return try {
            file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4 * 1_024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_STORED_ENVELOPE_BYTES) {
                        throw IOException("report deletion tracker envelope is too large")
                    }
                    output.write(buffer, 0, count)
                }
                if (output.size() == 0) {
                    throw IOException("report deletion tracker envelope is empty")
                }
                String(output.toByteArray(), Charsets.UTF_8)
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    override fun write(storageKey: String, envelope: String): Boolean {
        val bytes = envelope.toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..MAX_STORED_ENVELOPE_BYTES) return false
        if (!directory.exists() && !directory.mkdirs()) return false
        val file = target(storageKey)
        val output = runCatching { file.startWrite() }.getOrNull() ?: return false
        return try {
            output.write(bytes)
            file.finishWrite(output)
            true
        } catch (_: Exception) {
            runCatching { file.failWrite(output) }
            false
        } finally {
            bytes.fill(0)
        }
    }

    override fun delete(storageKey: String): Boolean = runCatching {
        target(storageKey).delete()
        true
    }.getOrDefault(false)

    private fun target(storageKey: String): AtomicFile {
        require(STORAGE_KEY.matches(storageKey))
        return AtomicFile(File(directory, "tracker-$storageKey.aead"))
    }

    private companion object {
        val STORAGE_KEY = Regex("^[0-9a-f]{64}$")
        const val MAX_STORED_ENVELOPE_BYTES = 128 * 1_024
    }
}
