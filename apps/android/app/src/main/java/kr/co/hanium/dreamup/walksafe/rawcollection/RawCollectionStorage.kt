package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Comparator

/** Each write method is a durable temp-write + fsync + atomic-rename transaction. */
internal interface RawCollectionStorage {
    fun readActiveCollectionId(): String?
    fun writeActiveCollectionIdAtomically(collectionId: String): Boolean
    fun clearActiveCollectionId(): Boolean
    fun readManifest(collectionId: String): String?
    fun writeManifestAtomically(collectionId: String, envelope: String): Boolean
    fun readChunk(collectionId: String, ordinal: Int): String?
    fun writeChunkAtomically(collectionId: String, ordinal: Int, envelope: String): Boolean
    fun deleteChunk(collectionId: String, ordinal: Int): Boolean
    fun listCollectionIds(): Set<String>
    fun deleteCollection(collectionId: String): Boolean
    fun deleteAllCollections(): Boolean
    fun verifiedEmptyAfterAccountPurge(): Boolean
    fun hasFence(name: String): Boolean
    fun readFence(name: String): String?
    fun writeFenceAtomically(name: String, value: String): Boolean
    fun listFenceNames(): Set<String>
    fun clearAllFences(): Boolean
}

internal class FileRawCollectionStorage(
    rootDirectory: File,
) : RawCollectionStorage {
    private val root = File(rootDirectory, ROOT_DIRECTORY_NAME)
    private val collections = File(root, COLLECTIONS_DIRECTORY_NAME)
    private val fences = File(root, FENCES_DIRECTORY_NAME)
    private val activePointer = File(root, ACTIVE_POINTER_NAME)

    @Synchronized
    override fun readActiveCollectionId(): String? = readBounded(activePointer, 64L)
        ?.trim()
        ?.takeIf(::isCanonicalUuid)

    @Synchronized
    override fun writeActiveCollectionIdAtomically(collectionId: String): Boolean =
        isCanonicalUuid(collectionId) && atomicWrite(activePointer, "$collectionId\n")

    @Synchronized
    override fun clearActiveCollectionId(): Boolean = deleteFileAndSync(activePointer)

    @Synchronized
    override fun readManifest(collectionId: String): String? =
        collectionDirectoryOrNull(collectionId)?.let { directory ->
            readBounded(File(directory, MANIFEST_FILE_NAME), MAX_MANIFEST_ENVELOPE_BYTES)
        }

    @Synchronized
    override fun writeManifestAtomically(collectionId: String, envelope: String): Boolean {
        if (envelope.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_ENVELOPE_BYTES) return false
        val directory = collectionDirectoryOrNull(collectionId) ?: return false
        return atomicWrite(File(directory, MANIFEST_FILE_NAME), envelope)
    }

    @Synchronized
    override fun readChunk(collectionId: String, ordinal: Int): String? =
        chunkFileOrNull(collectionId, ordinal)?.let { readBounded(it, MAX_CHUNK_ENVELOPE_BYTES) }

    @Synchronized
    override fun writeChunkAtomically(
        collectionId: String,
        ordinal: Int,
        envelope: String,
    ): Boolean {
        if (envelope.toByteArray(Charsets.UTF_8).size > MAX_CHUNK_ENVELOPE_BYTES) return false
        val target = chunkFileOrNull(collectionId, ordinal) ?: return false
        return atomicWrite(target, envelope)
    }

    @Synchronized
    override fun deleteChunk(collectionId: String, ordinal: Int): Boolean =
        chunkFileOrNull(collectionId, ordinal)?.let(::deleteFileAndSync) ?: false

    @Synchronized
    override fun listCollectionIds(): Set<String> = collections
        .listFiles { file -> file.isDirectory && isCanonicalUuid(file.name) }
        .orEmpty()
        .mapTo(linkedSetOf(), File::getName)

    @Synchronized
    override fun deleteCollection(collectionId: String): Boolean {
        val directory = collectionDirectoryOrNull(collectionId) ?: return false
        if (!directory.exists()) return true
        val deleted = runCatching {
            Files.walk(directory.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
            syncDirectory(collections)
            true
        }.getOrDefault(false)
        return deleted
    }

    @Synchronized
    override fun deleteAllCollections(): Boolean {
        if (!collections.exists()) return true
        val artifacts = collections.listFiles() ?: return false
        return artifacts.map(::deleteCollectionArtifact).all { it }
    }

    @Synchronized
    override fun verifiedEmptyAfterAccountPurge(): Boolean {
        if (!root.isDirectory || activePointer.exists()) return false
        val rootArtifacts = root.listFiles() ?: return false
        if (rootArtifacts.any { it.name !in EXPECTED_RESET_ROOT_ARTIFACTS }) return false
        if (collections.exists()) {
            if (!collections.isDirectory) return false
            val collectionArtifacts = collections.list() ?: return false
            if (collectionArtifacts.isNotEmpty()) return false
        }
        return !fences.exists() || fences.isDirectory
    }

    @Synchronized
    override fun hasFence(name: String): Boolean =
        fenceFileOrNull(name)?.isFile == true

    @Synchronized
    override fun readFence(name: String): String? = fenceFileOrNull(name)
        ?.let { readBounded(it, MAX_FENCE_BYTES) }
        ?.trim()

    @Synchronized
    override fun writeFenceAtomically(name: String, value: String): Boolean {
        if (!FENCE_NAME.matches(name) || !FENCE_VALUE.matches(value)) return false
        val target = fenceFileOrNull(name) ?: return false
        return atomicWrite(target, "$value\n")
    }

    @Synchronized
    override fun listFenceNames(): Set<String> = fences
        .listFiles { file -> file.isFile && file.name.endsWith(FENCE_SUFFIX) }
        .orEmpty()
        .mapNotNullTo(linkedSetOf()) { file ->
            file.name.removeSuffix(FENCE_SUFFIX).takeIf(FENCE_NAME::matches)
        }

    @Synchronized
    override fun clearAllFences(): Boolean {
        if (!fences.exists()) return true
        val artifacts = fences.listFiles() ?: return false
        if (artifacts.any { file ->
                !file.isFile ||
                    !file.name.endsWith(FENCE_SUFFIX) ||
                    !FENCE_NAME.matches(file.name.removeSuffix(FENCE_SUFFIX))
            }
        ) return false
        return artifacts.map(::deleteFileAndSync).all { it }
    }

    private fun collectionDirectoryOrNull(collectionId: String): File? =
        collectionId.takeIf(::isCanonicalUuid)?.let { File(collections, it) }

    private fun chunkFileOrNull(collectionId: String, ordinal: Int): File? {
        if (ordinal !in 0 until RAW_MAX_CHUNKS) return null
        val directory = collectionDirectoryOrNull(collectionId) ?: return null
        return File(directory, "chunk-%04d.aead".format(ordinal))
    }

    private fun fenceFileOrNull(name: String): File? =
        name.takeIf(FENCE_NAME::matches)?.let { File(fences, "$it$FENCE_SUFFIX") }

    private fun readBounded(file: File, maxBytes: Long): String? = runCatching {
        if (!file.isFile || file.length() !in 1..maxBytes) return@runCatching null
        file.readText(Charsets.UTF_8)
    }.getOrNull()

    private fun atomicWrite(target: File, value: String): Boolean = runCatching {
        val parent = target.parentFile ?: return@runCatching false
        if (!ensureDirectory(parent)) return@runCatching false
        val temporary = File.createTempFile(".${target.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncFile(target)
            syncDirectory(parent)
            true
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }.getOrDefault(false)

    private fun deleteFileAndSync(file: File): Boolean = runCatching {
        if (!file.exists()) return@runCatching true
        val parent = file.parentFile ?: return@runCatching false
        if (!file.isFile || !file.delete()) return@runCatching false
        syncDirectory(parent)
        true
    }.getOrDefault(false)

    private fun deleteCollectionArtifact(artifact: File): Boolean = runCatching {
        if (artifact.parentFile?.canonicalFile != collections.canonicalFile) {
            return@runCatching false
        }
        Files.walk(artifact.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        syncDirectory(collections)
        true
    }.getOrDefault(false)

    private fun ensureDirectory(directory: File): Boolean {
        if (directory.exists()) return directory.isDirectory
        val parent = directory.parentFile
        if (parent != null && !ensureDirectory(parent)) return false
        return directory.mkdir() && runCatching {
            parent?.let(::syncDirectory)
        }.isSuccess
    }

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private companion object {
        const val ROOT_DIRECTORY_NAME = "raw_collections_v2"
        const val COLLECTIONS_DIRECTORY_NAME = "collections"
        const val FENCES_DIRECTORY_NAME = "fences"
        const val ACTIVE_POINTER_NAME = "active_collection"
        const val MANIFEST_FILE_NAME = "manifest.aead"
        const val FENCE_SUFFIX = ".fence"
        const val MAX_MANIFEST_ENVELOPE_BYTES = 2L * 1_024L * 1_024L
        const val MAX_CHUNK_ENVELOPE_BYTES = 12L * 1_024L * 1_024L
        const val MAX_FENCE_BYTES = 128L
        val EXPECTED_RESET_ROOT_ARTIFACTS = setOf(
            COLLECTIONS_DIRECTORY_NAME,
            FENCES_DIRECTORY_NAME,
        )
        val FENCE_NAME = Regex("[a-z0-9-]{1,96}")
        val FENCE_VALUE = Regex("[A-Z_]{1,64}")
    }
}
