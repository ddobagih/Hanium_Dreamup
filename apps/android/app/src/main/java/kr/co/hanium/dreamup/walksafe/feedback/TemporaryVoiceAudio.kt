package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File

/** Call once at process start, before any recorder/player can own a temporary audio file. */
internal fun clearPreviousProcessVoiceAudio(cacheDirectory: File): Boolean {
    return try {
        val files = cacheDirectory.listFiles() ?: return false
        val audioName = Regex("(?:walksafe-command-[A-Za-z0-9_-]+\\.m4a|walksafe-(?:voice|korean-tts)-[A-Za-z0-9_-]+\\.wav)")
        var cleared = true
        for (file in files) {
            if (file.isFile && audioName.matches(file.name) && !file.delete()) cleared = false
        }
        cleared
    } catch (_: SecurityException) {
        false
    }
}
