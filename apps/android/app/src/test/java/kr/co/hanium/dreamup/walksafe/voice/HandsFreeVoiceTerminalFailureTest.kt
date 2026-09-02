package kr.co.hanium.dreamup.walksafe.voice

import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeVoiceTerminalFailureTest {
    @Test
    fun everyTranscriberFailurePreservesItsExactBoundedToken() {
        val expected = linkedMapOf(
            VoskStreamingError.MODEL_UNAVAILABLE to "model_unavailable",
            VoskStreamingError.MODEL_LOAD_FAILED to "model_load_failed",
            VoskStreamingError.AUDIO_START_FAILED to "audio_start_failed",
            VoskStreamingError.AUDIO_READ_FAILED to "audio_read_failed",
            VoskStreamingError.INFERENCE_FAILED to "inference_failed",
        )

        assertEquals(expected.keys, VoskStreamingError.values().toSet())
        expected.forEach { (error, token) ->
            val failure = HandsFreeVoiceTerminalFailure.Transcriber(error)
            assertSame(error, failure.error)
            assertEquals(token, failure.statusToken)
        }
    }

    @Test
    fun controllerFailuresHaveDistinctBoundedTokens() {
        assertEquals(
            "command_dispatch_failed",
            HandsFreeVoiceTerminalFailure.CommandDispatchFailed.statusToken,
        )
        assertEquals(
            "output_timeout",
            HandsFreeVoiceTerminalFailure.OutputTimeout.statusToken,
        )
    }

    @Test
    fun reservedRunIsVisibleToReadyAndErrorOnAnImmediateExecutor() {
        var startingRunId: Long? = null
        var readyAccepted = false
        var errorAccepted = false

        submitReservedVoskStreamingRun(
            runId = 17L,
            onRunReserved = { startingRunId = it },
            executor = Executor { task -> task.run() },
            task = Runnable {
                readyAccepted = HandsFreeVoiceRunCallbackFence.acceptsReady(
                    closed = false,
                    startingRunId = startingRunId,
                    callbackRunId = 17L,
                )
                errorAccepted = HandsFreeVoiceRunCallbackFence.acceptsError(
                    closed = false,
                    startingRunId = startingRunId,
                    activeRunId = null,
                    callbackRunId = 17L,
                )
            },
        )

        assertTrue(readyAccepted)
        assertTrue(errorAccepted)
    }

    @Test
    fun staleDuplicateStoppedAndClosedRunCallbacksAreRejected() {
        assertTrue(
            HandsFreeVoiceRunCallbackFence.acceptsError(
                closed = false,
                startingRunId = 11L,
                activeRunId = null,
                callbackRunId = 11L,
            ),
        )
        assertFalse(
            "an old startup error must not terminate a newer start",
            HandsFreeVoiceRunCallbackFence.acceptsError(
                closed = false,
                startingRunId = 12L,
                activeRunId = null,
                callbackRunId = 11L,
            ),
        )
        assertTrue(
            HandsFreeVoiceRunCallbackFence.acceptsError(
                closed = false,
                startingRunId = null,
                activeRunId = 12L,
                callbackRunId = 12L,
            ),
        )
        assertFalse(
            "clearing the run fence after the first claim must reject duplicates and stopped runs",
            HandsFreeVoiceRunCallbackFence.acceptsError(
                closed = false,
                startingRunId = null,
                activeRunId = null,
                callbackRunId = 12L,
            ),
        )
        assertFalse(
            HandsFreeVoiceRunCallbackFence.acceptsError(
                closed = true,
                startingRunId = 12L,
                activeRunId = null,
                callbackRunId = 12L,
            ),
        )
        assertFalse(
            HandsFreeVoiceRunCallbackFence.acceptsReady(
                closed = false,
                startingRunId = 12L,
                callbackRunId = 11L,
            ),
        )
    }

    @Test
    fun terminalCallbackExceptionCannotReopenStoppedState() {
        val machine = HandsFreeVoiceStateMachine()
        val listening = machine.startIfEligible(
            walkState = WalkSessionState.ACTIVE,
            voiceConsentGranted = true,
            modelsReady = true,
        )
        val failure = HandsFreeVoiceTerminalFailure.CommandDispatchFailed
        val stopped = machine.onError(listening.state.generation)
        var received: HandsFreeVoiceTerminalFailure? = null

        notifyHandsFreeVoiceTerminalFailure(failure) {
            received = it
            throw IllegalStateException("callback failed")
        }

        assertSame(failure, received)
        assertTrue(stopped.state is HandsFreeVoiceState.Stopped)
        assertEquals(stopped.state, machine.snapshot())
    }
}
