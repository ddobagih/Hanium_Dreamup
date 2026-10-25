package kr.co.hanium.dreamup.walksafe

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentPreflightController
import kr.co.hanium.dreamup.walksafe.navigation.start.GuidanceStartCalibration
import kr.co.hanium.dreamup.walksafe.navigation.start.NativePrewalkController
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkStartResult
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.runtime.GatewayWalkRequests
import kr.co.hanium.dreamup.walksafe.session.runtime.GatewayWalkScheduler
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Main cancel handler with the shared production HOME/start/readiness/Gateway/route owners.
 * Only platform inputs, HTTP replies and UI scheduling are controlled; no READY/confirmed/ACTIVE
 * state is seeded. This does not execute Android View clicks or prove physical sensor/audio output.
 */
class MainActivityNativePendingStartCancellationRegressionTest {
    @After fun resetCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun cancellingAReadyStartRejectsItsLateAckAndFreshReadinessDoesNotRestartIt() {
        Fixture("native_cancel_pending_gateway").use { fixture ->
            val f = fixture.replay
            fixture.beginStartAndHoldAck()
            val speechCount = f.runtime.spoken.size

            fixture.cancelThroughMain()
            assertEquals(NativeUiPage.HOME, f.workflow.nativeUiPage)
            assertNull(f.workflow.pendingUiDestination)
            assertNull(f.workflow.nativePrewalkStartEpoch)
            assertNull(f.workflow.nativePhoneMountingCheckRequest)
            assertNull(f.calibration.currentAttempt)

            // Cancellation is intent retirement, even while the same normal measurements remain
            // fresh. A continuous HOME GPS update must not turn the retired confirmation into a
            // replacement server start if cancellation only cleared the pending operation.
            f.gps(); f.heading(); f.refresh(); f.drainUi()
            assertTrue("The cancelled walk still has fresh measured readiness",
                f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
            assertTrue("Fresh readiness must not enqueue another start after explicit cancellation",
                f.gateway.background.isEmpty())

            fixture.deliverNextAck()
            fixture.assertNoGuidanceStarted()
            assertEquals("A cancelled start must not speak a start or route announcement",
                speechCount, f.runtime.spoken.size)
            assertNull(f.confirm.confirmedDecision())
            assertFalse(f.confirm.confirmationPending())
            assertFalse(f.confirm.retryRequired())
            assertNull(f.gatewayController.startConfirmationToken)

            // The abandoned server lease may need cleanup; none of that work may restart guidance.
            fixture.drainGatewayWork()
            f.gps(); f.refresh(); f.drainUi()
            assertTrue(f.gateway.background.isEmpty())
            assertEquals(1, fixture.serverStarts)
            fixture.assertNoGuidanceStarted()
        }
    }

    @Test fun oldAckCannotPromoteANewDestinationBeforeItsOwnPromptDoneAndObservationWindow() {
        Fixture("native_cancel_restart_pending_gateway").use { fixture ->
            val f = fixture.replay
            fixture.beginStartAndHoldAck()
            val oldRequest = requireNotNull(f.workflow.nativePhoneMountingCheckRequest)
            val oldPrompt = f.promptId()
            fixture.cancelThroughMain()

            val replacement = f.destination.copy(id = "replacement-place", name = "새 목적지",
                point = f.point(60.0, 70.0))
            f.workflow.requestNativeFeature(NativeUiPage.DESTINATION_SEARCH)
            f.workflow.openNativeDestinationConfirmation(replacement)
            f.button()
            val newRequest = requireNotNull(f.workflow.nativePhoneMountingCheckRequest)
            val newPrompt = f.promptId()
            assertNotSame(oldRequest, newRequest)
            assertNotEquals(oldPrompt, newPrompt)
            // Make the replacement's independent sensor check pass, deliberately leaving its
            // native prompt unfinished. The old ACK must not bypass that remaining user gate.
            f.advance(1); f.observeCameraFrame(); f.gps(); f.heading(); f.drainUi()
            assertTrue(f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
            assertEquals(GuidanceStartCalibration.Phase.WAIT_VOICE_DONE, f.calibration.snapshot?.phase)
            assertNull(f.calibration.snapshot?.voiceDoneAtMs)

            fixture.deliverNextAck()
            fixture.assertNoGuidanceStarted()
            assertSame(newRequest, f.workflow.nativePhoneMountingCheckRequest)
            assertEquals(replacement, f.workflow.pendingUiDestination)
            assertEquals(GuidanceStartCalibration.Phase.WAIT_VOICE_DONE, f.calibration.snapshot?.phase)
            assertFalse(f.runtime.spoken.single { it.id == newPrompt }.done)
            fixture.drainGatewayWork()
            assertEquals(1, fixture.serverStarts)

            // Positive control: the new explicit intent remains live and can start exactly once
            // after its own terminal DONE, full observation window and fresh check.
            f.runtime.start(newPrompt); f.advance(1_000); f.runtime.done(newPrompt)
            f.observeCameraFrame(); f.gps(); f.heading()
            f.advance(2_999)
            assertNull(f.confirm.confirmedDecision())
            assertTrue(f.gateway.background.isEmpty())
            fixture.assertNoGuidanceStarted()
            f.advance(1); f.drainUi()
            assertTrue(f.confirm.isStartupCapabilityConfirmed())
            assertEquals(1, f.gateway.background.size)
            f.prepareArchive()
            fixture.executeServerRequest()
            fixture.deliverNextAck()
            f.acknowledgeWalkOwnership()
            assertEquals(2, fixture.serverStarts)
            assertEquals(WalkSessionState.ACTIVE, f.walk.state)
            assertEquals(1, f.activations)
            assertEquals(1, f.navigationStarts)
            assertEquals(listOf(replacement), f.destinationHandOff)
            assertEquals(1, f.providerRequests.size)
        }
    }

    private class Fixture(caseId: String) : AutoCloseable {
        val replay = GuidanceFullJourneyReplayHarness(caseId = caseId)
        private val main = MainActivity()
        private val gatewayCompletions = ArrayDeque<() -> Unit>()
        var serverStarts = 0
            private set

        init {
            val f = replay
            // Reuse the same actual owners that receive the normal measurements and native TTS
            // callbacks. Main's cancellation body is invoked, never copied into the fixture.
            put(main, "walkSessionLifecycle", f.gateway.lifecycle)
            put(main, "navigationSessions", f.graph)
            put(main, "nativeWorkflowController\$delegate", lazyOf(f.workflow))
            put(main, "nativePrewalkController\$delegate", lazyOf(f.native))
            put(main, "guidancePreparationController\$delegate", lazyOf(f.preparation))
            put(main, "startupConfirmationInteraction\$delegate", lazyOf(f.confirm))
            put(main, "gatewayWalkSessionController\$delegate", lazyOf(f.gatewayController))
            put(main, "speechOutputController", f.output)

            // The journey fixture normally leaves these cancellation leaves inert. Connect the
            // same production cleanup owners used by Main without modifying the shared harness.
            val actions = read<NativePrewalkController.Actions>(f.native, "actions")
            val preflight = read<Lazy<OfficialEnvironmentPreflightController>>(f, "preflight\$delegate").value
            put(f.native, "actions", object : NativePrewalkController.Actions by actions {
                override fun discardAndStopEnvironmentCamera() {
                    preflight.discardAfterRelease()
                    preflight.stopCamera()
                }
                override fun cancelPreparationSpeech() = f.preparation.cancel()
                override fun cancelPendingFeatureEntry() = f.workflow.cancelNativePendingFeatureEntry()
            })

            val scheduler = read<GatewayWalkScheduler>(f.gatewayController, "scheduler")
            put(f.gatewayController, "scheduler", object : GatewayWalkScheduler by scheduler {
                // The Runnable overload still executes the production HTTP ownership probe.
                // Only its completed response's UI delivery is held across the user's cancel.
                override fun runOnUiThread(action: () -> Unit) { gatewayCompletions += action }
            })
            val requests = read<GatewayWalkRequests>(f.gatewayController, "requests")
            put(f.gatewayController, "requests", object : GatewayWalkRequests by requests {
                override fun startWithOwnedWalkRecovery(
                    session: GatewayFieldSession,
                    walkId: String,
                    requestId: String,
                    nowElapsedMs: () -> Long,
                    isCurrent: () -> Boolean,
                ): GatewayWalkStartResult {
                    check(isCurrent()) { "The HTTP request must start under its actual current owner" }
                    serverStarts += 1
                    return requests.startWithOwnedWalkRecovery(session, walkId, requestId, nowElapsedMs, isCurrent)
                }
            })
        }

        fun beginStartAndHoldAck() {
            val f = replay
            f.gps(); f.heading(); f.advance(250); f.gps(); f.heading()
            f.selectDestination(); f.button()
            f.runtime.start(f.promptId()); f.advance(1_000); f.runtime.done(f.promptId())
            f.confirmOfficialConditions(); f.confirmPhoneMounting(); f.observeCameraFrame(); f.loadDetector()
            f.gps(); f.heading(); f.advance(3_000); f.drainUi()
            assertTrue(f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
            assertTrue(f.confirm.isStartupCapabilityConfirmed())
            assertEquals(GuidanceStartCalibration.Phase.READY, f.calibration.snapshot?.phase)
            assertNotNull(f.gatewayController.startConfirmationToken)
            assertEquals(1, f.gateway.background.size)
            f.prepareArchive()
            executeServerRequest()
            assertEquals(1, serverStarts)
            assertEquals(1, gatewayCompletions.size)
            assertNoGuidanceStarted()
        }

        fun cancelThroughMain() {
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, "cancelNativeGuidanceAndReturnHome",
                    MethodType.methodType(Void.TYPE))
                .bindTo(main).invokeWithArguments()
            replay.advance(0); replay.drainUi()
        }

        fun executeServerRequest() {
            replay.gateway.runBackground()
            assertEquals("The server request completed but its UI ACK is still held", 1, gatewayCompletions.size)
        }

        fun deliverNextAck() {
            check(gatewayCompletions.isNotEmpty())
            gatewayCompletions.removeFirst()()
            replay.drainUi()
        }

        fun drainGatewayWork() {
            var tasks = 0
            while (replay.gateway.background.isNotEmpty() || gatewayCompletions.isNotEmpty()) {
                check(++tasks <= 20) { "Unexpected repeated Gateway work after cancellation" }
                if (replay.gateway.background.isNotEmpty()) replay.gateway.runBackground()
                if (gatewayCompletions.isNotEmpty()) deliverNextAck()
            }
            replay.drainUi()
        }

        fun assertNoGuidanceStarted() {
            val f = replay
            assertEquals("A retired start must not promote READY into ACTIVE", WalkSessionState.READY, f.walk.state)
            assertEquals(0, f.activations)
            assertEquals(0, f.navigationStarts)
            assertTrue(f.destinationHandOff.isEmpty())
            assertTrue(f.providerRequests.isEmpty())
            assertFalse(f.graph.run.active)
            assertFalse(f.graph.run.navigator.hasRoute())
            assertTrue(f.routeSpeech().isEmpty())
        }

        override fun close() = replay.close()

        @Suppress("UNCHECKED_CAST")
        private fun <T> read(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(owner) as T

        private fun put(owner: Any, name: String, value: Any?) {
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
        }
    }
}
