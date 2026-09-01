package kr.co.hanium.dreamup.walksafe.voice

import java.io.IOException
import java.security.MessageDigest

internal data class BundledVoskModelManifest(
    val manifestSha256: String,
    val files: List<BundledVoskModelFile>,
)

internal data class BundledVoskModelFile(
    val sha256: String,
    val relativePath: String,
)

internal fun parseBundledVoskModelManifest(bytes: ByteArray): BundledVoskModelManifest {
    if (bytes.isEmpty() || bytes.size > MAX_VOSK_MANIFEST_BYTES) {
        throw IOException("Voice model manifest has an invalid size")
    }
    val files = bytes.toString(Charsets.UTF_8)
        .lineSequence()
        .filter(String::isNotBlank)
        .map(::parseManifestLine)
        .toList()
    if (files.isEmpty() || files.size > MAX_VOSK_MODEL_FILES) {
        throw IOException("Voice model manifest has an invalid file count")
    }
    if (files.map(BundledVoskModelFile::relativePath).toSet().size != files.size) {
        throw IOException("Voice model manifest has duplicate paths")
    }
    return BundledVoskModelManifest(
        manifestSha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
        files = files,
    )
}

private fun parseManifestLine(line: String): BundledVoskModelFile {
    val separator = line.indexOf("  ")
    if (separator != 64 || line.length <= separator + 2) {
        throw IOException("Voice model manifest line is malformed")
    }
    val digest = line.substring(0, separator).lowercase()
    if (!digest.matches(Regex("[0-9a-f]{64}"))) {
        throw IOException("Voice model digest is malformed")
    }
    val relativePath = line.substring(separator + 2)
    if (!isSafeRelativePath(relativePath) || relativePath == "MODEL_FILES.sha256") {
        throw IOException("Voice model path is unsafe")
    }
    return BundledVoskModelFile(digest, relativePath)
}

private fun isSafeRelativePath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/') || '\\' in path || path.length > 240) return false
    return path.split('/').all { segment ->
        segment.isNotBlank() &&
            segment !in setOf(".", "..") &&
            segment.none(Char::isISOControl)
    }
}

internal const val MAX_VOSK_MANIFEST_BYTES = 32 * 1_024
private const val MAX_VOSK_MODEL_FILES = 128

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
