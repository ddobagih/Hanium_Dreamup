package kr.co.hanium.dreamup.walksafe.session

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64

internal sealed interface AccountDeletionIntentAuthorityState {
    data object Absent : AccountDeletionIntentAuthorityState
    data object Confirmed : AccountDeletionIntentAuthorityState
    data class FailClosed(val reason: String) : AccountDeletionIntentAuthorityState
    data object Corrupt : AccountDeletionIntentAuthorityState
    data object Unavailable : AccountDeletionIntentAuthorityState
}

internal interface AccountDeletionIntentAuthority {
    fun read(): AccountDeletionIntentAuthorityState
    fun confirm(): Boolean
    fun failClosed(reason: String): Boolean
    fun clear(): Boolean
}

internal enum class PreparedConfirmationRecoveryResult {
    RECOVERED_CONFIRMED,
    RECONFIRM_REQUIRED,
    RETRY_REQUIRED,
    REJECTED,
}

/**
 * Narrow recovery capability for a caller that has independently verified its durable PREPARED
 * marker. This capability does not inspect or validate that marker itself.
 */
internal interface PreparedAccountDeletionConfirmationRecovery {
    fun recoverPreparedConfirmation(): PreparedConfirmationRecoveryResult
}

/**
 * File authority intended for a dedicated child of Android's no-backup directory.
 *
 * Any surviving temporary file is corrupt during normal startup. Only an explicit
 * [recoverPreparedConfirmation] call may recover the narrowly defined PREPARED confirmation cases.
 * An existing fail-closed final record always preserves its first reason.
 */
internal class FileAccountDeletionIntentAuthority(
    private val directory: File,
    private val directorySync: (File) -> Boolean = ::syncAccountDeletionAuthorityDirectory,
    private val atomicMove: (Path, Path) -> Boolean = ::moveAccountDeletionAuthorityAtomically,
    private val fileSync: (File) -> Boolean = ::syncAccountDeletionAuthorityFile,
) : AccountDeletionIntentAuthority, PreparedAccountDeletionConfirmationRecovery {
    private val finalFile = File(directory, AUTHORITY_FILE_NAME)
    private val temporaryFile = File(directory, "$AUTHORITY_FILE_NAME.tmp")

    override fun read(): AccountDeletionIntentAuthorityState = synchronized(PROCESS_LOCK) {
        inspectLocked().state
    }

    override fun confirm(): Boolean = synchronized(PROCESS_LOCK) {
        when (val current = canonicalizeLocked()) {
            is CanonicalState.Ready -> when (current.state) {
                AccountDeletionIntentAuthorityState.Absent ->
                    writePayloadLocked(CONFIRMED_PAYLOAD)
                AccountDeletionIntentAuthorityState.Confirmed ->
                    verifyFinalAndBarrierLocked(CONFIRMED_PAYLOAD)
                is AccountDeletionIntentAuthorityState.FailClosed,
                AccountDeletionIntentAuthorityState.Corrupt,
                AccountDeletionIntentAuthorityState.Unavailable,
                -> false
            }
            CanonicalState.Blocked -> false
        }
    }

    override fun failClosed(reason: String): Boolean = synchronized(PROCESS_LOCK) {
        val payload = failClosedPayloadOrNull(reason) ?: return@synchronized false
        when (val current = canonicalizeLocked()) {
            is CanonicalState.Ready -> when (current.state) {
                AccountDeletionIntentAuthorityState.Absent,
                AccountDeletionIntentAuthorityState.Confirmed,
                -> writePayloadLocked(payload)
                is AccountDeletionIntentAuthorityState.FailClosed ->
                    verifyFinalAndBarrierLocked(current.payload)
                AccountDeletionIntentAuthorityState.Corrupt,
                AccountDeletionIntentAuthorityState.Unavailable,
                -> false
            }
            CanonicalState.Blocked -> false
        }
    }

    override fun recoverPreparedConfirmation(): PreparedConfirmationRecoveryResult =
        synchronized(PROCESS_LOCK) {
            runCatching {
                recoverPreparedConfirmationLocked()
            }.getOrDefault(PreparedConfirmationRecoveryResult.RETRY_REQUIRED)
        }

    override fun clear(): Boolean = synchronized(PROCESS_LOCK) {
        runCatching {
            when (directoryStateLocked()) {
                DirectoryState.Missing -> if (!ensureDirectoryLocked()) {
                    return@synchronized false
                }
                DirectoryState.Invalid -> return@synchronized false
                DirectoryState.Ready -> Unit
            }
            val files = listOf(finalFile, temporaryFile)
            val structures = files.associateWith(::fileStructure)
            if (structures.values.any { it == FileStructure.Invalid }) {
                return@synchronized false
            }
            val existing = structures.filterValues {
                it == FileStructure.RegularSingleLink
            }.keys
            existing.forEach { Files.delete(it.toPath()) }
            exactAbsentLocked() && durabilityBarrierLocked(file = null) && exactAbsentLocked()
        }.getOrDefault(false)
    }

    private fun recoverPreparedConfirmationLocked(): PreparedConfirmationRecoveryResult {
        when (directoryStateLocked()) {
            DirectoryState.Missing -> {
                if (!ensureDirectoryLocked()) {
                    return PreparedConfirmationRecoveryResult.RETRY_REQUIRED
                }
            }
            DirectoryState.Invalid -> return PreparedConfirmationRecoveryResult.REJECTED
            DirectoryState.Ready -> Unit
        }
        val finalStructure = fileStructure(finalFile)
        val temporaryStructure = fileStructure(temporaryFile)
        if (
            finalStructure == FileStructure.Invalid ||
            temporaryStructure == FileStructure.Invalid
        ) return PreparedConfirmationRecoveryResult.REJECTED

        if (finalStructure == FileStructure.RegularSingleLink) {
            if (temporaryStructure != FileStructure.Missing) {
                return PreparedConfirmationRecoveryResult.REJECTED
            }
            return when (val final = readFileState(finalFile)) {
                is FileState.Valid -> when (final.state) {
                    AccountDeletionIntentAuthorityState.Confirmed ->
                        if (verifyFinalAndBarrierLocked(CONFIRMED_PAYLOAD)) {
                            PreparedConfirmationRecoveryResult.RECOVERED_CONFIRMED
                        } else {
                            PreparedConfirmationRecoveryResult.RETRY_REQUIRED
                        }
                    is AccountDeletionIntentAuthorityState.FailClosed,
                    AccountDeletionIntentAuthorityState.Absent,
                    AccountDeletionIntentAuthorityState.Corrupt,
                    AccountDeletionIntentAuthorityState.Unavailable,
                    -> PreparedConfirmationRecoveryResult.REJECTED
                }
                FileState.Unavailable -> PreparedConfirmationRecoveryResult.RETRY_REQUIRED
                FileState.Invalid,
                FileState.Missing,
                -> PreparedConfirmationRecoveryResult.REJECTED
            }
        }

        if (temporaryStructure == FileStructure.Missing) {
            return absentPreparedRecoveryResultLocked()
        }
        val temporaryBytes = when (
            val temporaryRead = readBoundedRegularFileBytesLocked(temporaryFile)
        ) {
            is PreparedTemporaryRead.Bytes -> temporaryRead.value
            PreparedTemporaryRead.Invalid ->
                return PreparedConfirmationRecoveryResult.REJECTED
        }
        return when {
            MessageDigest.isEqual(temporaryBytes, CONFIRMED_PAYLOAD) -> {
                if (!atomicMove(temporaryFile.toPath(), finalFile.toPath())) {
                    PreparedConfirmationRecoveryResult.RETRY_REQUIRED
                } else if (verifyFinalAndBarrierLocked(CONFIRMED_PAYLOAD)) {
                    PreparedConfirmationRecoveryResult.RECOVERED_CONFIRMED
                } else {
                    PreparedConfirmationRecoveryResult.RETRY_REQUIRED
                }
            }
            temporaryBytes.size < CONFIRMED_PAYLOAD.size &&
                CONFIRMED_PAYLOAD.copyOf(temporaryBytes.size)
                    .contentEquals(temporaryBytes) -> {
                Files.delete(temporaryFile.toPath())
                absentPreparedRecoveryResultLocked()
            }
            else -> PreparedConfirmationRecoveryResult.REJECTED
        }
    }

    private fun absentPreparedRecoveryResultLocked(): PreparedConfirmationRecoveryResult =
        if (
            exactAbsentLocked() &&
            durabilityBarrierLocked(file = null) &&
            exactAbsentLocked()
        ) {
            PreparedConfirmationRecoveryResult.RECONFIRM_REQUIRED
        } else {
            PreparedConfirmationRecoveryResult.RETRY_REQUIRED
        }

    private fun readBoundedRegularFileBytesLocked(file: File): PreparedTemporaryRead {
        if (fileStructure(file) != FileStructure.RegularSingleLink) {
            return PreparedTemporaryRead.Invalid
        }
        val path = file.toPath()
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.size() !in 0L..MAX_AUTHORITY_BYTES.toLong()) {
            return PreparedTemporaryRead.Invalid
        }
        val bytes = ByteArray(attributes.size().toInt())
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) return PreparedTemporaryRead.Invalid
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                return PreparedTemporaryRead.Invalid
            }
        }
        return PreparedTemporaryRead.Bytes(bytes)
    }

    private fun canonicalizeLocked(): CanonicalState {
        val inspection = inspectLocked()
        if (
            inspection.state == AccountDeletionIntentAuthorityState.Corrupt ||
            inspection.state == AccountDeletionIntentAuthorityState.Unavailable
        ) return CanonicalState.Blocked

        if (inspection.final is FileState.Missing) {
            return CanonicalState.Ready(
                AccountDeletionIntentAuthorityState.Absent,
                ByteArray(0),
            )
        }
        val final = inspection.final as? FileState.Valid ?: return CanonicalState.Blocked
        return CanonicalState.Ready(final.state, final.payload)
    }

    private fun writePayloadLocked(payload: ByteArray): Boolean = runCatching {
        if (!ensureDirectoryLocked()) return@runCatching false
        if (fileStructure(temporaryFile) != FileStructure.Missing) {
            return@runCatching false
        }
        if (fileStructure(finalFile) == FileStructure.Invalid) return@runCatching false
        FileChannel.open(
            temporaryFile.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(payload)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        val temporary = readFileState(temporaryFile)
        if (
            temporary !is FileState.Valid ||
            !MessageDigest.isEqual(temporary.payload, payload)
        ) return@runCatching false
        if (!atomicMove(temporaryFile.toPath(), finalFile.toPath())) {
            return@runCatching false
        }
        verifyFinalAndBarrierLocked(payload)
    }.getOrDefault(false)

    private fun verifyFinalAndBarrierLocked(expectedPayload: ByteArray): Boolean =
        exactFinalPayloadLocked(expectedPayload) &&
            durabilityBarrierLocked(finalFile) &&
            exactFinalPayloadLocked(expectedPayload)

    private fun durabilityBarrierLocked(file: File?): Boolean = runCatching {
        if (directoryStateLocked() != DirectoryState.Ready) return@runCatching false
        val parent = directory.parentFile ?: return@runCatching false
        if (directoryState(parent) != DirectoryState.Ready) return@runCatching false
        if (file != null) {
            if (fileStructure(file) != FileStructure.RegularSingleLink) {
                return@runCatching false
            }
            if (!fileSync(file)) return@runCatching false
        }
        directorySync(directory) && directorySync(parent)
    }.getOrDefault(false)

    private fun exactFinalPayloadLocked(expectedPayload: ByteArray): Boolean = runCatching {
        if (directoryStateLocked() != DirectoryState.Ready) return@runCatching false
        val final = readFileState(finalFile)
        final is FileState.Valid &&
            MessageDigest.isEqual(final.payload, expectedPayload) &&
            readFileState(temporaryFile) is FileState.Missing
    }.getOrDefault(false)

    private fun exactAbsentLocked(): Boolean = runCatching {
        val directoryState = directoryStateLocked()
        directoryState == DirectoryState.Missing ||
            directoryState == DirectoryState.Ready &&
            readFileState(finalFile) is FileState.Missing &&
            readFileState(temporaryFile) is FileState.Missing
    }.getOrDefault(false)

    private fun inspectLocked(): Inspection = try {
        when (directoryStateLocked()) {
            DirectoryState.Missing -> Inspection(
                AccountDeletionIntentAuthorityState.Absent,
                FileState.Missing,
                FileState.Missing,
            )
            DirectoryState.Invalid -> Inspection(
                AccountDeletionIntentAuthorityState.Corrupt,
                FileState.Invalid,
                FileState.Invalid,
            )
            DirectoryState.Ready -> {
                val final = readFileState(finalFile)
                val temporary = readFileState(temporaryFile)
                Inspection(resolveState(final, temporary), final, temporary)
            }
        }
    } catch (_: Exception) {
        Inspection(
            AccountDeletionIntentAuthorityState.Unavailable,
            FileState.Unavailable,
            FileState.Unavailable,
        )
    }

    private fun resolveState(
        final: FileState,
        temporary: FileState,
    ): AccountDeletionIntentAuthorityState = when {
        final is FileState.Unavailable || temporary is FileState.Unavailable ->
            AccountDeletionIntentAuthorityState.Unavailable
        final is FileState.Invalid || temporary is FileState.Invalid ->
            AccountDeletionIntentAuthorityState.Corrupt
        temporary !is FileState.Missing ->
            AccountDeletionIntentAuthorityState.Corrupt
        final is FileState.Missing ->
            AccountDeletionIntentAuthorityState.Absent
        final is FileState.Valid -> final.state
        else -> AccountDeletionIntentAuthorityState.Corrupt
    }

    private fun readFileState(file: File): FileState {
        val path = file.toPath()
        when (fileStructure(file)) {
            FileStructure.Missing -> return FileState.Missing
            FileStructure.Invalid -> return FileState.Invalid
            FileStructure.RegularSingleLink -> Unit
        }
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.size() !in 1L..MAX_AUTHORITY_BYTES.toLong()) {
            return FileState.Invalid
        }
        val bytes = ByteArray(attributes.size().toInt())
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) return FileState.Invalid
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) return FileState.Invalid
        }
        val state = parsePayloadOrNull(bytes) ?: return FileState.Invalid
        return FileState.Valid(state, bytes)
    }

    private fun ensureDirectoryLocked(): Boolean {
        when (directoryStateLocked()) {
            DirectoryState.Ready -> return true
            DirectoryState.Invalid -> return false
            DirectoryState.Missing -> Unit
        }
        val parent = directory.parentFile ?: return false
        if (directoryState(parent) != DirectoryState.Ready) return false
        if (!directory.mkdir() || !directorySync(parent)) return false
        return directoryStateLocked() == DirectoryState.Ready
    }

    private fun directoryStateLocked(): DirectoryState = directoryState(directory)

    private fun directoryState(candidate: File): DirectoryState {
        val attributes = try {
            Files.readAttributes(
                candidate.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            return DirectoryState.Missing
        }
        return if (attributes.isDirectory && !attributes.isSymbolicLink) {
            DirectoryState.Ready
        } else {
            DirectoryState.Invalid
        }
    }

    private fun fileStructure(file: File): FileStructure {
        val path = file.toPath()
        val attributes = try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            return FileStructure.Missing
        }
        val links = Files.getAttribute(
            path,
            "unix:nlink",
            LinkOption.NOFOLLOW_LINKS,
        ) as? Number
        return if (
            attributes.isRegularFile &&
            !attributes.isSymbolicLink &&
            links?.toLong() == 1L
        ) {
            FileStructure.RegularSingleLink
        } else {
            FileStructure.Invalid
        }
    }

    private sealed interface CanonicalState {
        data class Ready(
            val state: AccountDeletionIntentAuthorityState,
            val payload: ByteArray,
        ) : CanonicalState
        data object Blocked : CanonicalState
    }

    private data class Inspection(
        val state: AccountDeletionIntentAuthorityState,
        val final: FileState,
        val temporary: FileState,
    )

    private sealed interface FileState {
        data object Missing : FileState
        data class Valid(
            val state: AccountDeletionIntentAuthorityState,
            val payload: ByteArray,
        ) : FileState
        data object Invalid : FileState
        data object Unavailable : FileState
    }

    private sealed interface PreparedTemporaryRead {
        data class Bytes(val value: ByteArray) : PreparedTemporaryRead
        data object Invalid : PreparedTemporaryRead
    }

    private enum class DirectoryState {
        Missing,
        Ready,
        Invalid,
    }

    private enum class FileStructure {
        Missing,
        RegularSingleLink,
        Invalid,
    }

    private companion object {
        const val AUTHORITY_FILE_NAME = "account_deletion_intent_authority_v1"
        const val MAX_AUTHORITY_BYTES = 256
        const val MAX_REASON_BYTES = 96
        const val PAYLOAD_PREFIX = "walksafe.account-deletion-intent-authority.v1"
        val REASON_PATTERN = Regex("[a-z0-9](?:[a-z0-9_:-]{0,95})")
        val CONFIRMED_PAYLOAD = "$PAYLOAD_PREFIX|CONFIRMED\n"
            .toByteArray(StandardCharsets.UTF_8)
        val PROCESS_LOCK = Any()

        fun failClosedPayloadOrNull(reason: String): ByteArray? {
            if (!REASON_PATTERN.matches(reason)) return null
            val reasonBytes = reason.toByteArray(StandardCharsets.UTF_8)
            if (reasonBytes.size !in 1..MAX_REASON_BYTES) return null
            if (strictUtf8OrNull(reasonBytes) != reason) return null
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(reasonBytes)
            return "$PAYLOAD_PREFIX|FAIL_CLOSED|$encoded\n"
                .toByteArray(StandardCharsets.UTF_8)
                .takeIf { it.size <= MAX_AUTHORITY_BYTES }
        }

        fun parsePayloadOrNull(payload: ByteArray): AccountDeletionIntentAuthorityState? {
            if (MessageDigest.isEqual(payload, CONFIRMED_PAYLOAD)) {
                return AccountDeletionIntentAuthorityState.Confirmed
            }
            val text = strictUtf8OrNull(payload) ?: return null
            val prefix = "$PAYLOAD_PREFIX|FAIL_CLOSED|"
            if (!text.startsWith(prefix) || !text.endsWith('\n')) return null
            val encoded = text.substring(prefix.length, text.length - 1)
            if (encoded.isEmpty() || '=' in encoded) return null
            val reasonBytes = runCatching {
                Base64.getUrlDecoder().decode(encoded)
            }.getOrNull() ?: return null
            if (reasonBytes.size !in 1..MAX_REASON_BYTES) return null
            if (
                Base64.getUrlEncoder().withoutPadding().encodeToString(reasonBytes) != encoded
            ) return null
            val reason = strictUtf8OrNull(reasonBytes) ?: return null
            val exact = failClosedPayloadOrNull(reason) ?: return null
            return AccountDeletionIntentAuthorityState.FailClosed(reason)
                .takeIf { MessageDigest.isEqual(payload, exact) }
        }

        fun strictUtf8OrNull(bytes: ByteArray): String? = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }
}

private fun moveAccountDeletionAuthorityAtomically(source: Path, target: Path): Boolean =
    runCatching {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    }.getOrDefault(false)

private fun syncAccountDeletionAuthorityDirectory(directory: File): Boolean = runCatching {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
        channel.force(true)
    }
    true
}.getOrDefault(false)

private fun syncAccountDeletionAuthorityFile(file: File): Boolean = runCatching {
    FileChannel.open(
        file.toPath(),
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS,
    ).use { channel ->
        channel.force(true)
    }
    true
}.getOrDefault(false)
