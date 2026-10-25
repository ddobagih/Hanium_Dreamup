package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.navigation.TactileSnapshotFrameResult
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationCallback
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.inference.RunningDetectorIdentity
import kr.co.hanium.dreamup.walksafe.inference.RunningDetectorLoadEvidence
import kr.co.hanium.dreamup.walksafe.inference.RunningDetectorVerificationStep
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import kr.co.hanium.dreamup.walksafe.navigation.session.LocationCollectionOwner
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android/Main/Actor consumers; native AR, detector-load and speech observations are doubles. */
@RunWith(AndroidJUnit4::class)
class MainActivityTactileFallbackAndroidTest {
    /** Public noarg construction for Instrumentation; inherits every Main behavior unchanged. */
    class ConfiguredActivity : MainActivity(
        ApprovedWalkRuntimeSafetyThresholdProfile("approved-profile", 100L, 200L),
    )

    @Test fun unstartedSourceLossOrTrackReplacementRejectsTheLateStartWithinItsOriginalDeadline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (replaceTrack in listOf(false, true)) {
            val f = onMain(instrumentation) { Fixture(instrumentation) }
            try {
                onMain(instrumentation) { f.prepare() }
                instrumentation.waitForIdleSync()
                onMain(instrumentation) { f.assertHealthy() }
                val stable = stableFrames(instrumentation, f, replaceTrack)
                val oldNotice = requireNotNull(stable.guidance.dotReferenceNotice)
                onMain(instrumentation) {
                    f.publish(stable)
                    f.dispatchPoint()
                    assertEquals(listOf(true), f.pointAdmissions)
                    assertEquals(1, f.runtime.spoken.size)
                }
                val old = onMain(instrumentation) { f.runtime.spoken.single() }
                val route = onMain(instrumentation) { f.routeState() }
                onMain(instrumentation) {
                    val replacement = f.frame(if (replaceTrack) FrameSource.TRACK_B else FrameSource.EMPTY)
                    if (replaceTrack) {
                        assertNotNull("Prepared stable second track must remain a real observation", replacement.guidance.dotReferenceNotice)
                        assertNotEquals(oldNotice.trackId, replacement.guidance.dotReferenceNotice!!.trackId)
                    } else assertNull(replacement.guidance.dotReferenceNotice)
                    assertTrue("FIXTURE_DEADLINE: isolate source invalidation before expiry", oldNotice.isValidAt(SystemClock.elapsedRealtime()))
                    f.publish(replacement)
                    f.refreshSafety()
                    f.runtime.start(old.id)
                }
                instrumentation.waitForIdleSync()
                onMain(instrumentation) {
                    val cancelled = f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.CANCELLED_ADVISORY)
                    val expired = f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.EXPIRED_BEFORE_START)
                    // The raw consumer expires at late START. An exact-cancel consumer retires
                    // before START, so its late platform callback has no registered owner.
                    assertEquals("Exactly one logical source cancellation", 1, cancelled + expired)
                    assertEquals("A detached receipt cannot emit a late STARTED", expired,
                        f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.STARTED))
                    assertEquals(1, f.runtime.stops)
                    assertFalse(f.actuator.isAppSpeechActive())
                    f.runtime.done(old.id)
                }
                instrumentation.waitForIdleSync()
                onMain(instrumentation) {
                    assertEquals(0, f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.DONE))
                    assertEquals(1, f.runtime.spoken.size)
                    assertEquals(route, f.routeState())
                    Log.i(TAG, "UNSTARTED_CONTROL replacement=$replaceTrack requested=1 " +
                        "rawStarted=${f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.STARTED)} " +
                        "expired=${f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.EXPIRED_BEFORE_START)} " +
                        "exactCancelled=${f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.CANCELLED_ADVISORY)} " +
                        "stopAttempts=${f.runtime.stops} done=0 routeUnchanged=true entries=${f.entries}")
                }
            } finally { onMain(instrumentation) { f.close() } }
        }
    }

    @Test fun startedSourceLossCancelsBeforeTheOriginalTurnAndCannotReplayOrCompleteTheOldPoint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val f = onMain(instrumentation) { Fixture(instrumentation) }
        try {
            onMain(instrumentation) { f.prepare() }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) { f.assertHealthy(); f.assertMetricOwnershipRejections() }
            val stable = stableFrames(instrumentation, f, false)
            val notice = requireNotNull(stable.guidance.dotReferenceNotice)
            onMain(instrumentation) {
                f.publish(stable)
                f.dispatchPoint()
                assertEquals(listOf(true), f.pointAdmissions)
                f.refreshSafety()
                f.runtime.start(f.runtime.spoken.single().id)
            }
            instrumentation.waitForIdleSync()
            val old = onMain(instrumentation) {
                assertEquals(1, f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.STARTED))
                assertTrue(f.actuator.isAppSpeechActive())
                f.runtime.spoken.single()
            }
            lateinit var cue: RouteNavigatorUpdate
            lateinit var route: List<Any?>
            var sourceStopAttempts = -1
            var sourceStillActive = true
            var sourceCancelled = -1
            onMain(instrumentation) {
                // The real route producer refreshes its normal corner decision before fallback.
                cue = f.turnCue()
                route = f.routeState()
                assertTrue("FIXTURE_DEADLINE: started loss occurs before the issued deadline", notice.isValidAt(SystemClock.elapsedRealtime()))
                val absent = f.frame(FrameSource.EMPTY)
                assertNull(absent.guidance.dotReferenceNotice)
                assertEquals(LocalRouteMode.TMAP, absent.guidance.decision.mode)
                f.publish(absent)
                sourceStopAttempts = f.runtime.stops
                sourceStillActive = f.actuator.isAppSpeechActive()
                sourceCancelled = f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.CANCELLED_ADVISORY)
                assertEquals(route, f.routeState())
                Log.i(TAG, "SOURCE_BOUNDARY beforeNav=true stopAttempts=$sourceStopAttempts " +
                    "oldStillActive=$sourceStillActive exactCancelled=$sourceCancelled " +
                    "pointRequested=${f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.REQUESTED)} " +
                    "pointStarted=${f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.STARTED)} routeUnchanged=true")
                f.dispatchTurn(cue)
                assertEquals(1, f.runtime.spoken.count { it.message == cue.instruction })
                assertEquals(1, f.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.REQUESTED))
                f.runtime.start(f.runtime.spoken.last().id)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(1, f.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.STARTED))
                f.runtime.done(old.id)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(0, f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.DONE))
                assertTrue("Late old DONE cannot retire the real TURN owner", f.actuator.isAppSpeechActive())
                f.runtime.done(f.runtime.spoken.last().id)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(1, f.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE))
                assertFalse(f.actuator.isAppSpeechActive())
                repeat(3) {
                    f.publish(f.frame(FrameSource.EMPTY))
                    f.dispatchTurn(cue)
                }
                f.runtime.done(old.id)
                f.runtime.done(f.runtime.spoken.last().id)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(1, f.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.REQUESTED))
                assertEquals(1, f.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.STARTED))
                assertEquals(1, f.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE))
                assertEquals(0, f.count(SpeechOutputKind.ADVISORY, SpeechOutputOutcome.DONE))
                assertEquals(2, f.runtime.spoken.size)
                assertEquals(route, f.routeState())
                assertEquals("마지막 안내: ${cue.instruction}", f.resultText.text.toString())
                Log.i(TAG, "TURN_CONTROL requested=1 started=1 done=1 repeatAdditional=0 latePointDone=0 " +
                    "routeIdentityVersionProgressDecisionUnchanged=true entries=${f.entries} mainOnCreate=false nativeTts=false")
                // Assertions use the exact source-loss snapshot captured BEFORE NAV. A later
                // priority cancellation cannot repair or mask this original production boundary.
                assertEquals("PRODUCT_SOURCE_LOSS_CANCEL: native stop attempt before NAV", 1, sourceStopAttempts)
                assertFalse("PRODUCT_SOURCE_LOSS_CANCEL: old logical owner inactive before NAV", sourceStillActive)
                assertEquals("PRODUCT_SOURCE_LOSS_CANCEL: exact advisory cancellation before NAV", 1, sourceCancelled)
            }
        } finally { onMain(instrumentation) { f.close() } }
    }

    private enum class FrameSource { TRACK_A, BOTH_TRACKS, TRACK_B, EMPTY }

    private class Fixture(instrumentation: Instrumentation) {
        val main: MainActivity
        val runtime = RecordingRuntime()
        val events = mutableListOf<SpeechOutputEvent>()
        val entries = mutableListOf<String>()
        val pointAdmissions = mutableListOf<Boolean>()
        val navigator: RouteNavigator
        val resultText: TextView
        val actuator: AndroidFeedbackActuator
        private val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        private val pipeline = ObjectDepthRuntimePipeline()
        private val nativeAr = MainActivity.RuntimeArObservation(Any(), Any(), MainActivity.ArSessionPurpose.RUNTIME, 1L)
        private var gatewayGeneration: Long? = null
        private var ownedPositioningBinding: PositioningCollectionBinding? = null
        private var ownedLocationCallback: LocationCallback? = null
        private lateinit var owner: TactileDotReferenceDeliveryOwner
        private var sequence = 0L
        private val epoch get() = lifecycle.snapshot().epoch

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val context = instrumentation.targetContext
            for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                assertEquals("ENV_OS_PERMISSION $permission", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
            assertTrue("ENV_LOCATION_ENABLED", context.getSystemService(LocationManager::class.java).isLocationEnabled)
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertNotNull(ConfiguredActivity::class.java.getConstructor())
            assertNotNull(MainActivity::class.java.getConstructor())
            val component = ComponentName(context, MainActivity::class.java)
            main = instrumentation.newActivity(ConfiguredActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main tactile fallback probe", null, null, null) as MainActivity
            navigator = field(main, "routeNavigator") as RouteNavigator // Existing accepted NAV fixture access.
            resultText = TextView(main).apply { visibility = View.INVISIBLE }
            actuator = AndroidFeedbackActuator(runtime = runtime,
                speechAllowed = { invoke("authorizeActuatorSpeechForHomeWakeInput") as Boolean })
            actuator.speechOutputListener = { events += it }
        }

        fun prepare() {
            assertNull("Never replace an existing process account", GatewaySessionProcessCoordinator.snapshot().session)
            assertNull("Never replace existing process onboarding", GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val onboarding = completedOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_tactile_probe", System.currentTimeMillis() + 60_000L)
            check(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            val device = completedDeviceCheck(requireNotNull(gatewayGeneration))
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val now = SystemClock.elapsedRealtime()
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val ready = WalkSessionReadinessCollector(epoch, plan).apply {
                plan.requiredRequirements.forEach { record(WalkSessionReadinessObservation(
                    epoch, it, WalkSessionReadinessStatus.READY, "synthetic prepared native/domain fixture input")) }
            }.build(now)
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(ready))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            check(lifecycle.snapshot().state == WalkSessionState.ACTIVE)
            val authority = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
            val start = requireNotNull(authority.beginStart(epoch))
            check(authority.completeStart(start, GatewayWalkStartResult.Granted(GatewayWalkLease(
                "started", ACTOR, session.deviceId, epoch.walkSessionId, "synthetic-lease", 1L,
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000L,
                System.currentTimeMillis(), now + 60_000L))) == GatewayWalkAuthorityCompletion.LEASE_ACTIVE)
            val profiles = WalkSafeEnvironmentProfiles.testCandidate
            val camera = cameraAssessment(now)
            val gps = OfficialEnvironmentPolicy.assessGpsQuality(epoch, now, GpsQualityObservation(epoch, now, true, 2.0), profiles.officialEnvironment)
            val confirmation = OfficialEnvironmentUserConfirmation(epoch, EnvironmentEvidenceStatus.PASS,
                EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
                EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS)
            val assessment = OfficialEnvironmentPolicy.assess(epoch, now, gps, camera.asMeasuredEnvironmentEvidence(), confirmation, profiles.officialEnvironment)
            val guard = OfficialEnvironmentRuntimeGuard(epoch, profiles.officialEnvironment)
            check(assessment.canStartWalk && guard.onAssessment(assessment, now).navigationOutputsAllowed)
            // The existing NAV nine purposes carry typed policy state, actual foreground and local UI.
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            setField(main, "postLoginDeviceCheckSnapshot", device)
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "isActivityForeground", true)
            setField(main, "officialEnvironmentOutputsAllowed", assessment.canStartWalk)
            setField(main, "officialEnvironmentRuntimeGuard", guard)
            setField(main, "isRouteActive", true)
            setField(main, "feedbackActuator", actuator)
            setField(main, "walkLastResultText", resultText)
            val capability = WalkSafeStartupCapabilityResolver.resolve(WalkSafeStartupCapabilityInput(
                true, true, true, true, true, true, true, true, true, "synthetic-capability-fixture"))
            check(capability.mayConfirmAndStart && capability.tier == WalkSafeStartupCapabilityTier.FULL)
            setField(main, "startupCapabilityDecision", capability)
            setField(main, "confirmedStartupCapabilityDecision", capability)
            val mountConfirmation = PhoneMountingUserConfirmation(epoch, now, PhoneMountingMethod.CHEST_FORWARD, false)
            val mounting = PhoneMountingPolicy.assess(PhoneMountingAssessmentPhase.ACTIVE, epoch, now,
                mountConfirmation, camera, PhoneMountingPolicy.initialState(epoch, profiles.phoneMounting))
            check(mounting.status == PhoneMountingStatus.SUITABLE)
            setField(main, "phoneMountingUserConfirmation", mountConfirmation)
            setField(main, "latestPhoneMountingCameraAssessment", camera)
            setField(main, "phoneMountingRuntimeState", mounting.nextState)
            setField(main, "phoneMountingStatusText", TextView(main)) // Actual Android UI leaf used by the original watchdog.
            invoke("applyPhoneMountingRuntimeAssessment", arrayOf(PhoneMountingAssessment::class.java,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!), arrayOf(mounting, false, false))
            check(main.observeRuntimeRawCameraAssessment(camera, now))
            // These four values are observations of the explicitly doubled native availability/load.
            val depthSupport = RuntimeMetricDepthSupport.SUPPORTED
            setField(main, "arCoreSupported", nativeAr.session != null && nativeAr.provider != null)
            setField(main, "depthSupported", depthSupport == RuntimeMetricDepthSupport.SUPPORTED)
            val configBytes = main.assets.open("model-config/two_model_runtime.json").use { it.readBytes() }
            val config = TwoModelRuntimeConfig.load(main)
            val configSha = MessageDigest.getInstance("SHA-256").digest(configBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val load = RunningDetectorLoadEvidence(assetIntegrityVerified = config.unifiedWalksafe?.enabled == true,
                interpreterLoaded = true, sampleInferencePassed = true) // Native load/sample observations only.
            check(load.passed)
            setField(main, "detectorConfigLoaded", load.assetIntegrityVerified)
            setField(main, "detectorAvailable", load.interpreterLoaded)
            setField(main, "reportModelConfigSha256", configSha)
            val detector = main.captureRunningDetectorObservation()
            val identity = requireNotNull(RunningDetectorIdentity.of(config, configSha, config.primaryModelKey, "cpu", false, detector.generation))
            main.beginRunningDetectorVerification(detector.instance, SystemClock.elapsedRealtime())
            assertEquals(RunningDetectorVerificationStep.VERIFIED, main.recordRunningDetectorVerification(
                detector.instance, identity, load, detector.generation))
            assertEquals(configSha, main.captureRunningDetectorObservation().configSha256)
            navigator.setRoute(cornerRoute())
            turnCue()
            owner = TactileDotReferenceDeliveryOwner(epoch, field(main, "feedbackLifecycleGeneration") as Int,
                field(main, "detectorGeneration") as Int, nativeAr.generation)
            main.beginWalkRuntimeSafetyEpoch(epoch)
            qualifyMetric()
            runtime.ready()
            Log.i(TAG, "PREPARED actualMainConstructor=true configuredNoargSubclass=true behaviorOverride=false " +
                "purposeFields=21 nativeArDetectorSpeech=DOUBLES syntheticProfile=100/200/100 mainOnCreate=false")
        }

        fun assertHealthy() {
            for (name in listOf("firstRunOnboardingComplete", "postLoginDeviceCheckPassesFeatureGate", "isWalkSessionRuntimeActive",
                "navigationEnvironmentOutputsAllowed", "authorizeActuatorSpeechForHomeWakeInput", "cameraEnvironmentOutputsAllowed",
                "isStartupCapabilityConfirmed", "runningDetectorVerifiedForOutput"))
                assertEquals("FIXTURE_ACTUAL_GATE $name", true, invoke(name))
            assertTrue("FIXTURE_ACTUAL_METRIC_GATE", main.currentRuntimeMetricOutputAllowsWork(nativeAr.generation) { nativeAr })
            assertTrue("FIXTURE_NATIVE_LEASE", main.isRuntimeArLeaseCurrent(nativeAr) { nativeAr })
            assertTrue("FIXTURE_DEVICE_GATE", deviceState().allows(DeviceGatePurpose.ACTUATOR_READINESS))
        }

        private fun qualifyMetric() {
            val started = SystemClock.elapsedRealtime()
            main.beginRuntimeMetricActivation(nativeAr.generation, started)
            for (index in 0..10) {
                if (index > 0) Thread.sleep(110L)
                val at = SystemClock.elapsedRealtime()
                val snapshot = depthSnapshot(at * 1_000_000L, at * 1_000_000L)
                metric(snapshot, at)
            }
            assertTrue("FIXTURE_QUALIFICATION: actual typed window must pass", main.currentRuntimeMetricOutputAllowsWork(nativeAr.generation) { nativeAr })
        }

        fun assertMetricOwnershipRejections() {
            for (mode in listOf("expected-generation", "evidence-generation", "old-epoch")) {
                assertTrue(main.currentRuntimeMetricOutputAllowsWork(nativeAr.generation) { nativeAr })
                val now = SystemClock.elapsedRealtime()
                val snapshot = depthSnapshot(now * 1_000_000L, now * 1_000_000L)
                val evidence = metricEvidence(snapshot, now).let { if (mode == "evidence-generation") it.copy(generation = nativeAr.generation + 1L) else it }
                val expected = if (mode == "expected-generation") nativeAr.generation + 1L else nativeAr.generation
                val capturedEpoch = if (mode == "old-epoch") epoch.copy(recoveryGeneration = epoch.recoveryGeneration + 1L) else epoch
                main.updateRuntimeMetricOutputGate(evidence, expected, capturedEpoch, observeRuntimeAr = { nativeAr })
                assertFalse("Actual healthy typed boundary rejects $mode", main.currentRuntimeMetricOutputAllowsWork(nativeAr.generation) { nativeAr })
                qualifyMetric()
                Log.i(TAG, "METRIC_NEGATIVE mode=$mode healthyBefore=true outputAfter=false requalified=true")
            }
        }

        private fun metricEvidence(snapshot: DepthFrameSnapshot, at: Long): RuntimeMetricFrameEvidence = RuntimeMetricFrameEvidence(
            nativeAr.generation, snapshot.frameTimestampNs, at, tracking = true,
            metricDepthAvailable = main.runtimeRecognitionHasMetricDepth(snapshot.cameraImageTimestampNs, snapshot.frameTimestampNs, snapshot),
            validMetricSamplesInRange = main.runtimeRecognitionMetricSampleCount(snapshot.cameraImageTimestampNs, snapshot.frameTimestampNs, snapshot))

        private fun metric(snapshot: DepthFrameSnapshot, at: Long) {
            entries += "updateRuntimeMetricOutputGate"
            main.updateRuntimeMetricOutputGate(metricEvidence(snapshot, at), nativeAr.generation, epoch, observeRuntimeAr = { nativeAr })
        }

        fun refreshSafety() {
            val now = SystemClock.elapsedRealtime()
            val result = main.observeWalkRuntimeSafety(WalkRuntimeSafetyObservation(epoch, now,
                cameraTrusted = true, depthTrusted = true, gpsTrusted = true, riskTrusted = true, ttsTrusted = true,
                batteryCritical = false, storageCritical = false, thermalCritical = false,
                frameCapturedAtElapsedRealtimeMs = now, inferenceLatencyMs = 0L))
            assertTrue("FIXTURE_ACTUAL_SAFETY ${result.disposition}", result.epoch == epoch && result.safetyOutputsAllowed)
        }

        private fun cameraAssessment(now: Long): CameraFrameQualityAssessment = CameraFrameQualityPolicy.assess(epoch, now,
            CameraFrameQualityObservation(epoch, now, true, .5, 0.0, 0.0, 0.0), WalkSafeEnvironmentProfiles.testCandidate.cameraFrameQuality)

        fun frame(source: FrameSource): TactileSnapshotFrameResult {
            val now = SystemClock.elapsedRealtime()
            val timestamp = now * 1_000_000L + sequence++
            val key = VisualFrameKey(nativeAr.generation, timestamp, timestamp, now, 3L)
            val depth = depthSnapshot(key.frameId, key.cameraTimestampNs)
            metric(depth, now)
            check(main.observeRuntimeRawCameraAssessment(cameraAssessment(now), now))
            val transform = requireNotNull(FrozenImageToDepthTransform.create(key.frameId,
                listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f)))
            val location = TrustedLocation(vertex().latitude, vertex().longitude, 3f, now)
            val context = TactileProjectionContext(key.frameId, navigator.currentRouteId(), navigator.currentProjection(location),
                location, null, null, TactileCameraFrame(key.frameId,
                    RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), TactileCameraIntrinsics(100, 100, 70f, 70f, 50f, 50f), now, true),
                0L, now, now)
            val evidence = DetectionFrameEvidence(key.frameId, key.cameraTimestampNs / 1_000_000L, depth,
                FrozenImageToTextureCoordinateMapper(key.frameId, transform, ImageSize(100, 100)), context,
                MotionContext(), false, false, visualFrameKey = key)
            val a = DetectionCandidate("dot_tactile_paving", .9f, RectNorm(.2f, .4f, .2f, .4f))
            val b = DetectionCandidate("dot_tactile_paving", .9f, RectNorm(.6f, .4f, .2f, .4f))
            val detections = when (source) { FrameSource.TRACK_A -> listOf(a); FrameSource.BOTH_TRACKS -> listOf(a, b); FrameSource.TRACK_B -> listOf(b); FrameSource.EMPTY -> emptyList() }
            val snapshot = DetectionSnapshot(
                detections = detections, identity = AndroidDetectionSnapshotFrameIdentity(key.frameId, key.cameraTimestampNs / 1_000_000L),
                capturedAtMs = now, startedAtMs = now, completedAtMs = now, imageWidth = 100, imageHeight = 100,
                detectDurationMs = 0L, detectorTiming = null, partial = false, frameEvidence = evidence, capturedAtElapsedRealtimeMs = now)
            entries += "prepareTactileFrameDispatch"
            return main.prepareTactileFrameDispatch(snapshot, now, key.cameraTimestampNs / 1_000_000L, 400L, 400L, 0L, now) { capture, candidates ->
                pipeline.process(snapshot = capture.depthSnapshot, frameId = capture.frameId, timestampMs = capture.frameTimestampMs,
                    detections = candidates, mapper = requireNotNull(capture.depthMapper), mapperFrameId = capture.depthMapper.frameId,
                    capturedAtElapsedRealtimeMs = now)
            }.frame
        }

        fun publish(frame: TactileSnapshotFrameResult) {
            refreshSafety()
            entries += "publishTactileFrameReferenceObservation"
            assertTrue(main.publishTactileFrameReferenceObservation(frame, owner))
            entries += "dispatchTactileFrameFeedback"
            val dispatch = main.dispatchTactileFrameFeedback(frame, false, deviceState().alertsAllowed, SystemClock.elapsedRealtime())
            assertNull("Dot reference fixture must not manufacture a generic risk announcement", dispatch.action)
        }

        fun dispatchPoint() {
            refreshSafety()
            entries += "maybeSpeakTactileDotReference"
            main.maybeSpeakTactileDotReference(owner, nativeAr, observeRuntimeAr = { nativeAr }, onDispatchResult = { pointAdmissions += it })
        }

        private fun deviceState(): DeviceGateState = invoke("buildDeviceGateState", arrayOf(TrackedObjectDepth::class.java,
            String::class.java, java.lang.Long::class.java, TrackedObjectDepth::class.java, Boolean::class.javaPrimitiveType!!,
            Function0::class.java), arrayOf(null, null, nativeAr.generation, null, false, { nativeAr })) as DeviceGateState

        fun turnCue(): RouteNavigatorUpdate {
            val now = SystemClock.elapsedRealtime()
            val point = vertex()
            val update = navigator.update(TrustedLocation(point.latitude, point.longitude, 3f, now), now, false,
                FilteredRoutePosition(point, 3.0, now, heading = RouteHeadingEstimate(180.0, 8.0)))
            assertNotNull("FIXTURE_TURN: real corner producer must issue an instruction", update.instruction)
            assertEquals("안내 지점 부근입니다. 경로 기준으로 3시 방향으로 꺾으세요.", update.instruction)
            assertEquals("route_guidance", update.reason)
            assertEquals(0, requireNotNull(update.speechCueToken).guideIndex)
            assertEquals(navigator.currentRouteId(), requireNotNull(update.speechCueToken).routeId)
            assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, update.routeAlignmentDiagnostic?.reason)
            assertFalse(requireNotNull(update.speechCueToken).requiresFacingEvidence)
            assertNull("FIXTURE_TURN: no forced INFO suppression", update.infoUtterance)
            setField(main, "latestTmapOnRoute", !update.offRoute)
            return update
        }

        fun dispatchTurn(cue: RouteNavigatorUpdate) {
            prepareNavigationCollection()
            entries += "tryDispatchRouteInstruction"
            invoke("tryDispatchRouteInstruction", arrayOf(RouteNavigatorUpdate::class.java, WalkRuntimeEpoch::class.java), arrayOf(cue, epoch))
        }

        private fun prepareNavigationCollection() {
            val positioning = MainActivityControllerTestFixture.positioning(main)
            val guidance = MainActivityControllerTestFixture.controller(main, "routeGuidanceController") as RouteGuidanceController
            val requestGeneration = (MainActivityControllerTestFixture.controller(main, "routeAcquisitionController")
                as RouteAcquisitionController).requestGeneration
            val feedbackGeneration = field(main, "feedbackLifecycleGeneration") as Int
            val currentOwner = requireNotNull(invoke("currentLocationCollectionOwnerOrNull") as? LocationCollectionOwner)
            assertEquals(ACTOR, currentOwner.actorId)
            assertSame(GatewaySessionProcessCoordinator.snapshot().session, currentOwner.gatewaySession)
            assertEquals(requireNotNull(gatewayGeneration), currentOwner.gatewayGeneration)
            if (ownedPositioningBinding == null) {
                assertNull(positioning.binding)
                assertNull(positioning.collectionOwner)
                assertNull(positioning.callback)
                assertFalse("The unbound collection must reject route speech",
                    guidance.isRouteSpeechCurrent(epoch, requestGeneration, feedbackGeneration))
                val binding = PositioningCollectionBinding(positioning.coordinator,
                    positioning.coordinator.currentInputGeneration(), currentOwner, positioning.callbackGeneration,
                    SystemClock.elapsedRealtime())
                val callback = object : LocationCallback() {} // Purpose input double; never registered with Fused.
                ownedPositioningBinding = binding
                ownedLocationCallback = callback
                setField(main, "locationCollectionOwner", currentOwner)
                setField(main, "positioningCollectionBinding", binding)
                setField(main, "locationCallback", callback)
            }
            val binding = requireNotNull(ownedPositioningBinding)
            assertEquals(currentOwner, binding.owner)
            assertSame(binding, positioning.binding)
            assertSame(ownedLocationCallback, positioning.callback)
            assertTrue(positioning.isPositioningCollectionCurrent(binding))
            assertTrue(lifecycle.isRuntimeEpochCurrent(epoch))
            assertTrue("The actual bound collection must admit the original route speech",
                guidance.isRouteSpeechCurrent(epoch, requestGeneration, feedbackGeneration))
        }

        fun routeState(): List<Any?> = listOf(navigator.currentRouteId(), navigator.currentTactileRouteProgress(),
            navigator.currentRouteMatch()?.geometricProgressM, navigator.pendingDecisionToken())
        fun count(kind: SpeechOutputKind, outcome: SpeechOutputOutcome) = events.count { it.kind == kind && it.outcome == outcome }
        fun invoke(name: String, types: Array<Class<*>> = emptyArray(), arguments: Array<Any?> = emptyArray()): Any? =
            MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(main, *arguments)

        fun close() {
            setField(main, "isRouteActive", false)
            setField(main, "isActivityForeground", false)
            lifecycle.handle(WalkSessionEvent.EnteredBackground)
            invoke("cancelNavigationSpeechRetry")
            ownedPositioningBinding?.let { binding ->
                val positioning = MainActivityControllerTestFixture.positioning(main)
                if (positioning.binding === binding && positioning.collectionOwner === binding.owner &&
                    positioning.callback === ownedLocationCallback) {
                    for (name in listOf("locationCallback", "positioningCollectionBinding", "locationCollectionOwner"))
                        MainActivityControllerTestFixture.field(name).set(main, null)
                    assertFalse(positioning.isPositioningCollectionCurrent(binding))
                }
            }
            actuator.close()
            gatewayGeneration?.let { check(GatewaySessionProcessCoordinator.clear(it) != null) }
            gatewayGeneration = null
            Log.i(TAG, "CLEANUP ownedActorClosed=true ownedGatewayCleared=true mainOnDestroy=false")
        }
    }

    private class RecordingRuntime : FeedbackSpeechRuntime {
        data class Spoken(val message: String, val id: String)
        val spoken = mutableListOf<Spoken>()
        var stops = 0
        private val handler = Handler(Looper.getMainLooper())
        private var initialize: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler = object : FeedbackSpeechScheduler {
            override fun post(task: Runnable) = handler.post(task)
            override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
            override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
            override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
        }
        override val engineInitialized get() = initialize != null
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        override fun initialize(onInitialized: (Int) -> Unit) { initialize = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int { spoken += Spoken(message, utteranceId); return TextToSpeech.SUCCESS }
        override fun stop() { stops++ }
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("Point/TURN fixture cannot create a progress tone")
        fun ready() { requireNotNull(initialize)(TextToSpeech.SUCCESS) }
        fun start(id: String) { requireNotNull(listener).onStart(id) }
        fun done(id: String) { requireNotNull(listener).onDone(id) }
    }

    companion object {
        private const val TAG = "MainTactileFallback"
        private const val ACTOR = "11111111-1111-4111-8111-111111111111"
        private fun <T> onMain(instrumentation: Instrumentation, block: () -> T): T {
            val task = FutureTask(Callable(block))
            instrumentation.runOnMainSync(task)
            return task.get()
        }
        private fun stableFrames(instrumentation: Instrumentation, fixture: Fixture, both: Boolean): TactileSnapshotFrameResult {
            var frame = onMain(instrumentation) { fixture.frame(if (both) FrameSource.BOTH_TRACKS else FrameSource.TRACK_A) }
            repeat(2) {
                Thread.sleep(370L)
                frame = onMain(instrumentation) { fixture.frame(if (both) FrameSource.BOTH_TRACKS else FrameSource.TRACK_A) }
            }
            assertNotNull("FIXTURE_STABLE_TRACK: actual Main/default coordinator requires 3 frames and 700 ms", frame.guidance.dotReferenceNotice)
            return frame
        }
        private fun depthSnapshot(frameId: Long, timestamp: Long): DepthFrameSnapshot {
            val values = IntArray(10_000) { pixel -> if (pixel % 100 >= 60) 2_400 else 2_000 }
            return DepthFrameSnapshot(frameId, DepthImage16(100, 100, values), ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
                DepthImage16(100, 100, values.copyOf()), timestamp, fullDepthTimestampNs = timestamp,
                cameraImageTimestampNs = timestamp, rawConfidenceTimestampNs = timestamp)
        }
        private fun point(eastM: Double, northM: Double = 0.0) = RoutePoint(northM / 111194.92664455874, eastM / 111194.92664455874)
        private fun vertex() = point(20.0)
        private fun cornerRoute() = WalkingRoute("TACTILE_TURN_PROBE", WalkingRouteSummary(120, 120),
            listOf(point(0.0), vertex(), point(20.0, -100.0)), listOf(
                WalkingRouteGuidePoint(0, vertex(), null, 20, 100, turnType = 13),
                WalkingRouteGuidePoint(1, point(20.0, -75.0), null, 95, 25, turnType = 11)))
        private fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)
        private fun setField(main: MainActivity, name: String, value: Any) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
        private fun completedOnboarding(): FirstRunOnboardingSnapshot {
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            check(restored.fullyRestored)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot, restored.snapshot.epoch, restored.snapshot.revision)
            check(jit.accepted)
            val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch, jit.current.revision)
            check(device.accepted && device.current.mayEnterWalk)
            return device.current
        }
        private fun completedDeviceCheck(generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), ACTOR, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, ACTOR, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { check(it.state == PostLoginDeviceCheckState.FULL) }
        }
        private fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
    }
}
