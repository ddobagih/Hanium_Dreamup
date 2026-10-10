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
        val expiryProbe=args.getString("sessionProbeExpiry")=="true"
        require(!expiryProbe || phase=="roundtrip")
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
                val expiresAt = (now / 1_000L + if(expiryProbe) 5L else 3_600L) * 1_000L
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

            if(expiryProbe) {
                val remaining=restored.session.expiresAtEpochMs-System.currentTimeMillis()
                assertTrue("Positive restoration must precede actual expiry",remaining>0)
                Thread.sleep(remaining+100L)
                assertNull("Expired credentials must not restore",store().restoreBackendDevice(GATEWAY_ORIGIN))
                assertFalse("Ordinary expiry must not be treated as corrupted storage",store().isStorageBlocked())
                assertEquals("Expiry preserves the installation identity",deviceId,store().getOrCreateInstallDeviceId())
                assertFalse(preferences.contains("gateway_backend_device_encrypted_v1"))
                val newExpiry=(System.currentTimeMillis()/1000+3600)*1000
                val replacement=GatewayFieldSession.backendAccountDeviceSession(GATEWAY_ORIGIN,
                    BackendAccountDeviceCookieBinding(ACTOR_ID,7L,3L,deviceId,SESSION_ID,newExpiry),
                    fixtureCookie(deviceId,newExpiry),newExpiry)
                assertEquals(GatewaySessionStoreResult.COMMITTED,store().saveBackendDeviceIfAbsent(
                    replacement,restored.firstRunSnapshot,GATEWAY_ORIGIN))
                val fresh=requireNotNull(store().restoreBackendDevice(GATEWAY_ORIGIN))
                assertEquals(newExpiry,fresh.session.expiresAtEpochMs)
                assertEquals(deviceId,fresh.session.deviceId)
                assertFalse("New disk restoration still requires server verification",fresh.session.isUsableFor(ACTOR_ID))
                assertEquals(GatewaySessionStoreResult.COMMITTED,store().removeBackendDeviceIfCurrent(fresh.version))
                assertNull(store().restoreBackendDevice(GATEWAY_ORIGIN))
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
                    putString("stream","SESSION_EXPIRY actual_clock_wait_ms=${remaining+100L} expired_bearer_removed=true storage_blocked=false install_identity_preserved=true synthetic_replacement_saved=true restored_unverified=true\n")
                })
                return
            }

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

    /** Each fault runs write/read in separate processes because the production block is process-wide. */
    @Test fun damagedCredentialsStayBlockedAcrossProcessRestart() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("This fault probe requires separate write/read instrumentation processes",
            args.getString("sessionFaultPhase") != null)
        val phase=requireNotNull(args.getString("sessionFaultPhase"))
        val fault=requireNotNull(args.getString("sessionFault"))
        val runId=requireNotNull(args.getString("sessionFaultRunId"))
        require(phase in setOf("write","read"))
        require(fault in setOf("ciphertext","session_key","install_key","origin"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,32}")))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="walksafe_session_fault_$runId"
        val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        fun policy(kind:String,version:Int)=AeadKeyPolicy(
            "walksafe.instrumentation.fault.$runId.$kind.v",version,setOf(version))
        val sessionPolicy=policy("session",4);val installPolicy=policy("install",1)
        val v3Policy=policy("legacy3",3);val legacyPolicy=policy("legacy1",1)
        val policies=listOf(sessionPolicy,installPolicy,v3Policy,legacyPolicy)
        fun store()=AndroidGatewaySessionStore(prefs,AndroidKeyStoreAead(sessionPolicy),
            AndroidKeyStoreAead(installPolicy),AndroidKeyStoreAead(v3Policy),AndroidKeyStoreAead(legacyPolicy))
        val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val lostAlias=when(fault){"session_key"->sessionPolicy.alias(4);"install_key"->installPolicy.alias(1);else->null}
        var keep=false
        try {
            if(phase=="write") {
                assertTrue("Fresh isolated preferences required",prefs.all.isEmpty())
                assertFalse(store().isStorageBlocked())
                val deviceId=requireNotNull(store().getOrCreateInstallDeviceId())
                val now=System.currentTimeMillis();val expiry=(now/1000+3600)*1000
                val session=GatewayFieldSession.backendAccountDeviceSession(GATEWAY_ORIGIN,
                    BackendAccountDeviceCookieBinding(ACTOR_ID,7L,3L,deviceId,SESSION_ID,expiry),
                    fixtureCookie(deviceId,expiry),expiry)
                val firstRun=FirstRunOnboardingPolicy.recordVerifiedEmailLogin(
                    FirstRunOnboardingPolicy.initialEmailAccount(42L),
                    FirstRunOpaqueActorBinding.fromProvider(ACTOR_ID),
                    FirstRunReceiptHash.fromSha256Hex("a".repeat(64))).current
                assertEquals(GatewaySessionStoreResult.COMMITTED,
                    store().saveBackendDeviceIfAbsent(session,firstRun,GATEWAY_ORIGIN,now))
                assertNotNull("Positive control before fault",store().restoreBackendDevice(GATEWAY_ORIGIN))
                assertTrue(prefs.edit().putInt("probe_writer_pid",Process.myPid()).commit())
                if(fault=="ciphertext") {
                    val envelope=requireNotNull(prefs.getString("gateway_backend_device_encrypted_v1",null))
                    val encoded=org.json.JSONObject(envelope).getString("ciphertext")
                    val bytes=Base64.getDecoder().decode(encoded)
                    bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
                    val altered=envelope.replace(encoded,Base64.getEncoder().encodeToString(bytes))
                    assertNotEquals(envelope,altered)
                    assertTrue(prefs.edit().putString("gateway_backend_device_encrypted_v1",altered).commit())
                }
                if(lostAlias!=null) {
                    assertTrue(keys.containsAlias(lostAlias));keys.deleteEntry(lostAlias)
                    assertFalse(keys.containsAlias(lostAlias))
                }
                val origin=if(fault=="origin") "https://different-session-probe.example.test" else GATEWAY_ORIGIN
                assertNull("Damaged or mismatched credentials cannot restore",store().restoreBackendDevice(origin))
            } else {
                val writer=prefs.getInt("probe_writer_pid",-1)
                assertTrue(writer>0);assertNotEquals("Must start a new Android process",writer,Process.myPid())
            }
            assertTrue("Block marker must reach disk",prefs.getBoolean("gateway_login_bundle_fail_closed_v4",false))
            assertFalse("Invalid bearer is removed",prefs.contains("gateway_backend_device_encrypted_v1"))
            assertTrue("A fresh store cannot clear the failure",store().isStorageBlocked())
            assertNull(store().restoreBackendDevice(GATEWAY_ORIGIN))
            assertNull("No automatic installation identity replacement",store().getOrCreateInstallDeviceId())
            if(lostAlias!=null) assertFalse("Lost key cannot silently regenerate",keys.containsAlias(lostAlias))
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
                putString("stream","SESSION_FAULT fault=$fault phase=$phase blocked=true bearer_removed=true pid=${Process.myPid()} writer_pid=${prefs.getInt("probe_writer_pid",-1)}\n")
            })
            keep=phase=="write"
        } finally {
            if(!keep) {
                assertTrue(context.deleteSharedPreferences(name))
                policies.forEach{p->p.knownVersions.forEach{v->keys.deleteEntry(p.alias(v));keys.deleteEntry(p.alias(v)+".generation")}}
            }
        }
    }

    @Test fun faultProbeResourcesAreCleanedUp() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val files=File(context.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty()
            .filter{it.name.startsWith("walksafe_session_fault_")}
        val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val aliases=java.util.Collections.list(keys.aliases())
            .filter{it.startsWith("walksafe.instrumentation.fault.")}
        assertTrue("Only test-owned preference files are inspected: remaining=${files.size}",files.isEmpty())
        assertTrue("Only test-owned aliases are inspected: remaining=${aliases.size}",aliases.isEmpty())
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
            putString("stream","SESSION_FAULT_CLEANUP test_preferences=0 test_keys_and_generation_markers=0\n")
        })
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
