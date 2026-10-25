package kr.co.hanium.dreamup.walksafe

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URI
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses Main's actual origin selector and read-only saved preferences; no credentials or HTTP. */
class MainActivityCloudflareOriginUpgradeBehaviorTest {
    @Test fun anOldQuickTunnelSettingDoesNotOverrideTheCurrentFieldBuild() {
        val build = BuildConfig.WALKSAFE_GATEWAY_ORIGIN
        assumeTrue(BuildConfig.DEBUG && URI(build).host?.endsWith(".trycloudflare.com") == true)
        assertEquals(build, configured("https://older-field-session.trycloudflare.com"))
    }

    @Test fun anExplicitFixedServerRemainsSelected() {
        assertEquals("https://gateway.example.test", configured("https://gateway.example.test"))
    }

    @Test fun theLoopbackDevelopmentSettingRemainsSelected() {
        assertEquals("http://127.0.0.1:8081", configured("http://127.0.0.1:8081"))
    }

    @Test fun aFreshInstallationUsesTheBuildOrigin() {
        assertEquals(BuildConfig.WALKSAFE_GATEWAY_ORIGIN, configured(null))
    }

    @Test fun anInvalidStoredValueIsNotSilentlyReplaced() {
        assertNull(configured("https://user:secret@example.test"))
    }

    private fun configured(saved: String?): String? {
        val main = MainActivity()
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            check(method.name == "getString" && args?.get(0) == "gateway_origin") {
                "Origin selection must not write settings or access authentication: ${method.name}"
            }
            saved ?: args?.get(1)
        } as SharedPreferences
        MainActivity::class.java.getDeclaredField("stepLengthPrefs").apply { isAccessible = true }.set(main, prefs)
        return MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, "configuredGatewayOriginOrNull", MethodType.methodType(String::class.java))
            .invokeWithArguments(main) as String?
    }
}
