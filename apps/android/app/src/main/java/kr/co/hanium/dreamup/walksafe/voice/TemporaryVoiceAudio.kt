package kr.co.hanium.dreamup.walksafe.voice

import java.io.File
import java.nio.file.Files

/** Only process startup calls this, before any recorder/player is created. */
internal object TemporaryVoiceAudio {
    data class Cleanup(val removed: Int, val failures: Int)
    private val ownedName = Regex("(?:walksafe-command-.+\\.m4a|walksafe-voice-.+\\.wav|walksafe-korean-tts-.+\\.wav)")

    fun cleanupAtProcessStart(cacheRoot: File, activeFiles: Set<File> = emptySet()): Cleanup {
        val root = runCatching { cacheRoot.canonicalFile }.getOrNull() ?: return Cleanup(0, 1)
        if (!root.isDirectory || Files.isSymbolicLink(cacheRoot.toPath())) return Cleanup(0, 1)
        val protected = activeFiles.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.toSet()
        val children = root.listFiles() ?: return Cleanup(0, 1)
        var removed = 0
        var failures = 0
        children.forEach { child ->
            if (!ownedName.matches(child.name) || !child.isFile || Files.isSymbolicLink(child.toPath())) return@forEach
            val canonical = runCatching { child.canonicalFile }.getOrNull()
            if (canonical == null) { failures++; return@forEach }
            if (canonical.parentFile != root || canonical in protected) return@forEach
            if (child.delete()) removed++ else failures++
        }
        return Cleanup(removed, failures)
    }
}
