package kr.co.hanium.dreamup.walksafe.navigation

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentEvidenceStore
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.mounting.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt in with allowCameraAngleContinuityTest=true. Device-side component tests use actual
 * SystemClock and Handler scheduling with the production raw policy, evidence gate and mounting
 * session. Camera/pose/luma and readiness are CONTROLLED INPUTS, never real sensor measurements.
 * Confirmation and profiles exist only in this fixture's memory. No MainActivity, real camera,
 * sensor-setting, saved profile, account, education, preference or permission changes occur.
 * This does not verify camera accuracy, spoken warnings, walking or field safety.
 */
@RunWith(AndroidJUnit4::class)
class CameraAngleContinuityDeviceTest {
    @Test(timeout = 30_000L)
    fun briefFortySevenDegreeExcursionKeepsRawFailAndAutomaticallyRecoversWithoutManualLatch() =
        withSession { fixture ->
            val originalConfirmation = fixture.onOwner { fixture.mount.confirmation }
            fixture.delay(100L)
            val pending = fixture.sample(47.0)
            assertPending(pending, fixture)
            assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_NEAR_BOUNDARY, fixture.onOwner { fixture.evidence.latestRawCameraAssessment?.reason })
            assertFalse(fixture.corrections.any { it.second })
            fixture.delay(250L)
            val recovered = fixture.sample(44.0)
            assertEquals(EnvironmentEvidenceStatus.PASS, recovered.raw.status)
            assertEquals(EnvironmentEvidenceStatus.PASS, recovered.selected.status)
            fixture.onOwner {
                assertTrue(fixture.mount.outputsAllowed)
                assertFalse(requireNotNull(fixture.mount.runtimeState).requiresExplicitCorrection)
                assertNull(fixture.gate.pendingFailureDeadlineElapsedRealtimeMs())
                assertSame("No new confirmation may be manufactured by recovery", originalConfirmation, fixture.mount.confirmation)
                assertNull(fixture.mount.explicitConfirmation)
            }
            report("scenario=brief_47_then_44 raw_failed_frame_retained=true pending_announce=false recovery=AUTOMATIC manual_confirmation_added=false actual_elapsed_ms=${recovered.atMs - pending.atMs}")
        }

    @Test(timeout = 30_000L)
    fun repeatedNearBoundaryFramesCannotExtendTheOriginalRealTimeDeadline() = withSession { fixture ->
        fixture.delay(100L)
        val first = fixture.sample(47.0)
        assertPending(first, fixture)
        val deadline = fixture.onOwner { requireNotNull(fixture.gate.pendingFailureDeadlineElapsedRealtimeMs()) }
        assertEquals("Deadline belongs to the first failure, not later frames", first.atMs + 4_500L, deadline)
        var repeats = 0
        while (SystemClock.elapsedRealtime() < deadline - 500L) {
            fixture.delay(350L)
            if (SystemClock.elapsedRealtime() >= deadline) break
            val repeated = fixture.sample(47.0)
            assertPending(repeated, fixture)
            assertEquals(deadline, fixture.onOwner { fixture.gate.pendingFailureDeadlineElapsedRealtimeMs() })
            repeats += 1
        }
        assertTrue("The real scheduler must deliver repeated failing frames before expiry", repeats > 0)
        fixture.delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 30L)
        fixture.onOwner {
            val expired = requireNotNull(fixture.gate.current(SystemClock.elapsedRealtime()))
            assertEquals(EnvironmentEvidenceStatus.FAIL, expired.status)
            assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, expired.detail)
            assertNull(fixture.gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
        val hard = fixture.sample(47.0)
        assertHard(hard, fixture)
        fixture.delay(120L)
        assertHard(fixture.sample(47.0), fixture)
        fixture.sample(44.0)
        fixture.onOwner {
            assertFalse("A sustained hard mounting fault requires its existing correction flow", fixture.mount.outputsAllowed)
            assertTrue(requireNotNull(fixture.mount.runtimeState).requiresExplicitCorrection)
            assertNull(fixture.gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
        report("scenario=continuous_47 original_deadline_ms=$deadline repeated_frames=$repeats first_failure_ms=${first.atMs} hard_failure_ms=${hard.atMs} extended=false rearmed_without_new_pass=false")
    }

    @Test(timeout = 30_000L)
    fun coldFortySixAndNinetyDegreeDeskPoseReceiveNoGrace() {
        listOf(46.0, 90.0, -90.0).forEach { pitch ->
            withSession(initialPitch = pitch) { fixture ->
                fixture.onOwner {
                    val selected = requireNotNull(fixture.gate.current(SystemClock.elapsedRealtime()))
                    assertEquals(EnvironmentEvidenceStatus.FAIL, selected.status)
                    assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, selected.detail)
                    assertNull(fixture.gate.pendingFailureDeadlineElapsedRealtimeMs())
                    assertFalse(fixture.mount.outputsAllowed)
                    assertTrue(requireNotNull(fixture.mount.runtimeState).requiresExplicitCorrection)
                }
                report("scenario=cold_pose controlled_pitch_deg=$pitch grace=false real_pose_measurement=false")
            }
        }
        withSession { fixture ->
            fixture.delay(30L)
            val hard = fixture.sample(90.0)
            assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE, hard.raw.reason)
            assertHard(hard, fixture)
            report("scenario=previous_pass_then_desk_90 grace=false raw_failed_frame_retained=true real_pose_measurement=false")
        }
    }

    @Test(timeout = 30_000L)
    fun originalTimestampFreshnessAndEpochOrProfileOwnershipCannotBeBorrowed() = withSession { fixture ->
        val sourceEpoch = fixture.epoch
        val shortProfile = cameraProfile.copy(profileId = "controlled-short-lived-camera", maximumEvidenceAgeMs = 100L)
        val staleGate = RuntimeMeasuredQualityGate(4_500L)
        val originalAt = SystemClock.elapsedRealtime()
        val originalPass = raw(sourceEpoch, originalAt, 44.0, shortProfile)
        staleGate.observe(originalPass.asMeasuredEnvironmentEvidence(), originalAt)
        val expiredPassGate = RuntimeMeasuredQualityGate(4_500L)
        expiredPassGate.observe(originalPass.asMeasuredEnvironmentEvidence(), originalAt)
        fixture.delay(180L)
        val now = SystemClock.elapsedRealtime()
        val delayed = CameraFrameQualityPolicy.assess(sourceEpoch, now,
            observation(sourceEpoch, originalAt, 47.0), shortProfile)
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, delayed.status)
        assertEquals(CameraFrameQualityReason.STALE_OR_INVALID_TIMESTAMP, delayed.reason)
        assertEquals(originalAt, delayed.observedAtElapsedRealtimeMs)
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, staleGate.observe(delayed.asMeasuredEnvironmentEvidence(), now).status)
        assertNull(staleGate.pendingFailureDeadlineElapsedRealtimeMs())

        val newFailure = raw(sourceEpoch, now, 46.0, shortProfile)
        val afterExpiry = expiredPassGate.observe(newFailure.asMeasuredEnvironmentEvidence(), now)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, afterExpiry.detail)
        assertNull(expiredPassGate.pendingFailureDeadlineElapsedRealtimeMs())

        listOf(
            sourceEpoch.copy(walkSessionId = "controlled-other-walk") to cameraProfile,
            sourceEpoch.copy(recoveryGeneration = sourceEpoch.recoveryGeneration + 1L) to cameraProfile,
            sourceEpoch to cameraProfile.copy(profileId = "controlled-other-camera"),
        ).forEach { (changedEpoch, changedProfile) ->
            val gate = RuntimeMeasuredQualityGate(4_500L)
            val passAt = SystemClock.elapsedRealtime()
            gate.observe(raw(sourceEpoch, passAt, 44.0).asMeasuredEnvironmentEvidence(), passAt)
            fixture.delay(20L)
            val failAt = SystemClock.elapsedRealtime()
            val changed = raw(changedEpoch, failAt, 47.0, changedProfile)
            val selected = gate.observe(changed.asMeasuredEnvironmentEvidence(), failAt)
            assertEquals(EnvironmentEvidenceStatus.FAIL, selected.status)
            assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, selected.detail)
            assertEquals(failAt, selected.observedAtElapsedRealtimeMs)
            assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
        report("scenario=source_fences delayed_original_timestamp_preserved=true expired_pass_grace=false changed_walk_or_generation_or_profile_grace=false timestamp_input=ACTUAL_CAPTURED_CLOCK")
    }

    private fun assertPending(sample: Sample, fixture: SessionFixture) {
        assertEquals(EnvironmentEvidenceStatus.FAIL, sample.raw.status)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_NEAR_BOUNDARY, sample.raw.reason)
        assertEquals(EnvironmentEvidenceStatus.FAIL, sample.selected.status)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_RECHECK_PENDING.name, sample.selected.detail)
        assertEquals(sample.raw.observedAtElapsedRealtimeMs, sample.selected.observedAtElapsedRealtimeMs)
        fixture.onOwner {
            assertEquals(sample.raw.asMeasuredEnvironmentEvidence(), fixture.gate.latestRawObservation())
            assertFalse(fixture.mount.outputsAllowed)
            assertFalse(requireNotNull(fixture.mount.runtimeState).requiresExplicitCorrection)
            assertEquals(PhoneMountingReason.CAMERA_ANGLE_RECHECK, sample.mounting?.reason)
        }
    }

    private fun assertHard(sample: Sample, fixture: SessionFixture) {
        assertEquals(EnvironmentEvidenceStatus.FAIL, sample.selected.status)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, sample.selected.detail)
        fixture.onOwner {
            assertNull(fixture.gate.pendingFailureDeadlineElapsedRealtimeMs())
            assertFalse(fixture.mount.outputsAllowed)
            assertTrue(requireNotNull(fixture.mount.runtimeState).requiresExplicitCorrection)
        }
    }

    private fun withSession(initialPitch: Double = 44.0, test: (SessionFixture) -> Unit) {
        assumeTrue("Requires explicit allowCameraAngleContinuityTest=true",
            InstrumentationRegistry.getArguments().getString("allowCameraAngleContinuityTest") == "true")
        val fixture = SessionFixture()
        report("scope=DEVICE_COMPONENT clock=REAL_ANDROID scheduler=REAL_HANDLER pose_luma_readiness=CONTROLLED actual_camera_measurement=false human_audibility=NOT_TESTED field_safety=NOT_TESTED")
        try {
            fixture.start(initialPitch)
            test(fixture)
            fixture.throwAsyncFailure()
        } finally {
            fixture.close()
        }
    }

    private data class Sample(val raw: CameraFrameQualityAssessment, val selected: MeasuredEnvironmentEvidence,
        val mounting: PhoneMountingAssessment?, val atMs: Long)

    /** Uses the existing session ports; only hardware input/output effects are controlled. */
    private class SessionFixture : MountingSensorLifecycle, MountingScheduler, MountingEffects,
        PositioningMountBridge<Unit, Unit> {
        private val thread = HandlerThread("CameraAngleContinuityOwner").apply { start() }
        private val handler = Handler(thread.looper)
        private val asyncFailure = AtomicReference<Throwable?>()
        val gate = RuntimeMeasuredQualityGate(4_500L)
        private val lock = Any()
        val evidence = OfficialEnvironmentEvidenceStore(lock, RuntimeMeasuredQualityGate(), gate)
        private val lifecycle = WalkSessionLifecycle({ "controlled-camera-device-session" }, System::currentTimeMillis, SystemClock::elapsedRealtime)
        val epoch: WalkRuntimeEpoch get() = lifecycle.snapshot().epoch
        val corrections = CopyOnWriteArrayList<Pair<PhoneMountingReason, Boolean>>()
        val mount = PhoneMountingSessionController(lock, evidence, MountingPreflightEvidence {},
            { lifecycle }, { mountProfile }, { true }, { false }, { null }, this, this, this, this,
            SystemClock::elapsedRealtime, 4_000L)

        fun start(initialPitch: Double) {
            onOwner {
                lifecycle.handle(WalkSessionEvent.EnteredForeground)
                val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
                val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, plan)
                plan.requiredRequirements.forEach {
                    collector.record(WalkSessionReadinessObservation(collector.epoch, it, WalkSessionReadinessStatus.READY))
                }
                lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(SystemClock.elapsedRealtime())))
                lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
                mount.assumeInitialConfirmation(epoch)
            }
            delay(20L)
            onOwner {
                val at = SystemClock.elapsedRealtime()
                val raw = raw(epoch, at, initialPitch)
                evidence.observeRuntimeRawCameraAssessment(raw, at)
                val selected = requireNotNull(gate.current(at))
                val admitted = selectedAssessment(raw, selected)
                synchronized(lock) {
                    mount.commitCameraAssessment(epoch, admitted, CameraPreflightStabilityDecision.ACCEPT, admitted.status != EnvironmentEvidenceStatus.PASS)
                    evidence.commitCameraEvidence(selected)
                }
                assertTrue(mount.activatePhoneMountingRuntime())
                corrections.clear()
                if (initialPitch == 44.0) assertTrue(mount.outputsAllowed)
            }
        }

        fun sample(pitch: Double): Sample = onOwner {
            val at = SystemClock.elapsedRealtime()
            val raw = raw(epoch, at, pitch)
            val generation = mount.observationGeneration()
            val (selected, admitted, sequence) = synchronized(lock) {
                evidence.observeRuntimeRawCameraAssessment(raw, at)
                val selected = requireNotNull(gate.current(at))
                val admitted = selectedAssessment(raw, selected)
                val fault = admitted.status != EnvironmentEvidenceStatus.PASS
                val sequence = mount.commitCameraAssessment(epoch, admitted, CameraPreflightStabilityDecision.ACCEPT, fault)
                evidence.commitCameraEvidence(selected)
                Triple(selected, admitted, sequence)
            }
            val fault = admitted.status != EnvironmentEvidenceStatus.PASS
            val mounting = mount.applyCurrentPhoneMountingRuntimeAssessment(false, admitted, sequence, generation, fault)
            if (fault && mounting != null) synchronized(lock) { mount.recordAppliedFaultIfCurrent(generation, sequence) }
            Sample(raw, selected, mounting, at)
        }

        fun delay(delayMs: Long) {
            val done = CountDownLatch(1)
            assertTrue(handler.postDelayed({ done.countDown() }, delayMs))
            assertTrue("Actual Handler delay did not finish", done.await(delayMs + 5_000L, TimeUnit.MILLISECONDS))
            throwAsyncFailure()
        }

        fun <T> onOwner(action: () -> T): T {
            throwAsyncFailure()
            val value = AtomicReference<T>()
            val failure = AtomicReference<Throwable?>()
            val done = CountDownLatch(1)
            assertTrue(handler.post {
                try { value.set(action()) } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
            })
            assertTrue("Camera session owner action timed out", done.await(5_000L, TimeUnit.MILLISECONDS))
            failure.get()?.let { throw it }
            return value.get()
        }

        fun throwAsyncFailure() { asyncFailure.get()?.let { throw it } }
        private fun post(action: () -> Unit, delayMs: Long): Boolean = handler.postDelayed({
            try { action() } catch (failure: Throwable) { asyncFailure.compareAndSet(null, failure) }
        }, delayMs)
        override fun postMounting(action: () -> Unit, delayMs: Long): Boolean = post(action, delayMs)
        override fun postStatus(delayMs: Long, action: () -> Unit): Boolean = post(action, delayMs)
        override fun start(): Boolean = true
        override fun stop() = Unit
        override fun captureTarget(): Unit? = null
        override fun captureLocked(target: Unit?, epoch: WalkRuntimeEpoch, observedAtMs: Long,
            phase: PhoneMountingAssessmentPhase, previousState: PhoneMountingRuntimeState?,
            cameraOverride: CameraFrameQualityAssessment?, retryRequested: Boolean, facts: MountingPositionFacts): Unit? = null
        override fun invalidatedLocked(target: Unit, generation: Long, observedAtMs: Long) = Unit
        override fun consume(receipt: Unit?, receivedAtMs: Long) = Unit
        override fun revokeReportOutputs() { assertTrue(Thread.holdsLock(lock)) }
        override fun updateMountingUi() = Unit
        override fun observeUnavailableCamera(epoch: WalkRuntimeEpoch, observedAtMs: Long) = Unit
        override fun sensorStartFailed() { throw AssertionError("Controlled source unexpectedly failed to start") }
        override fun cameraHandoffStarted() = Unit
        override fun reassessOfficialEnvironment() = Unit
        override fun cameraHandoffTimedOut() { throw AssertionError("Unexpected handoff timeout") }
        override fun correction(assessment: PhoneMountingAssessment, announce: Boolean) { corrections += assessment.reason to announce }
        override fun unusable(assessment: PhoneMountingAssessment, announce: Boolean) { throw AssertionError("Unexpected unusable state: ${assessment.reason}") }
        override fun invalidated(reason: String) = Unit

        fun close() {
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
            thread.join(2_000L)
            assertFalse("Owned camera test HandlerThread must terminate", thread.isAlive)
        }
    }

    private companion object {
        val cameraProfile = ApprovedCameraFrameQualityProfile("controlled-camera", .2, .1, 45.0, -45.0, 45.0, 5_000L)
        val mountProfile = ApprovedPhoneMountingProfile("controlled-mount", cameraProfile.profileId, 5_000L, 2, 60_000L)
        fun observation(epoch: WalkRuntimeEpoch, at: Long, pitch: Double) =
            CameraFrameQualityObservation(epoch, at, true, .6, 0.0, 0.0, pitch)
        fun raw(epoch: WalkRuntimeEpoch, at: Long, pitch: Double, profile: ApprovedCameraFrameQualityProfile = cameraProfile) =
            CameraFrameQualityPolicy.assess(epoch, at, observation(epoch, at, pitch), profile)
        fun selectedAssessment(raw: CameraFrameQualityAssessment, selected: MeasuredEnvironmentEvidence) =
            raw.copy(status = selected.status, reason = CameraFrameQualityReason.valueOf(selected.detail))
        fun report(message: String) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "CAMERA_ANGLE_CONTINUITY $message\n")
            })
        }
    }
}
