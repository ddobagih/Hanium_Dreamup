package kr.co.hanium.dreamup.walksafe

import android.content.SharedPreferences
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.fieldlog.GuidanceDiagnosticRuntimeCoordinator
import kr.co.hanium.dreamup.walksafe.fieldlog.GuidanceDiagnosticTransport
import kr.co.hanium.dreamup.walksafe.fieldlog.GuidanceDiagnosticAck
import kr.co.hanium.dreamup.walksafe.fieldlog.GuidanceDiagnosticBatch
import kr.co.hanium.dreamup.walksafe.fieldlog.GuidanceDiagnosticBinding
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/** Actual Main toggle + real coordinator. The absent Android Looper is an intentionally failing
 * SDK leaf; no Activity, disk, HTTP, native preference persistence or device success is claimed. */
class MainActivityGuidanceDiagnosticPreferenceBehaviorTest {
    @Test fun enablingDoesNotPersistTrueBeforeTheOwnedPurgeAndActivationComplete() {
        val f = Fixture(persisted = false)
        try {
            f.toggle()
            assertFalse(f.persisted)
            assertTrue(f.writes.isEmpty())
            assertTrue(f.runtime.state().requestedLocalEnabled)
            assertFalse(f.runtime.state().enabled)
            assertFalse(f.runtime.state().authorized)
            assertFalse(f.runtime.consumePendingEnableCommit(f.session, 1, 1L))
        } finally { f.runtime.close() }
    }

    @Test fun aStoredOnSettingCanBeTurnedOffEvenThoughEffectiveLoggingIsAlreadyBlocked() {
        val f = Fixture(persisted = true)
        try {
            assertFalse(f.runtime.state().enabled)
            f.toggle()
            assertFalse(f.persisted)
            assertEquals(listOf(false), f.writes)
            assertFalse(f.runtime.state().requestedLocalEnabled)
            assertFalse(f.runtime.state().enabled)
            assertFalse(f.runtime.state().authorized)
        } finally { f.runtime.close() }
    }

    private class Fixture(var persisted: Boolean) {
        val writes = mutableListOf<Boolean>()
        private val work = mutableListOf<Runnable>()
        val session = GatewayFieldSession.legacyVerified("http://127.0.0.1", "actor", "fixture-device",
            "walksafe_field_session=fixture", Long.MAX_VALUE).also {
            it.javaClass.getDeclaredField("backendAccountGeneration").apply { isAccessible = true }.set(it, 1L)
        }
        val runtime = GuidanceDiagnosticRuntimeCoordinator(true, "a".repeat(64),
            queueFactory = { error("Unverified Main fixture must not open storage") },
            transport = object : GuidanceDiagnosticTransport {
                override fun upload(binding: GuidanceDiagnosticBinding, batch: GuidanceDiagnosticBatch): CancellableNetworkCall<GuidanceDiagnosticAck> = error("No network")
            }, preference = { error("No network") }, authorizeIfAbsent = { error("No network") },
            setPreference = { _, _, _ -> error("No network") }, wallTimeMs = { 1_000L }, elapsedRealtimeMs = { 1_000L },
            worker = Executor { work += it }, ownsWorker = false)
        private val main: MainActivity
        init {
            runtime.publishAuthority(GuidanceDiagnosticRuntimeCoordinator.Authority(session, 1, 1L,
                admitted = false, networkAllowed = false, localEnabled = persisted))
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
            main = unsafe.allocateInstance(MainActivity::class.java) as MainActivity
            main.javaClass.getDeclaredField("guidanceDiagnosticRuntime").apply { isAccessible = true }.set(main, runtime)
            val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putBoolean" -> { assertEquals("guidance_diagnostic_enabled", args!![0]); persisted = args[1] as Boolean; writes += persisted; proxy }
                    "apply" -> null
                    "commit" -> true
                    else -> error("Unexpected editor method ${method.name}")
                }
            }
            val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "edit" -> editor
                    "getBoolean" -> { assertEquals("guidance_diagnostic_enabled", args!![0]); persisted }
                    else -> error("Unexpected preferences method ${method.name}")
                }
            }
            main.javaClass.getDeclaredField("stepLengthPrefs").apply { isAccessible = true }.set(main, prefs)
        }
        fun toggle() {
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup()).findVirtual(
                MainActivity::class.java, "toggleGuidanceDiagnostics", MethodType.methodType(Void.TYPE),
            ).invokeWithArguments(main)
        }
    }
}
