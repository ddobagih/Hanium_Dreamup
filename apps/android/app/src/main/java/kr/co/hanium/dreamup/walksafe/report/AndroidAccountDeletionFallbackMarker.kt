package kr.co.hanium.dreamup.walksafe.report

import android.content.Context
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournal
import kr.co.hanium.dreamup.walksafe.session.accountDeletionNetworkEntryAllowed
import kr.co.hanium.dreamup.walksafe.session.validCanonicalWholeSecondUtcInstant

/**
 * Durable fail-closed marker for an account deletion that still needs local cleanup.
 *
 * This marker intentionally lives directly under noBackupFilesDir, outside every payload queue.
 * All I/O remains on the caller thread; callers must use the cleanup executor.
 */
internal class AndroidAccountDeletionFallbackMarker internal constructor(
    private val markerFile: File,
    private val operations: Operations = Operations(),
    private val lockTimeoutNanos: Long =
        TimeUnit.MILLISECONDS.toNanos(LOCK_TIMEOUT_MILLIS),
    private val aead: LocalAead = AndroidKeyStoreAead(KEY_POLICY),
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, MARKER_FILE_NAME),
    )

    data class Identity(
        val requestId: String,
        val actorHash: String,
    )

    enum class Phase {
        PREPARED,
        ACCEPTED,
        LOCAL_PURGED,
        EVIDENCE_PENDING,
        EVIDENCE_ACKED,
        TERMINAL_RECEIPT,
    }

    data class Record(
        val identity: Identity,
        val recoveryActorId: String? = null,
        val gatewayOrigin: String,
        val installationId: String,
        val accessSecret: String,
        val clientRevision: Long,
        val phase: Phase,
        val acceptedAt: String? = null,
        val accountGeneration: Long? = null,
        val tombstoneId: String? = null,
        val requestReceiptSha256: String? = null,
        val statusRevision: Long = 0L,
        val evidenceId: String? = null,
        val evidenceSha256: String? = null,
        val evidenceExpectedStatusRevision: Long? = null,
        val evidenceResult: String? = null,
        val evidenceCompletedAt: String? = null,
        val completionReceiptSha256: String? = null,
    ) {
        override fun toString(): String {
            val recoveryActorState = if (recoveryActorId == null) "none" else "present"
            return "Record(identity=$identity, " +
                "recoveryActor=$recoveryActorState, " +
                "gatewayOrigin=$gatewayOrigin, installationId=$installationId, " +
                "accessSecret=redacted, clientRevision=$clientRevision, phase=$phase, " +
                "statusRevision=$statusRevision)"
        }
    }

    sealed class State {
        object Absent : State()

        data class Present(val record: Record) : State() {
            constructor(identity: Identity) : this(legacyRecord(identity))

            val identity: Identity
                get() = record.identity
        }

        object Corrupt : State()

        object Unavailable : State()
    }

    internal class Operations(
        val writeAndSync: (File, ByteArray) -> Unit = ::writeAndSyncFile,
        val atomicMove: (File, File) -> Unit = ::moveAtomicallyFile,
        val atomicReplace: (File, File) -> Unit = ::replaceAtomicallyFile,
        val delete: (File) -> Boolean = File::delete,
        val syncFile: (File) -> Unit = ::syncFileFile,
        val syncDirectory: (File) -> Unit = ::syncDirectoryFile,
    )

    fun create(identity: Identity): Boolean {
        return create(legacyRecord(identity))
    }

    fun create(record: Record): Boolean {
        if (!isValidRecord(record)) return false
        return withExclusiveLock(false) {
            if (!reconcileKeyResetLocked()) return@withExclusiveLock false
            when (val state = readLocked()) {
                State.Absent -> writeLocked(record, replace = false)
                is State.Present ->
                    state.record == record && verifyExistingDurabilityLocked(record)
                State.Corrupt,
                State.Unavailable,
                -> false
            }
        }
    }

    fun update(record: Record): Boolean {
        if (!isValidRecord(record)) return false
        return withExclusiveLock(false) {
            if (!reconcileKeyResetLocked()) return@withExclusiveLock false
            val current = readLocked() as? State.Present
                ?: return@withExclusiveLock false
            if (!validTransition(current.record, record)) return@withExclusiveLock false
            if (current.record == record) {
                return@withExclusiveLock verifyExistingDurabilityLocked(record)
            }
            writeLocked(record, replace = true)
        }
    }

    fun read(): State =
        withExclusiveLock(State.Unavailable) {
            if (!reconcileKeyResetLocked()) return@withExclusiveLock State.Unavailable
            readLocked()
        }

    fun clear(identity: Identity): Boolean {
        if (!isValidIdentity(identity)) return false
        return withExclusiveLock(false) {
            if (!reconcileKeyResetLocked()) return@withExclusiveLock false
            val state = readLocked()
            if (state is State.Absent) return@withExclusiveLock true
            if (state !is State.Present || state.identity != identity) {
                return@withExclusiveLock false
            }
            if (
                state.record.phase != Phase.TERMINAL_RECEIPT &&
                state.record != legacyRecord(identity)
            ) return@withExclusiveLock false
            val path = markerFile.toPath()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return@withExclusiveLock false
            }
            persistKeyResetPendingLocked() && reconcileKeyResetLocked()
        }
    }

    private fun persistKeyResetPendingLocked(): Boolean {
        val pending = keyResetPendingFile()
        val pendingPath = pending.toPath()
        if (Files.exists(pendingPath, LinkOption.NOFOLLOW_LINKS)) {
            return readExactSingleLink(
                pending,
                KEY_RESET_PENDING_VALUE.toByteArray(Charsets.UTF_8),
            )
        }
        val parent = markerFile.absoluteFile.parentFile ?: return false
        val temporary = runCatching {
            Files.createTempFile(parent.toPath(), "${pending.name}.", ".new").toFile()
        }.getOrNull() ?: return false
        return try {
            operations.writeAndSync(temporary, KEY_RESET_PENDING_VALUE.toByteArray(Charsets.UTF_8))
            operations.atomicMove(temporary, pending)
            operations.syncDirectory(parent)
            readExactSingleLink(
                pending,
                KEY_RESET_PENDING_VALUE.toByteArray(Charsets.UTF_8),
            )
        } catch (_: Exception) {
            false
        } finally {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
        }
    }

    private fun reconcileKeyResetLocked(): Boolean {
        val pending = keyResetPendingFile()
        val pendingPath = pending.toPath()
        if (Files.notExists(pendingPath, LinkOption.NOFOLLOW_LINKS)) return true
        if (!readExactSingleLink(
                pending,
                KEY_RESET_PENDING_VALUE.toByteArray(Charsets.UTF_8),
            )
        ) return false
        val parent = markerFile.absoluteFile.parentFile ?: return false
        return try {
            val markerPath = markerFile.toPath()
            if (
                Files.exists(markerPath, LinkOption.NOFOLLOW_LINKS) &&
                (!isRegularSingleLink(markerFile) ||
                    !operations.delete(markerFile))
            ) return false
            if (!Files.notExists(markerPath, LinkOption.NOFOLLOW_LINKS)) return false
            operations.syncDirectory(parent)
            if (!aead.destroyKnownVersions() || !aead.createFreshAfterVerifiedPurge()) return false
            if (!operations.delete(pending) || !Files.notExists(pendingPath, LinkOption.NOFOLLOW_LINKS)) {
                return false
            }
            operations.syncDirectory(parent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun keyResetPendingFile(): File = File(
        markerFile.absoluteFile.parentFile,
        KEY_RESET_PENDING_FILE_NAME,
    )

    private fun writeLocked(record: Record, replace: Boolean): Boolean {
        val parent = markerFile.absoluteFile.parentFile ?: return false
        val temporary =
            try {
                Files.createTempFile(
                    parent.toPath(),
                    "${markerFile.name}.",
                    ".new",
                ).toFile()
            } catch (_: Exception) {
                return false
            }
        return try {
            val temporaryPath = temporary.toPath()
            if (!isRegularSingleLink(temporary)) {
                return false
            }
            val plaintext = encodePayload(record)
            val envelope = try {
                (aead.seal(plaintext, MARKER_AAD, MARKER_LIMITS)
                    as? AeadSealResult.Sealed)?.envelope
            } finally {
                plaintext.fill(0)
            } ?: return false
            operations.writeAndSync(temporary, envelope.toByteArray(Charsets.UTF_8))
            if (!isRegularSingleLink(temporary)) {
                return false
            }
            if (replace) {
                operations.atomicReplace(temporary, markerFile)
            } else {
                operations.atomicMove(temporary, markerFile)
            }
            if (Files.exists(
                    temporaryPath,
                    LinkOption.NOFOLLOW_LINKS,
                )
            ) {
                return false
            }
            if (!isRegularSingleLink(markerFile)) return false
            operations.syncFile(markerFile)
            operations.syncDirectory(parent)
            readLocked() == State.Present(record)
        } catch (_: Exception) {
            false
        } finally {
            try {
                Files.deleteIfExists(temporary.toPath())
            } catch (_: Exception) {
                // The operation already fails closed; do not touch marker evidence.
            }
        }
    }

    private fun verifyExistingDurabilityLocked(record: Record): Boolean = runCatching {
        val parent = markerFile.absoluteFile.parentFile ?: return@runCatching false
        if (
            !isRegularSingleLink(markerFile) ||
            !Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) return@runCatching false
        operations.syncFile(markerFile)
        operations.syncDirectory(parent)
        readLocked() == State.Present(record)
    }.getOrDefault(false)

    private fun readLocked(): State =
        try {
            val path = markerFile.toPath()
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                State.Absent
            } else {
                readBoundedSingleLink(markerFile, MAX_MARKER_BYTES)
                    ?.let(::decodeEnvelope) ?: State.Corrupt
            }
        } catch (_: Exception) {
            State.Unavailable
        }

    private fun decodeEnvelope(bytes: ByteArray): State {
        if (bytes.isEmpty() || bytes.size > MAX_MARKER_BYTES) {
            return State.Corrupt
        }
        val envelope = bytes.toString(Charsets.UTF_8)
        if (!envelope.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            return State.Corrupt
        }
        val opened = aead.open(envelope, MARKER_AAD, MARKER_LIMITS)
            as? AeadOpenResult.Opened ?: return State.Corrupt
        val state = decodePayload(opened.plaintext)
        opened.plaintext.fill(0)
        return state
    }

    private fun decodePayload(bytes: ByteArray): State {
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes) || !text.endsWith('\n')) {
            return State.Corrupt
        }
        val legacy = text.startsWith(LEGACY_FORMAT_PREFIX)
        val formatPrefix = if (legacy) LEGACY_FORMAT_PREFIX else FORMAT_PREFIX
        if (!text.startsWith(formatPrefix)) return State.Corrupt
        val payloadFields = if (legacy) LEGACY_PAYLOAD_FIELDS else PAYLOAD_FIELDS
        val values = text.substring(formatPrefix.length).split('\n')
        if (values.size != payloadFields.size + 1 || values.last().isNotEmpty()) {
            return State.Corrupt
        }
        val decoded = linkedMapOf<String, String>()
        payloadFields.forEachIndexed { index, field ->
            val prefix = "$field:"
            val line = values[index]
            if (!line.startsWith(prefix)) return State.Corrupt
            decoded[field] = line.substring(prefix.length)
        }
        val record = runCatching {
            Record(
                identity = Identity(
                    requestId = decoded.getValue("request-id"),
                    actorHash = decoded.getValue("actor-sha256"),
                ),
                recoveryActorId = if (legacy) {
                    null
                } else {
                    decodeNullableText(decoded.getValue("recovery-actor-id"))
                },
                gatewayOrigin = decodeText(decoded.getValue("gateway-origin")),
                installationId = decoded.getValue("installation-id"),
                accessSecret = decoded.getValue("access-secret"),
                clientRevision = decoded.getValue("client-revision").toLong(),
                phase = Phase.valueOf(decoded.getValue("phase")),
                acceptedAt = decodeNullableText(decoded.getValue("accepted-at")),
                accountGeneration = decodeNullableLong(decoded.getValue("account-generation")),
                tombstoneId = decodeNullable(decoded.getValue("tombstone-id")),
                requestReceiptSha256 = decodeNullable(decoded.getValue("request-receipt-sha256")),
                statusRevision = decoded.getValue("status-revision").toLong(),
                evidenceId = decodeNullable(decoded.getValue("evidence-id")),
                evidenceSha256 = decodeNullable(decoded.getValue("evidence-sha256")),
                evidenceExpectedStatusRevision =
                    decodeNullableLong(decoded.getValue("evidence-expected-status-revision")),
                evidenceResult = decodeNullable(decoded.getValue("evidence-result")),
                evidenceCompletedAt =
                    decodeNullableText(decoded.getValue("evidence-completed-at")),
                completionReceiptSha256 =
                    decodeNullable(decoded.getValue("completion-receipt-sha256")),
            )
        }.getOrNull() ?: return State.Corrupt
        val canonical = if (legacy) encodeLegacyPayload(record) else encodePayload(record)
        if (!isValidRecord(record) || !canonical.contentEquals(bytes)) {
            return State.Corrupt
        }
        return State.Present(record)
    }

    private fun encodePayload(record: Record): ByteArray =
        encodePayload(record, FORMAT_PREFIX, includeRecoveryActor = true)

    private fun encodeLegacyPayload(record: Record): ByteArray =
        encodePayload(
            record.copy(recoveryActorId = null),
            LEGACY_FORMAT_PREFIX,
            includeRecoveryActor = false,
        )

    private fun encodePayload(
        record: Record,
        formatPrefix: String,
        includeRecoveryActor: Boolean,
    ): ByteArray =
        buildString {
            append(formatPrefix)
            append("request-id:${record.identity.requestId}\n")
            append("actor-sha256:${record.identity.actorHash}\n")
            if (includeRecoveryActor) {
                append(
                    "recovery-actor-id:${encodeNullableText(record.recoveryActorId)}\n",
                )
            }
            append("gateway-origin:${encodeText(record.gatewayOrigin)}\n")
            append("installation-id:${record.installationId}\n")
            append("access-secret:${record.accessSecret}\n")
            append("client-revision:${record.clientRevision}\n")
            append("phase:${record.phase.name}\n")
            append("accepted-at:${encodeNullableText(record.acceptedAt)}\n")
            append("account-generation:${record.accountGeneration ?: NULL_VALUE}\n")
            append("tombstone-id:${record.tombstoneId ?: NULL_VALUE}\n")
            append("request-receipt-sha256:${record.requestReceiptSha256 ?: NULL_VALUE}\n")
            append("status-revision:${record.statusRevision}\n")
            append("evidence-id:${record.evidenceId ?: NULL_VALUE}\n")
            append("evidence-sha256:${record.evidenceSha256 ?: NULL_VALUE}\n")
            append(
                "evidence-expected-status-revision:" +
                    "${record.evidenceExpectedStatusRevision ?: NULL_VALUE}\n",
            )
            append("evidence-result:${record.evidenceResult ?: NULL_VALUE}\n")
            append("evidence-completed-at:${encodeNullableText(record.evidenceCompletedAt)}\n")
            append(
                "completion-receipt-sha256:" +
                    "${record.completionReceiptSha256 ?: NULL_VALUE}\n",
            )
        }.toByteArray(Charsets.UTF_8)

    internal fun decodeLegacyPlaintextForTest(bytes: ByteArray): State =
        decodePayload(bytes)

    private fun isValidRecord(record: Record): Boolean {
        if (
            !isValidIdentity(record.identity) ||
            !isValidRecoveryActor(record.recoveryActorId, record.identity.actorHash) ||
            !isTrustedRootOrigin(record.gatewayOrigin) ||
            !OPAQUE_ID.matches(record.installationId) ||
            !isValidAccessSecret(record.accessSecret) ||
            record.clientRevision <= 0L ||
            record.statusRevision < 0L
        ) return false
        val accepted = record.phase >= Phase.ACCEPTED
        if (accepted != (
                record.acceptedAt?.let(::validInstant) == true &&
                    record.accountGeneration != null && record.accountGeneration > 0L &&
                    record.tombstoneId?.let(OPAQUE_ID::matches) == true &&
                    record.requestReceiptSha256?.let(SHA256::matches) == true &&
                    record.statusRevision > 0L
                )
        ) return false
        val evidence = record.phase >= Phase.EVIDENCE_PENDING
        if (evidence != (
                record.evidenceId?.let(OPAQUE_ID::matches) == true &&
                    record.evidenceSha256?.let(SHA256::matches) == true &&
                    record.evidenceExpectedStatusRevision != null &&
                    record.evidenceExpectedStatusRevision > 0L &&
                    record.evidenceResult in setOf("DELETED", "NOT_FOUND", "FAILED") &&
                    record.evidenceCompletedAt
                        ?.let(::validCanonicalWholeSecondUtcInstant) == true
                )
        ) return false
        val terminal = record.phase == Phase.TERMINAL_RECEIPT
        return terminal ==
            (record.completionReceiptSha256?.let(SHA256::matches) == true)
    }

    private fun isValidIdentity(identity: Identity): Boolean =
        isValidRequestId(identity.requestId) && ACTOR_HASH.matches(identity.actorHash)

    private fun isValidRecoveryActor(actorId: String?, expectedHash: String): Boolean {
        if (actorId == null) return true
        val normalized = actorId.lowercase(java.util.Locale.US)
        if (
            !ACTOR_ID.matches(actorId) ||
            normalized in RESERVED_ACTOR_IDS ||
            normalized.endsWith("-shared")
        ) return false
        val actualHash = MessageDigest.getInstance("SHA-256")
            .digest(actorId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return MessageDigest.isEqual(
            actualHash.toByteArray(Charsets.US_ASCII),
            expectedHash.toByteArray(Charsets.US_ASCII),
        )
    }

    private fun validTransition(current: Record, next: Record): Boolean {
        val sameEvidence =
            current.evidenceId == next.evidenceId &&
                current.evidenceSha256 == next.evidenceSha256 &&
                current.evidenceExpectedStatusRevision ==
                next.evidenceExpectedStatusRevision &&
                current.evidenceResult == next.evidenceResult &&
                current.evidenceCompletedAt == next.evidenceCompletedAt
        val revisionRebase =
            current.phase == Phase.EVIDENCE_PENDING &&
                next.phase == Phase.EVIDENCE_PENDING &&
                current.evidenceId != null &&
                next.evidenceId != current.evidenceId &&
                current.evidenceExpectedStatusRevision != null &&
                next.evidenceExpectedStatusRevision != null &&
                next.evidenceExpectedStatusRevision >
                current.evidenceExpectedStatusRevision &&
                next.evidenceExpectedStatusRevision == next.statusRevision &&
                current.evidenceResult == next.evidenceResult &&
                current.evidenceCompletedAt == next.evidenceCompletedAt
        val recoveryActorTransitionAllowed =
            current.recoveryActorId == next.recoveryActorId ||
                (
                    current.phase == Phase.PREPARED &&
                        next.phase == Phase.PREPARED &&
                        current.recoveryActorId == null &&
                        next.recoveryActorId != null
                    ) ||
                (
                    current.phase == Phase.PREPARED &&
                        next.phase >= Phase.ACCEPTED &&
                        next.recoveryActorId == null
                    )
        return current.identity == next.identity &&
            recoveryActorTransitionAllowed &&
            current.gatewayOrigin == next.gatewayOrigin &&
            current.installationId == next.installationId &&
            MessageDigest.isEqual(
                current.accessSecret.toByteArray(Charsets.US_ASCII),
                next.accessSecret.toByteArray(Charsets.US_ASCII),
            ) &&
            current.clientRevision == next.clientRevision &&
            next.phase.ordinal >= current.phase.ordinal &&
            (current.acceptedAt == null || current.acceptedAt == next.acceptedAt) &&
            (current.accountGeneration == null ||
                current.accountGeneration == next.accountGeneration) &&
            (current.tombstoneId == null || current.tombstoneId == next.tombstoneId) &&
            (current.requestReceiptSha256 == null ||
                current.requestReceiptSha256 == next.requestReceiptSha256) &&
            next.statusRevision >= current.statusRevision &&
            (current.evidenceId == null || sameEvidence || revisionRebase) &&
            (current.completionReceiptSha256 == null ||
                current.completionReceiptSha256 == next.completionReceiptSha256)
    }

    private fun isValidRequestId(requestId: String): Boolean =
        requestId.length in 16..MAX_REQUEST_ID_LENGTH &&
            requestId.all { character ->
                character in 'a'..'z' ||
                    character in 'A'..'Z' ||
                    character in '0'..'9' ||
                    character == '-' ||
                    character == '_'
            }

    private fun isValidAccessSecret(value: String): Boolean =
        ACCESS_SECRET.matches(value) && runCatching {
            val decoded = Base64.getUrlDecoder().decode(value)
            decoded.size == 32 &&
                Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value
        }.getOrDefault(false)

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String {
        val decoded = Base64.getUrlDecoder().decode(value)
        val text = decoded.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(decoded))
        require(encodeText(text) == value)
        return text
    }

    private fun encodeNullableText(value: String?): String =
        value?.let(::encodeText) ?: NULL_VALUE

    private fun decodeNullableText(value: String): String? =
        if (value == NULL_VALUE) null else decodeText(value)

    private fun decodeNullable(value: String): String? =
        value.takeUnless { it == NULL_VALUE }

    private fun decodeNullableLong(value: String): Long? =
        if (value == NULL_VALUE) null else value.toLong()

    private fun validInstant(value: String): Boolean =
        runCatching { java.time.Instant.parse(value) }.isSuccess

    private fun isTrustedRootOrigin(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return false
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
        return uri.rawPath.isNullOrEmpty()
    }

    private fun readExactSingleLink(file: File, expected: ByteArray): Boolean {
        val bytes = readBoundedSingleLink(file, expected.size.toLong()) ?: return false
        return MessageDigest.isEqual(bytes, expected)
    }

    private fun readBoundedSingleLink(file: File, maxBytes: Long): ByteArray? = runCatching {
        val path = file.toPath()
        val before = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!before.isRegularFile || before.size() !in 1L..maxBytes) return@runCatching null
        val links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as? Number
        if (links?.toLong() != 1L) return@runCatching null
        val bytes = ByteArray(before.size().toInt())
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != before.size()) return@runCatching null
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) return@runCatching null
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) return@runCatching null
        }
        val after = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (
            !after.isRegularFile || after.size() != before.size() ||
            after.fileKey() != before.fileKey()
        ) return@runCatching null
        bytes
    }.getOrNull()

    private fun isRegularSingleLink(file: File): Boolean = runCatching {
        val path = file.toPath()
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        val links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as? Number
        attributes.isRegularFile && links?.toLong() == 1L
    }.getOrDefault(false)

    private fun <T> withExclusiveLock(
        unavailable: T,
        action: () -> T,
    ): T {
        if (lockTimeoutNanos <= 0L) return unavailable
        val deadline = System.nanoTime() + lockTimeoutNanos
        var processLockHeld = false
        try {
            val processRemaining = deadline - System.nanoTime()
            if (processRemaining <= 0L ||
                !PROCESS_LOCK.tryLock(
                    processRemaining,
                    TimeUnit.NANOSECONDS,
                )
            ) {
                return unavailable
            }
            processLockHeld = true

            val parent = markerFile.absoluteFile.parentFile
                ?: return unavailable
            if (!ensureDirectory(parent)) return unavailable
            val channel = openExternalLockChannel(parent)
                ?: return unavailable
            try {
                val fileLock = acquireFileLock(channel, deadline)
                    ?: return unavailable
                try {
                    return action()
                } finally {
                    fileLock.release()
                }
            } finally {
                channel.close()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return unavailable
        } catch (_: Exception) {
            return unavailable
        } finally {
            if (processLockHeld) PROCESS_LOCK.unlock()
        }
    }

    private fun openExternalLockChannel(parent: File): FileChannel? {
        val path = File(parent, LOCK_FILE_NAME).toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            return null
        }
        val channel =
            FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            channel.close()
            return null
        }
        return channel
    }

    private fun acquireFileLock(
        channel: FileChannel,
        deadline: Long,
    ): FileLock? {
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return null
            val acquired =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            if (acquired != null) return acquired
            LockSupport.parkNanos(
                min(remaining, FILE_LOCK_RETRY_NANOS),
            )
            if (Thread.currentThread().isInterrupted) return null
        }
    }

    private fun ensureDirectory(directory: File): Boolean =
        try {
            val path = directory.toPath()
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            } else {
                Files.createDirectories(path)
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            }
        } catch (_: Exception) {
            false
        }

    internal companion object {
        const val MARKER_FILE_NAME =
            "account_deletion_fallback_v1.marker"
        const val LOCK_FILE_NAME =
            "account_deletion_fallback_v2.lock"
        const val KEY_RESET_PENDING_FILE_NAME =
            "account_deletion_fallback_key_reset_pending_v1.marker"
        const val LOCK_TIMEOUT_MILLIS = 2_000L
        private const val FORMAT_PREFIX =
            "walksafe-account-deletion-fallback-v4\n"
        private const val LEGACY_FORMAT_PREFIX =
            "walksafe-account-deletion-fallback-v3\n"
        private const val MAX_REQUEST_ID_LENGTH = 128
        private const val MAX_MARKER_BYTES = 8_192L
        private const val FILE_LOCK_RETRY_NANOS = 10_000_000L
        private const val KEY_RESET_PENDING_VALUE = "walksafe-key-reset-pending-v1\n"
        private const val NULL_VALUE = "-"
        private val PAYLOAD_FIELDS = listOf(
            "request-id",
            "actor-sha256",
            "recovery-actor-id",
            "gateway-origin",
            "installation-id",
            "access-secret",
            "client-revision",
            "phase",
            "accepted-at",
            "account-generation",
            "tombstone-id",
            "request-receipt-sha256",
            "status-revision",
            "evidence-id",
            "evidence-sha256",
            "evidence-expected-status-revision",
            "evidence-result",
            "evidence-completed-at",
            "completion-receipt-sha256",
        )
        private val LEGACY_PAYLOAD_FIELDS =
            PAYLOAD_FIELDS.filterNot { it == "recovery-actor-id" }
        private val ACTOR_HASH = Regex("[0-9a-f]{64}")
        private val ACTOR_ID = Regex("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}")
        private val RESERVED_ACTOR_IDS = setOf("unknown", "system", "anonymous")
        private val ACCESS_SECRET = Regex("[A-Za-z0-9_-]{43}")
        private val OPAQUE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val PROCESS_LOCK = ReentrantLock()
        private val MARKER_AAD =
            "kr.co.hanium.dreamup.walksafe|USER|no-backup|account-deletion-fallback|schema=4"
                .toByteArray(Charsets.UTF_8)
        private val KEY_POLICY = AeadKeyPolicy(
            aliasPrefix = "walksafe.user.deletion_fallback.aead.v",
            currentVersion = 1,
            readableVersions = setOf(1),
        )
        private val MARKER_LIMITS = AeadLimits(
            maxPlaintextBytes = 4_096,
            maxCiphertextBytes = 4_112,
            maxEnvelopeChars = MAX_MARKER_BYTES.toInt(),
        )

        private fun legacyRecord(identity: Identity): Record = Record(
            identity = identity,
            gatewayOrigin = "http://127.0.0.1",
            installationId = "legacy-installation",
            accessSecret = "A".repeat(43),
            clientRevision = 1L,
            phase = Phase.PREPARED,
        )
    }
}

internal fun dispatchAndroidAccountDeletionResume(
    journal: AccountDeletionJournal,
    markerPhase: AndroidAccountDeletionFallbackMarker.Phase,
    identityMatches: Boolean,
    rejectIdentityConflict: () -> Unit,
    replayPreparedRequest: () -> Unit,
    purgeAcceptedLocalData: () -> Unit,
    rejectMissingLocalEvidence: () -> Unit,
    submitPendingEvidence: () -> Unit,
    fetchAcknowledgedStatus: () -> Unit,
    cleanupTerminalBinding: () -> Unit,
): Boolean {
    if (!accountDeletionNetworkEntryAllowed(journal)) return false
    if (!identityMatches) {
        rejectIdentityConflict()
        return true
    }
    when (markerPhase) {
        AndroidAccountDeletionFallbackMarker.Phase.PREPARED ->
            replayPreparedRequest()
        AndroidAccountDeletionFallbackMarker.Phase.ACCEPTED ->
            purgeAcceptedLocalData()
        AndroidAccountDeletionFallbackMarker.Phase.LOCAL_PURGED ->
            rejectMissingLocalEvidence()
        AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_PENDING ->
            submitPendingEvidence()
        AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_ACKED ->
            fetchAcknowledgedStatus()
        AndroidAccountDeletionFallbackMarker.Phase.TERMINAL_RECEIPT ->
            cleanupTerminalBinding()
    }
    return true
}

private fun writeAndSyncFile(
    file: File,
    bytes: ByteArray,
) {
    FileChannel.open(
        file.toPath(),
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING,
        LinkOption.NOFOLLOW_LINKS,
    ).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
        channel.force(true)
    }
}

private fun moveAtomicallyFile(
    source: File,
    target: File,
) {
    Files.move(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
    )
}

private fun replaceAtomicallyFile(
    source: File,
    target: File,
) {
    Files.move(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

private fun syncFileFile(file: File) {
    FileChannel.open(
        file.toPath(),
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS,
    ).use {
        it.force(true)
    }
}

private fun syncDirectoryFile(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use {
        it.force(true)
    }
}
