package kr.co.hanium.dreamup.walksafe.security

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class AeadKeyPolicy(
    val aliasPrefix: String,
    val currentVersion: Int,
    val readableVersions: Set<Int>,
    val compromisedVersions: Set<Int> = emptySet(),
) {
    init {
        require(aliasPrefix.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        require(currentVersion > 0)
        require(currentVersion in readableVersions)
        require(readableVersions.all { it > 0 })
        require(compromisedVersions.all { it > 0 })
        require(readableVersions.intersect(compromisedVersions).isEmpty())
    }

    fun alias(version: Int): String = "$aliasPrefix$version"

    val knownVersions: Set<Int>
        get() = readableVersions + compromisedVersions + currentVersion
}

internal data class AeadLimits(
    val maxPlaintextBytes: Int,
    val maxCiphertextBytes: Int,
    val maxEnvelopeChars: Int,
) {
    init {
        require(maxPlaintextBytes > 0)
        require(maxCiphertextBytes >= maxPlaintextBytes + GCM_TAG_BYTES)
        require(maxEnvelopeChars > maxCiphertextBytes)
    }
}

internal enum class AeadBlockReason {
    INVALID_AAD,
    PLAINTEXT_TOO_LARGE,
    MALFORMED_ENVELOPE,
    CIPHERTEXT_TOO_LARGE,
    UNKNOWN_KEY_VERSION,
    COMPROMISED_KEY_VERSION,
    COMPROMISED_KEY_CLEANUP_FAILED,
    KEY_MISSING,
    KEY_ACCESS_FAILED,
    AUTHENTICATION_FAILED,
}

internal sealed interface AeadSealResult {
    data class Sealed(val envelope: String) : AeadSealResult
    data class Blocked(val reason: AeadBlockReason) : AeadSealResult
}

internal sealed interface AeadOpenResult {
    data class Opened(
        val plaintext: ByteArray,
        val keyVersion: Int,
        val needsRewrap: Boolean,
    ) : AeadOpenResult

    data class Blocked(val reason: AeadBlockReason) : AeadOpenResult
}

internal interface LocalAead {
    fun seal(
        plaintext: ByteArray,
        domainAad: ByteArray,
        limits: AeadLimits,
    ): AeadSealResult

    fun open(
        envelope: String,
        domainAad: ByteArray,
        limits: AeadLimits,
    ): AeadOpenResult

    fun destroyVersion(version: Int): Boolean
    fun destroyKnownVersions(): Boolean
    fun createFreshAfterVerifiedPurge(): Boolean
}

internal interface LocalAeadKeyProvider {
    fun getOrCreate(alias: String): SecretKey?
    fun getExisting(alias: String): SecretKey?
    fun delete(alias: String): Boolean
    fun createFreshAfterVerifiedPurge(alias: String): SecretKey?
}

internal class VersionedLocalAead(
    private val policy: AeadKeyPolicy,
    private val keyProvider: LocalAeadKeyProvider,
) : LocalAead {
    private val compromisedCleanupSucceeded = policy.compromisedVersions
        .map { version ->
            runCatching { keyProvider.delete(policy.alias(version)) }.getOrDefault(false)
        }
        .all { it }

    @Synchronized
    override fun seal(
        plaintext: ByteArray,
        domainAad: ByteArray,
        limits: AeadLimits,
    ): AeadSealResult {
        validateAad(domainAad)?.let { return AeadSealResult.Blocked(it) }
        if (plaintext.size > limits.maxPlaintextBytes) {
            return AeadSealResult.Blocked(AeadBlockReason.PLAINTEXT_TOO_LARGE)
        }
        if (!compromisedCleanupSucceeded) {
            return AeadSealResult.Blocked(AeadBlockReason.COMPROMISED_KEY_CLEANUP_FAILED)
        }
        val key = try {
            keyProvider.getOrCreate(policy.alias(policy.currentVersion))
        } catch (_: Exception) {
            return AeadSealResult.Blocked(AeadBlockReason.KEY_ACCESS_FAILED)
        } ?: return AeadSealResult.Blocked(AeadBlockReason.KEY_MISSING)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            if (iv == null || iv.size != GCM_IV_BYTES) {
                return AeadSealResult.Blocked(AeadBlockReason.KEY_ACCESS_FAILED)
            }
            cipher.updateAAD(authenticatedAad(domainAad, policy.currentVersion))
            val ciphertext = cipher.doFinal(plaintext)
            if (
                ciphertext.size != plaintext.size + GCM_TAG_BYTES ||
                ciphertext.size > limits.maxCiphertextBytes
            ) {
                return AeadSealResult.Blocked(AeadBlockReason.CIPHERTEXT_TOO_LARGE)
            }
            val envelope = encodeEnvelope(policy.currentVersion, iv, ciphertext)
            if (envelope.length > limits.maxEnvelopeChars) {
                AeadSealResult.Blocked(AeadBlockReason.CIPHERTEXT_TOO_LARGE)
            } else {
                AeadSealResult.Sealed(envelope)
            }
        } catch (_: Exception) {
            AeadSealResult.Blocked(AeadBlockReason.KEY_ACCESS_FAILED)
        }
    }

    @Synchronized
    override fun open(
        envelope: String,
        domainAad: ByteArray,
        limits: AeadLimits,
    ): AeadOpenResult {
        validateAad(domainAad)?.let { return AeadOpenResult.Blocked(it) }
        if (!compromisedCleanupSucceeded) {
            return AeadOpenResult.Blocked(AeadBlockReason.COMPROMISED_KEY_CLEANUP_FAILED)
        }
        val parsed = decodeEnvelope(envelope, limits)
            ?: return AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
        if (parsed.keyVersion in policy.compromisedVersions) {
            return AeadOpenResult.Blocked(AeadBlockReason.COMPROMISED_KEY_VERSION)
        }
        if (parsed.keyVersion !in policy.readableVersions) {
            return AeadOpenResult.Blocked(AeadBlockReason.UNKNOWN_KEY_VERSION)
        }
        val key = try {
            keyProvider.getExisting(policy.alias(parsed.keyVersion))
        } catch (_: Exception) {
            return AeadOpenResult.Blocked(AeadBlockReason.KEY_ACCESS_FAILED)
        } ?: return AeadOpenResult.Blocked(AeadBlockReason.KEY_MISSING)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, parsed.iv),
            )
            cipher.updateAAD(authenticatedAad(domainAad, parsed.keyVersion))
            val plaintext = cipher.doFinal(parsed.ciphertext)
            if (plaintext.size > limits.maxPlaintextBytes) {
                plaintext.fill(0)
                AeadOpenResult.Blocked(AeadBlockReason.PLAINTEXT_TOO_LARGE)
            } else {
                AeadOpenResult.Opened(
                    plaintext = plaintext,
                    keyVersion = parsed.keyVersion,
                    needsRewrap = parsed.keyVersion != policy.currentVersion,
                )
            }
        } catch (_: Exception) {
            AeadOpenResult.Blocked(AeadBlockReason.AUTHENTICATION_FAILED)
        }
    }

    @Synchronized
    override fun destroyVersion(version: Int): Boolean =
        version > 0 && runCatching {
            keyProvider.delete(policy.alias(version))
        }.getOrDefault(false)

    @Synchronized
    override fun destroyKnownVersions(): Boolean = policy.knownVersions
        .map(::destroyVersion)
        .all { it }

    @Synchronized
    override fun createFreshAfterVerifiedPurge(): Boolean {
        if (!compromisedCleanupSucceeded) return false
        return runCatching {
            keyProvider.createFreshAfterVerifiedPurge(policy.alias(policy.currentVersion)) != null
        }.getOrDefault(false)
    }

    private fun validateAad(domainAad: ByteArray): AeadBlockReason? =
        if (domainAad.isEmpty() || domainAad.size > MAX_AAD_BYTES) {
            AeadBlockReason.INVALID_AAD
        } else {
            null
        }

    private fun decodeEnvelope(
        envelope: String,
        limits: AeadLimits,
    ): ParsedEnvelope? {
        if (envelope.length > limits.maxEnvelopeChars) return null
        val match = ENVELOPE_PATTERN.matchEntire(envelope) ?: return null
        val keyVersion = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val iv = decodeCanonicalBase64(match.groupValues[2]) ?: return null
        val ciphertext = decodeCanonicalBase64(match.groupValues[3]) ?: return null
        if (iv.size != GCM_IV_BYTES) return null
        if (ciphertext.size < GCM_TAG_BYTES || ciphertext.size > limits.maxCiphertextBytes) return null
        return ParsedEnvelope(keyVersion, iv, ciphertext)
    }

    private fun authenticatedAad(domainAad: ByteArray, keyVersion: Int): ByteArray =
        domainAad + byteArrayOf(0) +
            "walksafe-envelope-v$ENVELOPE_VERSION|key=$keyVersion"
                .toByteArray(StandardCharsets.UTF_8)

    private fun encodeEnvelope(
        keyVersion: Int,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): String =
        "{\"envelope_version\":$ENVELOPE_VERSION," +
            "\"key_version\":$keyVersion," +
            "\"iv\":\"${BASE64_ENCODER.encodeToString(iv)}\"," +
            "\"ciphertext\":\"${BASE64_ENCODER.encodeToString(ciphertext)}\"}"

    private fun decodeCanonicalBase64(encoded: String): ByteArray? = runCatching {
        BASE64_DECODER.decode(encoded).takeIf {
            BASE64_ENCODER.encodeToString(it) == encoded
        }
    }.getOrNull()

    private data class ParsedEnvelope(
        val keyVersion: Int,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    private companion object {
        const val ENVELOPE_VERSION = 1
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val MAX_AAD_BYTES = 1_024
        val BASE64_ENCODER: Base64.Encoder = Base64.getEncoder()
        val BASE64_DECODER: Base64.Decoder = Base64.getDecoder()
        val ENVELOPE_PATTERN = Regex(
            """\{\"envelope_version\":1,\"key_version\":([1-9][0-9]*),\"iv\":\"([A-Za-z0-9+/]+={0,2})\",\"ciphertext\":\"([A-Za-z0-9+/]+={0,2})\"\}""",
        )
    }
}

private const val GCM_TAG_BYTES = 16
