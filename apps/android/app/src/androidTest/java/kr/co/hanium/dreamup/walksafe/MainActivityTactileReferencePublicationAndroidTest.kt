package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.navigation.TactileSnapshotFrameResult
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.AndroidDetectionSnapshotFrameIdentity
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android Context/MainLooper and Main consumer/private publication/default emitter bodies.
 * Typed onboarding/device/Gateway/lifecycle and captured depth/camera inputs are controlled;
 * native AR, detector loading, speech and full Activity startup are deliberately unprepared.
 * This fixture never replaces the feedback emitter, creates an Actor, or injects guard results.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTactileReferencePublicationAndroidTest {
    @Test(timeout = 30_000L)
    fun actualPublisherStoresOriginalGuidanceWithPreparedDomainOwner() = withFixture { f ->
        val frame = f.stableFrame()
        val notice = requireNotNull(frame.guidance.dotReferenceNotice)
        val routeBefore = onMain(f.instrumentation) { f.routeState() }
        onMain(f.instrumentation) {
            val receipt = f.consumeAndPublish(frame)
            assertTrue(f.main.tactileResultIsCurrent(receipt))
            val published = f.publicationState()
            assertEquals("localRoute=${frame.guidance.decision.mode.wireValue}:${frame.guidance.decision.reason} " +
                frame.guidance.observationDiagnosticsText(), published.routeText)
            assertSame("Private policy retains the original notice", notice, published.notice)
            assertSame("Publication carries the exact domain owner", f.owner, published.owner)
            val evidence = requireNotNull(frame.matchedEvidence)
            assertEquals(evidence.tactileContext.expectedRouteId, receipt.source.routeId)
            assertEquals(evidence.tactileContext.routeProjection?.routeRevision, receipt.source.routeProgress?.routeVersion)
            assertEquals(evidence.frameId, notice.captureFrameId)
            assertEquals(evidence.tactileContext.nowElapsedRealtimeMs, notice.capturedAtElapsedRealtimeMs)
            val output = frame.guidance.outputs.single { it.trackId == notice.trackId }
            assertTrue(notice.validUntilElapsedRealtimeMs <= requireNotNull(output.sourceValidUntilElapsedRealtimeMs))
            assertTrue(notice.isValidAt(SystemClock.elapsedRealtime()))
            assertEquals(routeBefore, f.routeState())
            assertEquals(3, f.depthCalls)
        }
    }

    @Test(timeout = 30_000L)
    fun actualPublisherRejectsRetiredOwnerWithoutChangingPublishedState() = withFixture { f ->
        val frame = f.stableFrame()
        onMain(f.instrumentation) {
            val receipt = f.consumeAndPublish(frame)
            val before = f.publicationState()
            val oldProgress = f.navigator.currentTactileRouteProgress()
            f.navigator.setRoute(f.route)
            assertNotEquals(oldProgress.routeVersion, f.navigator.currentTactileRouteProgress().routeVersion)
            f.assertDomainAdmission()
            assertFalse("Actual route reinstall retires the consumed receipt", f.main.tactileResultIsCurrent(receipt))
            var clockReads = 0
            val clock = { clockReads++; SystemClock.elapsedRealtime() }
            assertFalse(f.publish(frame, receipt, clock))
            assertEquals("Retired receipt is rejected before observation clocks", 0, clockReads)
            f.assertPublicationUnchanged(before)

            val originalEpoch = f.owner.epoch
            f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
            assertEquals(WalkSessionState.PAUSED, f.lifecycle.snapshot().state)
            assertNotEquals(originalEpoch, f.lifecycle.snapshot().epoch)
            assertFalse(f.lifecycle.isRuntimeEpochCurrent(originalEpoch))
            assertFalse("Actual retired walk owner cannot publish even without a result receipt",
                f.main.publishTactileFrameReferenceObservation(frame, f.owner, clock))
            assertEquals(0, clockReads)
            f.assertPublicationUnchanged(before)
        }
    }

    @Test(timeout = 30_000L)
    fun actualDefaultFeedbackEmitterRecordsTheNativeBlockedGate() = withFixture { f ->
        val frame = f.stableFrame()
        onMain(f.instrumentation) {
            val receipt = f.consumeAndPublish(frame)
            val nativeAr = f.main.captureRuntimeArObservation()
            assertNull(nativeAr.session)
            assertNull(nativeAr.provider)
            assertFalse(f.main.currentRuntimeMetricOutputAllowsWork())
            val gate = f.nativeDeviceGate(frame)
            assertFalse(gate.arSessionRunning)
            assertFalse(gate.alertsAllowed)
            val before = field(f.main, "latestFeedbackDeliveryState")
            val dispatch = requireNotNull(f.main.dispatchConsumedTactileFrameFeedback(
                receipt, frame, false, gate.alertsAllowed, SystemClock.elapsedRealtime()))
            assertNull("Blocked dot reference frame must not manufacture risk speech", dispatch.action)
            val after = requireNotNull(field(f.main, "latestFeedbackDeliveryState"))
            assertNotSame("Default dispatch must enter the actual private UI emitter", before, after)
            assertEquals(dispatch.activeFeedbackDeliveryKeys, field(after, "activeFeedbackDeliveryKeys"))
            assertEquals(gate.alertsAllowed, field(after, "deviceGateAllowsAlerts"))
            assertEquals(dispatch.policyEvaluatedAtMs, field(after, "observedAtMs"))
            assertNull("No Actor initialization in this blocked emitter condition", field(f.main, "feedbackActuator"))
        }
    }

    private data class PublicationState(val routeText: String, val notice: Any?, val owner: Any?)

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val navigator: RouteNavigator
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val route = cornerRoute()
        private val pipeline = ObjectDepthRuntimePipeline()
        private var gatewayGeneration: Long? = null
        private var leaseEpoch: WalkRuntimeEpoch? = null
        private var sequence = 0L
        private var capturedSource: MainActivity.TactileResultSource? = null
        lateinit var owner: TactileDotReferenceDeliveryOwner
            private set
        var depthCalls = 0
            private set

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main reference publication probe",
                null, null, null) as MainActivity
            navigator = field(main, "routeNavigator") as RouteNavigator
        }

        fun prepare() {
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertNull("Do not replace another process session", GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val onboarding = completedOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_publication_probe", System.currentTimeMillis() + 60_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            val device = completedDeviceCheck(requireNotNull(gatewayGeneration))
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val ready = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, plan).apply {
                plan.requiredRequirements.forEach { record(WalkSessionReadinessObservation(
                    lifecycle.snapshot().epoch, it, WalkSessionReadinessStatus.READY,
                    "controlled readiness observation for publication only")) }
            }.build(SystemClock.elapsedRealtime())
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(ready))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            val authority = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
            val epoch = lifecycle.snapshot().epoch
            val start = requireNotNull(authority.beginStart(epoch))
            val now = SystemClock.elapsedRealtime()
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
                authority.completeStart(start, GatewayWalkStartResult.Granted(GatewayWalkLease(
                    "started", ACTOR, session.deviceId, epoch.walkSessionId, "synthetic-publication-lease", 1L,
                    System.currentTimeMillis(), System.currentTimeMillis() + 60_000L,
                    System.currentTimeMillis(), now + 60_000L))))
            leaseEpoch = epoch
            // Six purposes, matching existing typed fixture inputs; no AR/metric/readiness result setters.
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            setField(main, "postLoginDeviceCheckSnapshot", device)
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            navigator.setRoute(route)
            val update = navigator.update(location(now), now, false)
            setField(main, "isRouteActive", navigator.currentRouteId() != null)
            setField(main, "latestTmapOnRoute", !update.offRoute)
            owner = TactileDotReferenceDeliveryOwner(epoch, field(main, "feedbackLifecycleGeneration") as Int,
                field(main, "detectorGeneration") as Int, main.captureRuntimeArObservation().generation)
            assertDomainAdmission()
        }

        fun assertDomainAdmission() {
            for (name in listOf("firstRunOnboardingComplete", "postLoginDeviceCheckPassesFeatureGate", "isWalkSessionRuntimeActive"))
                assertEquals("Actual domain gate $name", true, invokeBoolean(main, name))
            assertEquals(true, invokeBoolean(main, "isCurrentFrameGeneration",
                arrayOf(Int::class.javaPrimitiveType!!, WalkRuntimeEpoch::class.java), arrayOf(owner.frameGeneration, owner.epoch)))
            val process = GatewaySessionProcessCoordinator.snapshot()
            assertEquals(process.restoredFirstRunSnapshot, field(main, "firstRunOnboardingSnapshot"))
            assertFalse(process.storageBlocked)
            assertFalse(process.deletionRecoveryOnly)
            assertNotNull((field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController).activeLeaseOrNull(owner.epoch))
        }

        fun stableFrame(): TactileSnapshotFrameResult {
            check(Looper.myLooper() != Looper.getMainLooper())
            var frame = onMain(instrumentation) { capturedFrame() }
            repeat(2) { Thread.sleep(370L); frame = onMain(instrumentation) { capturedFrame() } }
            assertNotNull("Existing 3-frame/700ms dot policy must issue the original notice", frame.guidance.dotReferenceNotice)
            return frame
        }

        private fun capturedFrame(): TactileSnapshotFrameResult = synchronized(requireNotNull(field(main, "frameStateLock"))) {
            val now = SystemClock.elapsedRealtime()
            val timestamp = now * 1_000_000L + sequence++
            // Freeze the incoming tuple with the actual evaluation, never at later forwarding.
            val source = MainActivity.TactileResultSource(owner, timestamp, now, timestamp,
                field(main, "visualGeometryVersion") as Long,
                navigator.currentRouteId(), navigator.currentTactileRouteProgress())
            val values = IntArray(10_000) { 2_000 }
            val depth = DepthFrameSnapshot(timestamp, DepthImage16(100, 100, values),
                ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
                DepthImage16(100, 100, values.copyOf()), timestamp, fullDepthTimestampNs = timestamp,
                cameraImageTimestampNs = timestamp, rawConfidenceTimestampNs = timestamp)
            val transform = requireNotNull(FrozenImageToDepthTransform.create(timestamp,
                listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f), Point2(1f, 1f)), Point2(.5f, .5f)))
            val location = location(now)
            val context = TactileProjectionContext(timestamp, navigator.currentRouteId(), navigator.currentProjection(location),
                location, null, null, TactileCameraFrame(timestamp,
                    RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                    TactileCameraIntrinsics(100, 100, 70f, 70f, 50f, 50f), now, true), 0L, now, now)
            val evidence = DetectionFrameEvidence(timestamp, timestamp / 1_000_000L, depth,
                FrozenImageToTextureCoordinateMapper(timestamp, transform, ImageSize(100, 100)),
                context, MotionContext(), navigator.currentRouteId() != null, field(main, "latestTmapOnRoute") as Boolean)
            val snapshot = DetectionSnapshot(
                listOf(DetectionCandidate("dot_tactile_paving", .9f, RectNorm(.2f, .4f, .2f, .4f))),
                AndroidDetectionSnapshotFrameIdentity(timestamp, timestamp / 1_000_000L),
                now, now, now, 100, 100, 0L, null, partial = false, frameEvidence = evidence, capturedAtElapsedRealtimeMs = now)
            val frame = main.prepareTactileFrameDispatch(snapshot, now, timestamp / 1_000_000L, 400L, 400L, 0L, now) { capture, detections ->
                depthCalls++
                pipeline.process(capture.depthSnapshot, capture.frameId, capture.frameTimestampMs, detections,
                    mapper = requireNotNull(capture.depthMapper), mapperFrameId = capture.depthMapper.frameId,
                    capturedAtElapsedRealtimeMs = now)
            }.frame
            capturedSource = source
            frame
        }

        fun consumeAndPublish(frame: TactileSnapshotFrameResult): MainActivity.TactileResultReceipt {
            val evidence = requireNotNull(frame.matchedEvidence)
            val source = requireNotNull(capturedSource)
            assertEquals(evidence.frameId, source.frameId)
            assertEquals(evidence.tactileContext.nowElapsedRealtimeMs, source.capturedAtElapsedRealtimeMs)
            assertEquals(evidence.depthSnapshot.cameraImageTimestampNs, source.cpuImageTimestampNs)
            var admitted: MainActivity.TactileResultReceipt? = null
            var admissionReads = 0
            assertTrue(main.consumeTactileFrameResult(source, frame, admissionIsCurrent = {
                admissionReads++
                assertTrue("Incoming owner admission runs inside actual Main receipt-commit lock",
                    Thread.holdsLock(requireNotNull(field(main, "frameStateLock"))))
                // Before commit there is no receipt. Use the real domain owner guard, not the
                // later receipt-currentness check or a fabricated true GL/AR readiness result.
                invokeBoolean(main, "isCurrentFrameGeneration",
                    arrayOf(Int::class.javaPrimitiveType!!, WalkRuntimeEpoch::class.java),
                    arrayOf(source.owner.frameGeneration, source.owner.epoch))
            }) { original, receipt ->
                admitted = receipt
                assertSame(frame.guidance.dotReferenceNotice, original.guidance.dotReferenceNotice)
                assertTrue("Actual shared consumer reaches private publication", publish(original, receipt))
            })
            assertEquals(1, admissionReads)
            return requireNotNull(admitted)
        }

        fun publish(frame: TactileSnapshotFrameResult, receipt: MainActivity.TactileResultReceipt,
            clock: () -> Long = SystemClock::elapsedRealtime): Boolean = invokeBoolean(main,
            "publishConsumedTactileFrameReferenceObservation", arrayOf(TactileSnapshotFrameResult::class.java,
                TactileDotReferenceDeliveryOwner::class.java, MainActivity.TactileResultReceipt::class.java,
                Function0::class.java, Function0::class.java),
            arrayOf(frame, owner, receipt, { main.tactileResultIsCurrent(receipt) }, clock))

        fun nativeDeviceGate(frame: TactileSnapshotFrameResult): DeviceGateState {
            val method = MainActivity::class.java.getDeclaredMethod("buildDeviceGateState", TrackedObjectDepth::class.java,
                String::class.java, java.lang.Long::class.java, TrackedObjectDepth::class.java,
                java.lang.Boolean.TYPE, Function0::class.java).apply { isAccessible = true }
            check(method.returnType == DeviceGateState::class.java)
            return invokeUnwrapped { method.invoke(main, frame.guidance.outputs.firstOrNull(), null,
                null, null, false, { main.captureRuntimeArObservation() }) } as DeviceGateState
        }

        fun publicationState(): PublicationState {
            val policy = requireNotNull(field(main, "tactileDotReferenceDeliveryPolicy"))
            return PublicationState(field(main, "latestTactileRouteState") as String,
                field(policy, "latestNotice"), field(policy, "observationOwner"))
        }
        fun assertPublicationUnchanged(before: PublicationState) {
            val after = publicationState()
            assertEquals(before.routeText, after.routeText)
            assertSame(before.notice, after.notice)
            assertSame(before.owner, after.owner)
        }
        fun routeState(): List<Any?> = listOf(navigator.currentRouteId(), navigator.currentTactileRouteProgress(),
            navigator.currentRouteMatch()?.geometricProgressM, navigator.pendingDecisionToken())
        fun close() {
            setField(main, "isActivityForeground", false)
            lifecycle.handle(WalkSessionEvent.EnteredBackground)
            try { leaseEpoch?.let { (field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController).beginEnd(it) } }
            finally { gatewayGeneration?.let { assertNotNull(GatewaySessionProcessCoordinator.clear(it)) } }
        }
    }

    private companion object {
        const val FIXTURE_PACKAGE = "kr.co.hanium.dreamup.walksafe.speechstorefixture"
        const val USER_PACKAGE = "kr.co.hanium.dreamup.walksafe"
        const val ACTOR = "11111111-1111-4111-8111-111111111111"

        @Suppress("DEPRECATION")
        fun withFixture(test: (Fixture) -> Unit) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals(FIXTURE_PACKAGE, context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            assertNull(context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U $USER_PACKAGE")
            val original = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:([A-Za-z0-9_.]+)\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.takeIf { value -> value.groupValues[1] == USER_PACKAGE }
                    ?.groupValues?.get(2)?.toIntOrNull() }
            }
            assertEquals(1, original.size)
            assertNotEquals(original.single(), Process.myUid())
            val f = onMain(instrumentation) { Fixture(instrumentation) }
            var failure: Throwable? = null
            try { onMain(instrumentation) { f.prepare() }; test(f) }
            catch (problem: Throwable) { failure = problem; throw problem }
            finally {
                try { onMain(instrumentation) { f.close() }; instrumentation.waitForIdleSync() }
                catch (cleanup: Throwable) { if (failure == null) throw cleanup else failure.addSuppressed(cleanup) }
            }
        }
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val result = AtomicReference<T>()
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync { try { result.set(action()) } catch (problem: Throwable) { failure.set(problem) } }
            failure.get()?.let { throw it }
            return result.get()
        }
        fun field(owner: Any, name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(owner)
        fun setField(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        fun invokeBoolean(main: MainActivity, name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Boolean {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
            check(method.returnType == java.lang.Boolean.TYPE)
            return invokeUnwrapped { method.invoke(main, *args) } as Boolean
        }
        fun invokeUnwrapped(action: () -> Any?): Any? = try { action() }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        fun point(eastM: Double, northM: Double = 0.0) = RoutePoint(northM / 111194.92664455874, eastM / 111194.92664455874)
        fun location(now: Long) = TrustedLocation(point(20.0).latitude, point(20.0).longitude, 3f, now)
        fun cornerRoute() = WalkingRoute("TACTILE_TURN_PROBE", WalkingRouteSummary(120, 120),
            listOf(point(0.0), point(20.0), point(20.0, -100.0)), listOf(
                WalkingRouteGuidePoint(0, point(20.0), null, 20, 100, turnType = 13),
                WalkingRouteGuidePoint(1, point(20.0, -75.0), null, 95, 25, turnType = 11)))
        fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
        fun completedOnboarding(): FirstRunOnboardingSnapshot {
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
        fun completedDeviceCheck(generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), ACTOR, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, ACTOR, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { check(it.state == PostLoginDeviceCheckState.FULL) }
        }
    }
}
