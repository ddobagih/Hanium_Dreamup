package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.TaskCompletionSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Main creates the registration/callback binding and handles an initial delivered location. Later
 * endpoint evidence is synthetic input sent directly to the Main RouteNavigator. The resulting
 * automatic update invokes Main's production completion and final speech. This does not exercise
 * Main.onCreate, recognized voice phrases, a human-operated flow,
 * or OS Fused delivery.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityFusedLocationRegistrationAndroidTest {
    @Test fun asynchronousRegistrationFailureRetiresItsBindingAndAllowsANewRequest() = withFixture { f ->
        val registration = onMain(f.instrumentation) {
            f.start()
            assertEquals(1, f.transport.requests.size)
            assertNotNull(field(f.main, "positioningCollectionBinding"))
            assertNotNull(field(f.main, "locationCollectionOwner"))
            assertNull(field(f.main, "latestTrustedLocation"))
            assertNull(field(f.main, "latestRawRouteLocation"))
            f.transport.requests.single().also { assertFalse(it.completion.task.isComplete) }
        }
        onMain(f.instrumentation) { registration.completion.setException(IllegalStateException("controlled_fused_registration_failure")) }
        f.instrumentation.waitForIdleSync()
        onMain(f.instrumentation) {
            assertTrue(registration.completion.task.isComplete)
            assertFalse(registration.completion.task.isSuccessful)
            assertNull(field(f.main, "positioningCollectionBinding"))
            assertNull(field(f.main, "locationCallback"))
            assertNull(field(f.main, "locationCollectionOwner"))
            assertNull(field(f.main, "latestTrustedLocation"))
            assertNull(field(f.main, "latestRawRouteLocation"))
            assertTrue(f.transport.removed.any { it === registration.callback })
            f.start()
            assertEquals(2, f.transport.requests.size)
            assertNotSame(registration.callback, f.transport.requests.last().callback)
            assertTrue(f.bindingIsCurrent())
        }
    }

    @Test fun successfulRegistrationDoesNotInventAReceiptAndUsesTheOriginalDeliveredLocation() = withFixture { f ->
        val registration = onMain(f.instrumentation) { f.start(); f.transport.requests.single() }
        onMain(f.instrumentation) { registration.completion.setResult(null) }
        f.instrumentation.waitForIdleSync()
        onMain(f.instrumentation) {
            assertTrue(registration.completion.task.isSuccessful)
            assertSame(registration.callback, field(f.main, "locationCallback"))
            assertTrue(f.bindingIsCurrent())
            assertNull(field(f.main, "latestTrustedLocation"))
            assertNull(field(f.main, "latestRawRouteLocation"))
            val binding = field(f.main, "positioningCollectionBinding")
            f.start()
            assertEquals(1, f.transport.requests.size)
            assertSame(binding, field(f.main, "positioningCollectionBinding"))
            val fix = f.location()
            registration.callback.onLocationResult(LocationResult.create(listOf(fix)))
            f.assertOriginalFix(fix)
            assertSame(binding, field(f.main, "positioningCollectionBinding"))
        }
    }

    @Test fun actualFusedCallbackUpdatesTheActiveMainRouteNavigator() = withFixture { f ->
        onMain(f.instrumentation) {
            f.prepareActiveNavigation()
            val gates = listOf("firstRunOnboardingComplete", "postLoginDeviceCheckPassesFeatureGate",
                "isWalkSessionRuntimeActive", "isStartupCapabilityConfirmed", "navigationEnvironmentOutputsAllowed")
                .associateWith { f.invoke(it, java.lang.Boolean.TYPE) as Boolean }
            assertTrue("Main navigation gate components: $gates", gates.values.all { it })
            assertEquals(true, f.invoke("currentNavigationCollectionAllowsWork", java.lang.Boolean.TYPE))
            f.start()
        }
        val registration = f.transport.requests.single()
        onMain(f.instrumentation) { registration.completion.setResult(null) }
        f.instrumentation.waitForIdleSync()
        onMain(f.instrumentation) {
            val fix = f.locationAtRoute(50.0)
            registration.callback.onLocationResult(LocationResult.create(listOf(fix)))
            f.assertOriginalFix(fix)
            val navigator = field(f.main, "routeNavigator") as kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
            assertTrue("Actual Main callback must update the active route matcher", navigator.currentRouteMatch() != null)
            assertTrue("Route match must retain measured progress from the callback",
                requireNotNull(requireNotNull(navigator.currentRouteMatch()).geometricProgressM) > 0.0)
            assertEquals(true, f.invoke("currentNavigationCollectionAllowsWork", java.lang.Boolean.TYPE))
        }
    }

    @Test(timeout = 30_000L)
    fun mainCallbackOwnerSyntheticAutomaticArrivalEndsTheWalk() = withFixture { f ->
        onMain(f.instrumentation) {
            f.prepareActiveNavigation()
            f.start()
        }
        val registration = f.transport.requests.single()
        onMain(f.instrumentation) { registration.completion.setResult(null) }
        f.instrumentation.waitForIdleSync()

        onMain(f.instrumentation) {
            val first = f.locationAtRoute(50.0)
            registration.callback.onLocationResult(LocationResult.create(listOf(first)))
            f.assertOriginalFix(first)
            val navigator = field(f.main, "routeNavigator") as kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
            assertNotNull(navigator.currentRouteMatch())
        }
        val arrivalUpdate = onMain(f.instrumentation) {
            val navigator = field(f.main, "routeNavigator") as kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
            val routePoint: (Double) -> kr.co.hanium.dreamup.walksafe.navigation.RoutePoint = { eastM ->
                kr.co.hanium.dreamup.walksafe.navigation.RoutePoint(37.0,
                    127.0 + eastM / (111_194.92664455874 * kotlin.math.cos(Math.toRadians(37.0))))
            }
            val route = kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute("FUSED-MAIN-ARRIVAL",
                kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary(400, 400),
                listOf(routePoint(0.0), routePoint(400.0)), listOf(
                    kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteGuidePoint(0, routePoint(0.0), "출발", 0, 400,
                        turnType = 200, pointType = "SP"),
                    kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteGuidePoint(1, routePoint(200.0), "안내", 200, 200,
                        turnType = 13)))
            setField(f.main, "reporterUserId", ACTOR)
            MainActivity::class.java.getDeclaredMethod("buildContentView").apply { isAccessible = true }.invoke(f.main)
            setField(f.main, "nativeUiPage", kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage.GUIDANCE)
            val destination = kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult(
                "arrival", "목적지", routePoint(400.0), null, null, null, null)
            setField(f.main, "pendingUiDestination", destination)
            setField(f.main, "currentDestination", destination.point)
            var at = SystemClock.elapsedRealtime()
            val start = routePoint(0.0)
            val acquisition = MainActivityControllerTestFixture.controller(f.main, "routeAcquisitionController")
                as kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController
            acquisition.installRequestedRoute(route, destination.point,
                TrustedLocation(start.latitude, start.longitude, 3f, at), null, "user_destination")
            var candidate: kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUpdate? = null
            for (eastM in 10..400 step 10) {
                at += 3_000L
                val location = f.locationAtRoute(eastM.toDouble())
                candidate = navigator.update(TrustedLocation(location.latitude, location.longitude,
                    location.accuracy, at), at, false, facingObservation = null)
                if (candidate.arrivalCandidate) break
            }
            repeat(3) {
                if (candidate?.arrivalCandidate != true) {
                    at += 3_000L
                    val endpoint = f.locationAtRoute(400.0)
                    candidate = navigator.update(TrustedLocation(endpoint.latitude, endpoint.longitude,
                        endpoint.accuracy, at), at, false, facingObservation = null)
                }
            }
            assertTrue("Navigator must produce an arrival candidate from synthetic route evidence; last=${candidate?.reason}",
                candidate?.arrivalCandidate == true)
            assertEquals("Navigator must retain endpoint evidence; match=${navigator.currentRouteMatch()} " +
                "navigation=${field(f.main, "latestNavigationState")}",
                kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION,
                navigator.pendingUserDecision())
            requireNotNull(candidate)
        }

        onMain(f.instrumentation) {
            val navigator = field(f.main, "routeNavigator") as kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
            MainActivity::class.java.getDeclaredMethod("applyRouteDeviationSafetyUpdate",
                kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUpdate::class.java)
                .apply { isAccessible = true }.invoke(f.main, arrivalUpdate)
            assertFalse("Automatic arrival must retire this active route", navigator.hasRoute())
            assertNull(navigator.pendingDecisionToken())
        }
        f.instrumentation.waitForIdleSync()
        onMain(f.instrumentation) {
            val lifecycle = field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle
            assertEquals(WalkSessionState.ENDED, lifecycle.snapshot().state)
            assertEquals("HOME", field(f.main, "nativeUiPage").toString())
            assertFalse("Automatic arrival cleanup must retire active route collection", f.invoke("currentNavigationCollectionAllowsWork",
                java.lang.Boolean.TYPE) as Boolean)
            assertFalse(field(f.main, "isRouteActive") as Boolean)
            assertSame("The verified foreground account may retain its separate home location owner",
                registration.callback, field(f.main, "locationCallback"))
            assertTrue(f.bindingIsCurrent())
        }
    }

    @Test fun lateFailureFromRetiredGatewayOwnerCannotRetireTheNewBinding() = withFixture { f ->
        val old = onMain(f.instrumentation) { f.start(); f.transport.requests.single() }
        val current = onMain(f.instrumentation) {
            f.replaceGatewayOwner()
            f.start()
            assertEquals(2, f.transport.requests.size)
            assertTrue(f.transport.removed.any { it === old.callback })
            assertNotSame(old.callback, field(f.main, "locationCallback"))
            assertTrue(f.bindingIsCurrent())
            f.transport.requests.last().also { it.completion.setResult(null) }
        }
        f.instrumentation.waitForIdleSync()
        val before = onMain(f.instrumentation) {
            val fix = f.location()
            current.callback.onLocationResult(LocationResult.create(listOf(fix)))
            f.assertOriginalFix(fix)
            f.checkpoint()
        }
        onMain(f.instrumentation) { old.completion.setException(IllegalStateException("controlled_retired_registration_failure")) }
        f.instrumentation.waitForIdleSync()
        onMain(f.instrumentation) {
            assertEquals(before, f.checkpoint())
            assertSame(current.callback, field(f.main, "locationCallback"))
            assertFalse(f.transport.removed.any { it === current.callback })
            assertTrue(f.bindingIsCurrent())
        }
    }

    private data class Registration(val callback: LocationCallback, val completion: TaskCompletionSource<Void>)
    private data class Checkpoint(val binding: Any?, val owner: Any?, val callback: Any?, val generation: Int,
        val coordinator: PositioningCoordinator, val inputGeneration: Long, val trusted: Any?, val raw: Any?,
        val navigationState: Any?, val observer: Any?)

    private class FusedTransport {
        val requests = mutableListOf<Registration>()
        val removed = mutableListOf<LocationCallback>()
        val client = Proxy.newProxyInstance(FusedLocationProviderClient::class.java.classLoader,
            arrayOf(FusedLocationProviderClient::class.java)) { proxy, method, arguments ->
            check(Looper.myLooper() == Looper.getMainLooper())
            when (method.name) {
                "requestLocationUpdates" -> {
                    check(arguments != null && arguments.size == 3 && arguments[0] is LocationRequest &&
                        arguments[1] is LocationCallback && arguments[2] === Looper.getMainLooper())
                    val request = arguments[0] as LocationRequest
                    check(request.priority == Priority.PRIORITY_HIGH_ACCURACY)
                    check(request.granularity == Granularity.GRANULARITY_FINE)
                    check(request.maxUpdateAgeMillis == 0L)
                    val completion = TaskCompletionSource<Void>()
                    requests += Registration(arguments[1] as LocationCallback, completion)
                    completion.task
                }
                "removeLocationUpdates" -> {
                    check(arguments != null && arguments.size == 1 && arguments[0] is LocationCallback)
                    removed += arguments[0] as LocationCallback
                    TaskCompletionSource<Void>().also { it.setResult(null) }.task
                }
                "toString" -> "ControlledFusedTransport"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                else -> throw AssertionError("Unexpected Fused transport call: ${method.name}")
            }
        } as FusedLocationProviderClient
    }

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val transport = FusedTransport()
        val speechRuntime = RecordingSpeechRuntime()
        val actuator: AndroidFeedbackActuator
        private var gatewayGeneration: Long? = null
        private lateinit var onboarding: FirstRunOnboardingSnapshot
        private val bindingClass = Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding")

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main Fused registration probe", null, null, null) as MainActivity
            actuator = AndroidFeedbackActuator(runtime = speechRuntime, speechAllowed = { true })
            setField(main, "feedbackActuator", actuator)
            setField(main, "statusText", TextView(main))
            setField(main, "detailText", TextView(main))
            speechRuntime.ready()
            setField(main, "gatewayWalkRenewalHandler", Handler(Looper.getMainLooper()))
        }

        fun prepare() {
            assertTrue(BuildConfig.DEBUG)
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            val context = instrumentation.targetContext
            setField(main, "walkSessionLifecycle", WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime))
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            assertTrue(requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled)
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            assertTrue(restored.fullyRestored)
            onboarding = restored.snapshot
            publishGatewayOwner()
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            setField(main, "isActivityForeground", true)
            setField(main, "fusedLocationClient", transport.client)
            assertEquals(false, invoke("currentNavigationCollectionAllowsWork", java.lang.Boolean.TYPE))
            assertEquals(false, invoke("currentPositionFieldCollectionAllowsWork", java.lang.Boolean.TYPE))
        }

        fun prepareActiveNavigation() {
            val lifecycle = field(main, "walkSessionLifecycle") as WalkSessionLifecycle
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(onboarding,
                onboarding.epoch, onboarding.revision)
            assertTrue(jit.accepted)
            val completed = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current,
                jit.current.epoch, jit.current.revision)
            assertTrue(completed.accepted && completed.current.mayEnterWalk)
            onboarding = completed.current
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            assertNotNull(GatewaySessionProcessCoordinator.clear(requireNotNull(gatewayGeneration)))
            gatewayGeneration = null
            publishGatewayOwner()
            val device = completedDeviceCheck(requireNotNull(gatewayGeneration))
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val epoch = requireNotNull(lifecycle.snapshot().epoch)
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val readiness = WalkSessionReadinessCollector(epoch, plan).apply {
                plan.requiredRequirements.forEach { requirement -> record(WalkSessionReadinessObservation(
                    epoch, requirement, WalkSessionReadinessStatus.READY, "controlled fixture evidence"))
                }
            }.build(SystemClock.elapsedRealtime())
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            val session = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session)
            val authority = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
            val start = requireNotNull(authority.beginStart(epoch))
            val wall = System.currentTimeMillis()
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE, authority.completeStart(start,
                GatewayWalkStartResult.Granted(GatewayWalkLease("started", ACTOR, session.deviceId,
                    epoch.walkSessionId, "fixture-lease", 1L, wall, wall + 60_000L, wall,
                    SystemClock.elapsedRealtime() + 60_000L))))
            val profile = WalkSafeEnvironmentProfiles.testCandidate.officialEnvironment
            val guard = OfficialEnvironmentRuntimeGuard(epoch, profile)
            val now = SystemClock.elapsedRealtime()
            val gps = OfficialEnvironmentPolicy.assessGpsQuality(epoch, now, GpsQualityObservation(epoch, now, true, 2.0), profile)
            val camera = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.CAMERA_QUALITY, epoch, now,
                EnvironmentEvidenceStatus.PASS, profile.cameraQualityProfileId, profile.maximumMeasuredEvidenceAgeMs,
                "controlled fixture camera evidence")
            val confirmation = OfficialEnvironmentUserConfirmation(epoch,
                EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
                EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
                EnvironmentEvidenceStatus.PASS)
            val assessment = OfficialEnvironmentPolicy.assess(epoch, now, gps, camera, confirmation, profile)
            assertTrue(assessment.canStartWalk)
            assertTrue(guard.onAssessment(assessment, now).navigationOutputsAllowed)
            val capability = WalkSafeStartupCapabilityResolver.resolve(WalkSafeStartupCapabilityInput(
                true, true, true, true, true, true, true, true, true, "controlled active-navigation fixture"))
            assertTrue(capability.mayConfirmAndStart)
            assertEquals(WalkSafeStartupCapabilityTier.FULL, capability.tier)
            setField(main, "postLoginDeviceCheckSnapshot", device)
            setField(main, "officialEnvironmentOutputsAllowed", true)
            setField(main, "officialEnvironmentRuntimeGuard", guard)
            setField(main, "startupCapabilityDecision", capability)
            setField(main, "confirmedStartupCapabilityDecision", capability)
            setField(main, "isRouteActive", true)
            val navigator = field(main, "routeNavigator") as kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
            fun point(eastM: Double) = kr.co.hanium.dreamup.walksafe.navigation.RoutePoint(37.0,
                127.0 + eastM / (111_194.92664455874 * kotlin.math.cos(Math.toRadians(37.0))))
            navigator.setRoute(kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute("FUSED-MAIN",
                kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary(400, 400),
                listOf(point(0.0), point(400.0)), listOf(
                    kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteGuidePoint(0, point(0.0), "출발", 0, 400,
                        turnType = 200, pointType = "SP"),
                    kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteGuidePoint(1, point(200.0), "안내", 200, 200,
                        turnType = 13))))
        }

        private fun publishGatewayOwner() {
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_fused_registration_probe", System.currentTimeMillis() + 120_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
        }

        fun replaceGatewayOwner() {
            assertNotNull(GatewaySessionProcessCoordinator.clear(requireNotNull(gatewayGeneration)))
            gatewayGeneration = null
            publishGatewayOwner()
        }

        fun start() = invoke("startLocationUpdatesIfAllowed", Void.TYPE, arrayOf(java.lang.Boolean.TYPE), false)
        fun invoke(name: String, returns: Class<*>) = MainActivity::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
            check(returnType == returns)
        }.invoke(main)

        fun bindingIsCurrent() = invoke("isPositioningCollectionCurrent", java.lang.Boolean.TYPE,
            arrayOf(bindingClass), requireNotNull(field(main, "positioningCollectionBinding")) as Any) as Boolean

        fun location() = Location("gps").apply {
            latitude = 37.0
            longitude = 127.0
            accuracy = 2f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            assertTrue(hasAccuracy())
            assertFalse(isMock)
        }

        fun locationAtRoute(eastM: Double) = location().apply {
            longitude = 127.0 + eastM / (111_194.92664455874 * kotlin.math.cos(Math.toRadians(37.0)))
        }

        fun assertOriginalFix(fix: Location) {
            val trusted = field(main, "latestTrustedLocation") as TrustedLocation
            val raw = field(main, "latestRawRouteLocation") as TrustedLocation
            assertEquals(fix.elapsedRealtimeNanos / 1_000_000L, trusted.elapsedRealtimeMs)
            assertEquals(fix.elapsedRealtimeNanos / 1_000_000L, raw.elapsedRealtimeMs)
            assertEquals(fix.latitude, raw.latitude, 0.0)
            assertEquals(fix.longitude, raw.longitude, 0.0)
            assertEquals(fix.accuracy, raw.accuracyM, 0.0f)
        }

        fun checkpoint(): Checkpoint {
            val coordinator = field(main, "positioningCoordinator") as PositioningCoordinator
            return Checkpoint(field(main, "positioningCollectionBinding"), field(main, "locationCollectionOwner"),
                field(main, "locationCallback"), field(main, "locationCallbackGeneration") as Int, coordinator,
                coordinator.currentInputGeneration(), field(main, "latestTrustedLocation"), field(main, "latestRawRouteLocation"),
                field(main, "latestNavigationState"), field(main, "gnssQualityObserver"))
        }

        private fun invoke(name: String, returns: Class<*>, types: Array<Class<*>> = emptyArray(), vararg values: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply {
                isAccessible = true
                check(returnType == returns)
            }
            return try { method.invoke(main, *values) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }

        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (first == null) first = failure else first!!.addSuppressed(failure)
                }
            }
            cleanup { setField(main, "isRouteActive", false) }
            cleanup {
                val lifecycle = field(main, "walkSessionLifecycle") as WalkSessionLifecycle
                if (lifecycle.snapshot().state == WalkSessionState.ACTIVE) lifecycle.handle(WalkSessionEvent.EndRequested)
            }
            cleanup { assertEquals(true, invoke("stopLocationUpdates", java.lang.Boolean.TYPE, arrayOf(java.lang.Boolean.TYPE), true)) }
            cleanup { setField(main, "isActivityForeground", false) }
            cleanup { gatewayGeneration?.let { assertNotNull(GatewaySessionProcessCoordinator.clear(it)) } }
            cleanup { actuator.close() }
            first?.let { throw it }
        }
    }

    private class RecordingSpeechRuntime : FeedbackSpeechRuntime {
        private val handler = Handler(Looper.getMainLooper())
        override val scheduler = object : FeedbackSpeechScheduler {
            override fun post(task: Runnable) = handler.post(task)
            override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
            override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
            override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
        }
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val engineInitialized get() = initialized != null
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            listener?.onStart(utteranceId)
            listener?.onDone(utteranceId)
            return TextToSpeech.SUCCESS
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("Fixture does not request progress tones")
        fun ready() { requireNotNull(initialized)(TextToSpeech.SUCCESS) }
    }

    private companion object {
        const val ACTOR = "33333333-3333-4333-8333-333333333333"

        fun withFixture(test: (Fixture) -> Unit) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            @Suppress("DEPRECATION")
            assertNull(context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U kr.co.hanium.dreamup.walksafe")
            val original = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:kr\\.co\\.hanium\\.dreamup\\.walksafe\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            }
            assertEquals(1, original.size)
            assertNotEquals(original.single(), Process.myUid())
            val f = onMain(instrumentation) { Fixture(instrumentation) }
            var first: Throwable? = null
            try {
                onMain(instrumentation) { f.prepare() }
                test(f)
            } catch (failure: Throwable) {
                first = failure
                throw failure
            } finally {
                var cleanupFailure: Throwable? = null
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (failure: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
                    }
                }
                cleanup { onMain(instrumentation) { f.close() } }
                cleanup { instrumentation.waitForIdleSync() }
                cleanupFailure?.let { if (first == null) throw it else first.addSuppressed(it) }
            }
        }

        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val value = AtomicReference<T>()
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync { try { value.set(action()) } catch (problem: Throwable) { failure.set(problem) } }
            failure.get()?.let { throw it }
            return value.get()
        }

        fun field(target: Any, name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(target)

        fun setField(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)

        fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))

        fun completedDeviceCheck(generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), ACTOR, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, ACTOR, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { assertEquals(PostLoginDeviceCheckState.FULL, it.state) }
        }
    }
}
