package kr.co.hanium.dreamup.walksafe.device

import android.system.Os
import android.system.OsConstants
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

internal enum class DeviceCheckFilePhase { BEFORE_WRITE, AFTER_FILE_SYNC, BEFORE_RENAME, AFTER_RENAME, AFTER_DIRECTORY_SYNC, AFTER_READBACK }

/** Checked publication; AtomicFile.finishWrite's void/log-only failure path is not a receipt. */
internal class AndroidDeviceCheckAuthorityStorage(
    private val directory: File,
    private val aead: LocalAead,
    namespace: String,
    private val fault: (DeviceCheckFilePhase) -> Unit = {},
) : DeviceCheckAuthorityPersistence {
    private val base = File(directory, "state.aead")
    private val pending = File(directory, "state.aead.pending")
    private val aad = "walksafe-device-check-authority-v1:$namespace".toByteArray(Charsets.UTF_8)
    private val limits = AeadLimits(DeviceCheckAuthorityCodec.MAX_BYTES, DeviceCheckAuthorityCodec.MAX_BYTES + 16, 1_048_576)

    override fun read(): String? {
        require(!Files.isSymbolicLink(base.toPath()))
        val bytes = try { FileInputStream(base).use { input ->
            require(input.channel.size() <= limits.maxEnvelopeChars)
            input.readBytes()
        } } catch (error: java.io.FileNotFoundException) {
            // File.exists() cannot distinguish inaccessible from absent; NIO reports the actual error.
            try { Files.readAttributes(base.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java) }
            catch (_: NoSuchFileException) { return null }
            throw error
        }
        require(bytes.size <= limits.maxEnvelopeChars)
        val opened = aead.open(bytes.toString(Charsets.UTF_8), aad, limits)
        check(opened is AeadOpenResult.Opened) { "authority_key_or_ciphertext_blocked" }
        return try { opened.plaintext.toString(Charsets.UTF_8) } finally { opened.plaintext.fill(0) }
    }

    override fun publish(canonicalState: String): DeviceCheckPublication {
        var publicationPossible = false
        return try {
            require(DeviceCheckAuthorityCodec.decode(canonicalState) != null)
            val sealed = aead.seal(canonicalState.toByteArray(Charsets.UTF_8), aad, limits)
            if (sealed !is AeadSealResult.Sealed) return DeviceCheckPublication.NOT_PUBLISHED
            require(!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(pending.toPath()) && !Files.isSymbolicLink(base.toPath()))
            val missing = mutableListOf<File>()
            var cursor: File? = directory
            while (cursor != null && !cursor.exists()) { missing += cursor; cursor = cursor.parentFile }
            Files.createDirectories(directory.toPath())
            // Persist every newly created directory entry in its parent, including the first launch.
            missing.asReversed().forEach { it.parentFile?.let(::syncDirectory) }
            fault(DeviceCheckFilePhase.BEFORE_WRITE)
            FileOutputStream(pending, false).use { output ->
                output.write(sealed.envelope.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            fault(DeviceCheckFilePhase.AFTER_FILE_SYNC)
            fault(DeviceCheckFilePhase.BEFORE_RENAME)
            // A throwing rename may already have published. Never report NotPublished afterward.
            publicationPossible = true
            Files.move(pending.toPath(), base.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            fault(DeviceCheckFilePhase.AFTER_RENAME)
            syncDirectory(directory)
            fault(DeviceCheckFilePhase.AFTER_DIRECTORY_SYNC)
            check(read() == canonicalState) { "authority_readback_mismatch" }
            fault(DeviceCheckFilePhase.AFTER_READBACK)
            DeviceCheckPublication.COMMITTED
        } catch (_: Throwable) {
            if (publicationPossible) DeviceCheckPublication.INDETERMINATE else DeviceCheckPublication.NOT_PUBLISHED
        }
    }

    private fun syncDirectory(file: File) {
        val descriptor = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "authority_sync_target_not_directory" }
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }
}
