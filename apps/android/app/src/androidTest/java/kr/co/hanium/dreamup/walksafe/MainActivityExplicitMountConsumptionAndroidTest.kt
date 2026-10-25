package kr.co.hanium.dreamup.walksafe

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
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.session.LocationCollectionOwner
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningMountTarget
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningMountObservation
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exact Main/Context/MainLooper without onCreate. The OS permissions, location service and elapsed
 * clock remain real. Gateway/readiness/device and camera-quality observations are controlled typed
 * inputs to the existing policies; speech platform effects are doubles. No physical mounting,
 * camera/sensor measurement, UI click, Fused registration or full lifecycle startup is claimed.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityExplicitMountConsumptionAndroidTest {
    @Test fun actualStatusReportsTheSameCoordinatorsExplicitMount() = withFixture { f ->
        onMain(f.instrumentation) {
            val at = SystemClock.elapsedRealtime()
            val owner = requireNotNull(f.coordinator.currentUserMotionOwner())
            assertTrue(f.coordinator.observeUserMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT,
                at, at, f.coordinator.currentInputGeneration(), owner))
            val before = f.coordinator.peekUserMotion(at)
            // Both this existing private formatter and the Coordinator API exist on e6cb.
            val status = f.invoke("currentUserMotionStatusText", String::class.java) as String
            assertTrue("Main's actual status must include the same coordinator's mount", status.contains("mount=SUPPORTED_MOUNT"))
            assertEquals(before, f.coordinator.peekUserMotion(at))
        }
    }

    @Test fun actualExplicitConfirmationAndAssessmentReachTheCurrentCollectorAndOriginalStepConsumer() = withFixture { f ->
        for (method in listOf(PhoneMountingMethod.CHEST_FORWARD, PhoneMountingMethod.NECKLACE_FORWARD)) {
            f.confirmThroughActualEntry(method)
            val originalConfirmation = onMain(f.instrumentation) { f.confirmation() }
            f.applyFreshCameraThroughActualRuntimeEntry()
            onMain(f.instrumentation) {
                assertSame(originalConfirmation, f.confirmation())
                assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, f.mount())
                assertTrue((f.invoke("currentUserMotionStatusText", String::class.java) as String).contains("mount=SUPPORTED_MOUNT"))
                assertSame(f.confirmation(), field(f.main, "explicitPositioningMountConfirmation"))
                assertEquals(method, f.confirmation().method)
                assertEquals(PhoneMountingStatus.SUITABLE, f.lastAssessment!!.status)
                assertTrue(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
                assertEquals(true, f.invoke("isPositioningCollectionCurrent", java.lang.Boolean.TYPE,
                    arrayOf(bindingClass), f.binding))
                // NECKLACE support does not create a CHEST heading; no sensor tracker is fabricated.
                assertNull(f.invoke("chestMountedHeadingForStep", HeadingObservation::class.java,
                    arrayOf(java.lang.Long.TYPE, java.lang.Long.TYPE), SystemClock.elapsedRealtime(), 500L))
                f.invoke("invalidatePhoneMountingEvidence", Void.TYPE, arrayOf(String::class.java), "explicit-test-change")
                assertEquals(UserDeviceBodyRelationState.UNKNOWN, f.mount())
            }
        }
        for (method in listOf(PhoneMountingMethod.HANDHELD, PhoneMountingMethod.POCKET)) {
            f.confirmThroughActualEntry(method) // Existing typed entry negative, not an available UI button.
            onMain(f.instrumentation) {
                assertEquals(UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, f.mount())
                val seededAt = SystemClock.elapsedRealtime()
                if (f.coordinator.evaluate(seededAt).filtered == null) {
                    assertEquals(GnssObservationDisposition.INITIALIZED,
                        f.coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 1.5, seededAt)).gnssUpdate?.disposition)
                }
            }
            val mountAt = onMain(f.instrumentation) { SystemClock.elapsedRealtime() }
            waitAfter(mountAt)
            onMain(f.instrumentation) {
                val at = SystemClock.elapsedRealtime()
                val original = StepEvent(1, at, StepEventSource.STEP_DETECTOR, StepEventConfidence.HIGH)
                val before = requireNotNull(f.coordinator.evaluate(at).filtered)
                val result = f.step(original, gps = false)
                assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
                assertNull(result.selectedHeading)
                assertEquals(before.localEnu, requireNotNull(result.filtered).localEnu)
                val ref = requireNotNull(result.userMotion).evidenceRefs.last { it.stepEvidence?.originalStepEvent == original }
                assertEquals(original.timestampMs, ref.measuredAtElapsedRealtimeMs)
                assertEquals(original.timestampMs, ref.receivedAtElapsedRealtimeMs)
                assertEquals(original, ref.stepEvidence?.originalStepEvent)
            }
            val firstStepAt = onMain(f.instrumentation) { SystemClock.elapsedRealtime() }
            waitAfter(firstStepAt)
            onMain(f.instrumentation) {
                val original = StepEvent(1, SystemClock.elapsedRealtime(), StepEventSource.STEP_DETECTOR, StepEventConfidence.HIGH)
                val independent = f.step(original, gps = true)
                assertEquals(PedestrianMotionUpdateDisposition.APPLIED, independent.motionUpdate?.disposition)
                assertEquals(PedestrianHeadingSource.GPS_COURSE, independent.selectedHeading?.source)
                f.invoke("invalidatePhoneMountingEvidence", Void.TYPE, arrayOf(String::class.java), "typed-negative-cleared")
            }
        }
    }

    @Test fun actualInvalidationAndNewActorBindingRetireCapturedInputsWithoutRetagging() = withFixture { f ->
        f.confirmThroughActualEntry(PhoneMountingMethod.CHEST_FORWARD)
        f.applyFreshCameraThroughActualRuntimeEntry()
        onMain(f.instrumentation) {
            val original = f.captureMountInput()
            assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT,
                f.invoke("positioningMountRelation", UserDeviceBodyRelationState::class.java, arrayOf(observationClass), original))
            val observedAt = observationClass.getDeclaredField("observedAtElapsedRealtimeMs").apply { isAccessible = true }.getLong(original)
            val originalOwner = requireNotNull(f.coordinator.currentUserMotionOwner())
            val originalReceipt = f.confirmation()
            f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
            set(f.main, "isActivityForeground", f.lifecycle.snapshot().isForeground)
            f.invoke("invalidatePhoneMountingEvidence", Void.TYPE, arrayOf(String::class.java), "app-paused-test")
            assertEquals(UserDeviceBodyRelationState.UNKNOWN, f.mount())
            val estimator = PositioningCoordinator::class.java.getDeclaredField("userMotionEstimator").apply { isAccessible = true }.get(f.coordinator)
            val clearAt = estimator.javaClass.getDeclaredField("lastMountObservedAtMs").apply { isAccessible = true }.get(estimator) as Long
            assertTrue("Clear uses its actual Android clock without retagging the captured declaration", clearAt >= observedAt)
            println("MOUNT_REAL_CLOCK captured=$observedAt clear=$clearAt sameMs=${observedAt == clearAt}")
            assertNull(field(f.main, "explicitPositioningMountConfirmation"))
            assertFalse(f.consume(original))
            assertEquals(UserDeviceBodyRelationState.UNKNOWN, f.mount())
            assertEquals(originalOwner, f.coordinator.currentUserMotionOwner())
            assertEquals(originalReceipt.epoch, originalOwner.walkEpoch)
            assertEquals(true, f.invoke("bindPositioningUserMotionEpoch", java.lang.Boolean.TYPE,
                arrayOf(bindingClass, java.lang.Long.TYPE), f.binding, SystemClock.elapsedRealtime()))
            assertNotEquals(originalOwner, f.coordinator.currentUserMotionOwner())
            assertFalse(f.consume(original))
            assertEquals(UserDeviceBodyRelationState.UNKNOWN, f.mount())
        }
        // A new real process Gateway identity cannot authorize an old captured positive declaration.
        onMain(f.instrumentation) {
            f.lifecycle.handle(WalkSessionEvent.EnteredForeground)
            set(f.main, "isActivityForeground", f.lifecycle.snapshot().isForeground)
            f.publishGateway(OTHER_ACTOR)
            f.bindCurrentCollector()
            assertEquals(GatewaySessionProcessCoordinator.snapshot().generation,
                requireNotNull(f.coordinator.currentUserMotionOwner()).actorGeneration)
            assertEquals(UserDeviceBodyRelationState.UNKNOWN, f.mount())
            assertNull(field(f.main, "explicitPositioningMountConfirmation"))
            assertEquals(true, f.invoke("isPositioningCollectionCurrent", java.lang.Boolean.TYPE,
                arrayOf(bindingClass), f.binding))
        }
    }

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val coordinator: PositioningCoordinator
        val runtime = RecordingRuntime()
        val actor = AndroidFeedbackActuator(runtime = runtime)
        var gatewayGeneration: Long? = null
        lateinit var binding: Any
        var lastAssessment: PhoneMountingAssessment? = null
        init {
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main explicit mounting probe", null, null, null) as MainActivity
            coordinator = field(main, "positioningCoordinator") as PositioningCoordinator
        }
        fun prepare() {
            val context = instrumentation.targetContext
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                assertEquals("ENV_OS_PERMISSION $permission", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
            assertTrue("ENV_LOCATION_ENABLED", requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled)
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            publishGateway(ACTOR)
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
            for (name in listOf("phoneMountingStatusText", "statusText", "detailText")) set(main, name, TextView(context))
            MainActivityControllerTestFixture.replaceActuator(main, actor)
            runtime.ready()
            bindCurrentCollector()
        }
        fun publishGateway(actorId: String) {
            val onboarding = completedOnboarding(actorId)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", actorId,
                "walksafe_field_session=synthetic_explicit_mount_probe", System.currentTimeMillis() + 120_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            set(main, "firstRunOnboardingSnapshot", onboarding)
            set(main, "postLoginDeviceCheckSnapshot", completedDeviceCheck(actorId, requireNotNull(gatewayGeneration)))
            set(main, "reporterUserId", actorId)
        }
        fun bindCurrentCollector() {
            val owner = requireNotNull(invoke("currentLocationCollectionOwnerOrNull", ownerClass))
            val locationGeneration = field(main, "locationCallbackGeneration") as Int
            val startedAt = SystemClock.elapsedRealtime()
            binding = bindingClass.getDeclaredConstructor(PositioningCoordinator::class.java, java.lang.Long.TYPE,
                ownerClass, Integer.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
                .newInstance(coordinator, coordinator.currentInputGeneration(), owner, locationGeneration, startedAt)
            set(main, "locationCollectionOwner", owner)
            set(main, "positioningCollectionBinding", binding)
            set(main, "locationCallback", object : LocationCallback() {}) // Transport only, never registered with Fused.
            assertEquals(true, invoke("bindPositioningUserMotionEpoch", java.lang.Boolean.TYPE,
                arrayOf(bindingClass, java.lang.Long.TYPE), binding, startedAt))
            assertEquals(true, invoke("isPositioningCollectionCurrent", java.lang.Boolean.TYPE, arrayOf(bindingClass), binding))
        }
        fun confirmThroughActualEntry(method: PhoneMountingMethod) {
            val faultAt = onMain(instrumentation) {
                val at = SystemClock.elapsedRealtime()
                val epoch = lifecycle.snapshot().epoch
                val failed = camera(epoch, at, EnvironmentEvidenceStatus.FAIL)
                val fault = PhoneMountingPolicy.assess(PhoneMountingAssessmentPhase.ACTIVE, epoch, at,
                    null, failed, PhoneMountingPolicy.initialState(epoch, PROFILE))
                assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, fault.status)
                set(main, "phoneMountingRuntimeState", fault.nextState)
                set(main, "latestPhoneMountingCameraAssessment", failed)
                at
            }
            waitAfter(faultAt) // Real confirmation must follow the original fault; no timestamp rewriting.
            onMain(instrumentation) {
                invoke("confirmPhoneMounting", Void.TYPE, arrayOf(PhoneMountingMethod::class.java), method)
                assertEquals(method, confirmation().method)
                assertSame(confirmation(), field(main, "explicitPositioningMountConfirmation"))
                assertTrue(confirmation().confirmedAtElapsedRealtimeMs > faultAt)
            }
        }
        fun applyFreshCameraThroughActualRuntimeEntry() {
            val confirmedAt = onMain(instrumentation) { confirmation().confirmedAtElapsedRealtimeMs }
            waitAfter(confirmedAt)
            onMain(instrumentation) {
                val fresh = camera(lifecycle.snapshot().epoch, SystemClock.elapsedRealtime(), EnvironmentEvidenceStatus.PASS)
                // The producer's typed camera observation is input, never a mounting-ready Boolean.
                set(main, "latestPhoneMountingCameraAssessment", fresh)
                lastAssessment = invoke("applyCurrentPhoneMountingRuntimeAssessment", PhoneMountingAssessment::class.java,
                    arrayOf(java.lang.Boolean.TYPE, CameraFrameQualityAssessment::class.java, java.lang.Long::class.java,
                        java.lang.Long::class.java, java.lang.Boolean.TYPE), true, fresh, null, null, false) as PhoneMountingAssessment
                assertTrue(requireNotNull(lastAssessment).assessedAtElapsedRealtimeMs!! >= fresh.observedAtElapsedRealtimeMs!!)
            }
        }
        fun confirmation() = field(main, "phoneMountingUserConfirmation") as PhoneMountingUserConfirmation
        fun mount() = coordinator.peekUserMotion(SystemClock.elapsedRealtime()).mount
        fun step(original: StepEvent, gps: Boolean): PositioningSnapshot {
            val at = original.timestampMs
            return coordinator.observeStep(original.deltaSteps, PedestrianHeadingInput(at,
                WalkingSpeedObservation(if (gps) 1.0 else 0.4, 0.1, at),
                if (gps) HeadingObservation(0.0, 5.0, at) else null,
                HeadingObservation(90.0, 5.0, at), true), PdrStepQuality.HIGH,
                receivedAtElapsedRealtimeMs = at, expectedMotionOwner = coordinator.currentUserMotionOwner(),
                stepEventSource = original.source, stepEventConfidence = original.confidence, originalStepEvent = original)
        }
        fun captureMountInput(): Any {
            val target = requireNotNull(invoke("capturePositioningMountTarget", targetClass))
            return requireNotNull(invoke("capturePositioningMountObservation", observationClass,
                arrayOf(targetClass, WalkRuntimeEpoch::class.java, java.lang.Long.TYPE,
                    PhoneMountingAssessmentPhase::class.java, PhoneMountingRuntimeState::class.java,
                    CameraFrameQualityAssessment::class.java, java.lang.Boolean.TYPE),
                target, lifecycle.snapshot().epoch, SystemClock.elapsedRealtime(), PhoneMountingAssessmentPhase.PREFLIGHT,
                PhoneMountingPolicy.initialState(lifecycle.snapshot().epoch, PROFILE), null, false))
        }
        fun consume(observation: Any) = invoke("observePositioningMount", java.lang.Boolean.TYPE,
            arrayOf(observationClass, java.lang.Long.TYPE), observation, SystemClock.elapsedRealtime()) as Boolean
        fun invoke(name: String, returns: Class<*>, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true; check(returnType == returns) }
            return try { method.invoke(main, *args) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (failure: Throwable) {
                if (first == null) first = failure else first!!.addSuppressed(failure) } }
            cleanup { invoke("invalidatePhoneMountingEvidence", Void.TYPE, arrayOf(String::class.java), "fixture-ended") }
            cleanup { set(main, "locationCallback", null); set(main, "positioningCollectionBinding", null); set(main, "locationCollectionOwner", null) }
            cleanup { actor.close() }
            cleanup { runtime.close() }
            cleanup { gatewayGeneration?.let { GatewaySessionProcessCoordinator.clear(expectedGeneration = it) } }
            first?.let { throw it }
        }
    }

    private class RecordingRuntime : FeedbackSpeechRuntime {
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
        override fun speak(message: String, queueMode: Int, utteranceId: String) = TextToSpeech.SUCCESS
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
        const val OTHER_ACTOR = "33333333-3333-4333-8333-333333333333"
        val PROFILE = WalkSafeEnvironmentProfiles.testCandidate.phoneMounting
        val ownerClass by lazy { LocationCollectionOwner::class.java }
        val bindingClass by lazy { PositioningCollectionBinding::class.java }
        val targetClass by lazy { PositioningMountTarget::class.java }
        val observationClass by lazy { PositioningMountObservation::class.java }
        fun withFixture(test: (Fixture) -> Unit) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            // Before this fixture's resources, not before Application or SDK provider initialization.
            assertEquals(FIXTURE_PACKAGE, context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            @Suppress("DEPRECATION")
            val sharedUid = context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId
            assertNull(sharedUid)
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
            finally { f?.let { fixture -> try { onMain(instrumentation) { fixture.close() } } catch (failure: Throwable) {
                if (first == null) first = failure else first!!.addSuppressed(failure) } } }
            first?.let { throw it }
        }
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val result = AtomicReference<T>(); val failure = AtomicReference<Throwable>()
            instrumentation.runOnMainSync { try { result.set(action()) } catch (caught: Throwable) { failure.set(caught) } }
            failure.get()?.let { throw it }; return result.get()
        }
        fun waitAfter(at: Long) { check(Looper.myLooper() != Looper.getMainLooper()); while (SystemClock.elapsedRealtime() <= at) Thread.sleep(1L) }
        fun field(main: MainActivity, name: String) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)
        fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
        fun camera(epoch: WalkRuntimeEpoch, at: Long, status: EnvironmentEvidenceStatus) = CameraFrameQualityAssessment(
            epoch, at, PROFILE.cameraFrameQualityProfileId, PROFILE.maximumEvidenceAgeMs, status,
            if (status == EnvironmentEvidenceStatus.PASS) CameraFrameQualityReason.PASSED else CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE)
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
