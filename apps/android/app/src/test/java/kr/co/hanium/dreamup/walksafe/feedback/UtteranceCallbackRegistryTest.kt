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
}
