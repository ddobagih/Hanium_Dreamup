package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kr.co.hanium.dreamup.walksafe.security.LocalAead

/** Called after the serial diagnostic writer's ACK, including startup deletion-journal recovery. */
internal class GuidanceDiagnosticLocalStorePurge(
    private val directory: File,
    private val aead: LocalAead,
    private val syncDirectory: (File) -> Boolean,
    private val deleteFile: (File) -> Boolean = { it.delete() },
) {
    fun purgeAndRotate(): Boolean = synchronized(LOCK) {
        val filesCleared = runCatching {
            require(!Files.isSymbolicLink(directory.toPath()))
            if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(directory.isDirectory)
                val files = requireNotNull(directory.listFiles())
                require(files.all { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    (Regex("[a-f0-9]{64}\\.aead").matches(it.name) || Regex("\\.diagnostic-[A-Za-z0-9_-]+\\.tmp").matches(it.name)) })
                files.forEach { check(deleteFile(it)) }
                check(directory.listFiles()?.isEmpty() == true)
                check(syncDirectory(directory))
            }
            true
        }.getOrDefault(false)
        // Erase old decryption keys even when file cleanup failed; failure still prevents DELETED.
        val keysCleared = runCatching { aead.destroyKnownVersions() }.getOrDefault(false)
        filesCleared && keysCleared && runCatching { aead.createFreshAfterVerifiedPurge() }.getOrDefault(false)
    }
    private companion object { val LOCK = Any() }
}
