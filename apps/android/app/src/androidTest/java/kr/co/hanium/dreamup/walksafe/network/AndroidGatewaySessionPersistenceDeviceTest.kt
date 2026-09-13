package kr.co.hanium.dreamup.walksafe.network

import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueActorBinding
import kr.co.hanium.dreamup.walksafe.session.FirstRunReceiptHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses only synthetic credentials and test-owned preferences/Keystore aliases; no Activity or
 * network is started. Run with sessionProbePhase=write, then read and the same sessionProbeRunId
 * in separate instrumentation invocations to verify persistence across Android process death.
 * The default roundtrip checks new store/AEAD instances in one invocation. Read/roundtrip cleans
 * up only this probe's preferences and aliases; write leaves them for the subsequent read.
 */
@RunWith(AndroidJUnit4::class)
class AndroidGatewaySessionPersistenceDeviceTest {
    @Test
    fun encryptedPasswordSessionSurvivesStoreAndProcessRecreation() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("sessionProbePhase") ?: "roundtrip"
        require(phase in setOf("roundtrip", "write", "read"))
        val suppliedRunId = args.getString("sessionProbeRunId")
        require(phase == "roundtrip" || suppliedRunId != null)
        val runId = suppliedRunId ?: UUID.randomUUID().toString().replace("-", "")
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,32}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "walksafe_session_probe_$runId"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        fun policy(kind: String, version: Int) = AeadKeyPolicy(
            "walksafe.instrumentation.session.$runId.$kind.v", version, setOf(version),
        )
        val sessionPolicy = policy("session", 4)
        val installPolicy = policy("install", 1)
        val v3Policy = policy("legacy3", 3)
        val legacyPolicy = policy("legacy1", 1)
        val policies = listOf(sessionPolicy, installPolicy, v3Policy, legacyPolicy)
        fun store() = AndroidGatewaySessionStore(
            preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE),
            sessionAead = AndroidKeyStoreAead(sessionPolicy),
            installIdAead = AndroidKeyStoreAead(installPolicy),
            v3SessionAead = AndroidKeyStoreAead(v3Policy),
            legacySessionAead = AndroidKeyStoreAead(legacyPolicy),
        )
        if (phase != "read") {
            assertTrue("Use a fresh probe ID; existing test storage is preserved", preferences.all.isEmpty())
        }
        var keepForNextProcess = false
        try {
            if (phase != "read") {
                val deviceId = requireNotNull(store().getOrCreateInstallDeviceId())
                val now = System.currentTimeMillis()
                val expiresAt = (now / 1_000L + 3_600L) * 1_000L
                val cookie = fixtureCookie(deviceId, expiresAt)
                val session = GatewayFieldSession.backendAccountDeviceSession(
                    GATEWAY_ORIGIN,
                    BackendAccountDeviceCookieBinding(
                        ACTOR_ID, 7L, 3L, deviceId, SESSION_ID, expiresAt,
                    ),
                    cookie,
                    expiresAt,
                )
                val firstRun = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(
                    FirstRunOnboardingPolicy.initialEmailAccount(42L),
                    FirstRunOpaqueActorBinding.fromProvider(ACTOR_ID),
                    FirstRunReceiptHash.fromSha256Hex("a".repeat(64)),
                ).current
                assertEquals(GatewaySessionStoreResult.COMMITTED,
                    store().saveBackendDeviceIfAbsent(session, firstRun, GATEWAY_ORIGIN, now))
                assertTrue(preferences.edit().putInt("probe_writer_pid", Process.myPid()).commit())
                assertEncryptedOnDisk(context, preferenceName, cookie, deviceId)
            }

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keyStore.getKey(sessionPolicy.alias(4), null)
            assertNotNull("Session encryption must use an actual Android Keystore key", key)
            assertNull("Android Keystore key material must not be exportable", key?.encoded)
            assertTrue(keyStore.containsAlias(installPolicy.alias(1)))

            if (phase == "write") {
                keepForNextProcess = true
                return
            }
            val writerPid = preferences.getInt("probe_writer_pid", -1)
            assertTrue("The first phase must durably complete before reading", writerPid > 0)
            if (phase == "read") {
                assertNotEquals("Read must run in a new Android process", writerPid, Process.myPid())
            }
            val restored = requireNotNull(store().restoreBackendDevice(GATEWAY_ORIGIN))
            assertEquals(ACTOR_ID, restored.firstRunSnapshot.verifiedActorBinding?.value)
            assertEquals(42L, restored.firstRunSnapshot.epoch)
            assertEquals(GatewaySessionVerificationState.RESTORED_UNVERIFIED, restored.session.verificationState)
            assertFalse("Disk restoration alone cannot authorize requests", restored.session.isUsableFor(ACTOR_ID))
            val deviceId = requireNotNull(store().getOrCreateInstallDeviceId())
            assertEquals(deviceId, restored.session.deviceId)
            assertEquals(SESSION_ID, restored.version.familyId)
            assertEquals(3L, restored.version.rotation)
            assertFalse(store().isStorageBlocked())
            assertEncryptedOnDisk(context, preferenceName,
                fixtureCookie(deviceId, restored.session.expiresAtEpochMs), deviceId)

            assertEquals(GatewaySessionStoreResult.COMMITTED,
                store().removeBackendDeviceIfCurrent(restored.version))
            assertNull("Explicit logout must remain removed in another store instance",
                store().restoreBackendDevice(GATEWAY_ORIGIN))
            assertEquals("Logout preserves the installation binding",
                deviceId, store().getOrCreateInstallDeviceId())
        } finally {
            if (!keepForNextProcess) {
                assertTrue(context.deleteSharedPreferences(preferenceName))
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                policies.forEach { policy ->
                    policy.knownVersions.forEach { version ->
                        val alias = policy.alias(version)
                        keyStore.deleteEntry(alias)
                        keyStore.deleteEntry("$alias.generation")
                    }
                }
            }
        }
    }

    private fun assertEncryptedOnDisk(
        context: Context,
        preferenceName: String,
        cookie: String,
        deviceId: String,
    ) {
        val file = File(context.applicationInfo.dataDir, "shared_prefs/$preferenceName.xml")
        assertTrue("SharedPreferences commit must reach its XML file", file.isFile)
        val raw = file.readText()
        assertTrue(raw.contains("gateway_backend_device_encrypted_v1"))
        assertFalse("Raw bearer cookie must not appear in the preferences file", raw.contains(cookie))
        assertFalse("Actor must be inside authenticated ciphertext", raw.contains(ACTOR_ID))
        assertFalse("Installation ID must be inside authenticated ciphertext", raw.contains(deviceId))
    }

    private fun fixtureCookie(deviceId: String, expiresAt: Long): String {
        fun base64(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
        return "${GatewayFieldSession.COOKIE_NAME}=" + listOf(
            "v7", base64(ACTOR_ID), "7", "3", base64(deviceId), "general",
            (expiresAt / 1_000L).toString(), SESSION_ID, "s".repeat(43),
        ).joinToString(".")
    }

    private companion object {
        const val GATEWAY_ORIGIN = "https://session-probe.example.test"
        const val ACTOR_ID = "123e4567-e89b-42d3-a456-426614174000"
        val SESSION_ID = "i".repeat(43)
    }
}
