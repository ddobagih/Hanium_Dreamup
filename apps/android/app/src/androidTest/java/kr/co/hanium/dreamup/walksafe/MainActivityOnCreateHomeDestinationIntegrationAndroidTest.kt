package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.TaskCompletionSource
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentRuntimeController
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches real MainActivity and passes its startup inspection. The test injects controlled
 * account/onboarding/device/permission/lifecycle/foreground evidence, then calls the production
 * renderer and production HOME/destination controls. Search result, readiness, authority lease,
 * environment evidence, route geometry, and location samples are fixtures. Every sample enters
 * Main's production Fused callback and route/UI handlers. This does not prove natural startup to
 * HOME, live login/search, OS location delivery, physical readiness, acoustic output, or field-walk behavior.
 * The controlled TTS actor is installed by the test and routes readiness into Main's production
 * handler; this does not exercise SpeechOutputController's production actuator-factory wiring.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityOnCreateHomeDestinationIntegrationAndroidTest {
    @Test(timeout = 240_000L)
    fun controlledHomeDestinationArrivalAndEndUseTheSameActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", context.packageName)
        assertTrue(BuildConfig.DEBUG)
        assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA)) {
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
        }

        val onboarding = completedOnboarding()
        val education = completedPriorityEducation()
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
            "walksafe_field_session=synthetic_oncreate_home_probe", System.currentTimeMillis() + 86_400_000L)
        assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
        val generation = GatewaySessionProcessCoordinator.snapshot().generation
        val registrationCompletion = TaskCompletionSource<Void>()
        var registeredCallback: LocationCallback? = null
        val pointAtEastM: (Double) -> RoutePoint = { eastM -> RoutePoint(37.0,
            127.0 + eastM / (111_194.92664455874 * kotlin.math.cos(Math.toRadians(37.0)))) }
        var latestInjectedEastM = 50.0
        val mainReadyCallbackAccepted = AtomicBoolean(false)
        fun freshLocation(eastM: Double) = Location("gps").apply {
            val point = pointAtEastM(eastM)
            latitude = point.latitude
            longitude = point.longitude
            accuracy = 3f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        val startFix = freshLocation(latestInjectedEastM)
        fun deliverRouteFix(eastM: Double) {
            latestInjectedEastM = eastM
            val location = freshLocation(eastM)
            instrumentation.runOnMainSync {
                requireNotNull(registeredCallback).onLocationResult(LocationResult.create(listOf(location)))
            }
            instrumentation.waitForIdleSync()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitPrivacyStartupReady(scenario)
                val speechRuntime = RecordingSpeechRuntime()
                lateinit var controlledActuator: AndroidFeedbackActuator
                var controlledLifecycleGeneration = -1
                scenario.onActivity { main ->
                    val originalActuator = MainActivityControllerTestFixture.field("feedbackActuator")
                        .get(main) as AndroidFeedbackActuator?
                    val lifecycleGeneration = field(main, "feedbackLifecycleGeneration") as Int
                    controlledLifecycleGeneration = lifecycleGeneration
                    controlledActuator = AndroidFeedbackActuator(
                        runtime = speechRuntime,
                        onOfflineKoreanSpeechReady = {
                            main.runOnUiThread {
                                val accepted = invokeWithArgs(main, "processOfflineSpeechReady", controlledActuator,
                                    lifecycleGeneration, SystemClock.elapsedRealtime()) as Boolean
                                mainReadyCallbackAccepted.set(accepted)
                            }
                        },
                    )
                    MainActivityControllerTestFixture.replaceActuator(main, controlledActuator)
                    originalActuator?.close()
                }
                scenario.onActivity { main ->
                    MainActivityControllerTestFixture.field("firstRunOnboardingSnapshot").set(main, onboarding)
                    MainActivityControllerTestFixture.field("reporterUserId").set(main, ACTOR)
                    MainActivityControllerTestFixture.field("priorityUserOnboardingActorId").set(main, ACTOR)
                    MainActivityControllerTestFixture.field("priorityUserOnboardingPolicy").set(main, education)
                    MainActivityControllerTestFixture.field("postLoginDeviceCheckSnapshot").set(main,
                        completedDeviceCheck(ACTOR, generation))
                    MainActivityControllerTestFixture.field("initialAppPermissionExitRequired").set(main, false)
                    MainActivityControllerTestFixture.field("isActivityForeground").set(main, true)
                    val lifecycle = MainActivityControllerTestFixture.field("walkSessionLifecycle").get(main)
                        as WalkSessionLifecycle
                    if (!lifecycle.snapshot().isForeground) lifecycle.handle(WalkSessionEvent.EnteredForeground)
                    assertTrue(invoke(main, "nativeHomeFeatureContextAvailable") as Boolean)
                    assertTrue(invoke(main, "shouldShowNativeHome") as Boolean)
                    invoke(main, "renderMainUi")
                }
                instrumentation.runOnMainSync { speechRuntime.ready() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { main ->
                    assertSame("Main must retain the installed controlled speech actuator",
                        controlledActuator, field(main, "feedbackActuator"))
                    assertEquals("Controlled speech readiness must use the current Activity lifecycle generation",
                        controlledLifecycleGeneration, field(main, "feedbackLifecycleGeneration") as Int)
                    assertEquals("Controlled speech runtime must reach ready without platform voice downloads",
                        "tts=ready", (field(main, "feedbackActuator") as AndroidFeedbackActuator).statusText())
                    assertTrue("Main must accept current controlled actuator readiness",
                        mainReadyCallbackAccepted.get())
                }

                scenario.onActivity { main ->
                    assertEquals("HOME", field(main, "nativeUiPage").toString())
                    val card = descendants(main.window.decorView).filterIsInstance<TextView>()
                        .firstOrNull { it.isShown && it.text.toString() == "길라잡이" }
                        ?.let(::clickableAncestor)
                    assertNotNull("Production HOME renderer must publish 길라잡이", card)
                    assertTrue(requireNotNull(card).performClick())
                    assertEquals("DESTINATION_SEARCH", field(main, "nativeUiPage").toString())
                    val selectedEndpoint = RoutePoint(37.0,
                        127.0 + 100.0 / (111_194.92664455874 * kotlin.math.cos(Math.toRadians(37.0))))
                    val destination = DestinationSearchResult("oncreate-home-fixture", "통합시험 목적지",
                        selectedEndpoint, null, null, null, null)
                    main.deliverDestinationSearchResultForFixture(destination)
                    assertEquals("DESTINATION_CONFIRM", field(main, "nativeUiPage").toString())
                    assertEquals(destination, field(main, "pendingUiDestination"))
                    val start = field(main, "nativeDestinationStartButton") as View
                    assertTrue(start.isShown && start.performClick())
                    assertEquals("GUIDANCE", field(main, "nativeUiPage").toString())
                    assertEquals(destination, field(main, "pendingUiDestination"))
                    val guidance = bindingView(main, "guidanceBinding", "nativeGuidanceDestinationText") as TextView
                    assertTrue(guidance.isShown)
                    assertTrue(guidance.text.toString().contains(destination.name))

                    activateControlledWalk(main, generation)
                    val navigator = field(main, "routeNavigator") as RouteNavigator
                    assertEquals("Synthetic route endpoint must be the selected destination",
                        destination.point, pointAtEastM(100.0))

                    val controlledClient = Proxy.newProxyInstance(FusedLocationProviderClient::class.java.classLoader,
                        arrayOf(FusedLocationProviderClient::class.java)) { proxy, method, arguments ->
                        when (method.name) {
                            "requestLocationUpdates" -> {
                                registeredCallback = requireNotNull(arguments?.getOrNull(1)) as LocationCallback
                                registrationCompletion.task
                            }
                            "getCurrentLocation" -> TaskCompletionSource<Location>().also {
                                it.setResult(freshLocation(latestInjectedEastM))
                            }.task
                            "removeLocationUpdates" -> {
                                requireNotNull(arguments?.singleOrNull()) as LocationCallback
                                TaskCompletionSource<Void>().also { it.setResult(null) }.task
                            }
                            "toString" -> "ControlledSameActivityFusedClient"
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === arguments?.singleOrNull()
                            else -> throw AssertionError("Unexpected Fused client call: ${method.name}")
                        }
                    } as FusedLocationProviderClient
                    invokeWithArgs(main, "stopLocationUpdates", true)
                    assertNull(field(main, "locationCallback"))
                    MainActivityControllerTestFixture.field("fusedLocationClient").set(main, controlledClient)
                    MainActivityControllerTestFixture.field("isRouteActive").set(main, true)
                    navigator.setRoute(WalkingRoute("ONCREATE-HOME-ARRIVAL", WalkingRouteSummary(100, 100),
                        listOf(pointAtEastM(0.0), pointAtEastM(100.0)), listOf(
                            WalkingRouteGuidePoint(0, pointAtEastM(0.0), "출발", 0, 100, turnType = 200, pointType = "SP"),
                            WalkingRouteGuidePoint(1, pointAtEastM(50.0), "안내", 50, 50, turnType = 13))),
                        destination = destination.point)
                    val gates = listOf("firstRunOnboardingComplete", "postLoginDeviceCheckPassesFeatureGate",
                        "isWalkSessionRuntimeActive", "isStartupCapabilityConfirmed", "navigationEnvironmentOutputsAllowed")
                        .associateWith { invoke(main, it) as Boolean }
                    assertTrue("Main collection gate components: $gates", gates.values.all { it })
                    assertTrue(invoke(main, "currentNavigationCollectionAllowsWork") as Boolean)
                    invokeWithArgs(main, "startLocationUpdatesIfAllowed", false)
                    assertNotNull("Production Main must request Fused updates for this active route", registeredCallback)
                    refreshControlledCameraEvidence(main)
                }

                registrationCompletion.setResult(null)
                instrumentation.waitForIdleSync()

                scenario.onActivity { main ->
                    assertSame("Main must retain the installed controlled speech actuator",
                        controlledActuator, field(main, "feedbackActuator"))
                    assertEquals("Controlled speech readiness must use the current Activity lifecycle generation",
                        controlledLifecycleGeneration, field(main, "feedbackLifecycleGeneration") as Int)
                    assertEquals("Controlled speech runtime must reach ready without platform voice downloads",
                        "tts=ready", (field(main, "feedbackActuator") as AndroidFeedbackActuator).statusText())
                    assertTrue("Main must accept current controlled actuator readiness",
                        mainReadyCallbackAccepted.get())
                }

                scenario.onActivity { main ->
                    refreshControlledCameraEvidence(main)
                    val navigator = field(main, "routeNavigator") as RouteNavigator
                    requireNotNull(registeredCallback).onLocationResult(LocationResult.create(listOf(startFix)))
                    val trusted = field(main, "latestTrustedLocation") as TrustedLocation
                    assertEquals(startFix.elapsedRealtimeNanos / 1_000_000L, trusted.elapsedRealtimeMs)
                    assertEquals(startFix.longitude, trusted.longitude, 0.0)
                    assertNotNull("Controlled Fused callback must update this Activity's installed route matcher; " +
                        "navigationAllowed=${invoke(main, "currentNavigationCollectionAllowsWork")} " +
                        "outputsAllowed=${invoke(main, "navigationEnvironmentOutputsAllowed")} " +
                        "routeActive=${field(main, "isRouteActive")} trusted=$trusted route=${navigator.hasRoute()} " +
                        "navigation=${field(main, "latestNavigationState")} " +
                        "lifecycle=${(field(main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot()}",
                        navigator.currentRouteMatch())
                }

                var walkEnded = false
                var arrivalDiagnostic = "no location sample delivered"
                var latestProgressEastM = 50.0
                fun readArrivalDecision() {
                    scenario.onActivity { main ->
                        val navigator = field(main, "routeNavigator") as RouteNavigator
                        val lifecycle = field(main, "walkSessionLifecycle") as WalkSessionLifecycle
                        walkEnded = lifecycle.snapshot().state == WalkSessionState.ENDED
                        arrivalDiagnostic = "walk=${lifecycle.snapshot().state} decision=${navigator.pendingUserDecision()} match=${navigator.currentRouteMatch()} " +
                            "navigation=${field(main, "latestNavigationState")} " +
                            "routeActive=${field(main, "isRouteActive")} " +
                            "navigationAllowed=${invoke(main, "currentNavigationCollectionAllowsWork")} " +
                            "outputsAllowed=${invoke(main, "navigationEnvironmentOutputsAllowed")} " +
                            "trusted=${field(main, "latestTrustedLocation")} " +
                            "callbackSame=${field(main, "locationCallback") === registeredCallback} " +
                            "eastM=$latestProgressEastM"
                    }
                }
                for (eastM in 60..100 step 10) {
                    Thread.sleep(3_000L)
                    scenario.onActivity(::refreshControlledCameraEvidence)
                    deliverRouteFix(eastM.toDouble())
                    latestProgressEastM = eastM.toDouble()
                    readArrivalDecision()
                    if (walkEnded) break
                }
                repeat(3) {
                    if (!walkEnded) {
                        Thread.sleep(3_000L)
                        scenario.onActivity(::refreshControlledCameraEvidence)
                        deliverRouteFix(100.0)
                        readArrivalDecision()
                    }
                }
                assertTrue("Main Fused callback progression must automatically end the walk: $arrivalDiagnostic", walkEnded)

                scenario.onActivity { main ->
                    val navigator = field(main, "routeNavigator") as RouteNavigator
                    assertFalse("Automatic arrival must clear this Activity route", navigator.hasRoute())
                    assertNull(navigator.pendingDecisionToken())
                    val lifecycle = field(main, "walkSessionLifecycle") as WalkSessionLifecycle
                    assertEquals(WalkSessionState.ENDED, lifecycle.snapshot().state)
                    assertEquals("HOME", field(main, "nativeUiPage").toString())
                    assertFalse(invoke(main, "currentNavigationCollectionAllowsWork") as Boolean)
                    assertFalse(field(main, "isRouteActive") as Boolean)
                    assertEquals("도착했습니다. 안내를 종료합니다", speechRuntime.messages.single {
                        it == "도착했습니다. 안내를 종료합니다"
                    })
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { main ->
                    assertTrue("Signed-in foreground location collection remains allowed after END",
                        invoke(main, "currentLocationCollectionAllowsWork") as Boolean)
                    assertSame("The signed-in location callback may remain registered after END",
                        registeredCallback, field(main, "locationCallback"))
                }
            }
        } finally {
            assertNotNull(GatewaySessionProcessCoordinator.clear(generation))
        }
    }

    private fun activateControlledWalk(main: MainActivity, generation: Long) {
        val lifecycle = field(main, "walkSessionLifecycle") as WalkSessionLifecycle
        if (!lifecycle.snapshot().isForeground) lifecycle.handle(WalkSessionEvent.EnteredForeground)
        assertEquals("Reuse MainActivity's own lifecycle owner", WalkSessionState.READY, lifecycle.snapshot().state)
        MainActivityControllerTestFixture.field("metricDistanceCapabilityOverride").set(main, false)
        MainActivityControllerTestFixture.field("onDeviceSpeechRecognitionCapabilityOverride").set(main, true)
        MainActivityControllerTestFixture.field("offlineKoreanTextToSpeechCapabilityOverride").set(main, true)
        MainActivityControllerTestFixture.field("detectorAvailable").set(main, true)
        val epoch = requireNotNull(lifecycle.snapshot().epoch)
        val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.DISTANCE_LIMITED)
        val readiness = WalkSessionReadinessCollector(epoch, plan).apply {
            plan.requiredRequirements.forEach { requirement -> record(WalkSessionReadinessObservation(epoch,
                requirement, WalkSessionReadinessStatus.READY, "controlled Activity route fixture")) }
        }.build(SystemClock.elapsedRealtime())
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        // This is the production phone-mounting session owner's initial-assumption method.
        // Invoke the session owner directly because the explicit UI action also requires live
        // official-environment readiness, which this test supplies as a controlled fixture below.
        val mountingSessionDelegate = MainActivity::class.java.getDeclaredField("phoneMountingSession\$delegate")
            .apply { isAccessible = true }.get(main) as Lazy<*>
        mountingSessionDelegate.value!!.javaClass.getDeclaredMethod("assumeInitialConfirmation", WalkRuntimeEpoch::class.java)
            .apply { isAccessible = true }.invoke(mountingSessionDelegate.value, epoch)
        val mountingConfirmation = invoke(main, "getPhoneMountingUserConfirmation") as PhoneMountingUserConfirmation?
        assertNotNull("Main's production prewalk owner must record initial mounting confirmation", mountingConfirmation)
        assertEquals(epoch, requireNotNull(mountingConfirmation).epoch)
        assertEquals(PhoneMountingMethod.CHEST_FORWARD, mountingConfirmation.method)
        lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        val session = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session)
        val authority = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
        val ticket = requireNotNull(authority.beginStart(epoch))
        val wall = System.currentTimeMillis()
        assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE, authority.completeStart(ticket,
            GatewayWalkStartResult.Granted(GatewayWalkLease("started", ACTOR, session.deviceId,
                epoch.walkSessionId, "activity-fixture-lease", 1L, wall, wall + 60_000L, wall,
                SystemClock.elapsedRealtime() + 60_000L))))
        val profile = WalkSafeEnvironmentProfiles.testCandidate.officialEnvironment
        val guard = OfficialEnvironmentRuntimeGuard(epoch, profile)
        val now = SystemClock.elapsedRealtime()
        val gps = OfficialEnvironmentPolicy.assessGpsQuality(epoch, now, GpsQualityObservation(epoch, now, true, 2.0), profile)
        val camera = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.CAMERA_QUALITY, epoch, now,
            EnvironmentEvidenceStatus.PASS, profile.cameraQualityProfileId, profile.maximumMeasuredEvidenceAgeMs,
            "controlled Activity camera evidence")
        val confirmation = OfficialEnvironmentUserConfirmation(epoch, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS)
        val assessment = OfficialEnvironmentPolicy.assess(epoch, now, gps, camera, confirmation, profile)
        assertTrue(assessment.canStartWalk)
        assertTrue(guard.onAssessment(assessment, now).navigationOutputsAllowed)
        MainActivityControllerTestFixture.field("officialEnvironmentUserConfirmation").set(main, confirmation)
        MainActivityControllerTestFixture.field("officialEnvironmentGpsEvidence").set(main, gps)
        MainActivityControllerTestFixture.field("officialEnvironmentCameraEvidence").set(main, camera)
        assertTrue("Seed Main's runtime evidence store and activate its production controller",
            invoke(main, "activateOfficialEnvironmentRuntime") as Boolean)
        val capability = WalkSafeStartupCapabilityResolver.resolve(WalkSafeStartupCapabilityInput(
            true, true, true, true, true, true, true, false, true, "controlled Activity route fixture"))
        assertTrue(capability.mayConfirmAndStart)
        MainActivityControllerTestFixture.field("postLoginDeviceCheckSnapshot").set(main,
            completedDeviceCheck(ACTOR, generation))
        MainActivityControllerTestFixture.field("startupCapabilityDecision").set(main, capability)
        MainActivityControllerTestFixture.field("confirmedStartupCapabilityDecision").set(main, capability)
        MainActivityControllerTestFixture.field("isRouteActive").set(main, true)
    }

    private fun refreshControlledCameraEvidence(main: MainActivity) {
        val epoch = requireNotNull((field(main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot().epoch)
        val profile = WalkSafeEnvironmentProfiles.testCandidate.officialEnvironment
        val now = SystemClock.elapsedRealtime()
        val sample = CameraFrameQualityAssessment(epoch, now, profile.cameraQualityProfileId,
            profile.maximumMeasuredEvidenceAgeMs, EnvironmentEvidenceStatus.PASS, CameraFrameQualityReason.PASSED)
        val controllerDelegate = MainActivity::class.java.getDeclaredField(
            "officialEnvironmentRuntimeController\$delegate",
        ).apply { isAccessible = true }.get(main) as Lazy<*>
        val controller = controllerDelegate.value as OfficialEnvironmentRuntimeController
        val evidenceStore = OfficialEnvironmentRuntimeController::class.java.getDeclaredField("evidence")
            .apply { isAccessible = true }.get(controller)
        evidenceStore.javaClass.getDeclaredMethod("observeRuntimeRawCameraAssessment",
            CameraFrameQualityAssessment::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(evidenceStore, sample, now)
        invoke(main, "applyCurrentOfficialEnvironmentRuntimeAssessment")
    }

    private fun completedOnboarding(): FirstRunOnboardingSnapshot {
        val evidence = listOf(
            FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(1)),
            FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)),
            FirstRunOnboardingEvidence.Fp004Training(receipt(3)),
        )
        val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
            FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
        check(restored.fullyRestored)
        val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot,
            restored.snapshot.epoch, restored.snapshot.revision)
        check(jit.accepted)
        return FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch,
            jit.current.revision).current.also { check(it.mayEnterWalk) }
    }

    private fun completedDeviceCheck(actor: String, generation: Long): PostLoginDeviceCheckSnapshot {
        val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), actor, generation)
        val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, actor, generation, true)
        val binding = requireNotNull(started.bindingOrNull)
        val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
        val ready = PostLoginDeviceCheckSignal.READY
        return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
            ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
            metricDepth = PostLoginMetricDepthState.SUPPORTED,
        )).also { check(it.state == PostLoginDeviceCheckState.FULL) }
    }

    private fun completedPriorityEducation() = PriorityUserOnboardingPolicy().apply {
        selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
        reviewSafetyEducation(true)
        reviewPracticeNecessity(true)
        acceptEducationConsent()
        acknowledgeUsageConditions()
        finishAppUsageEducationPlayback(requireNotNull(beginAppUsageEducationPlayback()), completed = true)
        confirmSafePracticePlace()
        PriorityUserPractice.entries.forEach { practice ->
            val token = requireNotNull(beginPractice(practice))
            PriorityUserPracticeDeliverySignal.entries.forEach { recordPracticeDelivery(token, it) }
            if (practice == PriorityUserPractice.HAZARD_ALERT) confirmHazardResponse(token)
        }
        acceptAppUsageEducation()
        check(snapshot().nativeEducationComplete)
    }

    private fun awaitPrivacyStartupReady(scenario: ActivityScenario<MainActivity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = invoke(it, "getPrivacyStartupInspectionComplete") as Boolean }
            if (ready) return
            Thread.sleep(50L)
        }
        fail("MainActivity privacy startup inspection did not reach READY")
    }

    private class RecordingSpeechRuntime : FeedbackSpeechRuntime {
        val messages = mutableListOf<String>()
        private val handler = Handler(Looper.getMainLooper())
        override val scheduler = object : FeedbackSpeechScheduler {
            override fun post(task: Runnable) = handler.post(task)
            override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
            override fun removeCallbacks(task: Runnable) { handler.removeCallbacks(task) }
            override fun removeCallbacksAndMessages(token: Any?) { handler.removeCallbacksAndMessages(token) }
        }
        private var initializeCallback: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val engineInitialized get() = initializeCallback != null
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        override fun initialize(onInitialized: (Int) -> Unit) { initializeCallback = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            messages += message
            handler.post {
                listener?.onStart(utteranceId)
                listener?.onDone(utteranceId)
            }
            return TextToSpeech.SUCCESS
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects =
            error("The controlled route flow does not request progress tones")

        fun ready() = requireNotNull(initializeCallback)(TextToSpeech.SUCCESS)
    }

    private fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
    private fun invoke(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun invokeWithArgs(activity: MainActivity, name: String, vararg args: Any?): Any? =
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    private fun field(activity: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name).get(activity)
    private fun bindingView(activity: MainActivity, method: String, name: String): View {
        val presenter = MainActivity::class.java.getDeclaredMethod("getNativeScreenPresenter")
            .apply { isAccessible = true }.invoke(activity)
        val binding = presenter.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(presenter)
        return binding.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(binding) as View
    }
    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(descendants(root.getChildAt(i)))
    }
    private fun clickableAncestor(view: View): View? {
        var current: View? = view
        while (current != null && !current.isClickable) current = current.parent as? View
        return current
    }
    private companion object { const val ACTOR = "44444444-4444-4444-8444-444444444444" }
}
