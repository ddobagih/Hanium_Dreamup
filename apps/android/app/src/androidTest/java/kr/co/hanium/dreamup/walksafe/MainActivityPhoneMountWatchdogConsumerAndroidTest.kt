package kr.co.hanium.dreamup.walksafe

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
import kr.co.hanium.dreamup.walksafe.session.mounting.PhoneMountingSessionController
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeFalse
import org.junit.runner.RunWith

/**
 * SOURCE-only exact1: public camera/mount policies -> actual Main suitable consumer -> its original
 * captured watchdog Runnable -> actual expiry consumer on MainLooper -> same-walk mount-generation
 * retirement/new positive -> old Runnable rejection. No Boolean permission write or copied guard.
 * The TextView stays unattached and super.postDelayed is retained; the captured Runnable is consumed
 * manually. Natural OS dispatch, whole input interruption, physical output and whole S14-5 are not
 * proven. Camera-only expiry retains the warm Actor; fixture-owned close retires that controlled
 * Actor/command READY continuation before installing the fresh B fixture.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityPhoneMountWatchdogConsumerAndroidTest {
    @Test(timeout = 30_000L)
    fun actualMountWatchdogRevokesExpiredEvidenceAndCannotChangeTheFreshMountGeneration() = withFixture { f ->
        awaitOnMain(f.instrumentation, "Original controlled Actor onInit reached READY") {
            f.actor.readySpeechEngineGenerationOrNull() != null
        }
        val a = f.preparePositiveMount()
        onMain(f.instrumentation) {
            assertFalse(f.mountStatus.isAttachedToWindow)
            assertTrue(f.collectionCurrent())
            assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
            assertTrue(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
            assertTrue(a.post.accepted)
            assertTrue(a.post.delayMs > 0L)
            // A camera observation and confirmation are current before the no-observation interval.
            assertEquals(EnvironmentEvidenceStatus.PASS,
                CameraFrameQualityPolicy.assess(a.epoch, SystemClock.elapsedRealtime(), a.camera,
                    CAMERA_PROFILE).status)
            assertEquals(PhoneMountingStatus.SUITABLE, a.assessment.status)
            assertEquals(a.watchdogGeneration, field(f.main, "phoneMountingWatchdogGeneration"))
            assertTrue(a.post.dueAtElapsedMs > a.camera.observedAtElapsedRealtimeMs)
        }
        waitAfter(a.post.dueAtElapsedMs) // Original post delay, real Android clock; no clock retag.
        onMain(f.instrumentation) {
            assertSame(a.assessment.nextState, field(f.main, "phoneMountingRuntimeState"))
            assertEquals(a.watchdogGeneration, field(f.main, "phoneMountingWatchdogGeneration"))
            assertTrue(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN,
                CameraFrameQualityPolicy.assess(a.epoch, SystemClock.elapsedRealtime(), a.camera,
                    CAMERA_PROFILE).status)
            // Remove only the fixture's unattached pending post, then execute that exact callback.
            val oldDetectorGeneration = field(f.main, "detectorGeneration") as Int
            val originalReadyGeneration = requireNotNull(f.actor.readySpeechEngineGenerationOrNull())
            val currentAssessment = (MainActivityControllerTestFixture.controller(f.main, "phoneMountingSession") as
                PhoneMountingSessionController<*, *>).currentPhoneMountingAssessment(
                    epoch = a.epoch, phase = PhoneMountingAssessmentPhase.ACTIVE, previousState = a.assessment.nextState)
            assertEquals(PhoneMountingReason.CAMERA_EVIDENCE_STALE, currentAssessment.reason)
            assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, currentAssessment.status)
            assertFalse(currentAssessment.nextState.requiresExplicitCorrection)
            f.mountStatus.removeCallbacks(a.post.task)
            var expiryFailure: Throwable? = null
            val expired = try {
                a.post.task.run()
                assertFalse(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
                assertEquals(oldDetectorGeneration + 1, field(f.main, "detectorGeneration"))
                val expired = field(f.main, "phoneMountingRuntimeState") as PhoneMountingRuntimeState
                assertNotNull(expired.correctionRequiredSinceElapsedRealtimeMs)
                assertEquals(a.epoch, expired.epoch)
                assertSame(a.confirmation, field(f.main, "phoneMountingUserConfirmation"))
                assertNotEquals(a.watchdogGeneration, field(f.main, "phoneMountingWatchdogGeneration"))
                assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
                assertTrue(f.collectionCurrent())
                assertFalse(expired.requiresExplicitCorrection)
                // Camera-only expiry fences detection output and keeps the ready GPS speech actor.
                val retained = field(f.main, "feedbackActuator") as AndroidFeedbackActuator
                assertSame(f.actor, retained)
                assertEquals(originalReadyGeneration, retained.readySpeechEngineGenerationOrNull())
                expired
            } catch (failure: Throwable) {
                expiryFailure = failure
                throw failure
            } finally {
                // Also retire before this UI turn ends if the original callback or an assertion fails.
                try {
                    f.retireCurrentCommandAndActor()
                } catch (cleanupFailure: Throwable) {
                    val first = expiryFailure
                    if (first == null) throw cleanupFailure
                    if (first !== cleanupFailure) first.addSuppressed(cleanupFailure)
                }
            }
            val terminalGeneration = field(f.main, "phoneMountingWatchdogGeneration")
            a.post.task.run()
            assertSame(expired, field(f.main, "phoneMountingRuntimeState"))
            assertEquals(terminalGeneration, field(f.main, "phoneMountingWatchdogGeneration"))
            assertFalse(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
            f.invoke("invalidatePhoneMountingEvidence", Void.TYPE, arrayOf(String::class.java),
                "watchdog-a-retired")
            assertNull(field(f.main, "phoneMountingRuntimeState"))
            assertNull(field(f.main, "phoneMountingUserConfirmation"))
        }
        onMain(f.instrumentation) {
            assertNull(field(f.main, "feedbackActuator"))
            // Fresh B uses the existing controlled constructor/onInit port, so its actual confirm
            // does not start another native Actor after A's same-turn owner retirement.
            f.installControlledActor()
            f.runtime.ready()
        }
        awaitOnMain(f.instrumentation, "Fresh controlled B Actor onInit reached READY") {
            f.actor.readySpeechEngineGenerationOrNull() != null
        }
        val b = f.preparePositiveMount()
        onMain(f.instrumentation) {
            assertSame(f.actor, field(f.main, "feedbackActuator"))
            assertNotNull(f.actor.readySpeechEngineGenerationOrNull())
            // Positive B precedes the negative old-A assertion; there is no always-false fixture.
            assertEquals(a.epoch, b.epoch) // Same walk, fresh mounting/confirmation generation.
            assertEquals(PhoneMountingStatus.SUITABLE, b.assessment.status)
            assertTrue(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
            assertEquals(EnvironmentEvidenceStatus.PASS,
                CameraFrameQualityPolicy.assess(b.epoch, SystemClock.elapsedRealtime(), b.camera,
                    CAMERA_PROFILE).status)
            assertNotSame(a.confirmation, b.confirmation)
            assertTrue(b.confirmation.confirmedAtElapsedRealtimeMs > a.confirmation.confirmedAtElapsedRealtimeMs)
            val currentState = field(f.main, "phoneMountingRuntimeState")
            val generation = field(f.main, "phoneMountingWatchdogGeneration")
            val observationGeneration = field(f.main, "phoneMountingObservationGeneration")
            val detectorGeneration = field(f.main, "detectorGeneration")
            val owner = f.coordinator.currentUserMotionOwner()
            val postCount = f.mountStatus.posts.size
            a.post.task.run()
            assertSame(currentState, field(f.main, "phoneMountingRuntimeState"))
            assertSame(b.confirmation, field(f.main, "phoneMountingUserConfirmation"))
            assertEquals(generation, field(f.main, "phoneMountingWatchdogGeneration"))
            assertEquals(observationGeneration, field(f.main, "phoneMountingObservationGeneration"))
            assertEquals(detectorGeneration, field(f.main, "detectorGeneration"))
            assertEquals(postCount, f.mountStatus.posts.size)
            assertEquals(owner, f.coordinator.currentUserMotionOwner())
            assertTrue(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
            assertTrue(f.collectionCurrent())
            assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        }
    }

    @Test(timeout = 30_000L)
    fun actualFreshMountAngleFaultRequiresExplicitCorrectionAndRetiresTheOldActor() = withFixture { f ->
        awaitOnMain(f.instrumentation, "Original controlled Actor onInit reached READY") {
            f.actor.readySpeechEngineGenerationOrNull() != null
        }
        val a = f.preparePositiveMount()
        waitAfter(a.camera.observedAtElapsedRealtimeMs)
        onMain(f.instrumentation) {
            val at = SystemClock.elapsedRealtime()
            val observation = cameraObservation(a.epoch, at, available = true).copy(
                mountPitchDegrees = CAMERA_PROFILE.maximumMountPitchDegrees + 1.0)
            val camera = CameraFrameQualityPolicy.assess(a.epoch, at, observation, CAMERA_PROFILE)
            assertEquals(EnvironmentEvidenceStatus.FAIL, camera.status)
            assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE, camera.reason)
            assertTrue(at > a.camera.observedAtElapsedRealtimeMs)
            assertEquals(a.epoch, camera.epoch)
            assertTrue(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
            assertSame(f.actor, field(f.main, "feedbackActuator"))
            assertNotNull(f.actor.readySpeechEngineGenerationOrNull())
            val detectorGeneration = field(f.main, "detectorGeneration") as Int
            val feedbackGeneration = field(f.main, "feedbackLifecycleGeneration") as Int
            val owner = f.coordinator.currentUserMotionOwner()
            var correctionFailure: Throwable? = null
            try {
                val assessment = f.invoke("applyCurrentPhoneMountingRuntimeAssessment", PhoneMountingAssessment::class.java,
                    arrayOf(java.lang.Boolean.TYPE, CameraFrameQualityAssessment::class.java,
                        java.lang.Long::class.java, java.lang.Long::class.java, java.lang.Boolean.TYPE),
                    false, camera, null, null, false) as PhoneMountingAssessment
                assertEquals(PhoneMountingAssessmentPhase.ACTIVE, assessment.phase)
                assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, assessment.status)
                assertEquals(PhoneMountingReason.CAMERA_QUALITY_FAILED, assessment.reason)
                assertTrue(assessment.nextState.requiresExplicitCorrection)
                assertNotNull(assessment.nextState.correctionRequiredSinceElapsedRealtimeMs)
                assertEquals(a.epoch, assessment.nextState.epoch)
                assertSame(assessment.nextState, field(f.main, "phoneMountingRuntimeState"))
                assertSame(a.confirmation, field(f.main, "phoneMountingUserConfirmation"))
                assertFalse(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
                assertEquals(detectorGeneration + 1, field(f.main, "detectorGeneration"))
                assertEquals(feedbackGeneration + 1, field(f.main, "feedbackLifecycleGeneration"))
                assertEquals("tts=closed", f.actor.statusText())
                assertNull(f.actor.readySpeechEngineGenerationOrNull())
                val replacement = field(f.main, "feedbackActuator") as AndroidFeedbackActuator
                assertNotSame(f.actor, replacement)
                assertNull(replacement.readySpeechEngineGenerationOrNull())
                val watchdogGeneration = field(f.main, "phoneMountingWatchdogGeneration")
                assertNotEquals(a.watchdogGeneration, watchdogGeneration)
                // That captured old watchdog cannot revoke or replace the explicit correction owner.
                f.mountStatus.removeCallbacks(a.post.task)
                a.post.task.run()
                assertSame(assessment.nextState, field(f.main, "phoneMountingRuntimeState"))
                assertEquals(watchdogGeneration, field(f.main, "phoneMountingWatchdogGeneration"))
                assertEquals(detectorGeneration + 1, field(f.main, "detectorGeneration"))
                assertEquals(feedbackGeneration + 1, field(f.main, "feedbackLifecycleGeneration"))
                assertSame(replacement, field(f.main, "feedbackActuator"))
                assertFalse(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
                assertEquals(owner, f.coordinator.currentUserMotionOwner())
                assertTrue(f.collectionCurrent())
                assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
                f.runtime.ready() // The original captured onInit callback arrives after Actor.close.
            } catch (failure: Throwable) {
                correctionFailure = failure
                throw failure
            } finally {
                // Close the cold native replacement before this UI turn can dispatch onInit/output.
                try { f.retireCurrentCommandAndActor() }
                catch (cleanupFailure: Throwable) {
                    val first = correctionFailure
                    if (first == null) throw cleanupFailure
                    if (first !== cleanupFailure) first.addSuppressed(cleanupFailure)
                }
            }
        }
        f.instrumentation.waitForIdleSync()
        onMain(f.instrumentation) {
            assertEquals("tts=closed", f.actor.statusText())
            assertNull(f.actor.readySpeechEngineGenerationOrNull())
            assertNull(field(f.main, "feedbackActuator"))
            assertTrue((field(f.main, "phoneMountingRuntimeState") as PhoneMountingRuntimeState).requiresExplicitCorrection)
            assertFalse(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
            assertTrue(f.collectionCurrent())
            assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        }
    }

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
        init {
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main mounting watchdog probe",
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
                "walksafe_field_session=synthetic_mount_watchdog_probe", System.currentTimeMillis() + 120_000L)
            assertEquals(GatewaySessionScope.GENERAL, session.sessionScope)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            set(main, "firstRunOnboardingSnapshot", onboarding)
            set(main, "postLoginDeviceCheckSnapshot", completedDeviceCheck(ACTOR, requireNotNull(gatewayGeneration)))
            set(main, "reporterUserId", ACTOR)
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
            for (name in listOf("statusText", "detailText")) set(main, name, TextView(context))
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
