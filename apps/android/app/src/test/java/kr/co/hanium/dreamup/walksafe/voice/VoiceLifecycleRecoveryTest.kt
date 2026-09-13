package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.selectAndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises production state and request fences, not Android audio/Handler scheduling. */
class VoiceLifecycleRecoveryTest {
    @Test
    fun errorsAtEveryPhaseRequireExplicitRestartButNeverPermanentlyDisableIt() {
        for (phase in Phase.values()) {
            val machine = machineAt(phase)
            val retiredGeneration = machine.snapshot().generation

            val failed = machine.onError(retiredGeneration)
            assertTrue(failed.state is HandsFreeVoiceState.Stopped)
            assertEquals(
                listOf(HandsFreeVoiceEffect.StopAllVoiceActivity(failed.state.generation)),
                failed.effects,
            )
            rejectRetiredCallbacks(machine, retiredGeneration)
            assertFalse(machine.startIfEligible(WalkSessionState.ACTIVE, true, false).accepted)
            assertEquals(failed.state, machine.snapshot())

            assertTrue(startEligible(machine).accepted)
            rejectRetiredCallbacks(machine, retiredGeneration)
            completeCommandCycle(machine)
            completeCommandCycle(machine)
        }
    }

    @Test
    fun backgroundStopAtEveryPhaseRejectsLateCallbacksAndPreservesPauseAndConsentGates() {
        for (phase in Phase.values()) {
            val machine = machineAt(phase)
            val retiredGeneration = machine.snapshot().generation
            val stopped = machine.stop()

            assertTrue(stopped.state is HandsFreeVoiceState.Stopped)
            rejectRetiredCallbacks(machine, retiredGeneration)
            assertFalse(machine.startIfEligible(WalkSessionState.PAUSED, true, true).accepted)
            assertFalse(machine.startIfEligible(WalkSessionState.ACTIVE, false, true).accepted)
            assertEquals(stopped.state, machine.snapshot())

            assertTrue(startEligible(machine).accepted)
            rejectRetiredCallbacks(machine, retiredGeneration)
            completeCommandCycle(machine)
        }
    }

    @Test
    fun cancellationAtEveryPhaseRearmsAndCannotLetOldSpeechOrErrorStopTheNextCycle() {
        for (phase in Phase.values()) {
            val machine = machineAt(phase)
            val retiredGeneration = machine.snapshot().generation
            val cancelled = machine.cancel(retiredGeneration)

            assertTrue(cancelled.state is HandsFreeVoiceState.WaitingForWakeWord)
            assertEquals(
                listOf(
                    HandsFreeVoiceEffect.StopAllVoiceActivity(cancelled.state.generation),
                    HandsFreeVoiceEffect.StartWakeWordListening(cancelled.state.generation),
                ),
                cancelled.effects,
            )
            rejectRetiredCallbacks(machine, retiredGeneration)
            completeCommandCycle(machine)
            rejectRetiredCallbacks(machine, retiredGeneration)
            completeCommandCycle(machine)
        }
    }

    @Test
    fun silentAndUnknownCommandsDoNotConsumeTheFollowingValidCommand() {
        val machine = machineAt(Phase.WAKE)
        val silentWake = machine.onWakeWordDetected(machine.snapshot().generation)
        val silentWindow = machine.onWakeAcknowledgementFinished(silentWake.state.generation)
        assertFalse(machine.onCommandRecognized(silentWindow.state.generation, "  ").accepted)
        assertEquals(silentWindow.state, machine.snapshot())
        assertTrue(machine.onCommandWindowTimedOut(silentWindow.state.generation).accepted)

        val nextWake = machine.onWakeWordDetected(machine.snapshot().generation)
        val nextWindow = machine.onWakeAcknowledgementFinished(nextWake.state.generation)
        assertFalse(machine.onCommandWindowTimedOut(silentWindow.state.generation).accepted)
        val unknown = machine.onCommandRecognized(nextWindow.state.generation, "바나나 소나타")
        assertNull(selectAndroidVoiceAction(listOf("바나나 소나타"), floatArrayOf(0.95f)))
        val output = machine.onCommandDispatched(unknown.state.generation)
        assertTrue(output.state is HandsFreeVoiceState.Speaking)
        assertTrue(output.effects.isEmpty())
        // The controller also calls this terminal transition when no app output was queued.
        assertTrue(machine.onTtsFinished(output.state.generation).accepted)

        completeCommandCycle(machine)
    }

    @Test
    fun backgroundCancelledButtonRequestCannotBlockAGrantedForegroundRetry() {
        val requests = VoskSpeechRequestFence()
        val old = checkNotNull(requests.begin())
        requests.cancel()

        assertFalse(OneShotVoiceInputPolicy.isAvailable(false, true, null))
        assertFalse(OneShotVoiceInputPolicy.isAvailable(true, false, null))
        assertFalse(requests.accepts(old))
        assertTrue(OneShotVoiceInputPolicy.isAvailable(true, true, null))
        val next = checkNotNull(requests.begin())

        assertNotEquals(old, next)
        assertFalse(requests.finish(old))
        assertTrue(requests.accepts(next))
        assertTrue(requests.finish(next))
        assertTrue(requests.accepts(checkNotNull(requests.begin())))
    }

    @Test
    fun mixedButtonTerminalAndCancelSequencesNeverRetainOrConsumeAnotherRequest() {
        // Results, no-match, timeout and engine errors share finish; user/background stops use cancel.
        for (pattern in 0 until 64) {
            val requests = VoskSpeechRequestFence()
            val retired = mutableListOf<Long>()
            repeat(6) { step ->
                val current = checkNotNull(requests.begin())
                assertNull(requests.begin())
                retired.forEach {
                    assertFalse(requests.accepts(it))
                    assertFalse(requests.finish(it))
                }
                assertTrue(requests.accepts(current))

                if ((pattern and (1 shl step)) == 0) {
                    assertTrue(requests.finish(current))
                } else {
                    requests.cancel()
                }
                assertFalse(requests.accepts(current))
                assertFalse(requests.finish(current))
                retired += current
            }
            assertTrue(requests.accepts(checkNotNull(requests.begin())))
        }
    }

    private fun rejectRetiredCallbacks(machine: HandsFreeVoiceStateMachine, generation: Long) {
        val before = machine.snapshot()
        for (retired in 0L..generation) {
            val callbacks = listOf(
                machine.onWakeWordDetected(retired),
                machine.onWakeAcknowledgementFinished(retired),
                machine.onCommandRecognized(retired, "도움말"),
                machine.onCommandResponseReady(retired, "이전 응답"),
                machine.onCommandDispatched(retired),
                machine.onCommandWindowTimedOut(retired),
                machine.onTtsFinished(retired),
                machine.onError(retired),
                machine.cancel(retired),
            )
            callbacks.forEach {
                assertFalse(it.accepted)
                assertTrue(it.effects.isEmpty())
                assertEquals(before, it.state)
            }
            assertEquals(before, machine.snapshot())
        }
    }

    private fun completeCommandCycle(machine: HandsFreeVoiceStateMachine) {
        val waiting = machine.snapshot()
        assertTrue(waiting is HandsFreeVoiceState.WaitingForWakeWord)
        val acknowledging = machine.onWakeWordDetected(waiting.generation)
        val command = machine.onWakeAcknowledgementFinished(acknowledging.state.generation)
        assertTrue(command.accepted)
        val processing = machine.onCommandRecognized(command.state.generation, "도움말")
        assertTrue(processing.accepted)
        assertEquals(
            AndroidVoiceAction.SpeakVoiceHelp,
            selectAndroidVoiceAction(listOf("도움말"), floatArrayOf(0.95f)),
        )
        val speaking = machine.onCommandDispatched(processing.state.generation)
        assertTrue(speaking.accepted)
        val finished = machine.onTtsFinished(speaking.state.generation)
        assertTrue(finished.accepted)
        assertTrue(finished.state is HandsFreeVoiceState.WaitingForWakeWord)
        assertTrue(finished.state.generation > waiting.generation)
    }

    private fun startEligible(machine: HandsFreeVoiceStateMachine) =
        machine.startIfEligible(WalkSessionState.ACTIVE, true, true)

    private fun machineAt(phase: Phase): HandsFreeVoiceStateMachine {
        val machine = HandsFreeVoiceStateMachine()
        startEligible(machine)
        if (phase == Phase.WAKE) return machine
        machine.onWakeWordDetected(machine.snapshot().generation)
        if (phase == Phase.ACKNOWLEDGING) return machine
        machine.onWakeAcknowledgementFinished(machine.snapshot().generation)
        if (phase == Phase.COMMAND) return machine
        machine.onCommandRecognized(machine.snapshot().generation, "도움말")
        if (phase == Phase.PROCESSING) return machine
        machine.onCommandDispatched(machine.snapshot().generation)
        return machine
    }

    private enum class Phase { WAKE, ACKNOWLEDGING, COMMAND, PROCESSING, SPEAKING }
}
