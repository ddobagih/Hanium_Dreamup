package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeVoiceStateMachineTest {
    @Test
    fun waitsForWakeWordOnlyWhenWalkConsentAndModelsAreReady() {
        listOf(
            Eligibility(WalkSessionState.READY, consent = true, modelsReady = true),
            Eligibility(WalkSessionState.PAUSED, consent = true, modelsReady = true),
            Eligibility(WalkSessionState.ACTIVE, consent = false, modelsReady = true),
            Eligibility(WalkSessionState.ACTIVE, consent = true, modelsReady = false),
        ).forEach { eligibility ->
            val machine = HandsFreeVoiceStateMachine()

            val transition = machine.startIfEligible(
                walkState = eligibility.walkState,
                voiceConsentGranted = eligibility.consent,
                modelsReady = eligibility.modelsReady,
            )

            assertFalse(transition.accepted)
            assertTrue(transition.state is HandsFreeVoiceState.Stopped)
            assertTrue(transition.effects.isEmpty())
        }

        val started = eligibleMachine().snapshot()
        assertTrue(started is HandsFreeVoiceState.WaitingForWakeWord)
    }

    @Test
    fun completesWakeCommandProcessingSpeechCycleAndWaitsAgain() {
        val machine = eligibleMachine()
        val wakeGeneration = machine.snapshot().generation

        val acknowledging = machine.onWakeWordDetected(wakeGeneration)
        assertTrue(acknowledging.state is HandsFreeVoiceState.AcknowledgingWake)
        assertEquals(
            HandsFreeVoiceEffect.AcknowledgeWake(acknowledging.state.generation),
            acknowledging.effects.single(),
        )
        assertFalse(machine.onCommandRecognized(acknowledging.state.generation, "신고해줘").accepted)
        val commandWindow = machine.onWakeAcknowledgementFinished(acknowledging.state.generation)
        assertTrue(commandWindow.state is HandsFreeVoiceState.WaitingForCommand)
        assertEquals(
            HandsFreeVoiceEffect.StartCommandWindow(commandWindow.state.generation),
            commandWindow.effects.single(),
        )

        val processing = machine.onCommandRecognized(
            callbackGeneration = commandWindow.state.generation,
            command = "  서울역으로 안내해줘  ",
        )
        assertEquals(
            HandsFreeVoiceState.ProcessingCommand(
                processing.state.generation,
                "서울역으로 안내해줘",
            ),
            processing.state,
        )
        assertEquals(
            HandsFreeVoiceEffect.ProcessCommand(
                processing.state.generation,
                "서울역으로 안내해줘",
            ),
            processing.effects.single(),
        )

        val speaking = machine.onCommandResponseReady(
            callbackGeneration = processing.state.generation,
            spokenResponse = " 서울역 안내를 시작합니다. ",
        )
        assertTrue(speaking.state is HandsFreeVoiceState.Speaking)
        assertEquals(
            HandsFreeVoiceEffect.Speak(
                speaking.state.generation,
                "서울역 안내를 시작합니다.",
            ),
            speaking.effects.single(),
        )

        val waitingAgain = machine.onTtsFinished(speaking.state.generation)
        assertTrue(waitingAgain.state is HandsFreeVoiceState.WaitingForWakeWord)
        assertEquals(
            HandsFreeVoiceEffect.StartWakeWordListening(waitingAgain.state.generation),
            waitingAgain.effects.single(),
        )
    }

    @Test
    fun repeatedWakeAndEarlyCommandCannotBypassOrDuplicateAcknowledgement() {
        val machine = eligibleMachine()
        val wakeGeneration = machine.snapshot().generation
        val acknowledging = machine.onWakeWordDetected(wakeGeneration)

        assertFalse(machine.onWakeWordDetected(wakeGeneration).accepted)
        assertFalse(machine.onWakeWordDetected(acknowledging.state.generation).accepted)
        assertFalse(machine.onCommandRecognized(acknowledging.state.generation, "설정").accepted)
        assertFalse(machine.onCommandWindowTimedOut(acknowledging.state.generation).accepted)

        val commandWindow = machine.onWakeAcknowledgementFinished(acknowledging.state.generation)
        assertTrue(commandWindow.state is HandsFreeVoiceState.WaitingForCommand)
        assertFalse(machine.onWakeAcknowledgementFinished(acknowledging.state.generation).accepted)
        assertTrue(machine.onCommandRecognized(commandWindow.state.generation, "설정").accepted)
    }

    @Test
    fun cancelledStoppedOrIneligibleAcknowledgementCannotOpenCommandWindowLater() {
        listOf<(HandsFreeVoiceStateMachine, Long) -> Unit>(
            { machine, generation -> machine.cancel(generation) },
            { machine, _ -> machine.stop() },
            { machine, _ ->
                machine.startIfEligible(WalkSessionState.ACTIVE, voiceConsentGranted = false, modelsReady = true)
            },
        ).forEach { invalidate ->
            val machine = eligibleMachine()
            val acknowledging = machine.onWakeWordDetected(machine.snapshot().generation)
            invalidate(machine, acknowledging.state.generation)
            val invalidated = machine.snapshot()

            assertFalse(machine.onWakeAcknowledgementFinished(acknowledging.state.generation).accepted)
            assertEquals(invalidated, machine.snapshot())
        }
    }

    @Test
    fun timeoutAndCancelReturnToWakeWordListeningWithNewGenerations() {
        val machine = eligibleMachine()
        val firstCommandWindow = openCommandWindow(machine)

        val timedOut = machine.onCommandWindowTimedOut(firstCommandWindow.state.generation)
        assertTrue(timedOut.state is HandsFreeVoiceState.WaitingForWakeWord)
        assertTrue(timedOut.state.generation > firstCommandWindow.state.generation)

        val secondCommandWindow = openCommandWindow(machine)
        val cancelled = machine.cancel(secondCommandWindow.state.generation)
        assertTrue(cancelled.state is HandsFreeVoiceState.WaitingForWakeWord)
        assertEquals(
            listOf(
                HandsFreeVoiceEffect.StopAllVoiceActivity(cancelled.state.generation),
                HandsFreeVoiceEffect.StartWakeWordListening(cancelled.state.generation),
            ),
            cancelled.effects,
        )
    }

    @Test
    fun errorFailsClosedAndRequiresACompleteEligibilityCheckToRestart() {
        val machine = eligibleMachine()
        val failedGeneration = machine.snapshot().generation

        val failed = machine.onError(failedGeneration)
        assertTrue(failed.state is HandsFreeVoiceState.Stopped)
        assertEquals(
            HandsFreeVoiceEffect.StopAllVoiceActivity(failed.state.generation),
            failed.effects.single(),
        )

        assertFalse(machine.onWakeWordDetected(failedGeneration).accepted)
        assertFalse(
            machine.startIfEligible(
                walkState = WalkSessionState.ACTIVE,
                voiceConsentGranted = true,
                modelsReady = false,
            ).accepted,
        )
        assertTrue(machine.snapshot() is HandsFreeVoiceState.Stopped)

        val restarted = startEligible(machine)
        assertTrue(restarted.state is HandsFreeVoiceState.WaitingForWakeWord)
        assertTrue(restarted.state.generation > failed.state.generation)
    }

    @Test
    fun wakeWordCallbacksAreIgnoredWhileTtsIsSpeaking() {
        val machine = speakingMachine()
        val speaking = machine.snapshot()

        val ignored = machine.onWakeWordDetected(speaking.generation)

        assertFalse(ignored.accepted)
        assertEquals(speaking, ignored.state)
        assertTrue(ignored.effects.isEmpty())

        val stopped = machine.stop()
        assertFalse(machine.onTtsFinished(speaking.generation).accepted)
        assertEquals(stopped.state, machine.snapshot())
    }

    @Test
    fun existingCommandDispatcherCanOwnSpeechWithoutDuplicatingIt() {
        val machine = eligibleMachine()
        val commandWindow = openCommandWindow(machine)
        val processing = machine.onCommandRecognized(
            callbackGeneration = commandWindow.state.generation,
            command = "서울역으로 안내해줘",
        )

        val speaking = machine.onCommandDispatched(processing.state.generation)

        assertTrue(speaking.state is HandsFreeVoiceState.Speaking)
        assertTrue(speaking.effects.isEmpty())
    }

    @Test
    fun stopInvalidatesWorkBeforeFallibleAudioShutdownAndRejectsLateCallbacks() {
        val machine = eligibleMachine()
        val commandWindow = openCommandWindow(machine)
        val processing = machine.onCommandRecognized(
            callbackGeneration = commandWindow.state.generation,
            command = "다음 안내 알려줘",
        )

        val stopped = machine.stop()
        assertTrue(stopped.state is HandsFreeVoiceState.Stopped)
        assertTrue(stopped.state.generation > processing.state.generation)

        // Even if the external StopAllVoiceActivity effect fails, state is already fail-closed.
        val lateResponse = machine.onCommandResponseReady(
            callbackGeneration = processing.state.generation,
            spokenResponse = "다음 안내입니다.",
        )
        assertFalse(lateResponse.accepted)
        assertEquals(stopped.state, machine.snapshot())
    }

    @Test
    fun everyAsyncBoundaryRejectsCallbacksFromEarlierGenerations() {
        val machine = eligibleMachine()
        val staleWakeGeneration = machine.snapshot().generation
        val acknowledging = machine.onWakeWordDetected(staleWakeGeneration)
        assertFalse(machine.onWakeAcknowledgementFinished(staleWakeGeneration).accepted)
        val commandWindow = machine.onWakeAcknowledgementFinished(acknowledging.state.generation)
        assertFalse(machine.onWakeAcknowledgementFinished(acknowledging.state.generation).accepted)

        assertFalse(machine.onWakeWordDetected(staleWakeGeneration).accepted)
        assertFalse(machine.onCommandRecognized(staleWakeGeneration, "신고해줘").accepted)

        val processing = machine.onCommandRecognized(
            commandWindow.state.generation,
            "신고해줘",
        )
        assertFalse(machine.onCommandWindowTimedOut(commandWindow.state.generation).accepted)
        assertFalse(
            machine.onCommandResponseReady(
                commandWindow.state.generation,
                "신고 화면을 엽니다.",
            ).accepted,
        )

        val speaking = machine.onCommandResponseReady(
            processing.state.generation,
            "신고 화면을 엽니다.",
        )
        assertFalse(machine.onError(processing.state.generation).accepted)
        assertFalse(machine.onTtsFinished(processing.state.generation).accepted)
        assertEquals(speaking.state, machine.snapshot())
    }

    @Test
    fun losingAnyEligibilityStopsAnActiveCycle() {
        listOf(
            Eligibility(WalkSessionState.PAUSED, consent = true, modelsReady = true),
            Eligibility(WalkSessionState.ACTIVE, consent = false, modelsReady = true),
            Eligibility(WalkSessionState.ACTIVE, consent = true, modelsReady = false),
        ).forEach { eligibility ->
            val machine = eligibleMachine()

            val stopped = machine.startIfEligible(
                walkState = eligibility.walkState,
                voiceConsentGranted = eligibility.consent,
                modelsReady = eligibility.modelsReady,
            )

            assertTrue(stopped.state is HandsFreeVoiceState.Stopped)
            assertEquals(
                HandsFreeVoiceEffect.StopAllVoiceActivity(stopped.state.generation),
                stopped.effects.single(),
            )
        }
    }

    private fun eligibleMachine() = HandsFreeVoiceStateMachine().also(::startEligible)

    private fun openCommandWindow(machine: HandsFreeVoiceStateMachine): HandsFreeVoiceTransition {
        val acknowledging = machine.onWakeWordDetected(machine.snapshot().generation)
        return machine.onWakeAcknowledgementFinished(acknowledging.state.generation)
    }

    private fun startEligible(machine: HandsFreeVoiceStateMachine) = machine.startIfEligible(
        walkState = WalkSessionState.ACTIVE,
        voiceConsentGranted = true,
        modelsReady = true,
    )

    private fun speakingMachine(): HandsFreeVoiceStateMachine {
        val machine = eligibleMachine()
        val commandWindow = openCommandWindow(machine)
        val processing = machine.onCommandRecognized(
            commandWindow.state.generation,
            "목적지 서울역 설정해",
        )
        machine.onCommandResponseReady(
            processing.state.generation,
            "서울역을 목적지로 설정합니다.",
        )
        return machine
    }

    private data class Eligibility(
        val walkState: WalkSessionState,
        val consent: Boolean,
        val modelsReady: Boolean,
    )
}
