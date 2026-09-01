package kr.co.hanium.dreamup.walksafe.voice

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Copies the signed-APK model assets once into private storage for the native Vosk runtime. */
internal object BundledVoskModelInstaller {
    private const val ASSET_ROOT = "voice-models/vosk-model-small-ko-0.22"
    private const val MANIFEST_NAME = "MODEL_FILES.sha256"
    private const val READY_NAME = ".verified-model"

    fun bundledModelAvailable(context: Context): Boolean = runCatching {
        context.assets.open("$ASSET_ROOT/$MANIFEST_NAME").use { input ->
            input.read() >= 0
        }
    }.getOrDefault(false)

    fun installedModelOrNull(context: Context): File? {
        val manifest = runCatching { readManifest(context) }.getOrNull() ?: return null
        val target = targetDirectory(context, manifest.manifestSha256)
        val ready = File(target, READY_NAME)
        if (
            !target.isDirectory ||
            ready.readTextOrNull()?.trim() != manifest.manifestSha256 ||
            !hasRequiredModelFiles(target)
        ) return null
        return target
    }

    @Synchronized
    @Throws(IOException::class)
    fun install(context: Context): File {
        val manifest = readManifest(context)
        installedModelOrNull(context)?.let { return it }

        val parent = File(context.noBackupFilesDir, "voice-models")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create private voice model directory")
        }
        val target = targetDirectory(context, manifest.manifestSha256)
        val staging = File(parent, ".install-${UUID.randomUUID()}")
        if (!staging.mkdir()) throw IOException("Cannot create voice model staging directory")
        try {
            manifest.files.forEach { entry ->
                copyAndVerifyAsset(context, entry, staging)
            }
            context.assets.open("$ASSET_ROOT/$MANIFEST_NAME").use { input ->
                FileOutputStream(File(staging, MANIFEST_NAME)).use(input::copyTo)
            }
            if (!hasRequiredModelFiles(staging)) {
                throw IOException("Prepared voice model is incomplete")
            }
            File(staging, READY_NAME).writeText(manifest.manifestSha256)

            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Cannot replace invalid private voice model")
            }
            if (!staging.renameTo(target)) {
                throw IOException("Cannot publish private voice model atomically")
            }
            return target
        } finally {
            if (staging.exists()) staging.deleteRecursively()
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

    private fun copyAndVerifyAsset(
        context: Context,
        entry: BundledVoskModelFile,
        staging: File,
    ) {
        val destination = File(staging, entry.relativePath)
        val stagingPrefix = staging.canonicalPath + File.separator
        if (!destination.canonicalPath.startsWith(stagingPrefix)) {
            throw IOException("Voice model path escaped staging")
        }
        val parent = destination.parentFile ?: throw IOException("Voice model parent is missing")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create voice model subdirectory")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open("$ASSET_ROOT/${entry.relativePath}").use { input ->
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

    private fun targetDirectory(context: Context, manifestSha256: String): File =
        File(
            File(context.noBackupFilesDir, "voice-models"),
            "vosk-model-small-ko-0.22-${manifestSha256.take(16)}",
        )

    private fun hasRequiredModelFiles(directory: File): Boolean =
        listOf(
            "am/final.mdl",
            "conf/mfcc.conf",
            "conf/model.conf",
            "graph/Gr.fst",
            "graph/HCLr.fst",
        ).all { relative -> File(directory, relative).isFile }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

}
