package kr.co.hanium.dreamup.walksafe.report

import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.network.BackendAccountDeviceCookieBinding
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidReportDeletionTrackerStoreTest {
    @Test
    fun encryptedTrackerRestoresIdsWithoutPersistingActorRequestOrReportPlaintext() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val session = backendSession(ACTOR_A, accountGeneration = 7)
        val first = store(storage, provider)

        assertTrue(first.track(session, REQUEST_ID_A))
        assertTrue(first.track(session, REQUEST_ID_B))
        assertTrue(first.track(session, REQUEST_ID_A))

        val stored = storage.values.entries.single()
        assertTrue(Regex("^[0-9a-f]{64}$").matches(stored.key))
        assertFalse(stored.key.contains(ACTOR_A))
        listOf(
            ACTOR_A,
            REQUEST_ID_A,
            REQUEST_ID_B,
            "walksafe.android-report-deletion-tracker.v1",
            "request_ids",
            "user_description",
            "request_text",
            "deletion_reason",
        ).forEach { plaintext -> assertFalse(stored.value.contains(plaintext)) }

        val restored = store(storage, provider)
        assertEquals(listOf(REQUEST_ID_A, REQUEST_ID_B), restored.trackedRequestIds(session))
    }

    @Test
    fun actorAndAccountGenerationAreIsolatedAndAuthenticated() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val tracker = store(storage, provider)
        val actorGeneration7 = backendSession(ACTOR_A, accountGeneration = 7)
        val actorGeneration8 = backendSession(ACTOR_A, accountGeneration = 8)
        val otherActor = backendSession(ACTOR_B, accountGeneration = 7)

        assertTrue(tracker.track(actorGeneration7, REQUEST_ID_A))
        val actorGeneration7Key = storage.values.keys.single()
        val actorGeneration7Envelope = storage.values.getValue(actorGeneration7Key)
        assertTrue(tracker.trackedRequestIds(actorGeneration8).isEmpty())
        assertTrue(tracker.trackedRequestIds(otherActor).isEmpty())
        assertTrue(tracker.track(actorGeneration8, REQUEST_ID_B))
        val actorGeneration8Key = (storage.values.keys - actorGeneration7Key).single()
        assertTrue(tracker.track(otherActor, REQUEST_ID_C))
        assertEquals(3, storage.values.keys.toSet().size)
        storage.values[actorGeneration8Key] = actorGeneration7Envelope

        assertTrue(store(storage, provider).trackedRequestIds(actorGeneration8).isEmpty())
        assertFalse(storage.values.containsKey(actorGeneration8Key))
        assertEquals(
            listOf(REQUEST_ID_A),
            store(storage, provider).trackedRequestIds(actorGeneration7),
        )
        assertFalse(tracker.track(legacySession(), REQUEST_ID_A))
        assertTrue(tracker.trackedRequestIds(legacySession()).isEmpty())
    }

    @Test
    fun tamperedCiphertextIsDeletedAndNeverReturned() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val session = backendSession(ACTOR_A, accountGeneration = 7)
        val tracker = store(storage, provider)
        assertTrue(tracker.track(session, REQUEST_ID_A))
        val key = storage.values.keys.single()
        storage.values[key] = tamperCiphertext(storage.values.getValue(key))

        assertTrue(store(storage, provider).trackedRequestIds(session).isEmpty())
        assertFalse(storage.values.containsKey(key))
        assertTrue(key in storage.deletedKeys)
    }

    private fun store(
        storage: FakeStorage,
        provider: FakeKeyProvider,
    ): AndroidReportDeletionTrackerStore = AndroidReportDeletionTrackerStore(
        storage = storage,
        aead = VersionedLocalAead(
            policy = AeadKeyPolicy(
                aliasPrefix = "walksafe.test.report-deletion-tracker.v",
                currentVersion = 1,
                readableVersions = setOf(1),
            ),
            keyProvider = provider,
        ),
        testOnly = Unit,
    )

    private fun backendSession(
        actorId: String,
        accountGeneration: Long,
    ): GatewayFieldSession = GatewayFieldSession.backendAccountDeviceSession(
        gatewayBaseUrl = "https://gateway.example.test",
        binding = BackendAccountDeviceCookieBinding(
            actorId = actorId,
            accountGeneration = accountGeneration,
            authEpoch = AUTH_EPOCH,
            deviceId = DEVICE_ID,
            sessionId = SESSION_ID,
            expiresAtEpochMs = EXPIRES_AT_EPOCH_MS,
        ),
        cookiePair = v7CookiePair(actorId, accountGeneration),
        expiresAtEpochMs = EXPIRES_AT_EPOCH_MS,
    )

    private fun legacySession(): GatewayFieldSession = GatewayFieldSession.verified(
        gatewayBaseUrl = "https://gateway.example.test",
        actorId = "legacy-report-user",
        cookiePair = "walksafe_field_session=legacy.report.user.session",
        expiresAtEpochMs = Long.MAX_VALUE,
    )

    private fun v7CookiePair(actorId: String, accountGeneration: Long): String =
        "walksafe_field_session=" + listOf(
            "v7",
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(actorId.toByteArray(Charsets.UTF_8)),
            accountGeneration.toString(),
            AUTH_EPOCH.toString(),
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(DEVICE_ID.toByteArray(Charsets.UTF_8)),
            "general",
            EXPIRES_AT_EPOCH_SECONDS.toString(),
            SESSION_ID,
            "s".repeat(43),
        ).joinToString(".")

    private fun tamperCiphertext(envelope: String): String {
        val prefix = "\"ciphertext\":\""
        val index = envelope.indexOf(prefix) + prefix.length
        check(index >= prefix.length && index < envelope.length)
        val replacement = if (envelope[index] == 'A') 'B' else 'A'
        return envelope.replaceRange(index, index + 1, replacement.toString())
    }

    private class FakeStorage : ReportDeletionTrackerStorage {
        val values = linkedMapOf<String, String>()
        val deletedKeys = mutableListOf<String>()

        override fun read(storageKey: String): String? = values[storageKey]

        override fun write(storageKey: String, envelope: String): Boolean {
            values[storageKey] = envelope
            return true
        }

        override fun delete(storageKey: String): Boolean {
            deletedKeys += storageKey
            values.remove(storageKey)
            return true
        }
    }

    private class FakeKeyProvider : LocalAeadKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()

        override fun getOrCreate(alias: String): SecretKey = keys.getOrPut(alias) { newKey() }

        override fun getExisting(alias: String): SecretKey? = keys[alias]

        override fun delete(alias: String): Boolean {
            keys.remove(alias)
            return true
        }

        override fun createFreshAfterVerifiedPurge(alias: String): SecretKey =
            newKey().also { keys[alias] = it }

        private fun newKey(): SecretKey = KeyGenerator.getInstance("AES")
            .apply { init(256) }
            .generateKey()
    }

    private companion object {
        const val ACTOR_A = "123e4567-e89b-42d3-a456-426614174000"
        const val ACTOR_B = "123e4567-e89b-42d3-a456-426614174001"
        const val DEVICE_ID = "android-report-device"
        const val AUTH_EPOCH = 3L
        const val REQUEST_ID_A = "55555555-5555-4555-8555-555555555555"
        const val REQUEST_ID_B = "66666666-6666-4666-8666-666666666666"
        const val REQUEST_ID_C = "77777777-7777-4777-8777-777777777777"
        val EXPIRES_AT_EPOCH_SECONDS = System.currentTimeMillis() / 1_000L + 3_600L
        val EXPIRES_AT_EPOCH_MS = EXPIRES_AT_EPOCH_SECONDS * 1_000L
        val SESSION_ID = "i".repeat(43)
    }
}
