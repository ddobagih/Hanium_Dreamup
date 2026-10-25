package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.inference.DetectorStallWatchdog
import kr.co.hanium.dreamup.walksafe.inference.NativeDetectorFlight
import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationCallback
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.session.LocationCollectionOwner
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeFalse
import org.junit.runner.RunWith

/**
 * SOURCE-only exact1: existing DEBUG profile, conditional native-education precondition, public
 * camera/GPS assessment and actual Main observe/record/activate -> original environment post chain
 * -> no new frame/depth/progress inputs -> actual per-source expiry consumer -> retired A/fresh B.
 * The prior education helper is a controlled fixture precondition, not an education/TTS producer
 * success in this test. Weather/sidewalk stay UNKNOWN. No allowance/guard/READY flag is written.
 * Unattached Views preserve super.postDelayed; exact Runnables are consumed manually on MainLooper.
 * Actual detector flight admission/completion is prepared; real detector inference is not executed.
 * Native Actors created by fault consumers are retired in the same UI finally on failure too.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityOfficialEnvironmentWatchdogConsumerAndroidTest {
    @Test(timeout = 30_000L)
    fun actualEnvironmentWatchdogRevokesExpiredSourcesAfterDetectorCompletionAndRejectsRetiredCallbacks() = withFixture { f ->
        awaitOnMain(f.instrumentation, "Original controlled Actor onInit reached READY") {
            f.actor.readySpeechEngineGenerationOrNull() != null
        }
        val a = f.preparePositiveEnvironment(f.preparePositiveMount())
        val flight = onMain(f.instrumentation) { f.completeOwnedDetectorFlight(a.epoch) }
        onMain(f.instrumentation) {
            assertTrue(flight.ticket.completed)
            assertFalse(f.detectorBlocked())
            assertFalse((field(f.main, "detectionInFlight") as java.util.concurrent.atomic.AtomicBoolean).get())
            assertTrue(f.bool("navigationEnvironmentOutputsAllowed"))
            assertTrue(f.bool("cameraEnvironmentOutputsAllowed"))
            assertNotNull(f.actor.readySpeechEngineGenerationOrNull())
            assertFalse(f.envStatus.isAttachedToWindow)
            assertFalse(f.mountStatus.isAttachedToWindow)
            // Keep only the environment post chain under test; cancel fixture-owned mounting posts.
            f.mountStatus.discardOwnPosts()
        }
        var pending = a.post
        var sawCameraUnavailable = false
        var sawCameraUnavailableWhileGpsAvailable = false
        // No producer/progress inputs are added in this interval. Each next task is emitted by
        // the preceding actual Main consumer, with its original relative delay and generation.
        while (onMain(f.instrumentation) { a.guard.decision().action == OfficialEnvironmentRuntimeAction.CONTINUE }) {
            waitAfter(pending.post.dueAtElapsedMs)
            val tick = onMain(f.instrumentation) { f.consumeEnvironmentTick(pending, a, flight) }
            if (!tick.decision.cameraOutputsAllowed) sawCameraUnavailable = true
            if (!tick.decision.cameraOutputsAllowed && tick.decision.navigationOutputsAllowed)
                sawCameraUnavailableWhileGpsAvailable = true
            tick.next?.let { pending = it }
        }
        onMain(f.instrumentation) {
            assertTrue(sawCameraUnavailable)
            assertTrue("FIXTURE_CLOCK_STAGE camera expired while original GPS evidence was still current",
                sawCameraUnavailableWhileGpsAvailable)
            // This assertion identifies the intended clock stage; late fixture scheduling is not
            // a new production requirement and must not be classified as a product expiry bug.
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN, f.assessment(a.epoch).factorStatuses[OfficialEnvironmentFactor.CAMERA_QUALITY])
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN, f.assessment(a.epoch).factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY])
            assertEquals(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY, a.guard.decision().action)
            assertFalse(field(f.main, "officialEnvironmentOutputsAllowed") as Boolean)
            assertFalse(f.bool("cameraEnvironmentOutputsAllowed"))
            assertFalse(f.bool("navigationEnvironmentOutputsAllowed"))
            assertFalse(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
            assertNull(f.actor.readySpeechEngineGenerationOrNull())
            assertNull(field(f.main, "feedbackActuator"))
            assertTrue(flight.ticket.completed)
            assertFalse(f.detectorBlocked())
            assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
            assertTrue(f.collectionCurrent())
            assertEquals(a.gpsObservedAt, (field(f.main, "officialEnvironmentGpsEvidence") as MeasuredEnvironmentEvidence).observedAtElapsedRealtimeMs)
            assertEquals(a.cameraObservedAt, (field(f.main, "officialEnvironmentCameraEvidence") as MeasuredEnvironmentEvidence).observedAtElapsedRealtimeMs)
            f.invoke("invalidateOfficialEnvironmentEvidence", Void.TYPE, arrayOf(String::class.java), "environment-a-retired")
            f.invoke("invalidatePhoneMountingEvidence", Void.TYPE, arrayOf(String::class.java), "environment-a-retired")
            f.envStatus.discardOwnPosts()
            f.mountStatus.discardOwnPosts()
            f.installControlledActor()
            f.runtime.ready()
        }
        awaitOnMain(f.instrumentation, "Fresh controlled B Actor onInit reached READY") {
            f.actor.readySpeechEngineGenerationOrNull() != null
        }
        val b = f.preparePositiveEnvironment(f.preparePositiveMount())
        onMain(f.instrumentation) {
            assertEquals(a.epoch, b.epoch)
            assertNotSame(a.guard, b.guard)
            assertTrue(b.gpsObservedAt > a.gpsObservedAt)
            assertTrue(b.cameraObservedAt > a.cameraObservedAt)
            assertTrue(f.bool("navigationEnvironmentOutputsAllowed"))
            assertTrue(f.bool("cameraEnvironmentOutputsAllowed"))
            assertTrue(field(f.main, "officialEnvironmentOutputsAllowed") as Boolean)
            val decision = b.guard.decision()
            val generation = field(f.main, "officialEnvironmentWatchdogGeneration")
            val mountGeneration = field(f.main, "phoneMountingWatchdogGeneration")
            val detectorGeneration = field(f.main, "detectorGeneration")
            val currentActor = field(f.main, "feedbackActuator")
            val owner = f.coordinator.currentUserMotionOwner()
            val posts = f.envStatus.posts.size
            var retiredFailure: Throwable? = null
            try {
                a.post.post.task.run()
                assertSame(b.guard, field(f.main, "officialEnvironmentRuntimeGuard"))
                assertSame(decision, b.guard.decision())
                assertEquals(generation, field(f.main, "officialEnvironmentWatchdogGeneration"))
                assertEquals(mountGeneration, field(f.main, "phoneMountingWatchdogGeneration"))
                assertEquals(detectorGeneration, field(f.main, "detectorGeneration"))
                assertSame(currentActor, field(f.main, "feedbackActuator"))
                assertEquals(owner, f.coordinator.currentUserMotionOwner())
                assertEquals(posts, f.envStatus.posts.size)
                assertTrue(f.bool("navigationEnvironmentOutputsAllowed"))
                assertTrue(f.bool("cameraEnvironmentOutputsAllowed"))
                assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
                assertTrue(f.collectionCurrent())
            } catch (failure: Throwable) {
                retiredFailure = failure
                throw failure
            } finally {
                try {
                    if (field(f.main, "feedbackActuator") !== f.actor) f.retireCurrentCommandAndActor()
                } catch (cleanupFailure: Throwable) {
                    val original = retiredFailure
                    if (original == null) throw cleanupFailure
                    if (original !== cleanupFailure) original.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    private data class EnvironmentPost(val post: CapturedPost, val generation: Long)
    private data class EnvironmentPositive(val epoch: WalkRuntimeEpoch,
        val guard: OfficialEnvironmentRuntimeGuard, val post: EnvironmentPost,
        val gpsObservedAt: Long, val cameraObservedAt: Long)
    private data class EnvironmentTick(val decision: OfficialEnvironmentRuntimeDecision,
        val next: EnvironmentPost?)

    private data class CapturedPost(val task: Runnable, val delayMs: Long,
        val postedAtElapsedMs: Long, val accepted: Boolean) {
        val dueAtElapsedMs: Long get() = postedAtElapsedMs + delayMs
    }
    private class CapturingTextView(context: Context) : TextView(context) {
        val posts = mutableListOf<CapturedPost>()
        override fun postDelayed(action: Runnable, delayMillis: Long): Boolean {
            val at = SystemClock.elapsedRealtime()
            val accepted = super.postDelayed(action, delayMillis)
            posts += CapturedPost(action, delayMillis, at, accepted)
            return accepted // Actual View API result, never fabricated scheduling success.
        }
        fun discardOwnPosts() { posts.forEach { removeCallbacks(it.task) }; posts.clear() }
    }
    private data class PositiveMount(val epoch: WalkRuntimeEpoch,
        val confirmation: PhoneMountingUserConfirmation, val camera: CameraFrameQualityObservation,
        val assessment: PhoneMountingAssessment, val post: CapturedPost, val watchdogGeneration: Long)

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val coordinator: PositioningCoordinator
        val runtime = RecordingRuntime()
        val output = SpeechOutputDiagnostics()
        lateinit var actor: AndroidFeedbackActuator
        var gatewayGeneration: Long? = null
        lateinit var binding: Any
        private var probe: AndroidStartupCapabilityProbe? = null
        private var prepared = false
        lateinit var mountStatus: CapturingTextView
        lateinit var envStatus: CapturingTextView
        private var ownedDetectorFlight: NativeDetectorFlight? = null
        init {
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main environment watchdog probe",
                null, null, null) as MainActivity
            coordinator = field(main, "positioningCoordinator") as PositioningCoordinator
        }
        fun prepare() {
            val context = instrumentation.targetContext
            assumeFalse("App-TTS positive requires actual spoken accessibility service inactive",
                invoke("isScreenReaderActive", java.lang.Boolean.TYPE) as Boolean)
            assertTrue("Existing test-candidate profile requires DEBUG; no release approval", BuildConfig.DEBUG)
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
            for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                assertEquals("ENV_OS_PERMISSION $permission", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
            assertTrue("ENV_LOCATION_ENABLED", requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled)
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val onboarding = completedOnboarding(ACTOR)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_environment_watchdog_probe", System.currentTimeMillis() + 120_000L)
            assertEquals(GatewaySessionScope.GENERAL, session.sessionScope)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            set(main, "firstRunOnboardingSnapshot", onboarding)
            set(main, "postLoginDeviceCheckSnapshot", completedDeviceCheck(ACTOR, requireNotNull(gatewayGeneration)))
            set(main, "reporterUserId", ACTOR)
            set(main, "priorityUserOnboardingActorId", ACTOR)
            set(main, "priorityUserOnboardingPolicy", completedPriorityEducation())
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val epoch = lifecycle.snapshot().epoch
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val readiness = WalkSessionReadinessCollector(epoch, plan).apply {
                plan.requiredRequirements.forEach { record(WalkSessionReadinessObservation(epoch, it,
                    WalkSessionReadinessStatus.READY, "controlled typed mount-caller readiness input")) }
            }.build(SystemClock.elapsedRealtime())
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            set(main, "walkSessionLifecycle", lifecycle)
            set(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            prepared = true
            mountStatus = CapturingTextView(context)
            set(main, "phoneMountingStatusText", mountStatus)
            envStatus = CapturingTextView(context)
            set(main, "statusText", envStatus)
            set(main, "detailText", TextView(context))
            probe = AndroidStartupCapabilityProbe(context, {}) // No start/recheck or hardware result injection.
            set(main, "startupCapabilityProbe", requireNotNull(probe))
            assertTrue(featureEnabled(PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK))
            assertTrue(featureEnabled(PostLoginDeviceCheckFeature.VOICE_GUIDANCE))
            installControlledActor()
            runtime.ready() // Original onInit callback, not a READY or capability flag write.
            bindCurrentCollector()
        }
        fun installControlledActor() {
            assertNull(field(main, "feedbackActuator"))
            actor = AndroidFeedbackActuator(runtime,
                speechAllowed = { invoke("authorizeActuatorSpeechForHomeWakeInput", java.lang.Boolean.TYPE) as Boolean },
                hapticAllowed = { featureEnabled(PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK) })
            actor.speechOutputListener = output::record
            MainActivityControllerTestFixture.replaceActuator(main, actor)
        }
        fun featureEnabled(feature: PostLoginDeviceCheckFeature) = invoke("postLoginDeviceFeatureEnabled",
            java.lang.Boolean.TYPE, arrayOf(PostLoginDeviceCheckFeature::class.java), feature) as Boolean
        fun bindCurrentCollector() {
            val owner = requireNotNull(invoke("currentLocationCollectionOwnerOrNull", ownerClass))
            val locationGeneration = field(main, "locationCallbackGeneration") as Int
            val startedAt = SystemClock.elapsedRealtime()
            binding = bindingClass.getDeclaredConstructor(PositioningCoordinator::class.java, java.lang.Long.TYPE,
                ownerClass, Integer.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
                .newInstance(coordinator, coordinator.currentInputGeneration(), owner, locationGeneration, startedAt)
            set(main, "locationCollectionOwner", owner)
            set(main, "positioningCollectionBinding", binding)
            set(main, "locationCallback", object : LocationCallback() {}) // No Fused transport registration.
            assertEquals(true, invoke("bindPositioningUserMotionEpoch", java.lang.Boolean.TYPE,
                arrayOf(bindingClass, java.lang.Long.TYPE), binding, startedAt))
            assertTrue(collectionCurrent())
        }
        fun collectionCurrent() = invoke("isPositioningCollectionCurrent", java.lang.Boolean.TYPE,
            arrayOf(bindingClass), binding) as Boolean
        fun preparePositiveMount(): PositiveMount {
            val faultAt = onMain(instrumentation) {
                val at = SystemClock.elapsedRealtime()
                val epoch = lifecycle.snapshot().epoch
                val observation = cameraObservation(epoch, at, available = false)
                val failed = CameraFrameQualityPolicy.assess(epoch, at, observation, CAMERA_PROFILE)
                assertEquals(EnvironmentEvidenceStatus.FAIL, failed.status)
                val fault = PhoneMountingPolicy.assess(PhoneMountingAssessmentPhase.ACTIVE,
                    epoch, at, null, failed, PhoneMountingPolicy.initialState(epoch, PROFILE))
                assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, fault.status)
                set(main, "phoneMountingRuntimeState", fault.nextState)
                set(main, "latestPhoneMountingCameraAssessment", failed)
                at
            }
            waitAfter(faultAt)
            val confirmation = onMain(instrumentation) {
                invoke("confirmPhoneMounting", Void.TYPE,
                    arrayOf(PhoneMountingMethod::class.java), PhoneMountingMethod.CHEST_FORWARD)
                (field(main, "phoneMountingUserConfirmation") as PhoneMountingUserConfirmation).also {
                    assertTrue(it.confirmedAtElapsedRealtimeMs > faultAt)
                    assertSame(it, field(main, "explicitPositioningMountConfirmation"))
                }
            }
            waitAfter(confirmation.confirmedAtElapsedRealtimeMs)
            return onMain(instrumentation) {
                val at = SystemClock.elapsedRealtime()
                val epoch = lifecycle.snapshot().epoch
                val observation = cameraObservation(epoch, at, available = true)
                val camera = CameraFrameQualityPolicy.assess(epoch, at, observation, CAMERA_PROFILE)
                assertEquals(EnvironmentEvidenceStatus.PASS, camera.status)
                val previous = field(main, "phoneMountingRuntimeState") as PhoneMountingRuntimeState
                val positive = PhoneMountingPolicy.assess(PhoneMountingAssessmentPhase.ACTIVE,
                    epoch, at, confirmation, camera, previous, runtimeRetryRequested = true)
                assertEquals(PhoneMountingStatus.SUITABLE, positive.status)
                set(main, "latestPhoneMountingCameraAssessment", camera) // Typed producer output.
                val before = mountStatus.posts.size
                val beforeGeneration = field(main, "phoneMountingWatchdogGeneration") as Long
                val actual = invoke("applyCurrentPhoneMountingRuntimeAssessment", PhoneMountingAssessment::class.java,
                    arrayOf(java.lang.Boolean.TYPE, CameraFrameQualityAssessment::class.java,
                        java.lang.Long::class.java, java.lang.Long::class.java, java.lang.Boolean.TYPE),
                    true, camera, null, null, false) as PhoneMountingAssessment
                assertEquals(PhoneMountingStatus.SUITABLE, actual.status)
                assertTrue(field(main, "phoneMountingOutputsAllowed") as Boolean)
                assertSame(actual.nextState, field(main, "phoneMountingRuntimeState"))
                assertEquals(before + 1, mountStatus.posts.size)
                val generation = field(main, "phoneMountingWatchdogGeneration") as Long
                assertEquals(beforeGeneration + 1L, generation)
                PositiveMount(epoch, confirmation, observation, actual, mountStatus.posts.last(), generation)
            }
        }
        fun bool(name: String) = invoke(name, java.lang.Boolean.TYPE) as Boolean
        fun assessment(epoch: WalkRuntimeEpoch) = invoke("currentOfficialEnvironmentAssessment",
            OfficialEnvironmentAssessment::class.java, arrayOf(WalkRuntimeEpoch::class.java, java.lang.Long.TYPE),
            epoch, SystemClock.elapsedRealtime()) as OfficialEnvironmentAssessment
        fun preparePositiveEnvironment(mount: PositiveMount): EnvironmentPositive = onMain(instrumentation) {
            assertTrue((field(main, "priorityUserOnboardingPolicy") as PriorityUserOnboardingPolicy).snapshot().nativeEducationComplete)
            assertNull(field(main, "officialEnvironmentUserConfirmation"))
            val publicCamera = CameraFrameQualityPolicy.assess(mount.epoch, SystemClock.elapsedRealtime(),
                mount.camera, CAMERA_PROFILE)
            assertEquals(EnvironmentEvidenceStatus.PASS, publicCamera.status)
            assertNotNull(invoke("observeOfficialEnvironmentCameraFrame", CameraFrameQualityObservation::class.java,
                arrayOf(CameraFrameQualityObservation::class.java, java.lang.Long::class.java, String::class.java), mount.camera, null, null))
            val at = SystemClock.elapsedRealtime()
            val publicGps = OfficialEnvironmentPolicy.assessGpsQuality(mount.epoch, at,
                GpsQualityObservation(mount.epoch, at, true, ENVIRONMENT_PROFILE.maximumGpsHorizontalAccuracyMeters),
                ENVIRONMENT_PROFILE)
            assertEquals(EnvironmentEvidenceStatus.PASS, publicGps.status)
            invoke("recordOfficialEnvironmentGpsObservation", Void.TYPE,
                arrayOf(WalkRuntimeEpoch::class.java, java.lang.Long.TYPE, java.lang.Boolean::class.java,
                    java.lang.Double::class.java), mount.epoch, at, true,
                ENVIRONMENT_PROFILE.maximumGpsHorizontalAccuracyMeters)
            val assessed = assessment(mount.epoch)
            assertEquals(EnvironmentEvidenceStatus.PASS, assessed.factorStatuses[OfficialEnvironmentFactor.CAMERA_QUALITY])
            assertEquals(EnvironmentEvidenceStatus.PASS, assessed.factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY])
            assertTrue(assessed.usageLimitsAcknowledged)
            assertEquals(ENVIRONMENT_PROFILE.profileId, assessed.profileId)
            assertEquals(OfficialEnvironmentSupport.LIMITED, assessed.support)
            assertTrue(assessed.conditionallyAllowed)
            OfficialEnvironmentFactor.entries.filter { it != OfficialEnvironmentFactor.CAMERA_QUALITY && it != OfficialEnvironmentFactor.GPS_QUALITY }
                .forEach { assertEquals(EnvironmentEvidenceStatus.UNKNOWN, assessed.factorStatuses[it]) }
            invoke("cancelCommandSpeechResponse", Void.TYPE) // Mount confirmation's pending command is owned.
            val before = envStatus.posts.size
            val beforeGeneration = field(main, "officialEnvironmentWatchdogGeneration") as Long
            assertEquals(true, invoke("activateOfficialEnvironmentRuntime", java.lang.Boolean.TYPE))
            val guard = field(main, "officialEnvironmentRuntimeGuard") as OfficialEnvironmentRuntimeGuard
            assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, guard.decision().action)
            assertTrue(guard.decision().cameraOutputsAllowed)
            assertTrue(guard.decision().navigationOutputsAllowed)
            assertTrue(bool("cameraEnvironmentOutputsAllowed"))
            assertTrue(bool("navigationEnvironmentOutputsAllowed"))
            assertEquals(before + 1, envStatus.posts.size)
            val generation = field(main, "officialEnvironmentWatchdogGeneration") as Long
            assertEquals(beforeGeneration + 1L, generation)
            val post = envStatus.posts.last()
            assertTrue(post.accepted)
            assertTrue(post.delayMs > 0L)
            val gps = field(main, "officialEnvironmentGpsEvidence") as MeasuredEnvironmentEvidence
            val camera = field(main, "officialEnvironmentCameraEvidence") as MeasuredEnvironmentEvidence
            assertEquals(at, gps.observedAtElapsedRealtimeMs)
            assertEquals(mount.camera.observedAtElapsedRealtimeMs, camera.observedAtElapsedRealtimeMs)
            EnvironmentPositive(mount.epoch, guard, EnvironmentPost(post, generation), at,
                mount.camera.observedAtElapsedRealtimeMs)
        }
        fun detectorBlocked() = (field(main, "detectorStallWatchdog") as DetectorStallWatchdog).blocksNewWork()
        fun completeOwnedDetectorFlight(epoch: WalkRuntimeEpoch): NativeDetectorFlight {
            val flight = requireNotNull(invoke("beginNativeDetectorFlight", NativeDetectorFlight::class.java,
                arrayOf(WalkRuntimeEpoch::class.java, Integer.TYPE), epoch, field(main, "detectorGeneration"))) as NativeDetectorFlight
            ownedDetectorFlight = flight
            var first: Throwable? = null
            try {
                invoke("completeNativeDetectorFlight", Void.TYPE, arrayOf(NativeDetectorFlight::class.java), flight)
                assertTrue(flight.ticket.completed)
                assertFalse(detectorBlocked())
                ownedDetectorFlight = null
                return flight
            } catch (failure: Throwable) {
                first = failure
                throw failure
            } finally {
                var cleanupFailure: Throwable? = null
                fun cleanup(action: () -> Unit) { try { action() } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else if (cleanupFailure !== failure) cleanupFailure!!.addSuppressed(failure) } }
                cleanup { (invoke("getDetectorWatchdogHandler", Handler::class.java) as Handler).removeCallbacks(flight.timeout) }
                cleanup { if (field(main, "feedbackActuator") !== actor) retireCurrentCommandAndActor() }
                cleanupFailure?.let { failure ->
                    val original = first
                    if (original == null) throw failure
                    if (original !== failure) original.addSuppressed(failure)
                }
            }
        }
        fun consumeEnvironmentTick(pending: EnvironmentPost, positive: EnvironmentPositive,
            flight: NativeDetectorFlight): EnvironmentTick {
            assertEquals(pending.generation, field(main, "officialEnvironmentWatchdogGeneration"))
            assertSame(positive.guard, field(main, "officialEnvironmentRuntimeGuard"))
            assertTrue(lifecycle.isRuntimeEpochCurrent(positive.epoch))
            envStatus.removeCallbacks(pending.post.task)
            var first: Throwable? = null
            try {
                pending.post.task.run()
                val decision = positive.guard.decision()
                assertEquals(positive.gpsObservedAt, (field(main, "officialEnvironmentGpsEvidence") as MeasuredEnvironmentEvidence).observedAtElapsedRealtimeMs)
                assertEquals(positive.cameraObservedAt, (field(main, "officialEnvironmentCameraEvidence") as MeasuredEnvironmentEvidence).observedAtElapsedRealtimeMs)
                assertTrue(flight.ticket.completed)
                assertFalse(detectorBlocked())
                assertFalse((field(main, "detectionInFlight") as java.util.concurrent.atomic.AtomicBoolean).get())
                assertEquals(decision.navigationOutputsAllowed, bool("navigationEnvironmentOutputsAllowed"))
                if (!decision.cameraOutputsAllowed) assertFalse(bool("cameraEnvironmentOutputsAllowed"))
                if (decision.suppressAllWalkOutputs) {
                    assertFalse(field(main, "officialEnvironmentOutputsAllowed") as Boolean)
                    assertFalse(bool("cameraEnvironmentOutputsAllowed"))
                    assertFalse(bool("navigationEnvironmentOutputsAllowed"))
                }
                val current = field(main, "feedbackActuator") as AndroidFeedbackActuator?
                if (current !== actor && current != null) assertNull(current.readySpeechEngineGenerationOrNull())
                val next = if (decision.action == OfficialEnvironmentRuntimeAction.CONTINUE) {
                    val generation = field(main, "officialEnvironmentWatchdogGeneration") as Long
                    assertEquals(pending.generation + 1L, generation)
                    val post = envStatus.posts.last()
                    assertNotSame(pending.post.task, post.task)
                    assertTrue(post.accepted)
                    assertTrue(post.delayMs > 0L)
                    EnvironmentPost(post, generation)
                } else null
                return EnvironmentTick(decision, next)
            } catch (failure: Throwable) {
                first = failure
                throw failure
            } finally {
                try {
                    // Preserve an unchanged controlled idle owner on CONTINUE. Any actual native
                    // replacement/null retirement is cleaned in this same UI action, even on throw.
                    if (field(main, "feedbackActuator") !== actor) retireCurrentCommandAndActor()
                } catch (cleanupFailure: Throwable) {
                    val original = first
                    if (original == null) throw cleanupFailure
                    if (original !== cleanupFailure) original.addSuppressed(cleanupFailure)
                }
            }
        }
        fun retireCurrentCommandAndActor() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (failure: Throwable) {
                if (first == null) first = failure else first!!.addSuppressed(failure) } }
            cleanup { invoke("cancelCommandSpeechResponse", Void.TYPE) }
            cleanup { (field(main, "feedbackActuator") as AndroidFeedbackActuator?)?.close() }
            cleanup { MainActivityControllerTestFixture.replaceActuator(main, null) }
            first?.let { throw it }
        }
        fun invoke(name: String, returns: Class<*>, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply {
                isAccessible = true; check(returnType == returns)
            }
            return try { method.invoke(main, *args) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (failure: Throwable) {
                if (first == null) first = failure else first!!.addSuppressed(failure) } }
            val detectorFlight = ownedDetectorFlight
            cleanup { detectorFlight?.let { invoke("completeNativeDetectorFlight", Void.TYPE,
                arrayOf(NativeDetectorFlight::class.java), it) } }
            cleanup { detectorFlight?.let {
                (invoke("getDetectorWatchdogHandler", Handler::class.java) as Handler).removeCallbacks(it.timeout)
            }; ownedDetectorFlight = null }
            // Detach the Main command/ready owner before Actor.close can post terminal callbacks.
            cleanup { if (prepared) invoke("cancelCommandSpeechResponse", Void.TYPE) }
            cleanup { (field(main, "feedbackActuator") as AndroidFeedbackActuator?)?.close() }
            cleanup { if (prepared) MainActivityControllerTestFixture.replaceActuator(main, null) }
            cleanup { if (prepared) invoke("invalidatePhoneMountingEvidence", Void.TYPE,
                arrayOf(String::class.java), "mount-watchdog-fixture-ended") }
            cleanup { set(main, "locationCallback", null); set(main, "positioningCollectionBinding", null); set(main, "locationCollectionOwner", null) }
            cleanup { if (prepared) { lifecycle.handle(WalkSessionEvent.EnteredBackground)
                set(main, "isActivityForeground", lifecycle.snapshot().isForeground) } }
            cleanup { if (::actor.isInitialized) actor.close() }
            cleanup { runtime.close() }
            cleanup { if (::mountStatus.isInitialized) mountStatus.discardOwnPosts() }
            cleanup { if (::envStatus.isInitialized) envStatus.discardOwnPosts() }
            cleanup { if (prepared) invoke("invalidateOfficialEnvironmentEvidence", Void.TYPE, arrayOf(String::class.java), "environment-watchdog-fixture-ended") }
            cleanup { if (prepared) MainActivityControllerTestFixture.replaceActuator(main, null) }
            cleanup { probe?.close() }
            cleanup { gatewayGeneration?.let { assertNotNull(GatewaySessionProcessCoordinator.clear(expectedGeneration = it)) } }
            first?.let { throw it }
        }
    }

    private class RecordingRuntime : FeedbackSpeechRuntime {
        data class SpeechRequest(val message: String, val queueMode: Int, val utteranceId: String)
        val speechRequests = mutableListOf<SpeechRequest>()
        val vibrationRequests = mutableListOf<LongArray>()
        private val handler = Handler(Looper.getMainLooper())
        private var initializeCallback: ((Int) -> Unit)? = null
        override val scheduler = object : FeedbackSpeechScheduler {
            override fun post(task: Runnable) = handler.post(task)
            override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
            override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
            override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
        }
        override val engineInitialized get() = initializeCallback != null
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        override fun initialize(onInitialized: (Int) -> Unit) { initializeCallback = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener) = TextToSpeech.SUCCESS
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            speechRequests += SpeechRequest(message, queueMode, utteranceId)
            return TextToSpeech.SUCCESS // Same controlled submission result as mount SDKb99, not playback ACK.
        }
        override fun vibrate(patternMs: LongArray): Boolean {
            vibrationRequests += patternMs.copyOf()
            return false // Retain the runtime port's default unsuccessful result, do not fake hardware success.
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No tone in mounting fixture")
        fun ready() = requireNotNull(initializeCallback).invoke(TextToSpeech.SUCCESS)
        fun close() = handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val FIXTURE_PACKAGE = "kr.co.hanium.dreamup.walksafe.speechstorefixture"
        const val USER_PACKAGE = "kr.co.hanium.dreamup.walksafe"
        const val ACTOR = "22222222-2222-4222-8222-222222222222"
        val PROFILE = WalkSafeEnvironmentProfiles.testCandidate.phoneMounting
        val ownerClass by lazy { LocationCollectionOwner::class.java }
        val bindingClass by lazy { PositioningCollectionBinding::class.java }
        fun withFixture(test: (Fixture) -> Unit) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals(FIXTURE_PACKAGE, context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            @Suppress("DEPRECATION")
            assertNull(context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U $USER_PACKAGE")
            val userUids = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:kr\\.co\\.hanium\\.dreamup\\.walksafe\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            }
            assertEquals(1, userUids.size)
            assertNotEquals(userUids.single(), Process.myUid())
            var f: Fixture? = null
            var first: Throwable? = null
            try {
                val fixture = onMain(instrumentation) { Fixture(instrumentation) }
                f = fixture
                onMain(instrumentation) { fixture.prepare() }
                test(fixture)
            } catch (failure: Throwable) { first = failure }
            finally { f?.let { fixture -> try { onMain(instrumentation) { fixture.close() }; instrumentation.waitForIdleSync() }
                catch (failure: Throwable) { if (first == null) first = failure else first!!.addSuppressed(failure) } } }
            first?.let { throw it }
        }
        fun awaitOnMain(instrumentation: Instrumentation, label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 6_000L
            while (!onMain(instrumentation, predicate) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5L)
            assertTrue(label, onMain(instrumentation, predicate))
        }
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val result = AtomicReference<T>(); val failure = AtomicReference<Throwable>()
            instrumentation.runOnMainSync { try { result.set(action()) } catch (caught: Throwable) { failure.set(caught) } }
            failure.get()?.let { throw it }; return result.get()
        }
        fun field(main: MainActivity, name: String) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)
        fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
        val CAMERA_PROFILE = WalkSafeEnvironmentProfiles.testCandidate.cameraFrameQuality
        val ENVIRONMENT_PROFILE = WalkSafeEnvironmentProfiles.testCandidate.officialEnvironment
        fun cameraObservation(epoch: WalkRuntimeEpoch, at: Long, available: Boolean) = CameraFrameQualityObservation(
            epoch = epoch, observedAtElapsedRealtimeMs = at, frameAvailable = available,
            normalizedBrightness = CAMERA_PROFILE.minimumNormalizedBrightness,
            occludedFraction = CAMERA_PROFILE.maximumOccludedFraction,
            angularShakeDegreesPerSecond = CAMERA_PROFILE.maximumAngularShakeDegreesPerSecond,
            mountPitchDegrees = (CAMERA_PROFILE.minimumMountPitchDegrees + CAMERA_PROFILE.maximumMountPitchDegrees) / 2.0,
        )
        fun waitAfter(at: Long) {
            check(Looper.myLooper() != Looper.getMainLooper())
            while (SystemClock.elapsedRealtime() <= at) Thread.sleep(1L)
        }
        fun completedPriorityEducation() = PriorityUserOnboardingPolicy().apply {
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
        fun completedOnboarding(actorId: String): FirstRunOnboardingSnapshot {
            fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(actorId), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            assertTrue(restored.fullyRestored)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot, restored.snapshot.epoch, restored.snapshot.revision)
            assertTrue(jit.accepted)
            return FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch, jit.current.revision).current.also { assertTrue(it.mayEnterWalk) }
        }
        fun completedDeviceCheck(actorId: String, generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), actorId, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, actorId, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { assertEquals(PostLoginDeviceCheckState.FULL, it.state) }
        }
    }
}
