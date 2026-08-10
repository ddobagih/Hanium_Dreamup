package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import java.util.Locale
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import org.json.JSONObject

internal class TestFieldAead {
    private val provider = Provider()
    val aead: LocalAead = VersionedLocalAead(
        AeadKeyPolicy(
            aliasPrefix = "walksafe.test.field_log.aead.v",
            currentVersion = 1,
            readableVersions = setOf(1),
        ),
        provider,
    )

    fun deleteKey() {
        aead.destroyKnownVersions()
    }

    fun hasKey(): Boolean = provider.hasAnyKey()

    fun failNextDelete() {
        provider.failNextDelete = true
    }

    fun failNextFreshCreate() {
        provider.failNextFreshCreate = true
    }

    fun readManifest(root: File, sessionId: String): JSONObject =
        JSONObject(
            open(
                File(root, "$sessionId/manifest.json").readText(),
                manifestAad(sessionId),
                MANIFEST_LIMITS,
            ),
        )

    fun writeManifest(root: File, sessionId: String, manifest: JSONObject) {
        val directory = File(root, sessionId).apply { mkdirs() }
        File(directory, "manifest.json").writeText(
            seal(manifest.toString(), manifestAad(sessionId), MANIFEST_LIMITS),
        )
    }

    fun readRecords(sessionDirectory: File): List<JSONObject> {
        var ordinal = 0L
        return sessionDirectory
            .listFiles { file -> RECORD_FILE.matches(file.name) }
            .orEmpty()
            .sortedBy(File::getName)
            .flatMap { file ->
                val segment = RECORD_FILE.matchEntire(file.name)!!.groupValues[1].toInt()
                file.readLines().mapNotNull { envelope ->
                    val decoded = runCatching {
                        JSONObject(
                            open(
                                envelope,
                                recordAad(sessionDirectory.name, segment, ordinal),
                                RECORD_LIMITS,
                            ),
                        )
                    }.getOrNull()
                    ordinal += 1L
                    decoded
                }
            }
    }

    fun rewriteRecords(
        sessionDirectory: File,
        transform: (JSONObject) -> JSONObject,
    ) {
        var ordinal = 0L
        sessionDirectory.listFiles { file -> RECORD_FILE.matches(file.name) }
            .orEmpty()
            .sortedBy(File::getName)
            .forEach { file ->
                val segment = RECORD_FILE.matchEntire(file.name)!!.groupValues[1].toInt()
                val rewritten = file.readLines().map { envelope ->
                    val aad = recordAad(sessionDirectory.name, segment, ordinal)
                    val record = JSONObject(open(envelope, aad, RECORD_LIMITS))
                    ordinal += 1L
                    seal(transform(record).toString(), aad, RECORD_LIMITS)
                }
                file.writeText(rewritten.joinToString(separator = "\n", postfix = "\n"))
            }
    }

    private fun seal(plaintext: String, aad: ByteArray, limits: AeadLimits): String =
        (aead.seal(plaintext.toByteArray(), aad, limits) as AeadSealResult.Sealed).envelope

    private fun open(envelope: String, aad: ByteArray, limits: AeadLimits): String =
        String((aead.open(envelope, aad, limits) as AeadOpenResult.Opened).plaintext)

    private fun manifestAad(sessionId: String): ByteArray =
        "$AAD_PREFIX|field-log|session=$sessionId|manifest|storage=1".toByteArray()

    private fun recordAad(sessionId: String, segment: Int, ordinal: Long): ByteArray =
        (
            "$AAD_PREFIX|field-log|session=$sessionId|segment=" +
                String.format(Locale.US, "%04d", segment) +
                "|ordinal=$ordinal|record|storage=1"
        ).toByteArray()

    private class Provider : LocalAeadKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        private val generationTombstones = mutableSetOf<String>()
        var failNextDelete = false
        var failNextFreshCreate = false

        @Synchronized
        fun hasAnyKey(): Boolean = keys.isNotEmpty()

        @Synchronized
        override fun getOrCreate(alias: String): SecretKey? {
            if (alias !in keys && alias in generationTombstones) return null
            generationTombstones += alias
            return keys.getOrPut(alias) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
        }

        @Synchronized
        override fun getExisting(alias: String): SecretKey? = keys[alias]

        @Synchronized
        override fun delete(alias: String): Boolean {
            if (failNextDelete) {
                failNextDelete = false
                return false
            }
            generationTombstones += alias
            keys.remove(alias)
            return true
        }

        override fun createFreshAfterVerifiedPurge(alias: String): SecretKey? {
            if (failNextFreshCreate) {
                failNextFreshCreate = false
                return null
            }
            generationTombstones += alias
            return keys.getOrPut(alias) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
        }
    }

    private companion object {
        const val AAD_PREFIX = "kr.co.hanium.dreamup.walksafe|USER"
        val RECORD_FILE = Regex("records-(\\d{4})\\.jsonl")
        val MANIFEST_LIMITS = AeadLimits(64 * 1_024, 64 * 1_024 + 16, 96 * 1_024)
        val RECORD_LIMITS = AeadLimits(512 * 1_024, 512 * 1_024 + 16, 704 * 1_024)
    }
}
