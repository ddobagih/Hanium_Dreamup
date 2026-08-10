package kr.co.hanium.dreamup.walksafe.security

import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAeadTest {
    private val limits = AeadLimits(
        maxPlaintextBytes = 4_096,
        maxCiphertextBytes = 4_112,
        maxEnvelopeChars = 6_000,
    )
    private val aad = "kr.co.hanium.dreamup.walksafe|USER|test|schema=1".toByteArray()

    @Test
    fun exactEnvelopeUsesFreshTwelveByteIvAndAuthenticatesDomain() {
        val provider = FakeKeyProvider()
        val aead = aead(provider, current = 1, readable = setOf(1))
        val plaintext = "sensitive-value".toByteArray()

        val first = aead.seal(plaintext, aad, limits).sealed()
        val second = aead.seal(plaintext, aad, limits).sealed()

        assertNotEquals(first, second)
        assertTrue(
            first.matches(
                Regex(
                    """\{\"envelope_version\":1,\"key_version\":1,\"iv\":\"[A-Za-z0-9+/]{16}\",\"ciphertext\":\"[A-Za-z0-9+/]+=*\"\}""",
                ),
            ),
        )
        val ciphertext = Regex("""\"ciphertext\":\"([^\"]+)\"""")
            .find(first)!!
            .groupValues[1]
        assertEquals(plaintext.size + 16, Base64.getDecoder().decode(ciphertext).size)
        assertArrayEquals(plaintext, aead.open(first, aad, limits).opened().plaintext)
        assertEquals(
            AeadBlockReason.AUTHENTICATION_FAILED,
            (aead.open(first, "wrong-purpose".toByteArray(), limits) as AeadOpenResult.Blocked).reason,
        )
    }

    @Test
    fun malformedExtraFieldAndTamperedCiphertextFailClosed() {
        val aead = aead(FakeKeyProvider(), current = 1, readable = setOf(1))
        val envelope = aead.seal("secret".toByteArray(), aad, limits).sealed()
        val withWhitespace = envelope.replace("{", "{ ")
        val withExtraField = envelope.dropLast(1) + ",\"extra\":true}"
        val ciphertextStart = envelope.indexOf("\"ciphertext\":\"") + "\"ciphertext\":\"".length
        val replacement = if (envelope[ciphertextStart] == 'A') 'B' else 'A'
        val tampered = envelope.replaceRange(ciphertextStart, ciphertextStart + 1, replacement.toString())

        assertEquals(
            AeadBlockReason.MALFORMED_ENVELOPE,
            (aead.open(withWhitespace, aad, limits) as AeadOpenResult.Blocked).reason,
        )
        assertEquals(
            AeadBlockReason.MALFORMED_ENVELOPE,
            (aead.open(withExtraField, aad, limits) as AeadOpenResult.Blocked).reason,
        )
        assertEquals(
            AeadBlockReason.AUTHENTICATION_FAILED,
            (aead.open(tampered, aad, limits) as AeadOpenResult.Blocked).reason,
        )
    }

    @Test
    fun decryptNeverCreatesAMissingKey() {
        val provider = FakeKeyProvider()
        val aead = aead(provider, current = 1, readable = setOf(1))
        val envelope = aead.seal("secret".toByteArray(), aad, limits).sealed()
        assertTrue(aead.destroyVersion(1))
        val createsBeforeOpen = provider.getOrCreateCalls

        val result = aead.open(envelope, aad, limits)

        assertEquals(AeadBlockReason.KEY_MISSING, (result as AeadOpenResult.Blocked).reason)
        assertEquals(createsBeforeOpen, provider.getOrCreateCalls)
        assertFalse(provider.keys.containsKey("walksafe.test.aead.v1"))
    }

    @Test
    fun sealReportsMissingWhenProviderRefusesToRecreateDestroyedKey() {
        val provider = FakeKeyProvider()
        val aead = aead(provider, current = 1, readable = setOf(1))
        aead.seal("secret".toByteArray(), aad, limits).sealed()
        assertTrue(aead.destroyVersion(1))

        val result = aead.seal("replacement".toByteArray(), aad, limits)

        assertEquals(AeadBlockReason.KEY_MISSING, (result as AeadSealResult.Blocked).reason)
        assertFalse(provider.keys.containsKey("walksafe.test.aead.v1"))
    }

    @Test
    fun verifiedPurgeExplicitlyCreatesANewGeneration() {
        val provider = FakeKeyProvider()
        val aead = aead(provider, current = 1, readable = setOf(1))
        val original = aead.seal("secret".toByteArray(), aad, limits).sealed()
        assertTrue(aead.destroyKnownVersions())
        assertTrue(aead.seal("blocked".toByteArray(), aad, limits) is AeadSealResult.Blocked)

        assertTrue(aead.createFreshAfterVerifiedPurge())

        val replacement = aead.seal("replacement".toByteArray(), aad, limits).sealed()
        assertNotEquals(original, replacement)
        assertArrayEquals(
            "replacement".toByteArray(),
            aead.open(replacement, aad, limits).opened().plaintext,
        )
    }

    @Test
    fun ordinaryRotationReadsOldKeyAndRewrapsWithCurrentVersion() {
        val provider = FakeKeyProvider()
        val v1 = aead(provider, current = 1, readable = setOf(1))
        val oldEnvelope = v1.seal("secret".toByteArray(), aad, limits).sealed()
        val rotating = aead(provider, current = 2, readable = setOf(1, 2))

        val old = rotating.open(oldEnvelope, aad, limits).opened()
        val newEnvelope = rotating.seal(old.plaintext, aad, limits).sealed()
        val current = rotating.open(newEnvelope, aad, limits).opened()

        assertTrue(old.needsRewrap)
        assertEquals(1, old.keyVersion)
        assertFalse(current.needsRewrap)
        assertEquals(2, current.keyVersion)
        assertArrayEquals("secret".toByteArray(), current.plaintext)
    }

    @Test
    fun compromisedVersionIsDeletedAndNeverOpenedOrRewrapped() {
        val provider = FakeKeyProvider()
        val v1 = aead(provider, current = 1, readable = setOf(1))
        val oldEnvelope = v1.seal("secret".toByteArray(), aad, limits).sealed()

        val afterCompromise = aead(
            provider = provider,
            current = 2,
            readable = setOf(2),
            compromised = setOf(1),
        )

        assertFalse(provider.keys.containsKey("walksafe.test.aead.v1"))
        assertEquals(
            AeadBlockReason.COMPROMISED_KEY_VERSION,
            (afterCompromise.open(oldEnvelope, aad, limits) as AeadOpenResult.Blocked).reason,
        )
        assertEquals(0, provider.existingReadsAfterCompromise)
    }

    @Test
    fun cleanupAttemptsEveryKnownVersionAndBlocksWhenAnyDeletionFails() {
        val provider = FakeKeyProvider()
        val first = aead(provider, current = 1, readable = setOf(1))
        first.seal("one".toByteArray(), aad, limits).sealed()
        val rotating = aead(provider, current = 2, readable = setOf(1, 2))
        rotating.seal("two".toByteArray(), aad, limits).sealed()
        provider.failedDeletes = setOf("walksafe.test.aead.v1")

        assertFalse(rotating.destroyKnownVersions())
        assertTrue(provider.deleteAttempts.contains("walksafe.test.aead.v1"))
        assertTrue(provider.deleteAttempts.contains("walksafe.test.aead.v2"))
        assertTrue(provider.keys.containsKey("walksafe.test.aead.v1"))
        assertFalse(provider.keys.containsKey("walksafe.test.aead.v2"))
    }

    @Test
    fun compromisedCleanupFailureAttemptsAllAliasesAndBlocksUse() {
        val provider = FakeKeyProvider()
        aead(provider, current = 1, readable = setOf(1))
            .seal("one".toByteArray(), aad, limits).sealed()
        aead(provider, current = 2, readable = setOf(2))
            .seal("two".toByteArray(), aad, limits).sealed()
        provider.failedDeletes = setOf("walksafe.test.aead.v1")

        val blocked = aead(
            provider = provider,
            current = 3,
            readable = setOf(3),
            compromised = setOf(1, 2),
        )

        assertEquals(
            AeadBlockReason.COMPROMISED_KEY_CLEANUP_FAILED,
            (blocked.seal("three".toByteArray(), aad, limits) as AeadSealResult.Blocked).reason,
        )
        assertTrue(provider.deleteAttempts.contains("walksafe.test.aead.v1"))
        assertTrue(provider.deleteAttempts.contains("walksafe.test.aead.v2"))
        assertFalse(provider.keys.containsKey("walksafe.test.aead.v2"))
    }

    private fun aead(
        provider: FakeKeyProvider,
        current: Int,
        readable: Set<Int>,
        compromised: Set<Int> = emptySet(),
    ): VersionedLocalAead {
        provider.compromisedAliases = compromised.map { "walksafe.test.aead.v$it" }.toSet()
        return VersionedLocalAead(
            AeadKeyPolicy(
                aliasPrefix = "walksafe.test.aead.v",
                currentVersion = current,
                readableVersions = readable,
                compromisedVersions = compromised,
            ),
            provider,
        )
    }

    private fun AeadSealResult.sealed(): String =
        (this as AeadSealResult.Sealed).envelope

    private fun AeadOpenResult.opened(): AeadOpenResult.Opened =
        this as AeadOpenResult.Opened

    private class FakeKeyProvider : LocalAeadKeyProvider {
        val keys = mutableMapOf<String, SecretKey>()
        private val generationTombstones = mutableSetOf<String>()
        var getOrCreateCalls = 0
        var existingReadsAfterCompromise = 0
        var compromisedAliases: Set<String> = emptySet()
        var failedDeletes: Set<String> = emptySet()
        val deleteAttempts = mutableListOf<String>()

        override fun getOrCreate(alias: String): SecretKey? {
            getOrCreateCalls += 1
            keys[alias]?.let { return it }
            if (alias in generationTombstones) return null
            generationTombstones += alias
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also {
                keys[alias] = it
            }
        }

        override fun getExisting(alias: String): SecretKey? {
            if (alias in compromisedAliases) existingReadsAfterCompromise += 1
            return keys[alias]
        }

        override fun delete(alias: String): Boolean {
            deleteAttempts += alias
            if (alias in failedDeletes) return false
            generationTombstones += alias
            keys.remove(alias)
            return true
        }

        override fun createFreshAfterVerifiedPurge(alias: String): SecretKey {
            generationTombstones += alias
            return keys.getOrPut(alias) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
        }
    }
}
