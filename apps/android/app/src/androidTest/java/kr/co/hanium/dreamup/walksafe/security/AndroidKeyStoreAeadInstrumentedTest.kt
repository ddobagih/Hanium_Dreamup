package kr.co.hanium.dreamup.walksafe.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeyStoreAeadInstrumentedTest {
    @Test
    fun destroyedAliasIsNotRecreatedBySealInSameProcess() {
        val aliasPrefix = "walksafe.instrumentation.aead.${UUID.randomUUID()}.v"
        val policy = AeadKeyPolicy(
            aliasPrefix = aliasPrefix,
            currentVersion = KEY_VERSION,
            readableVersions = setOf(KEY_VERSION),
        )
        val alias = policy.alias(KEY_VERSION)
        val tombstoneAlias = "$alias.generation"
        val aead = AndroidKeyStoreAead(policy)

        try {
            assertTrue(aead.seal("secret".toByteArray(), AAD, LIMITS) is AeadSealResult.Sealed)
            assertTrue(androidKeyStore().containsAlias(alias))
            assertTrue(aead.destroyVersion(KEY_VERSION))
            assertFalse(androidKeyStore().containsAlias(alias))
            assertTrue(androidKeyStore().containsAlias(tombstoneAlias))

            val replacement = AndroidKeyStoreAead(policy)
            assertEquals(
                AeadSealResult.Blocked(AeadBlockReason.KEY_MISSING),
                replacement.seal("replacement".toByteArray(), AAD, LIMITS),
            )
            assertFalse(androidKeyStore().containsAlias(alias))
        } finally {
            assertTrue(aead.destroyKnownVersions())
            assertFalse(androidKeyStore().containsAlias(alias))
            androidKeyStore().deleteEntry(tombstoneAlias)
        }
    }

    @Test
    fun verifiedPurgeCanExplicitlyCreateANewAliasGeneration() {
        val aliasPrefix = "walksafe.instrumentation.aead.${UUID.randomUUID()}.v"
        val policy = AeadKeyPolicy(
            aliasPrefix = aliasPrefix,
            currentVersion = KEY_VERSION,
            readableVersions = setOf(KEY_VERSION),
        )
        val alias = policy.alias(KEY_VERSION)
        val tombstoneAlias = "$alias.generation"
        val aead = AndroidKeyStoreAead(policy)

        try {
            assertTrue(aead.seal("before".toByteArray(), AAD, LIMITS) is AeadSealResult.Sealed)
            assertTrue(aead.destroyKnownVersions())
            assertTrue(aead.seal("blocked".toByteArray(), AAD, LIMITS) is AeadSealResult.Blocked)

            assertTrue(aead.createFreshAfterVerifiedPurge())

            assertTrue(aead.seal("after".toByteArray(), AAD, LIMITS) is AeadSealResult.Sealed)
            assertTrue(androidKeyStore().containsAlias(alias))
        } finally {
            assertTrue(aead.destroyKnownVersions())
            androidKeyStore().deleteEntry(tombstoneAlias)
        }
    }

    @Test
    fun losingMainAndGenerationAliasesDoesNotSilentlyCreateAKey() {
        val aliasPrefix = "walksafe.instrumentation.aead.${UUID.randomUUID()}.v"
        val policy = AeadKeyPolicy(
            aliasPrefix = aliasPrefix,
            currentVersion = KEY_VERSION,
            readableVersions = setOf(KEY_VERSION),
        )
        val alias = policy.alias(KEY_VERSION)
        val tombstoneAlias = "$alias.generation"
        val aead = AndroidKeyStoreAead(policy)

        try {
            assertTrue(aead.seal("before-loss".toByteArray(), AAD, LIMITS) is AeadSealResult.Sealed)
            androidKeyStore().deleteEntry(alias)
            androidKeyStore().deleteEntry(tombstoneAlias)

            val replacement = AndroidKeyStoreAead(policy)
            assertEquals(
                AeadSealResult.Blocked(AeadBlockReason.KEY_MISSING),
                replacement.seal("must-not-rekey".toByteArray(), AAD, LIMITS),
            )
            assertFalse(androidKeyStore().containsAlias(alias))
        } finally {
            assertTrue(aead.destroyKnownVersions())
            androidKeyStore().deleteEntry(tombstoneAlias)
        }
    }

    @Test
    fun androidKeyStoreAesGcmFailsClosedAfterAuthenticationOrKeyLoss() {
        val aliasPrefix = "walksafe.instrumentation.aead.${UUID.randomUUID()}.v"
        val policy = AeadKeyPolicy(
            aliasPrefix = aliasPrefix,
            currentVersion = KEY_VERSION,
            readableVersions = setOf(KEY_VERSION),
        )
        val alias = policy.alias(KEY_VERSION)
        val tombstoneAlias = "$alias.generation"
        val aead = AndroidKeyStoreAead(policy)
        val plaintext = "device-protected-secret".toByteArray()

        try {
            assertFalse(androidKeyStore().containsAlias(alias))
            val envelope = (aead.seal(plaintext, AAD, LIMITS) as AeadSealResult.Sealed).envelope
            assertTrue(androidKeyStore().containsAlias(alias))

            val opened = aead.open(envelope, AAD, LIMITS) as AeadOpenResult.Opened
            assertArrayEquals(plaintext, opened.plaintext)
            assertEquals(KEY_VERSION, opened.keyVersion)
            assertFalse(opened.needsRewrap)

            assertEquals(
                AeadOpenResult.Blocked(AeadBlockReason.AUTHENTICATION_FAILED),
                aead.open(envelope, WRONG_AAD, LIMITS),
            )
            assertEquals(
                AeadOpenResult.Blocked(AeadBlockReason.AUTHENTICATION_FAILED),
                aead.open(tamperCiphertext(envelope), AAD, LIMITS),
            )

            assertTrue(aead.destroyVersion(KEY_VERSION))
            assertFalse(androidKeyStore().containsAlias(alias))
            assertEquals(
                AeadOpenResult.Blocked(AeadBlockReason.KEY_MISSING),
                aead.open(envelope, AAD, LIMITS),
            )
            assertFalse(androidKeyStore().containsAlias(alias))
        } finally {
            assertTrue(aead.destroyKnownVersions())
            assertFalse(androidKeyStore().containsAlias(alias))
            androidKeyStore().deleteEntry(tombstoneAlias)
        }
    }

    private fun tamperCiphertext(envelope: String): String {
        val marker = "\"ciphertext\":\""
        val index = envelope.indexOf(marker) + marker.length
        require(index >= marker.length && index < envelope.length)
        val replacement = if (envelope[index] == 'A') 'B' else 'A'
        return envelope.replaceRange(index, index + 1, replacement.toString())
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val KEY_VERSION = 1
        val AAD = "walksafe|instrumentation|credential|schema=1".toByteArray()
        val WRONG_AAD = "walksafe|instrumentation|field-log|schema=1".toByteArray()
        val LIMITS = AeadLimits(
            maxPlaintextBytes = 4_096,
            maxCiphertextBytes = 4_112,
            maxEnvelopeChars = 6_000,
        )
    }
}
