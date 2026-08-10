package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal enum class AccountDeletionResetPhase {
    STARTED,
    LEGACY_INTENT_STORED,
    GATEWAY_RESET,
    SENSITIVE_RESET,
    PLAIN_RESET,
    FIELD_RESET,
    INTENT_FENCE_CLEARED,
}

internal sealed interface AccountDeletionResetJournalState {
    data object Absent : AccountDeletionResetJournalState
    data class Pending(val phase: AccountDeletionResetPhase) : AccountDeletionResetJournalState
    data object Corrupt : AccountDeletionResetJournalState
}

internal interface AccountDeletionResetJournal {
    fun read(): AccountDeletionResetJournalState
    fun write(phase: AccountDeletionResetPhase): Boolean
    fun clear(): Boolean
}

internal enum class AccountDeletionResetResult {
    NO_PENDING,
    COMPLETED,
    COMPLETED_ELSEWHERE,
    BLOCKED,
}

/**
 * Replays the complete destructive reset while the no-backup journal remains authoritative.
 *
 * Stored phases are diagnostic only. Every resume starts from the first idempotent step, so a
 * forged forward phase cannot skip an old-account purge. The journal is removed only after every
 * participant reports a verified reset.
 */
internal class AccountDeletionResetCoordinator(
    private val journal: AccountDeletionResetJournal,
    private val storeLegacyIntent: () -> Boolean,
    private val resetGateway: () -> Boolean,
    private val resetSensitivePreferences: () -> Boolean,
    private val resetPlainPreferences: () -> Boolean,
    private val resetFieldStorage: () -> Boolean,
    private val clearDeletionIntentFence: () -> Boolean,
) {
    private var observedCompletionEpoch = synchronized(PROCESS_LOCK) { completionEpoch }
    private var retiredAfterExternalCompletion = false

    fun journalState(): AccountDeletionResetJournalState = synchronized(PROCESS_LOCK) {
        journal.read()
    }

    fun hasPendingOrCorruptJournal(): Boolean = synchronized(PROCESS_LOCK) {
        journalState() !is AccountDeletionResetJournalState.Absent
    }

    fun beginAndReconcile(): AccountDeletionResetResult = synchronized(PROCESS_LOCK) {
        completedByAnotherCoordinatorLocked()?.let { return@synchronized it }
        when (journal.read()) {
            AccountDeletionResetJournalState.Absent -> {
                if (!journal.write(AccountDeletionResetPhase.STARTED)) {
                    AccountDeletionResetResult.BLOCKED
                } else {
                    reconcileVerifiedJournal()
                }
            }
            is AccountDeletionResetJournalState.Pending -> reconcileVerifiedJournal()
            AccountDeletionResetJournalState.Corrupt -> {
                if (!journal.write(AccountDeletionResetPhase.STARTED)) {
                    AccountDeletionResetResult.BLOCKED
                } else {
                    reconcileVerifiedJournal()
                }
            }
        }
    }

    fun reconcilePending(): AccountDeletionResetResult = synchronized(PROCESS_LOCK) {
        completedByAnotherCoordinatorLocked()?.let { return@synchronized it }
        when (journal.read()) {
            AccountDeletionResetJournalState.Absent -> AccountDeletionResetResult.NO_PENDING
            is AccountDeletionResetJournalState.Pending -> reconcileVerifiedJournal()
            AccountDeletionResetJournalState.Corrupt -> AccountDeletionResetResult.BLOCKED
        }
    }

    private fun completedByAnotherCoordinatorLocked(): AccountDeletionResetResult? {
        if (retiredAfterExternalCompletion) {
            return AccountDeletionResetResult.COMPLETED_ELSEWHERE
        }
        if (observedCompletionEpoch == completionEpoch) return null
        retiredAfterExternalCompletion = true
        return AccountDeletionResetResult.COMPLETED_ELSEWHERE
    }

    private fun reconcileVerifiedJournal(): AccountDeletionResetResult {
        if (!journal.write(AccountDeletionResetPhase.STARTED)) {
            return AccountDeletionResetResult.BLOCKED
        }
        val steps = listOf(
            AccountDeletionResetPhase.LEGACY_INTENT_STORED to storeLegacyIntent,
            AccountDeletionResetPhase.GATEWAY_RESET to resetGateway,
            AccountDeletionResetPhase.SENSITIVE_RESET to resetSensitivePreferences,
            AccountDeletionResetPhase.PLAIN_RESET to resetPlainPreferences,
            AccountDeletionResetPhase.FIELD_RESET to resetFieldStorage,
            AccountDeletionResetPhase.INTENT_FENCE_CLEARED to clearDeletionIntentFence,
        )
        for ((phase, action) in steps) {
            if (!runCatching(action).getOrDefault(false) || !journal.write(phase)) {
                return AccountDeletionResetResult.BLOCKED
            }
        }
        return if (journal.clear()) {
            check(completionEpoch < Long.MAX_VALUE) { "account deletion reset epoch exhausted" }
            completionEpoch += 1L
            observedCompletionEpoch = completionEpoch
            AccountDeletionResetResult.COMPLETED
        } else {
            AccountDeletionResetResult.BLOCKED
        }
    }

    private companion object {
        val PROCESS_LOCK = Any()
        var completionEpoch = 0L
    }
}

internal class FileAccountDeletionResetJournal(
    private val directory: File,
    private val directorySync: (File) -> Boolean = ::syncResetDirectory,
) : AccountDeletionResetJournal {
    private val journalFile = File(directory, JOURNAL_NAME)
    private val temporaryFile = File(directory, "$JOURNAL_NAME.tmp")

    override fun read(): AccountDeletionResetJournalState {
        if (Files.isSymbolicLink(directory.toPath())) {
            return AccountDeletionResetJournalState.Corrupt
        }
        if (!directory.exists()) return AccountDeletionResetJournalState.Absent
        if (!directory.isDirectory) return AccountDeletionResetJournalState.Corrupt
        val final = readPhase(journalFile)
        val temporary = readPhase(temporaryFile)
        if (final is FileState.Invalid) {
            return AccountDeletionResetJournalState.Corrupt
        }
        val phase = when (final) {
            is FileState.Valid -> final.phase
            FileState.Missing -> when (temporary) {
                is FileState.Valid -> temporary.phase
                FileState.Invalid -> return AccountDeletionResetJournalState.Corrupt
                FileState.Missing -> return AccountDeletionResetJournalState.Absent
            }
            FileState.Invalid -> return AccountDeletionResetJournalState.Corrupt
        }
        return AccountDeletionResetJournalState.Pending(phase)
    }

    override fun write(phase: AccountDeletionResetPhase): Boolean = runCatching {
        if (!ensureDirectory()) return@runCatching false
        if (journalFile.exists() && !isRegularSingleLink(journalFile)) {
            return@runCatching false
        }
        if (temporaryFile.exists() && !isRegularSingleLink(temporaryFile)) {
            return@runCatching false
        }
        val payload = payload(phase)
        FileChannel.open(
            temporaryFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(payload)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(
            temporaryFile.toPath(),
            journalFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        directorySync(directory) &&
            read() == AccountDeletionResetJournalState.Pending(phase)
    }.getOrDefault(false)

    override fun clear(): Boolean = runCatching {
        if (read() !is AccountDeletionResetJournalState.Pending) return@runCatching false
        listOf(journalFile, temporaryFile).forEach { file ->
            if (file.exists()) {
                if (!isRegularSingleLink(file)) return@runCatching false
                Files.delete(file.toPath())
            }
        }
        if (directorySync(directory) && read() is AccountDeletionResetJournalState.Absent) {
            true
        } else {
            write(AccountDeletionResetPhase.INTENT_FENCE_CLEARED)
            false
        }
    }.getOrDefault(false)

    private fun ensureDirectory(): Boolean {
        if (Files.isSymbolicLink(directory.toPath())) return false
        if (!directory.exists()) {
            val parent = directory.parentFile ?: return false
            if (!directory.mkdir() || !directorySync(parent)) return false
        }
        return directory.isDirectory && !Files.isSymbolicLink(directory.toPath())
    }

    private fun readPhase(file: File): FileState {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return FileState.Missing
        if (!isRegularSingleLink(file)) return FileState.Invalid
        return runCatching {
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (attributes.size() !in 1L..MAX_JOURNAL_BYTES.toLong()) {
                return@runCatching FileState.Invalid
            }
            val bytes = ByteArray(attributes.size().toInt())
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) return@runCatching FileState.Invalid
                }
                if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                    return@runCatching FileState.Invalid
                }
            }
            AccountDeletionResetPhase.entries.firstNotNullOfOrNull { phase ->
                phase.takeIf { MessageDigest.isEqual(bytes, payload(it)) }
            }?.let(FileState::Valid) ?: FileState.Invalid
        }.getOrDefault(FileState.Invalid)
    }

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

    private sealed interface FileState {
        data object Missing : FileState
        data class Valid(val phase: AccountDeletionResetPhase) : FileState
        data object Invalid : FileState
    }

    private companion object {
        const val JOURNAL_NAME = "confirmed_account_deletion_reset_v1.journal"
        const val MAX_JOURNAL_BYTES = 128

        fun payload(phase: AccountDeletionResetPhase): ByteArray =
            "walksafe.confirmed-account-deletion-reset.v1|${phase.name}\n"
                .toByteArray(StandardCharsets.UTF_8)
    }
}

private fun syncResetDirectory(directory: File): Boolean = runCatching {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
        channel.force(true)
    }
    true
}.getOrDefault(false)
