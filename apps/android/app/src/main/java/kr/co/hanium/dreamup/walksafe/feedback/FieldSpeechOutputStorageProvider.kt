package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.fieldlog.PersistentFieldSessionLog.SpeechStorageAccess
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONObject

/**
 * One A window on the existing field root/key/profile. Constructing the production provider is
 * I/O-free; its privacy-only field accessor is initialized lazily on the serial storage worker.
 * A window is an operational file sequence, not a field session/ACTIVE pointer/manifest or user ID.
 * All filesystem work shares the existing field PROCESS_LOCK, never a Main/actor lock.
 */
internal class FieldSpeechOutputStorageProvider private constructor(
    accessFactory: () -> SpeechStorageAccess,
) : SpeechOutputEncryptedStorage {
    private val access by lazy(accessFactory)
    private val admissionBlocked = AtomicBoolean()
    private val accountDeletionIntent = AtomicBoolean()
    private var prepared = false
    private var windowNumber = 0L
    private var createdAtMs = 0L
    private var windowBytes = 0L
    private var segmentIndex = 1
    private var segmentBytes = 0L
    private var recordOrdinal = 0L
    private var windowCreated = false

    /** Direct tests inject the same approved field accessor with TestFieldAead, not a new policy. */
    internal constructor(access: SpeechStorageAccess) : this({ access })

    override val aead: LocalAead get() = access.aead
    override val limits: AeadLimits get() = access.recordLimits

    override fun associatedData(): ByteArray {
        if (admissionBlocked.get()) return byteArrayOf()
        return access.withWritableStorage {
            if (!prepareWindow() || admissionBlocked.get()) byteArrayOf()
            else SpeechStorageAccess.recordAad(windowNumber, recordOrdinal)
        } ?: byteArrayOf()
    }

    override fun appendEncrypted(envelope: String): Boolean {
        if (admissionBlocked.get()) return false
        return access.withWritableStorage {
            if (!prepareWindow() || admissionBlocked.get()) return@withWritableStorage false
            try {
                val now = access.nowMillis()
                val profile = access.profile
                if (now < createdAtMs || now - createdAtMs >= profile.maxActiveWindowAgeMs) {
                    admissionBlocked.set(true)
                    access.prepareExistingArchives()
                    return@withWritableStorage false
                }
                val bytes = (envelope + "\n").toByteArray(Charsets.UTF_8)
                if (bytes.size > profile.maxSegmentBytes ||
                    windowBytes + bytes.size > profile.maxWindowBytes
                ) return@withWritableStorage false
                if (!validRecord(envelope, aead, limits, windowNumber, recordOrdinal)) {
                    access.blockCrypto()
                    return@withWritableStorage false
                }
                val artifact = File(access.rootDirectory, ARTIFACT_DIRECTORY)
                val window = File(artifact, windowName(windowNumber))
                if (windowCreated && !Files.exists(window.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    // Another field writer's shared retention may have pruned this window.
                    admissionBlocked.set(true)
                    return@withWritableStorage false
                }
                if ((!Files.exists(artifact.toPath(), LinkOption.NOFOLLOW_LINKS) && !artifact.mkdir()) ||
                    (!windowCreated && !window.mkdir())
                ) {
                    access.blockCrypto()
                    return@withWritableStorage false
                }
                if (!realDirectory(artifact) || !realDirectory(window)) {
                    access.blockCrypto()
                    return@withWritableStorage false
                }
                if (segmentBytes > 0L && segmentBytes + bytes.size > profile.maxSegmentBytes) {
                    segmentIndex += 1
                    segmentBytes = 0L
                }
                if (segmentIndex > 9_999) return@withWritableStorage false
                val segment = File(window, segmentName(segmentIndex))
                val segmentExists = Files.exists(segment.toPath(), LinkOption.NOFOLLOW_LINKS)
                if (segmentExists && (!realSingleLinkFile(segment) || segment.length() != segmentBytes)) {
                    access.blockCrypto()
                    return@withWritableStorage false
                }
                if (!segmentExists && segmentBytes != 0L) {
                    access.blockCrypto()
                    return@withWritableStorage false
                }
                FileChannel.open(segment.toPath(), StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                    StandardOpenOption.CREATE, LinkOption.NOFOLLOW_LINKS).use { output ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) output.write(buffer)
                    output.force(true)
                }
                // Creating a segment changes directory mtime: restore the original window age.
                if (!window.setLastModified(createdAtMs) || !access.syncDirectory(window) ||
                    !access.syncDirectory(artifact) || !access.syncDirectory(access.rootDirectory)
                ) {
                    access.blockCrypto()
                    return@withWritableStorage false
                }
                windowBytes += bytes.size
                segmentBytes += bytes.size
                recordOrdinal += 1L
                val first = !windowCreated
                windowCreated = true
                // A windows consume the existing root's retained slots with DEBUG archives.
                if (first && !access.prepareExistingArchives()) return@withWritableStorage false
                if (!Files.exists(window.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    admissionBlocked.set(true)
                    return@withWritableStorage false
                }
                true
            } catch (_: Exception) {
                access.blockCrypto()
                false
            }
        } ?: false
    }

    /** Admission-only fence: no lazy accessor creation, lock wait, file or keystore I/O. */
    fun blockNewProcessingForAccountDeletion() {
        accountDeletionIntent.set(true)
        admissionBlocked.set(true)
    }

    fun blockForAccountDeletion(): Boolean {
        blockNewProcessingForAccountDeletion()
        return access.blockForAccountDeletion()
    }

    fun blockForRawSourceWithdrawal(): Boolean {
        admissionBlocked.set(true)
        return access.blockForRawSourceWithdrawal()
    }

    override fun purgeAll(): Boolean {
        blockNewProcessingForAccountDeletion()
        return access.purgeAll()
    }

    /** RAW cleanup preserves prior/queued account intent and never revives this old window. */
    fun purgeForRawSourceWithdrawal(): Boolean {
        admissionBlocked.set(true)
        return access.purgeForRawSourceWithdrawal(accountDeletionIntent::get)
    }

    /** Callback is a short memory authority check, with no Main/actor lock or I/O. */
    fun resetAfterConfirmedRawConsent(accountDeletionActive: () -> Boolean): Boolean =
        access.resetAfterConfirmedRawConsent {
            accountDeletionIntent.get() || accountDeletionActive()
        }

    /** These return actual field reset results; they never revive this provider/window. */
    fun resetRawSourceAfterConfirmedConsent(): Boolean = access.resetRawSourceAfterConfirmedConsent()
    fun resetForNewEnrollment(): Boolean = access.resetForNewEnrollment()

    private fun prepareWindow(): Boolean {
        if (prepared) return true
        if (!access.prepareExistingArchives()) return false
        val artifact = File(access.rootDirectory, ARTIFACT_DIRECTORY)
        val windows = if (Files.exists(artifact.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            readValidWindows(artifact, aead, limits, access.profile) ?: run {
                access.blockCrypto()
                return false
            }
        } else emptyList()
        val created = access.nowMillis()
        if (created < 0L) return false
        // Both reads and reservation run under the existing field PROCESS_LOCK. Another provider
        // may have prepared its AAD without creating a file yet; keep one bounded process scalar.
        val latest = maxOf(windows.maxOfOrNull(ArtifactWindow::number) ?: 0L,
            processWindowHighWatermark)
        if (latest >= MAX_WINDOW_NUMBER) return false
        windowNumber = latest + 1L
        createdAtMs = created
        processWindowHighWatermark = windowNumber
        prepared = true
        return true
    }

    internal data class ArtifactWindow(
        val directory: File,
        val number: Long,
        val createdAtMs: Long,
    )

    companion object {
        const val ARTIFACT_DIRECTORY = "speech-output"
        private const val MAX_WINDOW_NUMBER = 9_999_999_999_999_999L
        // Guarded by the existing field PROCESS_LOCK through withWritableStorage; never reset on
        // purge/retention, so still-reserved lifetimes cannot collide with a fresh window (ABA).
        private var processWindowHighWatermark = 0L
        private val WINDOW = Regex("window-([0-9]{16})")
        private val SEGMENT = Regex("records-([0-9]{4})\\.jsonl")

        fun create(
            appFilesDirectory: File,
            initiallyBlockedForAccountDeletion: Boolean = false,
            initiallyBlockedForRawSourceCollection: Boolean = false,
        ): FieldSpeechOutputStorageProvider = FieldSpeechOutputStorageProvider {
            SpeechStorageAccess.create(appFilesDirectory, initiallyBlockedForAccountDeletion,
                initiallyBlockedForRawSourceCollection)
        }

        /** Exact recognition only. Unknown/empty dirs, wrong files, links, failed auth fail closed. */
        fun readValidWindows(
            artifact: File,
            aead: LocalAead,
            limits: AeadLimits,
            profile: SpeechStorageAccess.Profile,
        ): List<ArtifactWindow>? {
            return runCatching {
            if (artifact.name != ARTIFACT_DIRECTORY || !realDirectory(artifact)) return null
            val directories = artifact.listFiles()?.toList() ?: return null
            if (directories.isEmpty() || directories.size > profile.maxRetainedWindows + 1) return null
            directories.map { directory ->
                val number = WINDOW.matchEntire(directory.name)?.groupValues?.get(1)?.toLongOrNull()
                    ?.takeIf { it > 0L && it <= MAX_WINDOW_NUMBER } ?: return null
                if (!realDirectory(directory)) return null
                val segments = directory.listFiles()?.sortedBy(File::getName) ?: return null
                if (segments.isEmpty()) return null
                var ordinal = 0L
                var totalBytes = 0L
                segments.forEachIndexed { index, segment ->
                    val sequence = SEGMENT.matchEntire(segment.name)?.groupValues?.get(1)?.toIntOrNull()
                    if (sequence != index + 1 || !realSingleLinkFile(segment)) return null
                    val bytes = segment.length()
                    if (bytes <= 0L || bytes > profile.maxSegmentBytes) return null
                    totalBytes += bytes
                    if (totalBytes > profile.maxWindowBytes) return null
                    RandomAccessFile(segment, "r").use { file ->
                        file.seek(bytes - 1L)
                        if (file.read() != '\n'.code) return null
                    }
                    segment.bufferedReader().use { reader ->
                        while (true) {
                            val envelope = reader.readLine() ?: break
                            if (!validRecord(envelope, aead, limits, number, ordinal)) return null
                            ordinal += 1L
                        }
                    }
                }
                val anchor = directory.lastModified()
                if (anchor < 0L) return null
                ArtifactWindow(directory, number, anchor)
            }
            }.getOrNull()
        }

        private fun validRecord(envelope: String, aead: LocalAead, limits: AeadLimits,
            window: Long, ordinal: Long): Boolean {
            val opened = aead.open(envelope, SpeechStorageAccess.recordAad(window, ordinal), limits)
                as? AeadOpenResult.Opened ?: return false
            return try {
                val record = JSONObject(String(opened.plaintext, Charsets.UTF_8))
                val elapsed = record.opt("elapsed_realtime_ms")
                val result = record.opt("result")
                record.keys().asSequence().toSet() ==
                    setOf("metric", "state", "elapsed_realtime_ms", "result") &&
                    record.opt("metric") in SpeechOutputKind.values().map(SpeechOutputKind::name) &&
                    record.opt("state") in SpeechOutputOutcome.values().map(SpeechOutputOutcome::name) &&
                    ((elapsed is Long && elapsed >= 0L) || (elapsed is Int && elapsed >= 0)) &&
                    (result is Int || result == "NONE")
            } catch (_: Exception) { false }
            finally { opened.plaintext.fill(0) }
        }

        private fun windowName(number: Long): String = String.format(Locale.US, "window-%016d", number)
        private fun segmentName(index: Int): String = String.format(Locale.US, "records-%04d.jsonl", index)

        private fun realDirectory(file: File): Boolean = runCatching {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS).isDirectory
        }.getOrDefault(false)

        private fun realSingleLinkFile(file: File): Boolean = runCatching {
            val path = file.toPath()
            Files.readAttributes(path, BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS).isRegularFile &&
                (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as? Number)
                    ?.toLong() == 1L
        }.getOrDefault(false)
    }
}
