package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import kr.co.hanium.dreamup.walksafe.device.AndroidStartupCapabilityProbe
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled verified receipts/search/model input and a closed PENDING SDK probe, matching the
 * field liveness boundary. The real HOME destination button must start a real synthesis probe.
 * Its terminal result may be unavailable; neither capability success nor ACTIVE is injected.
 * Camera/GPS qualification, audible speech, activation and the full journey are separate tests.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityPendingTtsPreparationAndroidTest {
    @Test(timeout = 120_000L)
    fun actualHomeDestinationStartRestartsAStoppedPendingSynthesisProbe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", context.packageName)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA)) {
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
        }
        val previous = MainActivityOnCreateHomeDestinationIntegrationAndroidTest()
        val onboarding = call(previous, "completedOnboarding") as FirstRunOnboardingSnapshot
        val actor = requireNotNull(onboarding.verifiedActorBinding).value
        val session = GatewayFieldSession.verified("https://gateway.example.test", actor,
            "walksafe_field_session=synthetic_pending_tts_probe", System.currentTimeMillis() + 86_400_000L)
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
        val generation = GatewaySessionProcessCoordinator.snapshot().generation
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                call(previous, "awaitPrivacyStartupReady", scenario)
                lateinit var stopped: AndroidStartupCapabilityProbe
                lateinit var replacement: AndroidStartupCapabilityProbe
                scenario.onActivity { main ->
                    set(main, "firstRunOnboardingSnapshot", onboarding)
                    set(main, "reporterUserId", actor)
                    set(main, "priorityUserOnboardingActorId", actor)
                    set(main, "priorityUserOnboardingPolicy", call(previous, "completedPriorityEducation"))
                    set(main, "postLoginDeviceCheckSnapshot", call(previous, "completedDeviceCheck", actor, generation))
                    set(main, "initialAppPermissionExitRequired", false)
                    set(main, "isActivityForeground", true)
                    set(main, "detectorAvailable", true) // Controlled loaded-model platform observation.
                    set(main, "metricDistanceCapabilityOverride", false)
                    set(main, "offlineKoreanTextToSpeechCapabilityOverride", null)
                    val lifecycle = field(main, "walkSessionLifecycle") as WalkSessionLifecycle
                    if (!lifecycle.snapshot().isForeground) lifecycle.handle(WalkSessionEvent.EnteredForeground)
                    assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
                    (field(main, "startupCapabilityProbe") as AndroidStartupCapabilityProbe).close()
                    stopped = AndroidStartupCapabilityProbe(main) {}.also { it.close() }
                    assertNull(stopped.snapshot().offlineKoreanTextToSpeechAvailable)
                    set(main, "startupCapabilityProbe", stopped)
                    set(main, "startupCapabilityProbeStarted", false)
                    assertTrue(call(main, "shouldShowNativeHome") as Boolean)
                    call(main, "renderMainUi")
                    @Suppress("UNCHECKED_CAST")
                    val descendants = call(previous, "descendants", main.window.decorView) as List<View>
                    val card = requireNotNull(descendants.filterIsInstance<TextView>()
                        .firstOrNull { it.isShown && it.text.toString() == "길라잡이" })
                    assertTrue((call(previous, "clickableAncestor", card) as View).performClick())
                    val destination = DestinationSearchResult("pending-tts-fixture", "음성 준비 시험 목적지",
                        RoutePoint(37.0, 127.0), null, null, null, null)
                    // Search completion is a platform leaf; confirmation/start are the real controls.
                    main.deliverDestinationSearchResultForFixture(destination)
                    val button = field(main, "nativeDestinationStartButton") as View
                    assertTrue(button.isShown && button.performClick())
                    assertSame(destination, field(main, "pendingUiDestination"))
                    assertTrue("HOME must actually start the pending capability probe",
                        field(main, "startupCapabilityProbeStarted") as Boolean)
                    replacement = field(main, "startupCapabilityProbe") as AndroidStartupCapabilityProbe
                    assertNotSame("A closed SDK probe must be replaced before starting", stopped, replacement)
                    val nativeStarted = AndroidStartupCapabilityProbe::class.java.getDeclaredField("started")
                        .apply { isAccessible = true }.getBoolean(replacement)
                    assertTrue("The real synthesis SDK start must execute", nativeStarted)
                    assertFalse("No ACTIVE state is used to repair pending readiness",
                        lifecycle.snapshot().state == WalkSessionState.ACTIVE)
                }
                val deadline = SystemClock.elapsedRealtime() + 15_000L
                var terminal: Boolean? = null
                while (terminal == null && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(100L)
                    scenario.onActivity { terminal = replacement.snapshot().offlineKoreanTextToSpeechAvailable }
                }
                assertNotNull("Actual synthesis completion or its real timeout must end PENDING", terminal)
                scenario.onActivity { main ->
                    assertNotEquals("Measured probe completion must not fabricate a successful capability override",
                        true, field(main, "offlineKoreanTextToSpeechCapabilityOverride"))
                }
                println("ACTUAL_MAIN_PENDING_TTS_PROBE terminal_available=$terminal; acoustic/activation/full journey not verified")
            }
        } finally {
            assertNotNull(GatewaySessionProcessCoordinator.clear(generation))
        }
    }

    private fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name).get(main)
    private fun set(main: MainActivity, name: String, value: Any?) { MainActivityControllerTestFixture.field(name).set(main, value) }
    private fun call(owner: Any, name: String, vararg args: Any?): Any? {
        val method = owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        try { return method.apply { isAccessible = true }.invoke(owner, *args) }
        catch (failure: InvocationTargetException) { throw requireNotNull(failure.cause) }
    }
}
