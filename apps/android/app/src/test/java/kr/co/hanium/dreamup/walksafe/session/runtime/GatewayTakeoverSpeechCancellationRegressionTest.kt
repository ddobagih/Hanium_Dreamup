package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkConflict
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkStartResult
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.*
import org.junit.Test

class GatewayTakeoverSpeechCancellationRegressionTest {
    @Test fun canceledPromptFailureCannotReopenRetryOrPublishAnOldFailure() {
        val f = GatewayFixture()
        val token = f.prepare()
        f.startConflict = true
        f.controller.requestGatewayWalkStart(token); f.runBackground()
        val failed = requireNotNull(f.promptFailed)
        f.controller.cancelPendingGatewayWalkStart()
        f.events.clear()

        failed()

        assertFalse(f.controller.takeoverPromptPending)
        assertNull(f.controller.startConfirmationToken)
        assertTrue("Canceled speech must not make a new user retry necessary", f.retries.isEmpty())
        assertTrue("A retired prompt must not overwrite the current screen", f.events.isEmpty())
        assertEquals(0, f.activations)
    }

    @Test fun retiredPromptFailureCannotCancelANewStartInTheSameWalkEpoch() {
        val f = GatewayFixture()
        val token = f.prepare()
        f.startConflict = true
        f.controller.requestGatewayWalkStart(token); f.runBackground()
        val failed = requireNotNull(f.promptFailed)
        f.controller.cancelPendingGatewayWalkStart()
        f.startConflict = false
        f.controller.requestGatewayWalkStart(token)
        assertEquals(token, f.controller.startConfirmationToken)

        failed()

        assertEquals("A late old terminal cannot clear the new server request", token, f.controller.startConfirmationToken)
        assertTrue(f.retries.isEmpty())
        f.runBackground()
        assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        assertEquals(1, f.activations)
    }

    @Test fun retiredPromptFailureCannotClearTheNextTakeoverPromptOrItsConflict() {
        val f = GatewayFixture()
        val token = f.prepare()
        f.startConflict = true
        f.controller.requestGatewayWalkStart(token); f.runBackground()
        val oldPrompt = f.controller.takeoverPromptOperationId
        val failed = requireNotNull(f.promptFailed)
        f.controller.cancelPendingGatewayWalkStart()
        f.controller.requestGatewayWalkStart(token); f.runBackground()
        val newPrompt = f.controller.takeoverPromptOperationId
        assertNotNull(newPrompt)
        assertNotEquals(oldPrompt, newPrompt)

        failed()

        assertEquals(newPrompt, f.controller.takeoverPromptOperationId)
        assertTrue(f.controller.takeoverPromptPending)
        assertNotNull(f.authority.currentConflictOrNull(token.epoch))
        assertTrue(f.retries.isEmpty())
        requireNotNull(f.promptDone).invoke()
        assertEquals(1, f.microphones)
        f.controller.handleGatewayWalkTakeoverRecognition(listOf("예")); f.runBackground()
        assertEquals(1, f.activations)
        assertEquals(1, f.takeovers)
    }

    @Test fun currentPromptFailureStillRequiresANewUserAttempt() {
        val f = GatewayFixture()
        val token = f.prepare()
        f.startConflict = true
        f.controller.requestGatewayWalkStart(token); f.runBackground()

        requireNotNull(f.promptFailed).invoke()

        assertFalse(f.controller.takeoverPromptPending)
        assertNull(f.controller.startConfirmationToken)
        assertNull(f.authority.currentConflictOrNull(token.epoch))
        assertEquals(1, f.retries.size)
        assertEquals(0, f.activations)
        assertEquals(0, f.microphones)
    }

    @Test fun currentPromptFailureRetiresItsStartEvenWhenTheOtherWalkLeaseJustExpired() {
        val f = GatewayFixture()
        val requests = object : GatewayWalkRequests by f {
            override fun startWithOwnedWalkRecovery(session: GatewayFieldSession, walkId: String,
                requestId: String, nowElapsedMs: () -> Long, isCurrent: () -> Boolean): GatewayWalkStartResult =
                GatewayWalkStartResult.Conflict(GatewayWalkConflict("previous-walk", "other-device", 7L,
                    1_500L, 1_000L, f.now + 500L))
        }
        val controller = GatewayWalkSessionController(f.authority, requests, f, f, f, f, 30_000L, "restart", false)
        val token = f.prepare()
        controller.requestGatewayWalkStart(token); f.runBackground()
        assertTrue(controller.takeoverPromptPending)
        val failed = requireNotNull(f.promptFailed)
        // An already old server lease may expire while its current spoken question is playing.
        // Failure cleanup still belongs to that question, even though takeover is no longer allowed.
        f.now += 501L
        assertNull(f.authority.currentConflictOrNull(token.epoch))

        failed()

        assertFalse(controller.takeoverPromptPending)
        assertNull("An expired conflict must not strand the current start token", controller.startConfirmationToken)
        assertEquals(1, f.retries.size)
        assertEquals(0, f.activations)
        assertEquals(0, f.microphones)
    }

    @Test fun currentPromptCompletionReleasesAnExpiredConflictWithoutRequestingTakeover() {
        val f = GatewayFixture()
        val requests = object : GatewayWalkRequests by f {
            override fun startWithOwnedWalkRecovery(session: GatewayFieldSession, walkId: String,
                requestId: String, nowElapsedMs: () -> Long, isCurrent: () -> Boolean): GatewayWalkStartResult =
                GatewayWalkStartResult.Conflict(GatewayWalkConflict("previous-walk", "other-device", 7L,
                    1_500L, 1_000L, f.now + 500L))
        }
        val controller = GatewayWalkSessionController(f.authority, requests, f, f, f, f, 30_000L, "restart", false)
        val token = f.prepare()
        controller.requestGatewayWalkStart(token); f.runBackground()
        assertTrue(controller.takeoverPromptPending)
        val done = requireNotNull(f.promptDone)
        val failed = requireNotNull(f.promptFailed)
        f.now += 501L

        done()

        assertFalse(controller.takeoverPromptPending)
        assertNull("A completed question must not strand the start after its conflict expires", controller.startConfirmationToken)
        assertNull(f.authority.currentConflictOrNull(token.epoch))
        assertEquals(1, f.retries.size)
        assertEquals(0, f.activations)
        assertEquals(0, f.microphones)
        assertEquals(0, f.takeovers)
        // Duplicate or late terminal callbacks cannot publish another retry after cleanup.
        done(); failed()
        assertEquals(1, f.retries.size)
    }
}
