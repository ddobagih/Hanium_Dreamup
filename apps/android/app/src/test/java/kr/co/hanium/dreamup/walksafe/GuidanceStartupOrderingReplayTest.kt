package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityTier
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Production HOME/start/readiness/route/output controllers with controlled platform inputs. */
class GuidanceStartupOrderingReplayTest {
    @After fun resetCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun finalCapabilityCallbackDuringPostedStartContinuesThroughRouteStorageAndFirstSpeech() {
        GuidanceFullJourneyReplayHarness(caseId = "startup_final_capability_callback").use { f ->
            f.gps(); f.heading(); f.advance(250); f.gps(); f.heading()
            f.selectDestination(); f.button()
            f.runtime.start(f.promptId()); f.advance(1_000); f.runtime.done(f.promptId())
            f.confirmOfficialConditions(); f.confirmPhoneMounting(); f.observeCameraFrame(); f.loadDetector()
            f.gps(); f.heading()
            f.advance(2_999)
            assertNull(f.confirm.confirmedDecision())
            // Deliver the ready-window callback before the queued UI confirmation, then the final
            // capability result. Neither input forces READY, ACTIVE or a successful route result.
            f.now += 1
            f.native.continueIfReady()
            assertEquals("POST_START_CONFIRMATION", f.stage)
            assertTrue(f.ui.isNotEmpty())
            f.metricDistanceAvailable = true
            f.refresh()
            assertNull(f.confirm.confirmedDecision())
            f.drainUi()
            assertEquals(WalkSafeStartupCapabilityTier.FULL, f.confirm.confirmedDecision()?.tier)
            installAndCompleteFirstCue(f)
        }
    }

    @Test fun initiallyMissingGpsAndCameraSamplesStartAutomaticallyAfterFreshInputsArrive() {
        GuidanceFullJourneyReplayHarness(caseId = "startup_delayed_normal_samples").use { f ->
            f.selectDestination(); f.button()
            val request = f.workflow.nativePhoneMountingCheckRequest
            f.runtime.start(f.promptId()); f.advance(1_000); f.runtime.done(f.promptId())
            f.advance(3_000)
            assertFalse(f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
            assertNull(f.confirm.confirmedDecision())
            assertTrue(f.providerRequests.isEmpty())
            f.confirmOfficialConditions(); f.confirmPhoneMounting(); f.observeCameraFrame(); f.loadDetector()
            f.gps(); f.heading(); f.advance(250); f.observeCameraFrame(); f.gps(); f.heading(); f.drainUi()
            assertSame("A delayed first measurement must preserve the original start request", request,
                f.workflow.nativePhoneMountingCheckRequest)
            assertTrue(f.confirm.isStartupCapabilityConfirmed())
            assertEquals(1, f.prompts.size)
            installAndCompleteFirstCue(f)
        }
    }

    @Test fun capabilityChangeWhileGatewayRespondsRechecksWithoutLosingTheOriginalDestination() {
        GuidanceFullJourneyReplayHarness(caseId = "startup_capability_changed_during_gateway").use { f ->
            f.gps(); f.heading(); f.advance(250); f.gps(); f.heading()
            f.selectDestination(); f.button()
            f.runtime.start(f.promptId()); f.advance(1_000); f.runtime.done(f.promptId())
            f.confirmOfficialConditions(); f.confirmPhoneMounting(); f.observeCameraFrame(); f.loadDetector()
            f.gps(); f.heading(); f.advance(3_000); f.drainUi()
            val request = f.workflow.nativePhoneMountingCheckRequest
            val epoch = f.walk.epoch
            val pending = f.gatewayController.startConfirmationToken
            assertNotNull(pending)
            f.metricDistanceAvailable = true
            f.refresh()
            assertEquals(pending, f.gatewayController.startConfirmationToken)
            f.prepareArchive(); f.gateway.runBackground(); f.drainUi()
            assertEquals("The old capability plan must not start a walk", WalkSessionState.READY, f.walk.state)
            assertEquals(epoch, f.walk.epoch)
            assertEquals(0, f.activations)
            assertTrue(f.confirm.retryRequired())
            assertNotNull(f.prepTimer)
            assertSame(request, f.workflow.nativePhoneMountingCheckRequest)
            // A rejected start releases its server lease before the same intent is retried.
            f.runGatewayRequests()
            f.finishPendingStatusSpeech()
            f.advance(1_900); f.observeCameraFrame(); f.gps(); f.heading()
            f.advance(100); f.drainUi()
            assertEquals(2, f.eventCount("PREFLIGHT_CAMERA_EVIDENCE_RESET"))
            assertTrue(f.eventCount("PREFLIGHT_CAMERA_RELEASED") > 0)
            assertNull("A new check cannot confirm from its discarded camera frame", f.confirm.confirmedDecision())
            assertTrue("A new camera observation is required before another server start", f.gateway.background.isEmpty())
            f.advance(1); f.observeCameraFrame(); f.gps(); f.heading(); f.drainUi()
            assertEquals(WalkSafeStartupCapabilityTier.FULL, f.confirm.confirmedDecision()?.tier)
            assertNotEquals(pending, f.gatewayController.startConfirmationToken)
            assertSame(request, f.workflow.nativePhoneMountingCheckRequest)
            installAndCompleteFirstCue(f)
        }
    }

    @Test fun delayedCameraReleaseDuringCapabilityRecheckKeepsTheDestinationAndStartsOnce() {
        GuidanceFullJourneyReplayHarness(caseId = "startup_capability_retry_delayed_camera_release").use { f ->
            f.gps(); f.heading(); f.advance(250); f.gps(); f.heading()
            f.selectDestination(); f.button()
            f.runtime.start(f.promptId()); f.advance(1_000); f.runtime.done(f.promptId())
            f.confirmOfficialConditions(); f.confirmPhoneMounting(); f.observeCameraFrame(); f.loadDetector()
            f.gps(); f.heading(); f.advance(3_000); f.drainUi()
            val request = f.workflow.nativePhoneMountingCheckRequest
            val epoch = f.walk.epoch
            f.metricDistanceAvailable = true
            f.refresh()
            f.prepareArchive(); f.gateway.runBackground(); f.drainUi()
            assertTrue(f.confirm.retryRequired())
            f.runGatewayRequests(); f.finishPendingStatusSpeech()
            f.deferPreflightCameraRelease = true
            f.advance(1_900); f.observeCameraFrame(); f.gps(); f.heading()
            f.advance(100); f.drainUi()
            assertEquals(1, f.pendingPreflightCameraReleaseCount)
            assertEquals("A new attempt must wait for the camera resource's actual close", 1,
                f.eventCount("PREFLIGHT_CAMERA_EVIDENCE_RESET"))
            assertEquals(epoch, f.walk.epoch)
            assertSame(request, f.workflow.nativePhoneMountingCheckRequest)
            assertEquals(0, f.activations)

            f.advance(100)
            f.deferPreflightCameraRelease = false
            f.completePreflightCameraRelease()
            assertEquals(0, f.pendingPreflightCameraReleaseCount)
            assertEquals(2, f.eventCount("PREFLIGHT_CAMERA_EVIDENCE_RESET"))
            assertFalse(f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
            f.advance(1); f.observeCameraFrame(); f.gps(); f.heading(); f.drainUi()
            assertSame(request, f.workflow.nativePhoneMountingCheckRequest)
            assertEquals(WalkSafeStartupCapabilityTier.FULL, f.confirm.confirmedDecision()?.tier)
            installAndCompleteFirstCue(f)
        }
    }

    private fun installAndCompleteFirstCue(f: GuidanceFullJourneyReplayHarness) {
        assertEquals(1, f.gateway.background.size)
        assertTrue(f.providerRequests.isEmpty())
        f.prepareArchive(); f.gateway.runBackground(); f.drainUi(); f.acknowledgeWalkOwnership()
        assertEquals(WalkSessionState.ACTIVE, f.walk.state)
        assertEquals(1, f.activations)
        assertEquals(1, f.navigationStarts)
        assertEquals(listOf(f.destination), f.destinationHandOff)
        assertEquals(1, f.providerRequests.size)
        f.runProvider()
        assertFalse("An HTTP response alone cannot bypass the route-storage acknowledgement", f.graph.run.navigator.hasRoute())
        f.runIo(); f.drainUi()
        assertTrue(f.graph.run.navigator.hasRoute())
        val cue = GuidanceFullJourneyReplayTest().firstCue(f)
        f.runtime.start(cue.id); f.advance(0); f.drainUi(); f.assertNativeRouteStartAccepted(cue)
        f.runtime.done(cue.id); f.advance(0); f.drainUi(); f.assertNativeRouteCompletionAcknowledged(cue)
        assertTrue(cue.started && cue.done && !cue.cancelled)
        f.assertSameHomeOwner()
    }
}
