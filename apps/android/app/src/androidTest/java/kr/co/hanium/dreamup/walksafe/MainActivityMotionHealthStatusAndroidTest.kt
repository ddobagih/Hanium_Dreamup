package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationCallback
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.navigation.AndroidStepTracker
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepSensorRegistration
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Main status getter/OS permission/current binding. Sensor transport and login/device/readiness
 * are controlled typed inputs. Internal producer sample entry is not physical SensorEvent delivery.
 * No onCreate, UI drawing, Actor/TTS, Fused registration or new permission grant is performed. */
@RunWith(AndroidJUnit4::class)
class MainActivityMotionHealthStatusAndroidTest {
    @Test fun actualMainGetterSeparatesRegistrationOriginalReceiptsConflictsExpiryAndRetiredOwners() = withFixture { f ->
        onMain(f.instrumentation) {
            assertTrue(f.step.start())
            assertTrue(f.motion.start())
            val registrationOnly = f.message()
            assertTrue(registrationOnly.contains("등록됐지만 실제 측정은 아직 확인되지 않았습니다."))
            assertFalse(registrationOnly.contains("실제 측정 이력이 있습니다."))
            val measurement = SystemClock.elapsedRealtimeNanos()
            assertTrue(f.step.onStepCounterTotal(100f, measurement))
            assertTrue(f.motion.onSensorSample(Sensor.TYPE_ACCELEROMETER, floatArrayOf(0f, 0f, 9.8f), measurement))
            val stepHealth = f.step.operationalHealth
            val motionHealth = f.motion.operationalHealth
            val stepOriginal = requireNotNull(stepHealth.source(TrackedSensorSource.STEP_COUNTER)?.lastAcceptedSample)
            val accelerationOriginal = requireNotNull(motionHealth.source(TrackedSensorSource.ACCELEROMETER)?.lastAcceptedSample)
            assertNull(stepOriginal.receivedAtElapsedRealtimeNanos)
            assertNull(accelerationOriginal.receivedAtElapsedRealtimeNanos)
            val originalReceiptUnknown = f.message()
            assertTrue(originalReceiptUnknown.contains("실제 측정 이력이 있습니다."))
            assertTrue(originalReceiptUnknown.contains("원 수신 시각은 확인되지 않았습니다."))
            assertSame(stepHealth, f.step.operationalHealth)
            assertSame(motionHealth, f.motion.operationalHealth)
            assertEquals(measurement, stepOriginal.measurementElapsedRealtimeNanos)
            assertEquals(measurement, accelerationOriginal.measurementElapsedRealtimeNanos)
        }
        val lastMeasurement = onMain(f.instrumentation) {
            f.step.operationalHealth.source(TrackedSensorSource.STEP_COUNTER)!!.lastAcceptedSample!!.measurementElapsedRealtimeNanos
        }
        while (SystemClock.elapsedRealtimeNanos() <= lastMeasurement) Thread.sleep(1L)
        val expiresAt = onMain(f.instrumentation) {
            // Controlled original Android fix metadata, not a fabricated fix id or a step clock relabeled as GPS.
            val fix = Location("gps").apply {
                latitude = 37.0; longitude = 127.0; accuracy = 2f
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            val owner = requireNotNull(f.coordinator.currentUserMotionOwner())
            val fixMs = fix.elapsedRealtimeNanos / 1_000_000L
            f.coordinator.observeUserSpeed(WalkingSpeedObservation(0.0, 0.1, fixMs),
                UserMotionLocationEvidence(fixMs, fix.accuracy.toDouble(), fix.isMock,
                    latitude = fix.latitude, longitude = fix.longitude,
                    measurementElapsedRealtimeNanos = fix.elapsedRealtimeNanos), SystemClock.elapsedRealtime(),
                expectedMotionOwner = owner)
            val measurement = SystemClock.elapsedRealtimeNanos()
            assertTrue(f.step.onStepDetected(measurement, SystemClock.elapsedRealtimeNanos()))
            val event = f.steps.single()
            val receiptMs = requireNotNull(event.receivedAtElapsedRealtimeNanos) / 1_000_000L
            f.coordinator.observeStep(event.deltaSteps, PedestrianHeadingInput(event.timestampMs, null, null, null, false),
                PdrStepQuality.HIGH, receivedAtElapsedRealtimeMs = receiptMs, expectedMotionOwner = owner,
                stepEventSource = event.source, stepEventConfidence = event.confidence, originalStepEvent = event)
            val state = f.coordinator.peekUserMotion(SystemClock.elapsedRealtime())
            assertEquals(UserMotionStateReason.STEP_WITH_STATIONARY_GNSS, state.support.conflicts.single().reason)
            val current = f.message()
            assertTrue(current.contains(CONFLICT))
            UserMotionStateReason.entries.forEach { assertFalse(it.name, current.contains(it.name)) }
            assertEquals(state, f.coordinator.peekUserMotion(state.evaluatedAtElapsedRealtimeMs))
            requireNotNull(state.expiresAtElapsedRealtimeMs)
        }
        // Wait only for the producer's original deadline on the same real Android clock.
        while (SystemClock.elapsedRealtime() <= expiresAt) Thread.sleep(1L)
        onMain(f.instrumentation) {
            val current = f.message()
            assertFalse(current.contains(CONFLICT))
            assertTrue(current.contains("이전 움직임 근거가 만료"))
            f.stepRegistration.unregisterFails = true
            assertTrue(runCatching { f.step.stop() }.isFailure)
            assertTrue(f.step.operationalHealth.cleanupPending)
            val cleanup = f.message()
            assertTrue(cleanup.contains("이전 등록 자원의 정리가 끝나지 않았습니다."))
            if (f.instrumentation.targetContext.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                assertTrue(cleanup.contains("현재 활동 인식 권한이 없습니다."))
            }
            f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
            set(f.main, "isActivityForeground", f.lifecycle.snapshot().isForeground)
            val retired = f.message()
            assertTrue(retired.contains("현재 보행 범위의 움직임 근거를 다시 확인"))
            assertTrue(retired.contains("정리가 끝나지 않았습니다."))
            assertFalse(retired.contains(CONFLICT))
            assertFalse(retired.contains("실제 측정 이력이 있습니다."))
            f.stepRegistration.unregisterFails = false
            f.step.stop()
            val stopped = f.step.operationalHealth
            // Controlled legacy scalar input, not a queued SDK SensorEvent callback. Retired health stays unchanged.
            f.step.onStepCounterTotal(102f, SystemClock.elapsedRealtimeNanos(), SystemClock.elapsedRealtimeNanos())
            assertSame(stopped, f.step.operationalHealth)
        }
    }

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val coordinator: PositioningCoordinator
        val stepRegistration = Registration()
        val steps = mutableListOf<StepEvent>()
        val step = AndroidStepTracker(stepRegistration, onStepCountChanged = {}, onStepEvent = steps::add)
        val motion = AndroidPedestrianMotionTracker(Registration(), onDecision = {})
        var gatewayGeneration: Long? = null
        init {
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main motion health probe", null, null, null) as MainActivity
            coordinator = field(main, "positioningCoordinator") as PositioningCoordinator
        }
        fun prepare() {
            val context = instrumentation.targetContext
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertEquals("ENV_FINE", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            assertTrue("ENV_LOCATION_ENABLED", requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled)
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val restored = restoredOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_motion_health_probe", System.currentTimeMillis() + 120_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, restored))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            set(main, "firstRunOnboardingSnapshot", restored)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val epoch = lifecycle.snapshot().epoch
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val ready = WalkSessionReadinessCollector(epoch, plan).apply {
                plan.requiredRequirements.forEach { record(WalkSessionReadinessObservation(epoch, it,
                    WalkSessionReadinessStatus.READY, "controlled typed health status owner")) }
            }.build(SystemClock.elapsedRealtime())
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(ready))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            set(main, "walkSessionLifecycle", lifecycle)
            set(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            val owner = requireNotNull(invoke("currentLocationCollectionOwnerOrNull", ownerClass))
            val generation = field(main, "locationCallbackGeneration") as Int
            val started = SystemClock.elapsedRealtime()
            val binding = bindingClass.getDeclaredConstructor(PositioningCoordinator::class.java, java.lang.Long.TYPE,
                ownerClass, Integer.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
                .newInstance(coordinator, coordinator.currentInputGeneration(), owner, generation, started)
            set(main, "locationCollectionOwner", owner)
            set(main, "positioningCollectionBinding", binding)
            set(main, "locationCallback", object : LocationCallback() {}) // No native registration.
            assertEquals(true, invoke("bindPositioningUserMotionEpoch", java.lang.Boolean.TYPE,
                arrayOf(bindingClass, java.lang.Long.TYPE), binding, started))
            assertEquals(true, invoke("isPositioningCollectionCurrent", java.lang.Boolean.TYPE, arrayOf(bindingClass), binding))
            // Typed owner of the controlled sensor transport, not a success/currentness Boolean.
            set(main, "stepTracker", step)
            set(main, "stepTrackingEpoch", lifecycle.snapshot().epoch)
            set(main, "pedestrianMotionTracker", motion)
        }
        fun message() = invoke("currentMotionEvidenceStatusMessage", String::class.java) as String
        fun invoke(name: String, returns: Class<*>, types: Array<Class<*>> = emptyArray(), vararg values: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true; check(returnType == returns) }
            return try { method.invoke(main, *values) } catch (failed: InvocationTargetException) { throw failed.targetException }
        }
        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (failed: Throwable) {
                if (first == null) first = failed else first!!.addSuppressed(failed) } }
            cleanup { stepRegistration.unregisterFails = false; step.stop() }
            cleanup { motion.close() }
            cleanup { set(main, "locationCallback", null); set(main, "positioningCollectionBinding", null); set(main, "locationCollectionOwner", null) }
            cleanup { set(main, "pedestrianMotionTracker", null); set(main, "stepTrackingEpoch", null) }
            cleanup { gatewayGeneration?.let { GatewaySessionProcessCoordinator.clear(expectedGeneration = it) } }
            first?.let { throw it }
        }
    }
    private class Registration : StepSensorRegistration, MotionSensorRegistration {
        var unregisterFails = false
        override fun hasSensor(type: Int) = type in setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR,
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)
        override fun register(listener: SensorEventListener, type: Int) = true
        override fun unregister(listener: SensorEventListener?) { if (unregisterFails) throw IllegalStateException("controlled platform release") }
    }
    private companion object {
        const val ACTOR = "22222222-2222-4222-8222-222222222222"
        const val CONFLICT = "GPS 정지 근거와 실제 걸음 감지가 함께 들어와 근거가 서로 맞지 않습니다."
        val ownerClass by lazy { Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.LocationCollectionOwner") }
        val bindingClass by lazy { Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding") }
        fun withFixture(test: (Fixture) -> Unit) {
            val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U kr.co.hanium.dreamup.walksafe")
            val userUids = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:kr\\.co\\.hanium\\.dreamup\\.walksafe\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            }
            assertEquals(1, userUids.size); assertNotEquals(userUids.single(), Process.myUid())
            var fixture: Fixture? = null; var first: Throwable? = null
            try { val current = onMain(instrumentation) { Fixture(instrumentation) }; fixture = current
                onMain(instrumentation) { current.prepare() }; test(current) }
            catch (failed: Throwable) { first = failed }
            finally { fixture?.let { current -> try { onMain(instrumentation) { current.close() } }
                catch (failed: Throwable) { if (first == null) first = failed else first!!.addSuppressed(failed) } } }
            first?.let { throw it }
        }
        fun restoredOnboarding(): FirstRunOnboardingSnapshot {
            fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            assertTrue(restored.fullyRestored)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot, restored.snapshot.epoch, restored.snapshot.revision)
            assertTrue(jit.accepted)
            return FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch, jit.current.revision).current.also { assertTrue(it.mayEnterWalk) }
        }
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val result = AtomicReference<T>(); val failure = AtomicReference<Throwable>()
            instrumentation.runOnMainSync { try { result.set(action()) } catch (caught: Throwable) { failure.set(caught) } }
            failure.get()?.let { throw it }; return result.get()
        }
        fun field(main: MainActivity, name: String) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)
        fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
    }
}
