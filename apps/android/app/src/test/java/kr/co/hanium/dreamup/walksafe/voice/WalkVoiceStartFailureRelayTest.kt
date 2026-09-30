package kr.co.hanium.dreamup.walksafe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkVoiceStartFailureRelayTest {
    private fun controller() = object : WalkVoiceSessionController {
        override fun start(): Boolean = true
        override fun stop() = Unit
    }

    @Test
    fun onlyMatchingRequestAndControllerReceiveFailureOnce() {
        val relay = WalkVoiceStartFailureRelay()
        val currentController = controller()
        val otherController = controller()
        val received = mutableListOf<WalkVoiceStartFailure>()
        val requestId = relay.register(currentController) { _, _, failure -> received += failure }

        assertFalse(relay.report(requestId, otherController, WalkVoiceStartFailure.RUNTIME))
        assertFalse(relay.report(requestId + 1, currentController, WalkVoiceStartFailure.RUNTIME))
        assertTrue(relay.accepts(requestId, currentController))
        assertTrue(relay.report(requestId, currentController, WalkVoiceStartFailure.CONTROLLER_REJECTED))
        assertFalse(relay.report(requestId, currentController, WalkVoiceStartFailure.RUNTIME))
        assertEquals(listOf(WalkVoiceStartFailure.CONTROLLER_REJECTED), received)
    }

    @Test
    fun failingFailureCallbackCannotPropagateOrReviveItsRequest() {
        val relay = WalkVoiceStartFailureRelay()
        val currentController = controller()
        var deliveries = 0
        val requestId = relay.register(currentController) { _, _, _ ->
            deliveries++
            throw IllegalStateException("UI failure callback failed")
        }

        assertTrue(relay.report(requestId, currentController, WalkVoiceStartFailure.RUNTIME))
        assertEquals(1, deliveries)
        assertFalse(relay.accepts(requestId, currentController))
        assertFalse(relay.report(requestId, currentController, WalkVoiceStartFailure.RUNTIME))

        val nextId = relay.register(currentController) { _, _, _ -> deliveries++ }
        assertTrue(relay.report(nextId, currentController, WalkVoiceStartFailure.SECURITY))
        assertEquals(2, deliveries)
    }

    @Test
    fun rejectingControllerReturnsFalseEvenIfItsFailureCallbackThrows() {
        val relay = WalkVoiceStartFailureRelay()
        val rejectingController = object : WalkVoiceSessionController {
            override fun start(): Boolean = false
            override fun stop() = Unit
        }
        var deliveries = 0
        val requestId = relay.register(rejectingController) { _, _, failure ->
            assertEquals(WalkVoiceStartFailure.CONTROLLER_REJECTED, failure)
            deliveries++
            throw IllegalStateException("UI failure callback failed")
        }

        assertFalse(relay.startController(requestId, rejectingController))
        assertEquals(1, deliveries)
        assertFalse(relay.accepts(requestId, rejectingController))
    }

    @Test
    fun stoppedOrReplacedActivityRequestCannotReceiveLateServiceFailure() {
        val relay = WalkVoiceStartFailureRelay()
        val oldController = controller()
        val newController = controller()
        var deliveries = 0
        val stoppedId = relay.register(oldController) { _, _, _ -> deliveries++ }
        relay.invalidate(stoppedId, oldController)
        assertFalse(relay.report(stoppedId, oldController, WalkVoiceStartFailure.SECURITY))

        val oldId = relay.register(oldController) { _, _, _ -> deliveries++ }
        val currentId = relay.register(newController) { _, _, _ -> deliveries++ }
        assertFalse(relay.report(oldId, oldController, WalkVoiceStartFailure.RUNTIME))
        assertTrue(relay.accepts(currentId, newController))
        relay.invalidateController(newController)
        assertFalse(relay.report(currentId, newController, WalkVoiceStartFailure.RUNTIME))
        assertEquals(0, deliveries)
    }

    @Test
    fun securityFailureIsNotInferredFromForegroundRestriction() {
        assertEquals(
            WalkVoiceStartFailure.SECURITY,
            classifyWalkVoiceStartFailure(SecurityException(), foregroundStartRestricted = true),
        )
        assertEquals(
            WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED,
            classifyWalkVoiceStartFailure(IllegalStateException(), foregroundStartRestricted = true),
        )
        assertEquals(
            WalkVoiceStartFailure.RUNTIME,
            classifyWalkVoiceStartFailure(IllegalStateException(), foregroundStartRestricted = false),
        )
    }

    @Test
    fun synchronousSessionEndDuringControllerStartStillReportsFalseStart() {
        val relay = WalkVoiceStartFailureRelay()
        var requestId = 0L
        var sessionEndDeferred = false
        val received = mutableListOf<WalkVoiceStartFailure>()
        val endingController = object : WalkVoiceSessionController {
            override fun start(): Boolean {
                sessionEndDeferred = !relay.invalidateOnSessionEnded(requestId, this)
                return false
            }

            override fun stop() = Unit
        }
        requestId = relay.register(endingController) { _, _, failure -> received += failure }

        assertFalse(relay.startController(requestId, endingController))
        assertTrue(sessionEndDeferred)
        assertEquals(listOf(WalkVoiceStartFailure.CONTROLLER_REJECTED), received)
        assertFalse(relay.report(requestId, endingController, WalkVoiceStartFailure.RUNTIME))
    }

    @Test
    fun replacingActiveControllerStopsOldSessionOnlyForCurrentNewRequest() {
        val relay = WalkVoiceStartFailureRelay()
        val oldController = controller()
        val newController = controller()
        val oldId = relay.register(oldController) { _, _, _ -> }
        val newId = relay.register(newController) { _, _, _ -> }
        var stopCount = 0

        assertFalse(relay.accepts(oldId, oldController))
        assertTrue(relay.accepts(newId, newController))
        assertTrue(
            replaceWalkVoiceSessionIfNeeded(true, oldController, newController) { stopCount++ },
        )
        assertEquals(1, stopCount)
        assertFalse(
            replaceWalkVoiceSessionIfNeeded(true, newController, newController) { stopCount++ },
        )
        assertEquals(1, stopCount)
    }
}
