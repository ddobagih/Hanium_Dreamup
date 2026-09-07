package kr.co.hanium.dreamup.walksafe.feedback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the production callback owner and wait decision, not Android TTS or Activity lifecycle. */
class CommandSpeechReadinessTest {
    @Test
    fun coldStartWaitsUntilInitializationFinishesWithoutClaimingCompletion() {
        val registry = UtteranceCallbackRegistry()
        var completed = 0
        registry.registerLatest("response", { completed += 1 }, {})

        assertEquals(CommandSpeechReadiness.WAITING, decision(registry, initializing = true))
        assertEquals(0, completed)
        assertEquals(CommandSpeechReadiness.DISPATCH, decision(registry, initializing = false))
        assertEquals(0, completed)
        registry.takeTerminalCallback("response", completed = true, notifyFailure = true)?.invoke()
        assertEquals(1, completed)
        assertEquals(CommandSpeechReadiness.CANCELLED, decision(registry, initializing = false))
    }

    @Test
    fun newerRequestRetiresTheOldInitializationWaitAndItsLateCallbacks() {
        val registry = UtteranceCallbackRegistry()
        var staleCallbacks = 0
        registry.registerLatest("old", { staleCallbacks += 1 }, { staleCallbacks += 1 })
        assertEquals(CommandSpeechReadiness.WAITING, decision(registry, id = "old"))
        registry.registerLatest("new", {}, {})

        assertEquals(CommandSpeechReadiness.CANCELLED, decision(registry, id = "old", initializing = false))
        registry.takeTerminalCallback("old", completed = true, notifyFailure = true)?.invoke()
        registry.takeTerminalCallback("old", completed = false, notifyFailure = true)?.invoke()
        assertEquals(0, staleCallbacks)
        assertEquals(CommandSpeechReadiness.DISPATCH, decision(registry, id = "new", initializing = false))
    }

    @Test
    fun explicitCancellationPreventsLateReadinessFromDispatchingOrCompleting() {
        val registry = UtteranceCallbackRegistry()
        var completed = 0
        registry.registerLatest("response", { completed += 1 }, {})
        assertEquals(CommandSpeechReadiness.WAITING, decision(registry))
        registry.clear()

        assertEquals(CommandSpeechReadiness.CANCELLED, decision(registry, initializing = false))
        registry.takeTerminalCallback("response", completed = true, notifyFailure = true)?.invoke()
        assertEquals(0, completed)
    }

    @Test
    fun invalidatedRequestCannotDispatchRegardlessOfEngineReadinessOrDeadline() {
        val registry = UtteranceCallbackRegistry()
        registry.registerLatest("response", {}, {})

        assertEquals(CommandSpeechReadiness.CANCELLED, decision(registry, current = false))
        assertEquals(CommandSpeechReadiness.CANCELLED, decision(registry, current = false, initializing = false))
        assertEquals(CommandSpeechReadiness.CANCELLED, decision(registry, current = false, nowMs = 8_001L))
    }

    @Test
    fun waitDeadlineAppliesEvenWhenInitializationFinishesTooLate() {
        val registry = UtteranceCallbackRegistry()
        registry.registerLatest("response", {}, {})

        assertEquals(CommandSpeechReadiness.WAITING, decision(registry, nowMs = 7_999L))
        assertEquals(CommandSpeechReadiness.TIMED_OUT, decision(registry, nowMs = 8_000L))
        assertEquals(CommandSpeechReadiness.TIMED_OUT, decision(registry, initializing = false, nowMs = 8_001L))
    }

    @Test
    fun timeoutFailsOnceAndLateDoneCannotEnableThePendingInput() {
        val registry = UtteranceCallbackRegistry()
        var inputStarts = 0
        var failed = 0
        registry.registerLatest("response", { inputStarts += 1 }, { failed += 1 })

        assertEquals(CommandSpeechReadiness.TIMED_OUT, decision(registry, nowMs = 8_000L))
        registry.takeTerminalCallback("response", completed = false, notifyFailure = true)?.invoke()
        registry.takeTerminalCallback("response", completed = true, notifyFailure = true)?.invoke()
        registry.takeTerminalCallback("response", completed = false, notifyFailure = true)?.invoke()
        assertEquals(0, inputStarts)
        assertEquals(1, failed)
        assertEquals(CommandSpeechReadiness.CANCELLED, decision(registry, initializing = false, nowMs = 8_001L))
    }

    private fun decision(
        registry: UtteranceCallbackRegistry,
        id: String = "response",
        current: Boolean = true,
        initializing: Boolean = true,
        nowMs: Long = 0L,
    ) = registry.commandSpeechReadiness(
        utteranceId = id,
        requestCurrent = current,
        speechInitializing = initializing,
        nowMs = nowMs,
        deadlineMs = 8_000L,
    )
}
