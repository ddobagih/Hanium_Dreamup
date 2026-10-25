package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.tasks.TaskCompletionSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executes Main's existing View.postDelayed scheduler in READY HOME. Account receipts and SDK
 * location/heading sources are controlled leaves. No ACTIVE/readiness/route/match is injected.
 * This proves Android timer registration, repeated delivery, and owner retirement, not a walk,
 * sensor conversion, speech, or the complete HOME-to-arrival journey.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityCompassSchedulerAndroidTest {
    @Test(timeout = 120_000L)
    fun readyHomeRegistersTheProductionViewTimerAndRetiresItWithItsBinding() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", context.packageName)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        val priorFixture = MainActivityOnCreateHomeDestinationIntegrationAndroidTest()
        val onboarding = call(priorFixture, "completedOnboarding") as FirstRunOnboardingSnapshot
        val actor = requireNotNull(onboarding.verifiedActorBinding).value
        val session = GatewayFieldSession.verified("https://gateway.example.test", actor,
            "walksafe_field_session=synthetic_scheduler_probe", System.currentTimeMillis() + 86_400_000L)
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
        val generation = GatewaySessionProcessCoordinator.snapshot().generation
        val headingReads = AtomicInteger()
        val trackerStarts = AtomicInteger()
        val locationRegistrations = AtomicInteger()
        var registered: LocationCallback? = null
        var savedBinding: PositioningCollectionBinding? = null
        val client = Proxy.newProxyInstance(FusedLocationProviderClient::class.java.classLoader,
            arrayOf(FusedLocationProviderClient::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "requestLocationUpdates" -> {
                    registered = arguments!![1] as LocationCallback
                    locationRegistrations.incrementAndGet()
                    TaskCompletionSource<Void>().also { it.setResult(null) }.task
                }
                "removeLocationUpdates" -> TaskCompletionSource<Void>().also { it.setResult(null) }.task
                "getCurrentLocation" -> TaskCompletionSource<android.location.Location>().also { it.setResult(null) }.task
                "toString" -> "ControlledSchedulerFusedClient"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                else -> error("Unexpected Fused call: ${method.name}")
            }
        } as FusedLocationProviderClient
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                call(priorFixture, "awaitPrivacyStartupReady", scenario)
                scenario.onActivity { main ->
                    set(main, "firstRunOnboardingSnapshot", onboarding)
                    set(main, "reporterUserId", actor)
                    set(main, "priorityUserOnboardingActorId", actor)
                    set(main, "priorityUserOnboardingPolicy", call(priorFixture, "completedPriorityEducation"))
                    set(main, "postLoginDeviceCheckSnapshot", call(priorFixture, "completedDeviceCheck", actor, generation))
                    set(main, "initialAppPermissionExitRequired", false)
                    set(main, "isActivityForeground", true)
                    val lifecycle = field(main, "walkSessionLifecycle") as WalkSessionLifecycle
                    if (!lifecycle.snapshot().isForeground) lifecycle.handle(WalkSessionEvent.EnteredForeground)
                    assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
                    assertTrue(call(main, "shouldShowNativeHome") as Boolean)
                    call(main, "renderMainUi")
                    val compass = controller(main, "routeCompassSession") as RouteCompassSession
                    set(compass, "tracker", object : RouteCompassSession.Tracker {
                        override val available = true
                        override fun listener(listener: (RouteCompassObservationEvent) -> Unit) = Unit
                        override fun start() { trackerStarts.incrementAndGet() }
                        override fun stop() = Unit
                        override fun current(event: RouteCompassObservationEvent) = true
                        override fun heading(nowMs: Long): RouteCompassHeadingResult {
                            headingReads.incrementAndGet()
                            return RouteCompassHeadingResult(null, RouteCompassHeadingReason.ORIENTATION_MISSING)
                        }
                    })
                    call(main, "stopLocationUpdates", true)
                    set(main, "fusedLocationClient", client)
                    call(main, "startLocationUpdatesIfAllowed", false)
                    assertNotNull(registered)
                    savedBinding = field(main, "positioningCollectionBinding") as PositioningCollectionBinding
                    assertSame(session, requireNotNull(savedBinding).owner.gatewaySession)
                    assertTrue("Main's actual compass View must permit timer registration",
                        (field(compass, "callbacks") as RouteCompassSession.Callbacks).viewReady)
                    // Starting the collection normally starts the tracker. Calling the same production
                    // entry also covers environments whose optional orientation source is unavailable.
                    compass.ensureEarthOrientationForLocation()
                    assertTrue(trackerStarts.get() > 0)
                    assertNotNull("The production 300ms Runnable must be registered",
                        field(compass, "pendingRouteCompassRefresh"))
                }
                val before = headingReads.get()
                val deadline = SystemClock.elapsedRealtime() + 5_000L
                while (headingReads.get() < before + 2 && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(50L)
                    instrumentation.waitForIdleSync()
                }
                assertTrue("Android's production View timer must run and register its successor",
                    headingReads.get() >= before + 2)
                scenario.onActivity { main ->
                    assertSame("Timer evaluation must retain the original HOME positioning binding",
                        savedBinding, field(main, "positioningCollectionBinding"))
                    assertEquals("Timer ticks must not re-register Fused location", 1, locationRegistrations.get())
                    val lifecycle = field(main, "walkSessionLifecycle") as WalkSessionLifecycle
                    assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
                    val compass = controller(main, "routeCompassSession") as RouteCompassSession
                    call(main, "stopLocationUpdates", true)
                    assertNull(field(main, "positioningCollectionBinding"))
                    assertNull(field(compass, "pendingRouteCompassRefresh"))
                    assertNull(field(compass, "compassObservationOwner"))
                }
                val afterRetirement = headingReads.get()
                Thread.sleep(700L)
                instrumentation.waitForIdleSync()
                assertEquals("A retired binding must not deliver another compass evaluation",
                    afterRetirement, headingReads.get())
            }
        } finally {
            assertNotNull(GatewaySessionProcessCoordinator.clear(generation))
        }
    }

    private fun controller(main: MainActivity, name: String): Any =
        MainActivityControllerTestFixture.controller(main, name)
    private fun field(owner: Any, name: String): Any? =
        if (owner is MainActivity) MainActivityControllerTestFixture.field(name).get(owner)
        else owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun set(owner: Any, name: String, value: Any?) {
        if (owner is MainActivity) MainActivityControllerTestFixture.field(name).set(owner, value)
        else owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }
    private fun call(owner: Any, name: String, vararg args: Any?): Any? {
        val method = owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        try { return method.apply { isAccessible = true }.invoke(owner, *args) }
        catch (failure: InvocationTargetException) { throw requireNotNull(failure.cause) }
    }
}
