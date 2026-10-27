package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator

class RawCollectionStorageReadException internal constructor(area: String, cause: Exception? = null) :
    IllegalStateException("raw collection storage is unreadable: $area", cause)

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
    fun quarantineCollection(collectionId: String): Boolean = false
    fun listQuarantinedCollectionIds(): Set<String> = emptySet()
    fun deleteCollection(collectionId: String): Boolean
    fun deleteAllCollections(): Boolean
    fun verifiedEmptyAfterAccountPurge(): Boolean
    fun hasFence(name: String): Boolean
    fun readFence(name: String): String?
    fun writeFenceAtomically(name: String, value: String): Boolean
    fun listFenceNames(): Set<String>
    fun clearAllFences(): Boolean
    /** On-disk footprint of live and quarantined collections; null when it cannot be measured. */
    fun measureUsage(): RawStorageUsage? = null
}

internal class FileRawCollectionStorage(
    rootDirectory: File,
) : RawCollectionStorage {
    private val root = File(rootDirectory, ROOT_DIRECTORY_NAME)
    private val collections = File(root, COLLECTIONS_DIRECTORY_NAME)
    private val quarantine = File(root, QUARANTINE_DIRECTORY_NAME)
    private val fences = File(root, FENCES_DIRECTORY_NAME)
    // A collection or the primary fence directory can independently become unwritable.
    // Use the same fsync/rename transaction in a separate directory for the blocking decision.
    private val recoveryFences = File(root, RECOVERY_FENCES_DIRECTORY_NAME)
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
    override fun listCollectionIds(): Set<String> = collectionIds(collections)

    @Synchronized
    override fun listQuarantinedCollectionIds(): Set<String> = collectionIds(quarantine)

    /** Preserve unreadable bytes; neither infer an owner/expiry nor decrypt them during isolation. */
    @Synchronized
    override fun quarantineCollection(collectionId: String): Boolean = synchronized(QUARANTINE_LOCK) {
        runCatching {
            val source = collectionDirectoryOrNull(collectionId) ?: return@runCatching false
            val target = File(quarantine, collectionId)
            val sourceIds = collectionIds(collections)
            val targetIds = collectionIds(quarantine)
            if (collectionId !in sourceIds) return@runCatching collectionId in targetIds
            if (collectionId in targetIds || !ensureDirectory(quarantine)) return@runCatching false
            if (readActiveCollectionId() == collectionId && !clearActiveCollectionId()) {
                return@runCatching false
            }
            // Do not request replacement: an existing quarantine artifact must never be overwritten.
            Files.move(source.toPath(), target.toPath())
            syncDirectory(collections)
            syncDirectory(quarantine)
            true
        }.getOrDefault(false)
    }

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
        return runCatching {
            val live = directoryEntries(collections)
            val isolated = directoryEntries(quarantine)
            val liveDeleted = live.map { deleteCollectionArtifact(it, collections) }.all { it }
            val isolatedDeleted = isolated.map { deleteCollectionArtifact(it, quarantine) }.all { it }
            liveDeleted && isolatedDeleted
        }.getOrDefault(false)
    }

    @Synchronized
    override fun verifiedEmptyAfterAccountPurge(): Boolean {
        return runCatching {
            if (!root.isDirectory || Files.exists(activePointer.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@runCatching false
            }
            if (directoryEntries(root).any { it.name !in EXPECTED_RESET_ROOT_ARTIFACTS }) {
                return@runCatching false
            }
            directoryEntries(collections).isEmpty() &&
                directoryEntries(quarantine).isEmpty() &&
                listOf(fences, recoveryFences).all { directory ->
                    directoryEntries(directory).all { !Files.isSymbolicLink(it.toPath()) }
                }
        }.getOrDefault(false)
    }

    @Synchronized
    override fun hasFence(name: String): Boolean = existingFence(name) != null

    @Synchronized
    override fun readFence(name: String): String? = existingFence(name)?.let {
        readBounded(it, MAX_FENCE_BYTES)?.trim()
            ?: throw RawCollectionStorageReadException("fence content")
    }

    @Synchronized
    override fun writeFenceAtomically(name: String, value: String): Boolean {
        if (!FENCE_NAME.matches(name) || !FENCE_VALUE.matches(value)) return false
        val target = fenceFileOrNull(name) ?: return false
        val recovery = File(recoveryFences, "$name$FENCE_SUFFIX")
        // Once a fallback exists it stays authoritative; an older primary copy cannot win again.
        if (Files.exists(recovery.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return atomicWrite(recovery, "$value\n")
        }
        return atomicWrite(target, "$value\n") || atomicWrite(recovery, "$value\n")
    }

    @Synchronized
    override fun listFenceNames(): Set<String> = listOf(fences, recoveryFences)
        .flatMap { directoryEntries(it).toList() }
        .mapTo(linkedSetOf()) { file ->
            val metadata = entryAttributes(file)
            val name = file.name.removeSuffix(FENCE_SUFFIX)
            if (!metadata.isRegularFile || metadata.isSymbolicLink ||
                !file.name.endsWith(FENCE_SUFFIX) || !FENCE_NAME.matches(name)
            ) throw RawCollectionStorageReadException("fences")
            name
        }

    @Synchronized
    override fun clearAllFences(): Boolean {
        return runCatching {
            listFenceNames() // Validate both directories and entries before removing either copy.
            listOf(fences, recoveryFences).flatMap { directoryEntries(it).toList() }
                .map(::deleteFileAndSync).all { it }
        }.getOrDefault(false)
    }

    @Synchronized
    override fun measureUsage(): RawStorageUsage? = runCatching {
        val live = directoryEntries(collections)
        val isolated = directoryEntries(quarantine)
        var bytes = 0L
        (live + isolated).forEach { artifact ->
            Files.walk(artifact.toPath()).use { paths ->
                paths.forEach { path ->
                    val metadata = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (metadata.isSymbolicLink) throw RawCollectionStorageReadException("usage")
                    if (metadata.isRegularFile) bytes = Math.addExact(bytes, metadata.size())
                }
            }
        }
        RawStorageUsage(
            liveCollections = live.count { isCanonicalUuid(it.name) },
            quarantinedCollections = isolated.count { isCanonicalUuid(it.name) },
            totalBytes = bytes,
        )
    }.getOrNull()

    private fun collectionDirectoryOrNull(collectionId: String): File? =
        collectionId.takeIf(::isCanonicalUuid)?.let { File(collections, it) }

    private fun chunkFileOrNull(collectionId: String, ordinal: Int): File? {
        if (ordinal !in 0 until RAW_MAX_CHUNKS) return null
        val directory = collectionDirectoryOrNull(collectionId) ?: return null
        return File(directory, "chunk-%04d.aead".format(ordinal))
    }

    private fun fenceFileOrNull(name: String): File? =
        name.takeIf(FENCE_NAME::matches)?.let { File(fences, "$it$FENCE_SUFFIX") }

    private fun existingFence(name: String): File? {
        if (!FENCE_NAME.matches(name)) return null
        for (directory in listOf(recoveryFences, fences)) {
            requireDirectoryPath(directory)
            val file = File(directory, "$name$FENCE_SUFFIX")
            val attributes = try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                continue
            } catch (error: Exception) {
                throw RawCollectionStorageReadException("fence metadata", error)
            }
            if (!attributes.isRegularFile || attributes.isSymbolicLink) {
                throw RawCollectionStorageReadException("fence entry")
            }
            return file
        }
        return null
    }

    private fun readBounded(file: File, maxBytes: Long): String? = runCatching {
        requireDirectoryPath(file.parentFile ?: return@runCatching null)
        val metadata = entryAttributes(file)
        if (!metadata.isRegularFile || metadata.isSymbolicLink || metadata.size() !in 1..maxBytes) {
            return@runCatching null
        }
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

    private fun deleteCollectionArtifact(artifact: File, directory: File): Boolean = runCatching {
        requireDirectoryPath(directory)
        if (artifact.parentFile?.canonicalFile != directory.canonicalFile) {
            return@runCatching false
        }
        Files.walk(artifact.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        syncDirectory(directory)
        true
    }.getOrDefault(false)

    private fun ensureDirectory(directory: File): Boolean {
        if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return runCatching { requireDirectoryPath(directory); true }.getOrDefault(false)
        }
        val parent = directory.parentFile
        if (parent != null && !ensureDirectory(parent)) return false
        return directory.mkdir() && runCatching {
            parent?.let(::syncDirectory)
        }.isSuccess
    }

    private fun collectionIds(directory: File): Set<String> = directoryEntries(directory)
        .mapNotNullTo(linkedSetOf()) { file ->
            val metadata = entryAttributes(file)
            if (metadata.isSymbolicLink || (!metadata.isDirectory && !metadata.isRegularFile)) {
                throw RawCollectionStorageReadException("collections")
            }
            file.name.takeIf(::isCanonicalUuid)
        }

    private fun directoryEntries(directory: File): Array<File> {
        try {
            requireDirectoryPath(directory)
            if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyArray()
            return directory.listFiles() ?: throw RawCollectionStorageReadException("directory listing")
        } catch (error: RawCollectionStorageReadException) {
            throw error
        } catch (error: Exception) {
            throw RawCollectionStorageReadException("directory listing", error)
        }
    }

    private fun requireDirectoryPath(directory: File) {
        val path = directory.toPath().toAbsolutePath().normalize()
        var current = path.root ?: throw RawCollectionStorageReadException("directory path")
        for (part in path) {
            current = current.resolve(part)
            val metadata = try {
                Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                continue
            }
            if (!metadata.isDirectory || metadata.isSymbolicLink) {
                throw RawCollectionStorageReadException("directory path")
            }
        }
    }

    private fun entryAttributes(file: File): BasicFileAttributes = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (error: Exception) {
        throw RawCollectionStorageReadException("entry metadata", error)
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
        const val QUARANTINE_DIRECTORY_NAME = "quarantine"
        const val FENCES_DIRECTORY_NAME = "fences"
        const val RECOVERY_FENCES_DIRECTORY_NAME = "recovery_fences"
        const val ACTIVE_POINTER_NAME = "active_collection"
        const val MANIFEST_FILE_NAME = "manifest.aead"
        const val FENCE_SUFFIX = ".fence"
        const val MAX_MANIFEST_ENVELOPE_BYTES = 2L * 1_024L * 1_024L
        const val MAX_CHUNK_ENVELOPE_BYTES = 12L * 1_024L * 1_024L
        const val MAX_FENCE_BYTES = 128L
        val EXPECTED_RESET_ROOT_ARTIFACTS = setOf(
            COLLECTIONS_DIRECTORY_NAME,
            QUARANTINE_DIRECTORY_NAME,
            FENCES_DIRECTORY_NAME,
            RECOVERY_FENCES_DIRECTORY_NAME,
        )
        val FENCE_NAME = Regex("[a-z0-9-]{1,96}")
        val FENCE_VALUE = Regex("[A-Z_]{1,64}")
        val QUARANTINE_LOCK = Any()
    }
}
