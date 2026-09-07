package kr.co.hanium.dreamup.walksafe.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceCallbackRegistryTest {
    @Test
    fun terminalTimeoutIsBoundedButAllowsLongVoiceCandidatePrompts() {
        assertEquals(10_000L, utteranceTerminalTimeoutMs(1))
        assertTrue(utteranceTerminalTimeoutMs(350) >= 110_000L)
        assertEquals(120_000L, utteranceTerminalTimeoutMs(10_000))
    }

    @Test
    fun terminalCallbackCanBeTakenOnlyOnce() {
        val registry = UtteranceCallbackRegistry()
        var completions = 0
        var failures = 0
        registry.register("nav-1", { completions += 1 }, { failures += 1 })

        registry.takeTerminalCallback("nav-1", completed = true, notifyFailure = true)?.invoke()
        assertNull(registry.takeTerminalCallback("nav-1", completed = false, notifyFailure = true))
        assertEquals(1, completions)
        assertEquals(0, failures)
        assertEquals(0, registry.size())
    }

    @Test
    fun synchronousRejectionAndIntentionalCancellationNeverNotifyFailure() {
        val registry = UtteranceCallbackRegistry()
        var failures = 0
        registry.register("sync-error", null, { failures += 1 })
        assertNull(registry.takeTerminalCallback("sync-error", completed = false, notifyFailure = false))

        registry.register("stt-stop", null, { failures += 1 })
        registry.clear()
        assertNull(registry.takeTerminalCallback("stt-stop", completed = false, notifyFailure = true))

        registry.register("queue-flush", null, { failures += 1 })
        registry.cancel(listOf("queue-flush"))
        assertNull(registry.takeTerminalCallback("queue-flush", completed = false, notifyFailure = true))
        assertEquals(0, failures)
        assertEquals(0, registry.size())
    }

    @Test
    fun protectedPromptsAndReplaceableCommandCallbacksHaveSeparateOwnership() {
        val registry = UtteranceCallbackRegistry()
        var commandDone = 0
        var trainingDone = 0
        var trainingFailed = 0
        registry.register("command", { commandDone += 1 }, {}, protectsFromFollowingSpeech = false)
        registry.register("training", { trainingDone += 1 }, { trainingFailed += 1 },
            protectsFromFollowingSpeech = true)
        assertEquals(listOf("training"), registry.protectedUtteranceIds())
        assertEquals(0, commandDone)
        assertEquals(0, trainingDone)

        registry.takeTerminalCallback("training", completed = false, notifyFailure = true)?.invoke()
        assertTrue(registry.protectedUtteranceIds().isEmpty())
        assertEquals(0, trainingDone)
        assertEquals(1, trainingFailed)
        assertEquals(1, registry.size())
        registry.takeTerminalCallback("command", completed = true, notifyFailure = true)?.invoke()
        assertEquals(1, commandDone)
    }

    @Test
    fun replacingSearchProgressRetiresOldFailureWithoutOverwritingLatestResult() {
        val registry = UtteranceCallbackRegistry()
        var visible = "searching"
        registry.registerLatest("response-1", { visible = "old done" }, { visible = "old failure" })
        visible = "results"
        registry.registerLatest("response-2", { visible = "results done" }, { visible = "results failure" })

        assertNull(registry.takeTerminalCallback("response-1", completed = false, notifyFailure = true))
        assertNull(registry.takeTerminalCallback("response-1", completed = true, notifyFailure = true))
        assertEquals("results", visible)
        assertEquals(1, registry.size())
        registry.takeTerminalCallback("response-2", completed = true, notifyFailure = true)?.invoke()
        assertEquals("results done", visible)
        assertEquals(0, registry.size())
    }

    @Test
    fun dispatchIsNotCompletionAndInterruptedCurrentResponseFailsExactlyOnce() {
        val registry = UtteranceCallbackRegistry()
        var completed = 0
        var failed = 0
        registry.registerLatest("response", { completed += 1 }, { failed += 1 })
        assertEquals(1, registry.size())
        assertEquals(0, completed)
        assertEquals(0, failed)

        registry.takeTerminalCallback("response", completed = false, notifyFailure = true)?.invoke()
        registry.takeTerminalCallback("response", completed = true, notifyFailure = true)?.invoke()
        registry.takeTerminalCallback("response", completed = false, notifyFailure = true)?.invoke()
        assertEquals(0, completed)
        assertEquals(1, failed)
        assertEquals(0, registry.size())
    }

    @Test
    fun cancelledInputCueCannotStartRecordingAndOrdinaryResponseDoesNotListenAgain() {
        val registry = UtteranceCallbackRegistry()
        var recordingStarts = 0
        var nextInputReady = 0
        registry.registerLatest("cancelled-cue", { recordingStarts += 1 }, {})
        registry.clear()
        assertNull(registry.takeTerminalCallback("cancelled-cue", completed = true, notifyFailure = true))

        registry.registerLatest("reply", { nextInputReady += 1 }, { nextInputReady += 1 })
        registry.takeTerminalCallback("reply", completed = true, notifyFailure = true)?.invoke()
        assertEquals(0, recordingStarts)
        assertEquals(1, nextInputReady)

        registry.registerLatest("explicit-new-cue", { recordingStarts += 1 }, {})
        assertEquals(0, recordingStarts)
        registry.takeTerminalCallback("explicit-new-cue", completed = true, notifyFailure = true)?.invoke()
        registry.takeTerminalCallback("explicit-new-cue", completed = true, notifyFailure = true)?.invoke()
        assertEquals(1, recordingStarts)
    }

    @Test
    fun cancellationOwnershipRequiresEveryQueuedUtteranceToBeAnUnprotectedTerminalCommand() {
        val registry = UtteranceCallbackRegistry()
        registry.register("command-1", {}, {})
        registry.register("command-2", {}, {})
        val pending = listOf("command-1", "command-2")
        assertEquals(pending, registry.exclusiveCommandUtteranceIds(pending, pending.toSet()))
        assertTrue(registry.exclusiveCommandUtteranceIds(pending, setOf("command-1")).isEmpty())
        assertTrue(registry.exclusiveCommandUtteranceIds(emptyList(), emptySet()).isEmpty())
        assertEquals(2, registry.size())
    }

    @Test
    fun riskNavigationAndProtectedEducationPreventWholeQueueCancellation() {
        val registry = UtteranceCallbackRegistry()
        registry.register("command", {}, {})
        registry.register("training", {}, {}, protectsFromFollowingSpeech = true)
        registry.register("consent", {}, {}, protectsFromFollowingSpeech = true)
        registry.register("navigation", {}, {})
        val explicit = setOf("command", "training", "consent")
        for (other in listOf("risk", "navigation", "training", "consent")) {
            assertTrue(registry.exclusiveCommandUtteranceIds(listOf("command", other), explicit).isEmpty())
        }
        assertTrue(registry.exclusiveCommandUtteranceIds(listOf("training"), explicit).isEmpty())
        assertTrue(registry.exclusiveCommandUtteranceIds(listOf("consent"), explicit).isEmpty())
        assertEquals(4, registry.size())
    }

    @Test
    fun explicitCommandCancellationFailsOnceAndLateDoneCannotCompleteOrStartInput() {
        val registry = UtteranceCallbackRegistry()
        var recordingStarts = 0
        var failures = 0
        registry.register("input-cue", { recordingStarts += 1 }, { failures += 1 })
        val owned = registry.exclusiveCommandUtteranceIds(listOf("input-cue"), setOf("input-cue"))
        assertEquals(listOf("input-cue"), owned)
        owned.forEach { registry.takeTerminalCallback(it, completed = false, notifyFailure = true)?.invoke() }
        registry.takeTerminalCallback("input-cue", completed = true, notifyFailure = true)?.invoke()
        registry.takeTerminalCallback("input-cue", completed = false, notifyFailure = true)?.invoke()
        assertEquals(0, recordingStarts)
        assertEquals(1, failures)
        assertTrue(registry.exclusiveCommandUtteranceIds(listOf("input-cue"), setOf("input-cue")).isEmpty())
    }

}
