package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Comparator

/**
 * S21-50: local stores of debug-only collection paths that are not owned by another purge.
 * The outdoor position test (debug APK only) keeps encrypted position sessions and plaintext raw
 * GNSS measurement files under this directory; a full account deletion must remove them too.
 */
object DebugCollectionArtifacts {
    const val OUTDOOR_POSITION_TEST_DIRECTORY = "outdoor_position_test"

    /**
     * Deletes the outdoor position test store under [noBackupRoot] without following symbolic
     * links. True only when nothing is left; an absent store is already purged.
     */
    fun purgeOutdoorPositionTest(noBackupRoot: File): Boolean = runCatching {
        val directory = File(noBackupRoot, OUTDOOR_POSITION_TEST_DIRECTORY).toPath()
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return@runCatching true
        if (Files.isSymbolicLink(directory)) {
            Files.delete(directory)
        } else {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
        !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(false)
}
