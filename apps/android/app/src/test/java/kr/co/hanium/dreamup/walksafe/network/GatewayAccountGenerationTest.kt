package kr.co.hanium.dreamup.walksafe.network

import android.content.SharedPreferences
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAccountGenerationTest {
    @Test
    fun actualGenerationIsBoundToSessionLeaseAndPersistenceButLegacyStaysUnknown() {
        val current = login(body().put("account_generation", 7L))
        assertEquals(7L, current.accountGenerationOrNull)
        assertEquals(7L, current.lease.backendAccountGeneration)
        val saved = requireNotNull(current.persistenceSnapshotOrNull())
        assertEquals(7L, saved.accountGeneration)
        val restored = requireNotNull(GatewayFieldSession.restore(saved, ORIGIN, ACTOR, DEVICE, NOW))
        assertEquals(7L, restored.accountGenerationOrNull)
        assertEquals(GatewaySessionVerificationState.RESTORED_UNVERIFIED, restored.verificationState)
        assertNull(login(body()).accountGenerationOrNull)
    }

    @Test
    fun malformedOrUnsafeGenerationCannotBecomeAccountAuthority() {
        listOf<Any>(0L, -1L, MAX_ACCOUNT_GENERATION + 1L, "7", JSONObject.NULL).forEach {
            assertThrows(GatewaySessionHttpException::class.java) { login(body().put("account_generation", it)) }
        }
        val decimal = body().put("account_generation", 7L).toString().replace("\"account_generation\":7", "\"account_generation\":7.0")
        assertThrows(GatewaySessionHttpException::class.java) { login(JSONObject(decimal), decimal) }
    }

    @Test
    fun knownGenerationMustMatchStatusAndCannotBeDowngradedToLegacy() {
        val session = login(body().put("account_generation", 7L))
        for (generation in listOf<Any?>(7L, 8L, null, "7", 0L)) {
            val status = status().also { if (generation != null) it.put("account_generation", generation) }
            val result = GatewayFieldSessionClient(Transport(status = GatewayHttpResponse(200, status.toString())))
                .revalidate(session, ACTOR, NOW)
            assertEquals(if (generation == 7L) GatewaySessionRevalidationStatus.READY else GatewaySessionRevalidationStatus.NOT_READY, result.status)
        }
    }

    @Test
    fun refreshCannotChangeOrDropAKnownAccountGeneration() {
        for (generation in listOf<Long?>(7L, 8L, null)) {
            resetGatewayRefreshProofFenceForTests()
            val original = login(body().put("account_generation", 7L))
            val renewedBody = body().put("rotation", 1L).put("refresh_token", "s".repeat(64))
                .also { if (generation != null) it.put("account_generation", generation) }
            val response = GatewayHttpResponse(200, renewedBody.toString(), mapOf("Set-Cookie" to
                "${GatewayFieldSession.COOKIE_NAME}=renewed-access-generation-0000001; Path=/; HttpOnly; SameSite=Strict; Max-Age=60; Secure"))
            val client = GatewayFieldSessionClient(Transport(response), elapsedRealtimeNanos = { 0L })
            if (generation == 7L) {
                assertEquals(7L, client.renew(original, ACTOR, NOW).session.accountGenerationOrNull)
            } else {
                assertThrows(GatewaySessionHttpException::class.java) { client.renew(original, ACTOR, NOW) }
            }
            assertFalse(original.isUsableFor(ACTOR, NOW))
        }
        resetGatewayRefreshProofFenceForTests()
    }

    @Test
    fun encryptedStoreSessionCodecPreservesTheExactGenerationAndRejectsCoercion() {
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, _, _ -> error("codec test cannot touch preferences") } as SharedPreferences
        val store = AndroidGatewaySessionStore(preferences)
        val encode = AndroidGatewaySessionStore::class.java.getDeclaredMethod("encodeSession", GatewayFieldSessionPersistence::class.java).apply { isAccessible = true }
        val decode = AndroidGatewaySessionStore::class.java.getDeclaredMethod("decodeSession", JSONObject::class.java).apply { isAccessible = true }
        val saved = requireNotNull(login(body().put("account_generation", 7L)).persistenceSnapshotOrNull())
        val encoded = encode.invoke(store, saved) as JSONObject
        assertEquals(7L, (decode.invoke(store, encoded) as GatewayFieldSessionPersistence).accountGeneration)
        val unexpected = JSONObject(encoded.toString()).put("unexpected", true)
        assertThrows(InvocationTargetException::class.java) { decode.invoke(store, unexpected) }
        encoded.put("account_generation", "7")
        assertThrows(InvocationTargetException::class.java) { decode.invoke(store, encoded) }
        encoded.remove("account_generation")
        assertNull((decode.invoke(store, encoded) as GatewayFieldSessionPersistence).accountGeneration)
    }

    private fun login(value: JSONObject, text: String = value.toString()): GatewayFieldSession =
        GatewayFieldSessionClient(Transport(GatewayHttpResponse(200, text,
            mapOf("Set-Cookie" to "${GatewayFieldSession.COOKIE_NAME}=$ACCESS_TOKEN; Path=/; HttpOnly; SameSite=Strict; Max-Age=60; Secure"))), elapsedRealtimeNanos = { 0L })
            .login(ORIGIN, ACTOR, ACCOUNT_TOKEN, NOW, enableLongLivedSession = true, deviceId = DEVICE)

    private fun body(): JSONObject = JSONObject().put("session_scope", "general").put("actor_id", ACTOR)
        .put("device_id", DEVICE).put("family_id", FAMILY).put("rotation", 0L).put("refresh_token", "r".repeat(64))
        .put("access_expires_at_epoch_ms", ACCESS).put("idle_expires_at_epoch_ms", IDLE).put("absolute_expires_at_epoch_ms", ABSOLUTE)
    private fun status(): JSONObject = body().also { it.remove("refresh_token") }.put("required", true)
        .put("authenticated", true).put("session_mode", "long_lived")

    private class Transport(private val response: GatewayHttpResponse? = null, private val status: GatewayHttpResponse? = null) : GatewaySessionTransport {
        override fun postJson(url: String, body: String) = response ?: error("unexpected POST")
        override fun get(url: String, headers: Map<String, String>) = status ?: error("unexpected GET")
        override fun delete(url: String, headers: Map<String, String>) = GatewayHttpResponse(204, "")
        override fun deleteJson(url: String, body: String) = GatewayHttpResponse(204, "")
    }
    private companion object {
        const val ORIGIN = "https://field.example"
        const val ACTOR = "tester-01"
        const val DEVICE = "android-device-generation-01"
        const val FAMILY = "ffffffffffffffffffffffffffffffff"
        const val ACCOUNT_TOKEN = "field-account-token-generation-000001"
        const val ACCESS_TOKEN = "access-token-generation-000000001"
        const val NOW = 1_000L
        const val ACCESS = 61_000L
        const val IDLE = 121_000L
        const val ABSOLUTE = 241_000L
    }
}
