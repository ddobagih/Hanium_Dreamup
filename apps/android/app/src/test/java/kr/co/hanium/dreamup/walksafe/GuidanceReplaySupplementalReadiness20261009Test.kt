package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessRequirement
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.navigation.WalkSessionVoiceAction
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Additional behavioral scenarios using the other session's actual production-controller harness.
 * Independent platform observations stay controlled. No ACTIVE/confirmed/readiness flag is seeded.
 * These cases cover READY request retention and first native speech after provider/archive ACK.
 * They do not exercise Android HOME Views, RAW sensor conversion, walking, or acoustic output.
 */
@RunWith(Parameterized::class)
class GuidanceReplaySupplementalReadiness20261009Test(private val scenario: String) {
    @After fun resetCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun readinessRecoveryMustKeepTheHomeRequestAndReachFirstSpeech() {
        replay()
    }

    private fun replay() {
        val cold = scenario == "cold_engine_ready_after_home_request"
        GuidanceFullJourneyReplayHarness(initialEngineReady = !cold, caseId = "supplemental_$scenario").use { f ->
            val omitted = when (scenario) {
                "late_camera_after_calibration", "camera_arrives_one_ms_before_timer",
                "camera_arrives_one_ms_after_timer" -> setOf("camera")
                "late_official_after_calibration" -> setOf("official")
                "initial_mount_assumption_without_manual_action" -> setOf("mount")
                "detector_unavailable_blocks_start" -> setOf("detector")
                "camera_recovery_uses_initial_mount_assumption" -> setOf("camera", "mount")
                "late_camera_and_official" -> setOf("camera", "official")
                "camera_and_detector_unavailable_blocks_start" -> setOf("camera", "detector")
                else -> emptySet()
            }
            if ("detector" in omitted) f.unloadDetector()
            f.gps(); f.heading(); f.advance(250); f.gps(); f.heading()
            f.selectDestination()
            f.button()
            assertSame(f.destination, f.workflow.pendingUiDestination)
            assertTrue(f.providerRequests.isEmpty())
            if ("detector" in omitted) {
                // Actual Main blocks a pre-start model failure while keeping the HOME walk READY.
                // Prove the unavailable model independently of native calibration's waiting time.
                assertEquals(WalkSessionState.READY, f.walk.state)
                assertEquals(WalkSessionReadinessStatus.UNAVAILABLE,
                    f.readinessStatus(WalkSessionReadinessRequirement.MODEL))
                assertNull(f.confirm.confirmedDecision())
                assertTrue(f.gateway.background.isEmpty())
                assertTrue(f.routeSpeech().isEmpty())
                val prompt = f.promptId()
                f.runtime.start(prompt); f.runtime.done(prompt)
                supply(f, omitted)
                holdCurrentInputs(f, 3_100, "camera" !in omitted)
                assertEquals(WalkSessionState.READY, f.walk.state)
                assertEquals(WalkSessionReadinessStatus.UNAVAILABLE,
                    f.readinessStatus(WalkSessionReadinessRequirement.MODEL))
                assertNotNull("A failed model must retain the actual pre-start block", f.startBlockReason())
                if ("camera" !in omitted) assertTrue("The model failure needs its actual recovery notice",
                    requireNotNull(f.startBlockReason()).contains("모델"))
                assertNull(f.confirm.confirmedDecision())
                assertTrue(f.gateway.background.isEmpty())
                assertTrue("Elapsed time cannot admit a failed model", f.providerRequests.isEmpty())
                assertTrue(f.routeSpeech().isEmpty())
                return@use
            }
            assertEquals(WalkSessionState.READY, f.walk.state)
            if (cold) {
                holdCurrentInputs(f, 1_000, cameraAvailable = false)
                assertTrue("An offline engine wait cannot activate a walk", f.gateway.background.isEmpty())
                assertTrue(f.providerRequests.isEmpty())
                f.engineReady()
                f.drainUi()
            }
            assertEquals("One retained HOME request must create one calibration prompt", 1, f.prompts.size)
            val prompt = f.promptId()
            f.runtime.start(prompt)
            if (scenario == "start_callback_without_done") {
                supply(f, emptySet())
                holdCurrentInputs(f, 3_100, cameraAvailable = true)
                assertEquals("Waiting for DONE must retain the READY preparation", WalkSessionState.READY, f.walk.state)
                assertNotNull("A cancelled preparation cannot prove waiting for native DONE", f.calibration.snapshot)
                assertNull("START and wall-clock time cannot manufacture native DONE", f.calibration.snapshot?.voiceDoneAtMs)
                assertNull(f.confirm.confirmedDecision())
                assertTrue(f.gateway.background.isEmpty())
                assertTrue(f.providerRequests.isEmpty())
            }
            f.runtime.done(prompt)
            val done = f.now
            supply(f, omitted)
            val firstWait = if (scenario == "camera_arrives_one_ms_before_timer") 2_999L else 3_000L
            holdCurrentInputs(f, firstWait, "camera" !in omitted, done + 3_000)
            if (firstWait == 2_999L) {
                assertNull("Confirmation before actual DONE+3s is forbidden", f.confirm.confirmedDecision())
                assertTrue(f.gateway.background.isEmpty())
                f.observeCameraFrame()
                f.advance(1)
                f.drainUi()
            } else if ((omitted - "mount").isNotEmpty()) {
                // A consumed/cancelled calibration snapshot may be absent. Actual pending
                // producer evidence and the absence of Gateway/route activation are decisive.
                assertFalse("Elapsed calibration is not sufficient physical readiness", f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
                assertNull(f.confirm.confirmedDecision())
                assertTrue(f.gateway.background.isEmpty())
                assertTrue(f.providerRequests.isEmpty())
                assertSame("Preparation wait must retain the selected destination", f.destination, f.workflow.pendingUiDestination)
                f.assertSameHomeOwner()
                val extraWait = if (scenario == "camera_arrives_one_ms_after_timer") 1L else 1_500L
                holdCurrentInputs(f, extraWait, "camera" !in omitted)
                // Each observation is delivered independently through its existing harness seam.
                // No second start button or direct confirmation/activation call is permitted.
                if ("official" in omitted) f.confirmOfficialConditions()
                if ("camera" in omitted) f.observeCameraFrame()
                f.gps(); f.heading(); f.drainUi()
            }
            f.finishPendingSpeech()
            f.drainUi()
            val currentDone = f.calibration.snapshot?.voiceDoneAtMs ?: done
            val currentDeadline = currentDone + 3_000
            if (f.now < currentDeadline) holdCurrentInputs(f, currentDeadline - f.now, true, currentDeadline)
            assertTrue("Startup cannot precede actual calibration DONE+3s", f.now >= currentDeadline)
            f.calibration.snapshot?.readyAtMs?.let { assertEquals(currentDeadline, it) }
            assertTrue("Independent producer recovery must make current readiness ready", f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
            assertTrue("The retained request must automatically post its actual confirmation", f.confirm.isStartupCapabilityConfirmed())
            assertEquals("One actual Gateway START, without a second start-button press", 1, f.gateway.background.size)
            assertTrue(f.providerRequests.isEmpty())
            assertSame(f.destination, f.workflow.pendingUiDestination)
            f.assertSameHomeOwner()

            f.prepareArchive()
            f.gateway.runBackground(); f.drainUi()
            assertEquals(WalkSessionState.ACTIVE, f.walk.state)
            assertEquals(1, f.activations)
            assertEquals(1, f.navigationStarts)
            // Runtime activation queues the ACTIVE ownership write. Its actual IO ACK must
            // release the pending explicit start before a provider request may be submitted.
            f.runIo(); f.drainUi()
            assertEquals(1, f.providerRequests.size)
            assertSame(f.destination.point, f.providerRequests.single().destination)
            f.runProvider()
            assertFalse("Provider response alone must not install a route", f.graph.run.navigator.hasRoute())
            assertTrue("First route speech requires the archive ACK", f.routeSpeech().isEmpty())
            if (scenario == "provider_archive_ack_delayed") {
                holdCurrentInputs(f, 3_000, cameraAvailable = true)
                assertFalse("A timer cannot stand in for encrypted archive ACK", f.graph.run.navigator.hasRoute())
                assertTrue(f.routeSpeech().isEmpty())
                assertEquals(1, f.providerRequests.size)
                f.assertSameHomeOwner()
            }
            f.runIo(); f.drainUi()
            assertTrue(f.graph.run.navigator.hasRoute())
            assertNotNull(f.stored)
            f.finishPendingSpeech()
            f.advance(300); f.heading(); f.gps(); f.advance(300)
            assertTrue("Ready recovery and route ACK must reach actual first clock/metres output", f.routeSpeech().isNotEmpty())
            val first = f.routeSpeech().first()
            assertFalse(first.done)
            f.runtime.start(first.id); f.advance(0); f.drainUi()
            f.assertNativeRouteStartAccepted(first)
            f.advance(100); f.runtime.done(first.id); f.advance(0); f.drainUi()
            f.assertNativeRouteCompletionAcknowledged(first)
            assertTrue(first.started && first.done)
            f.assertSameHomeOwner()
            f.finishPendingSpeech()
            f.commands.executeWalkSessionVoiceAction(WalkSessionVoiceAction.REQUEST_END, f.walk.epoch)
            assertTrue(f.commands.endConfirmationPending)
            f.finishPendingSpeech()
            f.commands.executeWalkSessionVoiceAction(WalkSessionVoiceAction.CONFIRM_END, f.walk.epoch)
            assertEquals(WalkSessionState.ENDED, f.walk.state)
            assertFalse(f.graph.run.navigator.hasRoute())
        }
    }

    private fun supply(f: GuidanceFullJourneyReplayHarness, omitted: Set<String>) {
        if ("official" !in omitted) f.confirmOfficialConditions()
        if ("mount" !in omitted) f.confirmPhoneMounting()
        if ("camera" !in omitted) f.observeCameraFrame()
        if ("detector" !in omitted) f.loadDetector()
        f.gps(); f.heading()
    }

    private fun holdCurrentInputs(f: GuidanceFullJourneyReplayHarness, milliseconds: Long, cameraAvailable: Boolean,
                                 activationNotBefore: Long? = null) {
        var remaining = milliseconds
        while (remaining > 0) {
            val step = minOf(remaining, 300L)
            f.advance(step)
            f.gps(); f.heading()
            if (cameraAvailable) f.observeCameraFrame()
            f.drainUi()
            if (activationNotBefore != null && f.now < activationNotBefore) {
                assertNull("Live input refresh cannot bypass native DONE+3s", f.confirm.confirmedDecision())
                assertTrue(f.gateway.background.isEmpty())
                assertTrue(f.providerRequests.isEmpty())
            }
            remaining -= step
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            "late_camera_after_calibration", "late_official_after_calibration",
            "initial_mount_assumption_without_manual_action", "detector_unavailable_blocks_start",
            "camera_recovery_uses_initial_mount_assumption", "late_camera_and_official", "camera_and_detector_unavailable_blocks_start",
            "camera_arrives_one_ms_before_timer", "camera_arrives_one_ms_after_timer",
            "start_callback_without_done", "provider_archive_ack_delayed",
            "cold_engine_ready_after_home_request",
        ).map { arrayOf<Any>(it) }
    }
}
