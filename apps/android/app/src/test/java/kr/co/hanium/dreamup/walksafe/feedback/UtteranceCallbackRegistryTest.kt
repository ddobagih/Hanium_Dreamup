package kr.co.hanium.dreamup.walksafe.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun closeDrainsOnlyExplicitCancellationAndCannotNotifyOrReplayFailureFallback() {
        val registry = UtteranceCallbackRegistry()
        var failures = 0
        var ordinaryCancellations = 0
        var closeCancellations = 0
        registry.register("legacy", null, { failures += 1 })
        registry.register("explicit", null, { failures += 1 },
            onCancelled = { ordinaryCancellations += 1 },
            onCloseCancelled = { closeCancellations += 1 })
        val closing = registry.takeCloseCancellationCallbacks(listOf("legacy", "explicit"))
        assertEquals(1, closing.size)
        assertEquals(0, closeCancellations)
        assertEquals(0, registry.size())
        closing.forEach { it() }
        assertEquals(1, closeCancellations)
        assertEquals(0, failures + ordinaryCancellations)
        assertTrue(registry.takeCloseCancellationCallbacks(listOf("legacy", "explicit")).isEmpty())
        assertNull(registry.takeTerminalCallback("explicit", completed = false, notifyFailure = true))

        registry.register("legacy-preemption", null, { failures += 1 })
        registry.takeTerminalCallback("legacy-preemption", completed = false, notifyFailure = true,
            cancelled = true)?.invoke()
        assertEquals(1, failures)
    }

    @Test
    fun ordinaryTerminalAndSilentClearRetireExplicitCloseOwnershipWithoutReplay() {
        val registry = UtteranceCallbackRegistry()
        var completed = 0
        var closeCancellations = 0
        registry.register("done", { completed += 1 }, null,
            onCloseCancelled = { closeCancellations += 1 })
        registry.takeTerminalCallback("done", completed = true, notifyFailure = true)?.invoke()
        assertTrue(registry.takeCloseCancellationCallbacks(listOf("done")).isEmpty())
        registry.register("cleared", null, null, onCloseCancelled = { closeCancellations += 1 })
        registry.clear()
        assertTrue(registry.takeCloseCancellationCallbacks(listOf("cleared")).isEmpty())
        registry.register("cancelled", null, null, onCloseCancelled = { closeCancellations += 1 })
        registry.cancel(listOf("cancelled"))
        assertTrue(registry.takeCloseCancellationCallbacks(listOf("cancelled")).isEmpty())
        assertEquals(1, completed)
        assertEquals(0, closeCancellations)
        assertEquals(0, registry.size())
    }

    @Test
    fun retirementDrainsAllEntriesButReturnsOnlyExplicitCallbacksWithoutInvokingThem() {
        val registry = UtteranceCallbackRegistry()
        var retired = 0
        var ordinary = 0
        registry.register("legacy", { ordinary += 1 }, { ordinary += 1 })
        registry.register("explicit", { ordinary += 1 }, { ordinary += 1 },
            onCancelled = { ordinary += 1 }, onCloseCancelled = {
                assertFalse(Thread.holdsLock(registry))
                retired += 1
            })
        registry.register("retirement-only", null, null, onCloseCancelled = { retired += 1 })

        val callbacks = registry.takeRetirementCallbacks()

        assertEquals(2, callbacks.size)
        assertEquals(0, registry.size())
        assertEquals(0, retired)
        assertTrue(registry.takeRetirementCallbacks().isEmpty())
        assertTrue(registry.takeCloseCancellationCallbacks(listOf("explicit", "retirement-only")).isEmpty())
        callbacks.forEach { it() }
        assertEquals(2, retired)
        assertEquals(0, ordinary)
        for (id in listOf("legacy", "explicit", "retirement-only")) {
            assertNull(registry.takeTerminalCallback(id, completed = true, notifyFailure = true))
        }
    }

    @Test
    fun callerCanRetireWaitingOrDispatchedResponseAfterInstallingItsReplacement() {
        for (initializing in listOf(true, false)) {
            val registry = UtteranceCallbackRegistry()
            var retired = 0
            var oldTerminal = 0
            var newCompleted = 0
            registry.registerLatest("old", { oldTerminal += 1 }, { oldTerminal += 1 }, onRetired = {
                assertFalse(Thread.holdsLock(registry))
                assertEquals(1, registry.size())
                retired += 1
            })
            assertEquals(if (initializing) CommandSpeechReadiness.WAITING else CommandSpeechReadiness.DISPATCH,
                registry.commandSpeechReadiness("old", true, initializing, 0L, 1_000L))

            val callbacks = registry.takeRetirementCallbacks()
            registry.registerLatest("new", { newCompleted += 1 }, {})
            assertEquals(0, retired)
            callbacks.forEach { it() }

            assertEquals(1, retired)
            assertEquals(CommandSpeechReadiness.CANCELLED,
                registry.commandSpeechReadiness("old", true, initializing, 0L, 1_000L))
            assertNull(registry.takeTerminalCallback("old", completed = true, notifyFailure = true))
            assertNull(registry.takeTerminalCallback("old", completed = false, notifyFailure = true))
            assertEquals(1, registry.size())
            registry.takeTerminalCallback("new", completed = true, notifyFailure = true)?.invoke()
            assertTrue(registry.takeRetirementCallbacks().isEmpty())
            assertEquals(0, oldTerminal)
            assertEquals(1, newCompleted)
        }
    }

    @Test
    fun lateOldDoneAfterRetirementCannotConsumeTheNewResponse() {
        val registry = UtteranceCallbackRegistry()
        var oldRetired = 0
        var newCompleted = 0
        var newRetired = 0
        registry.registerLatest("old", null, null, onRetired = { oldRetired += 1 })
        val callbacks = registry.takeRetirementCallbacks()
        registry.registerLatest("new", { newCompleted += 1 }, null, onRetired = { newRetired += 1 })

        callbacks.forEach { it() }
        assertNull(registry.takeTerminalCallback("old", completed = true, notifyFailure = true))
        assertEquals(1, registry.size())
        registry.takeTerminalCallback("new", completed = true, notifyFailure = true)?.invoke()

        assertEquals(1, oldRetired)
        assertEquals(1, newCompleted)
        assertEquals(0, newRetired)
        assertTrue(registry.takeRetirementCallbacks().isEmpty())
    }

    @Test
    fun explicitCancellationDrainsWaitingOrDispatchedResponseAndLateDoneCannotStartInput() {
        for (initializing in listOf(true, false)) {
            val registry = UtteranceCallbackRegistry()
            var recordingStarts = 0
            var retired = 0
            registry.registerLatest("input", { recordingStarts += 1 }, {}, onRetired = { retired += 1 })
            assertEquals(if (initializing) CommandSpeechReadiness.WAITING else CommandSpeechReadiness.DISPATCH,
                registry.commandSpeechReadiness("input", true, initializing, 0L, 1_000L))

            val callbacks = registry.takeRetirementCallbacks()
            registry.clear()
            callbacks.forEach { it() }

            assertEquals(1, retired)
            assertEquals(0, recordingStarts)
            assertEquals(0, registry.size())
            assertNull(registry.takeTerminalCallback("input", completed = true, notifyFailure = true))
            assertTrue(registry.takeRetirementCallbacks().isEmpty())
        }
    }

    @Test
    fun defaultRetirementDoesNotReplayCompletionFailureOrCancellationFallbacks() {
        val registry = UtteranceCallbackRegistry()
        var notifications = 0
        registry.registerLatest("legacy", { notifications += 1 }, { notifications += 1 })

        assertTrue(registry.takeRetirementCallbacks().isEmpty())
        assertEquals(0, registry.size())
        assertNull(registry.takeTerminalCallback("legacy", completed = false, notifyFailure = true))
        assertEquals(0, notifications)
    }

    @Test
    fun registerLatestAndClearRemainSilentUnlessTheCallerDrainsFirst() {
        val registry = UtteranceCallbackRegistry()
        var retired = 0
        registry.registerLatest("old", null, null, onRetired = { retired += 1 })
        registry.registerLatest("new", null, null, onRetired = { retired += 1 })
        assertEquals(0, retired)
        assertEquals(1, registry.size())

        registry.clear()

        assertTrue(registry.takeRetirementCallbacks().isEmpty())
        assertEquals(0, retired)
    }

    @Test
    fun completedOrFailedResponseCannotBeRetiredAgain() {
        for (completed in listOf(true, false)) {
            val registry = UtteranceCallbackRegistry()
            var terminal = 0
            var retired = 0
            registry.registerLatest("response", { terminal += 1 }, { terminal += 1 },
                onRetired = { retired += 1 })

            registry.takeTerminalCallback("response", completed, notifyFailure = true)?.invoke()

            assertEquals(1, terminal)
            assertTrue(registry.takeRetirementCallbacks().isEmpty())
            assertEquals(0, retired)
        }
    }

}
