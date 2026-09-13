package kr.co.hanium.dreamup.walksafe

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.BitSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.sin
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownDepthFrameResult
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import kr.co.hanium.dreamup.walksafe.navigation.AndroidTactileRouteGuidance
import kr.co.hanium.dreamup.walksafe.navigation.TactileFrameFeedbackActuator
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in physical Korean speech through the actual Main dispatcher, shared policy and actuator.
 * Masks, raw depth, pose, source clocks and walk epochs are synthetic; FastSAM is not invoked here.
 * The existing dispatcher override supplies a controlled device gate only inside this test. No
 * first-login, consent, account, walk state, permissions or production guards are edited or bypassed
 * in product code. Actual onDone proves engine completion, not audibility or real approach accuracy.
 */
@RunWith(AndroidJUnit4::class)
class UnknownObjectGuidanceDeviceTest {
    @Test(timeout = 180_000L)
    fun syntheticMaskDepthMotionReachesMainDispatchAndActualKoreanSpeech() {
        assumeTrue("Explicit physical speech opt-in required",
            InstrumentationRegistry.getArguments().getString("runUnknownObjectGuidanceDeviceTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val startedAtMs = SystemClock.elapsedRealtime()
        val report = JSONObject().put("scope", "synthetic_mask_raw_depth_pose_to_actual_main_dispatch_and_korean_tts")
            .put("modelInvoked", false).put("liveCameraOrDepthMeasured", false)
            .put("controlledDeviceGateAtTestOverride", true).put("productSessionGuardChanged", false)
            .put("humanAudibility", "NOT_TESTED").put("physicalApproachAccuracy", "NOT_TESTED")
        val scenarios = JSONArray()
        val speech = JSONArray()
        var activity: MainActivity? = null
        var actuator: AndroidFeedbackActuator? = null
        try {
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val main = checkNotNull(activity)
            val sharedPolicy = MainActivity::class.java.getDeclaredField("feedbackPolicy").apply { isAccessible = true }
                .get(main) as WalkSafeFeedbackPolicy
            val emptyNamedFrame = MainActivity.TactileSnapshotFrameResult(null, emptyList(), false,
                AndroidTactileRouteGuidance().apply(emptyList(), null, false, false))
            val fixtureClockMs = SystemClock.elapsedRealtime()
            val fixtures = Scenario.entries.associateWith { scenario -> sequence(scenario, fixtureClockMs).also { fixture ->
                val output = fixture.result.objects.single()
                scenarios.put(JSONObject().put("scenario", scenario.name)
                    .put("depthSource", output.source.name).put("riskDistanceM", output.riskDistanceM ?: JSONObject.NULL)
                    .put("objectMotion", output.objectMotion.name).put("movementDirection", output.motionEstimate.direction.name)
                    .put("trackCount", fixture.trackIds.distinct().size).put("inputFrames", fixture.trackIds.size))
            } }
            assertEquals(ObjectMotion.OBJECT_APPROACHING, fixtures.getValue(Scenario.OBJECT_APPROACH).output.objectMotion)
            assertEquals(ObjectMovementDirection.TOWARD_USER, fixtures.getValue(Scenario.OBJECT_APPROACH).output.motionEstimate.direction)
            assertEquals(ObjectMovementDirection.STATIONARY, fixtures.getValue(Scenario.STATIC).output.motionEstimate.direction)
            assertEquals(ObjectMotion.USER_APPROACHING_STATIONARY, fixtures.getValue(Scenario.USER_ADVANCE).output.objectMotion)
            assertEquals(ObjectMovementDirection.STATIONARY, fixtures.getValue(Scenario.USER_ADVANCE).output.motionEstimate.direction)
            listOf(Scenario.ROTATION, Scenario.REFERENCE_CHANGE).forEach { scenario ->
                val fixture = fixtures.getValue(scenario)
                assertEquals(ObjectMotion.UNKNOWN, fixture.output.objectMotion)
                assertEquals(ObjectMovementDirection.UNKNOWN, fixture.output.motionEstimate.direction)
                val message = admit(fixture, fixtureClockMs)?.outputs?.single()?.userFacing?.message.orEmpty()
                assertFalse("Uncertain motion must not claim object approach", message.contains("사용자 쪽으로"))
                assertFalse("Uncertain motion must not claim stationary attribution", message.contains("정지해"))
            }
            listOf(Scenario.OBJECT_APPROACH, Scenario.STATIC, Scenario.USER_ADVANCE).forEach { scenario ->
                assertEquals("A continuous synthetic mask must preserve identity", 1, fixtures.getValue(scenario).trackIds.distinct().size)
            }
            assertEquals(DepthSource.UNKNOWN, fixtures.getValue(Scenario.NO_DEPTH).output.source)
            assertNull(fixtures.getValue(Scenario.NO_DEPTH).output.riskDistanceM)
            assertNull(admit(fixtures.getValue(Scenario.NO_DEPTH), fixtureClockMs))
            val changedEpoch = WalkRuntimeEpoch("synthetic-guidance-other-walk", 2L)
            assertNull(admit(fixtures.getValue(Scenario.OBJECT_APPROACH), fixtureClockMs, changedEpoch))

            val silentDispatches = AtomicInteger()
            instrumentation.runOnMainSync {
                val nowMs = SystemClock.elapsedRealtime()
                val expired = sequence(Scenario.OBJECT_APPROACH, nowMs - 1_000L)
                val expiredBatch = checkNotNull(admit(expired, nowMs - 1_000L))
                val suppressedBatches = listOf(
                    "expired_source" to listOf(expiredBatch),
                    "epoch_mismatch" to listOfNotNull(admit(fixtures.getValue(Scenario.OBJECT_APPROACH), nowMs, changedEpoch)),
                    "unknown_depth" to listOfNotNull(admit(fixtures.getValue(Scenario.NO_DEPTH), nowMs)),
                )
                suppressedBatches.forEach { (label, batches) ->
                    val dispatch = main.dispatchTactileFrameFeedback(emptyNamedFrame, true, true, nowMs,
                        TactileFrameFeedbackActuator { selected ->
                            assertNull("$label must not dispatch speech", selected.action)
                            silentDispatches.incrementAndGet()
                        }, batches)
                    assertNull(dispatch.action)
                }
            }
            assertEquals(3, silentDispatches.get())
            report.put("mainSuppressedDispatchCases", silentDispatches.get())

            val initialized = CountDownLatch(1)
            val ready = AtomicBoolean(false)
            instrumentation.runOnMainSync {
                actuator = AndroidFeedbackActuator(instrumentation.targetContext,
                    speechAllowed = { true }, hapticAllowed = { false },
                    onOfflineKoreanSpeechReady = { ready.set(true); initialized.countDown() },
                    onOfflineKoreanSpeechUnavailable = { initialized.countDown() })
            }
            assertTrue("Korean TTS readiness callback timed out", initialized.await(25L, TimeUnit.SECONDS))
            assertTrue("Existing offline Korean TTS is unavailable", ready.get())
            val actualActuator = checkNotNull(actuator)
            val warmup = Terminal()
            instrumentation.runOnMainSync {
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actualActuator.speakHomeCommandInteraction(
                    "한국어 물체 안내 연결을 확인합니다.", onCompleted = warmup::completed, onFailed = warmup::failed))
            }
            warmup.awaitDone()

            listOf(Scenario.OBJECT_APPROACH, Scenario.USER_ADVANCE).forEachIndexed { index, scenario ->
                if (index > 0) SystemClock.sleep(2_700L) // Existing per-track cooldown after the actual onDone.
                val terminal = Terminal()
                val startObservedAtMs = AtomicLong(-1L)
                var deadlineMs = 0L
                var dispatchReturned = false
                val completedDelivery = AtomicBoolean(false)
                val record = JSONObject().put("scenario", scenario.name)
                speech.put(record)
                instrumentation.runOnMainSync {
                    val fixture = sequence(scenario, SystemClock.elapsedRealtime())
                    val nowMs = SystemClock.elapsedRealtime()
                    val batch = checkNotNull(admit(fixture, nowMs))
                    deadlineMs = batch.validUntilElapsedRealtimeMs
                    val phrase = checkNotNull(batch.outputs.single().userFacing.message)
                    if (scenario == Scenario.OBJECT_APPROACH) assertTrue(phrase.contains("사용자 쪽으로 다가오는"))
                    else assertTrue(phrase.contains("정지해 있는 것으로 보이며"))
                    val dispatch = main.dispatchTactileFrameFeedback(emptyNamedFrame, true, true, nowMs,
                        TactileFrameFeedbackActuator { selected ->
                            val action = checkNotNull(selected.action) { "Main did not select $scenario" }
                            assertTrue(action.validUntilMs <= deadlineMs)
                            assertTrue(sharedPolicy.claimFeedbackDelivery(action.trackId, selected.policyEvaluatedAtMs))
                            val emitted = actualActuator.emit(action,
                                onSpeechCompleted = {
                                    completedDelivery.set(sharedPolicy.confirmFeedbackDelivery(
                                        action.trackId, selected.policyEvaluatedAtMs, SystemClock.elapsedRealtime()))
                                    terminal.completed()
                                },
                                onSpeechFailed = {
                                    sharedPolicy.rejectUndeliveredFeedback(action.trackId, selected.policyEvaluatedAtMs)
                                    terminal.failed()
                                },
                                isStillValidAtStart = {
                                    val atMs = SystemClock.elapsedRealtime()
                                    if (dispatchReturned) startObservedAtMs.compareAndSet(-1L, atMs)
                                    batch.isFreshAt(atMs)
                                })
                            dispatchReturned = true
                            if (emitted.speech != NavigationSpeechDispatchResult.ACCEPTED)
                                sharedPolicy.rejectUndeliveredFeedback(action.trackId, selected.policyEvaluatedAtMs)
                            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, emitted.speech)
                            assertFalse(emitted.vibrationAccepted)
                            record.put("selectedMessage", action.message).put("selectedLevel", action.level.name)
                        }, listOf(batch))
                    assertNotNull(dispatch.action)
                }
                terminal.awaitDone()
                record.put("actualOnDoneCount", terminal.done.get()).put("failureCount", terminal.errors.get())
                    .put("startValidationElapsedMs", startObservedAtMs.get()).put("sourceDeadlineElapsedMs", deadlineMs)
                    .put("actualOnDoneElapsedMs", terminal.doneAtMs.get()).put("deliveryConfirmed", completedDelivery.get())
                assertTrue("Actual onStart validation must occur within the source deadline",
                    startObservedAtMs.get() >= 0L && startObservedAtMs.get() <= deadlineMs)
                assertTrue("A started sentence may finish after the 800 ms source deadline", terminal.doneAtMs.get() > deadlineMs)
                assertTrue("Actual completion must consume the claimed Main delivery", completedDelivery.get())
            }
            report.put("outcome", "PASS")
        } catch (failure: Throwable) {
            report.put("outcome", "FAIL").put("failure", failure.message ?: failure.javaClass.simpleName)
            throw failure
        } finally {
            instrumentation.runOnMainSync { actuator?.close(); activity?.finish() }
            report.put("scenarios", scenarios).put("speech", speech)
                .put("elapsedMs", SystemClock.elapsedRealtime() - startedAtMs)
            File(instrumentation.targetContext.filesDir, "unknown-object-guidance-device-report.json").writeText(report.toString(2))
        }
    }

    private enum class Scenario { OBJECT_APPROACH, STATIC, USER_ADVANCE, ROTATION, REFERENCE_CHANGE, NO_DEPTH }

    private data class Fixture(val token: FastSamFrameToken, val result: UnknownDepthFrameResult, val trackIds: List<String>) {
        val output get() = result.objects.single()
    }

    private fun sequence(scenario: Scenario, finalCapturedAtMs: Long): Fixture {
        var clock = finalCapturedAtMs - 2_000L
        val pipeline = UnknownObjectDepthPipeline({ clock }, UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS)
        val mask = syntheticMask()
        val ids = mutableListOf<String>()
        var latest: Fixture? = null
        repeat(11) { index ->
            clock = finalCapturedAtMs - 2_000L + index * 200L
            val cameraNs = (50_000L + index * 200L) * 1_000_000L
            // LIVE_CAMERA selects the production capture contract; these clock values are simulated.
            val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 1L, cameraNs, cameraNs,
                cameraNs - 2_000_000_000L, clock * 1_000_000L, "synthetic-guidance", WIDTH, HEIGHT)
            val millimeters = when (scenario) { Scenario.NO_DEPTH -> 0; Scenario.STATIC -> 1_200; else -> 2_200 - index * 100 }
            val yaw = if (scenario == Scenario.ROTATION && index == 10) Math.toRadians(12.0) else 0.0
            val pose = CameraPoseEvidence(if (scenario == Scenario.REFERENCE_CHANGE && index == 10) 2L else 1L,
                cameraNs / 1_000_000L, 0f, 0f, if (scenario == Scenario.USER_ADVANCE) -index * 0.1f else 0f,
                sin(yaw).toFloat(), 0f, -cos(yaw).toFloat(), CameraImageProjection(WIDTH, HEIGHT,
                    40f, 40f, 20f, 16f, cos(yaw).toFloat(), 0f, sin(yaw).toFloat(), 0f, 1f, 0f))
            val snapshot = DepthFrameSnapshot(cameraNs,
                DepthImage16(WIDTH, HEIGHT, IntArray(WIDTH * HEIGHT) { millimeters }),
                ConfidenceImage8(WIDTH, HEIGHT, ByteArray(WIDTH * HEIGHT) { 255.toByte() }), fullDepth = null,
                rawDepthTimestampNs = token.cpuImageTimestampNs, cameraImageTimestampNs = token.cpuImageTimestampNs,
                rawConfidenceTimestampNs = token.cpuImageTimestampNs, cameraPoseEvidence = pose)
            val capture = checkNotNull(FrozenUnknownDepthCapture.freeze(token, snapshot,
                doubleArrayOf(1.0 / WIDTH, 0.0, 0.0, 0.0, 1.0 / HEIGHT, 0.0, 0.0, 0.0, 1.0), token.frameId))
            val result = pipeline.process(capture, token, listOf(mask), clock)
            assertNull(result.rejectionReason)
            ids += result.objects.single().trackId
            latest = Fixture(token, result, ids.toList())
        }
        return checkNotNull(latest)
    }

    private fun admit(fixture: Fixture, nowMs: Long, currentEpoch: WalkRuntimeEpoch = EPOCH): UnknownObjectFeedbackBatch? =
        UnknownObjectFeedbackPolicy().admit(fixture.result.objects, fixture.token.frameId,
            fixture.token.cameraTimestampNs / 1_000_000L, fixture.token.capturedElapsedNs,
            fixture.token.capturedElapsedNs / 1_000_000L, EPOCH, currentEpoch, nowMs)

    private fun syntheticMask(): InstanceMask {
        val bits = BitSet(WIDTH * HEIGHT)
        for (y in 8 until 24) for (x in 12 until 28) bits.set(y * WIDTH + x)
        val constructor = InstanceMask::class.java.declaredConstructors.single().apply { isAccessible = true }
        return constructor.newInstance(WIDTH, HEIGHT, 0, 0, WIDTH, HEIGHT, bits.cardinality(), 17,
            0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), 0.99f, bits) as InstanceMask
    }

    private class Terminal {
        val received = CountDownLatch(1)
        val done = AtomicInteger()
        val errors = AtomicInteger()
        val doneAtMs = AtomicLong(-1L)
        fun completed() { doneAtMs.set(SystemClock.elapsedRealtime()); done.incrementAndGet(); received.countDown() }
        fun failed() { errors.incrementAndGet(); received.countDown() }
        fun awaitDone() {
            assertTrue("Actual Korean TTS terminal timed out", received.await(25L, TimeUnit.SECONDS))
            assertEquals("Expected one real onDone", 1, done.get())
            assertEquals("Speech failed", 0, errors.get())
        }
    }

    companion object {
        private const val WIDTH = 40
        private const val HEIGHT = 32
        private val EPOCH = WalkRuntimeEpoch("synthetic-unknown-guidance", 1L)
    }
}
