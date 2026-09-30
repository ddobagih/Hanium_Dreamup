package kr.co.hanium.dreamup.walksafe.voice

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** Copies the signed-APK model assets once into private storage for the native Vosk runtime. */
internal object BundledVoskModelInstaller {
    private const val ASSET_ROOT = "voice-models/vosk-model-small-ko-0.22"
    private const val MANIFEST_NAME = "MODEL_FILES.sha256"
    private const val READY_NAME = ".verified-model"
    private const val STAGING_PREFIX = ".install-"
    private const val MODEL_DIRECTORY_PREFIX = "vosk-model-small-ko-0.22-"
    private val STALE_STAGING_NAME = Regex("\\.install-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val STALE_MODEL_NAME = Regex("vosk-model-small-ko-0\\.22-[0-9a-f]{16}")

    fun bundledModelAvailable(context: Context): Boolean = runCatching {
        context.assets.open("$ASSET_ROOT/$MANIFEST_NAME").use { input ->
            input.read() >= 0
        }
    }.getOrDefault(false)

    fun installedModelOrNull(context: Context): File? {
        val manifest = runCatching { readManifest(context) }.getOrNull() ?: return null
        return installedModelOrNull(File(context.noBackupFilesDir, "voice-models"), manifest)
    }

    internal fun installedModelOrNull(parent: File, manifest: BundledVoskModelManifest): File? {
        val target = targetDirectory(parent, manifest.manifestSha256)
        val ready = File(target, READY_NAME)
        if (
            Files.isSymbolicLink(parent.toPath()) ||
            Files.isSymbolicLink(target.toPath()) ||
            !hasNoSymlinkComponents(target, READY_NAME) ||
            !target.isDirectory ||
            ready.readTextOrNull()?.trim() != manifest.manifestSha256 ||
            !hasRequiredModelFiles(target) || !validateInstalledVoskFiles(target, manifest, verifyHashes = true)
        ) return null
        return target
    }

    @Synchronized
    @Throws(IOException::class)
    fun install(context: Context): File {
        val manifest = readManifest(context)
        val parent = File(context.noBackupFilesDir, "voice-models")
        return install(parent, manifest) { relativePath -> context.assets.open("$ASSET_ROOT/$relativePath") }
    }

    @Synchronized
    @Throws(IOException::class)
    internal fun install(
        parent: File,
        manifest: BundledVoskModelManifest,
        openAsset: (String) -> InputStream,
    ): File {
        installedModelOrNull(parent, manifest)?.let { return it }
        if (Files.isSymbolicLink(parent.toPath())) {
            throw IOException("Private voice model directory is a symbolic link")
        }
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create private voice model directory")
        }
        val target = targetDirectory(parent, manifest.manifestSha256)
        removeStaleInstallArtifacts(parent, keep = target)
        val staging = File(parent, "$STAGING_PREFIX${UUID.randomUUID()}")
        if (!staging.mkdir()) throw IOException("Cannot create voice model staging directory")
        try {
            manifest.files.forEach { entry ->
                copyAndVerifyAsset(openAsset, entry, staging)
            }
            openAsset(MANIFEST_NAME).use { input ->
                FileOutputStream(File(staging, MANIFEST_NAME)).use(input::copyTo)
            }
            if (!hasRequiredModelFiles(staging)) {
                throw IOException("Prepared voice model is incomplete")
            }
            File(staging, READY_NAME).writeText(manifest.manifestSha256)

            if (Files.isSymbolicLink(parent.toPath())) {
                throw IOException("Private voice model directory became a symbolic link")
            }
            ensureNoSymlinks(target)
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Cannot replace invalid private voice model")
            }
            if (!staging.renameTo(target)) {
                throw IOException("Cannot publish private voice model atomically")
            }
            return target
        } finally {
            if (staging.exists() && runCatching { ensureNoSymlinks(staging) }.isSuccess) {
                staging.deleteRecursively()
            }
        }
    }

    private fun readManifest(context: Context): BundledVoskModelManifest {
        val bytes = context.assets.open("$ASSET_ROOT/$MANIFEST_NAME").use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(4 * 1_024)
                var total = 0
                while (total <= MAX_VOSK_MANIFEST_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    total += read
                }
                output.toByteArray()
            }
        }
        return parseBundledVoskModelManifest(bytes)
    }

    /** Called on the decoder loader, never on the UI lookup path. Asset format is unchanged. */
    fun verifyOrRepairInstalledModel(context: Context, directory: File): File {
        val parent = File(context.noBackupFilesDir, "voice-models")
        if (directory.parentFile?.canonicalFile != parent.canonicalFile) return directory
        val manifest = readManifest(context)
        if (directory.canonicalFile != targetDirectory(parent, manifest.manifestSha256).canonicalFile) return directory
        return install(context)
    }

    private fun copyAndVerifyAsset(
        openAsset: (String) -> InputStream,
        entry: BundledVoskModelFile,
        staging: File,
    ) {
        val destination = File(staging, entry.relativePath)
        val stagingPrefix = staging.canonicalPath + File.separator
        if (!hasNoSymlinkComponents(staging, entry.relativePath) ||
            !destination.canonicalPath.startsWith(stagingPrefix)
        ) {
            throw IOException("Voice model path escaped staging")
        }
        val parent = destination.parentFile ?: throw IOException("Voice model parent is missing")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create voice model subdirectory")
        }
        if (!hasNoSymlinkComponents(staging, entry.relativePath)) {
            throw IOException("Voice model staging path became a symbolic link")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        openAsset(entry.relativePath).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        throw IOException("Voice model installation was interrupted")
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        if (digest.digest().toHex() != entry.sha256) {
            throw IOException("Voice model asset digest mismatch")
        }
    }

    private fun targetDirectory(parent: File, manifestSha256: String): File =
        File(
            parent,
            "$MODEL_DIRECTORY_PREFIX${manifestSha256.take(16)}",
        )

    /**
     * A killed install leaves its staging directory behind, and a new APK manifest publishes a
     * new model directory. Both names are produced only by this installer, which runs one install
     * at a time, and assets cannot change within a process, so only [keep] can be in use. Other
     * names in the private directory are left alone; links are removed without being followed.
     */
    private fun removeStaleInstallArtifacts(parent: File, keep: File) {
        val children = parent.listFiles() ?: return
        children.forEach { child ->
            val stale = STALE_STAGING_NAME.matches(child.name) ||
                (STALE_MODEL_NAME.matches(child.name) && child.name != keep.name)
            if (stale) runCatching { deleteTreeWithoutFollowingLinks(child.toPath()) }
        }
    }

    private fun deleteTreeWithoutFollowingLinks(root: Path) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(root)
            return
        }
        // walkFileTree does not follow links by default: a link is visited, and deleted, as a file.
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun hasRequiredModelFiles(directory: File): Boolean =
        listOf(
            "am/final.mdl",
            "conf/mfcc.conf",
            "conf/model.conf",
            "graph/Gr.fst",
            "graph/HCLr.fst",
        ).all { relative ->
            hasNoSymlinkComponents(directory, relative) &&
                File(directory, relative).let { it.isFile && it.length() > 0L }
        }

    private fun ensureNoSymlinks(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            throw IOException("Voice model contains a symbolic link")
        }
        if (!file.exists() || !file.isDirectory) return
        val children = file.listFiles() ?: throw IOException("Cannot inspect voice model directory")
        children.forEach(::ensureNoSymlinks)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

}

private fun hasNoSymlinkComponents(root: File, relativePath: String): Boolean {
    if (Files.isSymbolicLink(root.toPath())) return false
    var current = root
    for (component in relativePath.split('/')) {
        if (component.isEmpty() || component == "." || component == "..") return false
        current = File(current, component)
        if (Files.isSymbolicLink(current.toPath())) return false
    }
    return true
}

internal fun validateInstalledVoskFiles(
    directory: File,
    manifest: BundledVoskModelManifest,
    verifyHashes: Boolean,
): Boolean {
    return try {
    if (Files.isSymbolicLink(directory.toPath())) return false
    val root = directory.canonicalPath + File.separator
    manifest.files.all { entry ->
        val file = File(directory, entry.relativePath)
        if (!hasNoSymlinkComponents(directory, entry.relativePath) ||
            !file.canonicalPath.startsWith(root) || !file.isFile || file.length() <= 0L
        ) {
            false
        } else if (!verifyHashes) {
            true
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Voice model validation interrupted")
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == entry.sha256
        }
    }
} catch (_: Exception) {
    false
}
}
