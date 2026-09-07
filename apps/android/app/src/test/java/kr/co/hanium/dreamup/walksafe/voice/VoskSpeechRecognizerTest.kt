package kr.co.hanium.dreamup.walksafe.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskSpeechRecognizerTest {
    @Test
    fun aCompletedButtonRequestAllowsAnotherRequestAndOnlyOneTerminalResult() {
        val requests = VoskSpeechRequestFence()
        val first = checkNotNull(requests.begin())

        assertNull(requests.begin())
        assertTrue(requests.finish(first))
        assertFalse(requests.finish(first))
        val second = checkNotNull(requests.begin())
        assertNotEquals(first, second)
        assertTrue(requests.accepts(second))
        assertFalse(requests.accepts(first))
    }

    @Test
    fun cancelRejectsQueuedResultsAndTimeoutsWithoutConsumingTheNextRequest() {
        val requests = VoskSpeechRequestFence()
        val cancelled = checkNotNull(requests.begin())

        requests.cancel()
        val replacement = checkNotNull(requests.begin())

        assertFalse(requests.accepts(cancelled))
        assertFalse(requests.finish(cancelled))
        assertTrue(requests.accepts(replacement))
        assertTrue(requests.finish(replacement))
    }

    @Test
    fun timeoutOrEngineErrorRetiresOnlyThatRequestAndNeverStartsAnother() {
        val requests = VoskSpeechRequestFence()
        val timedOut = checkNotNull(requests.begin())

        assertTrue(requests.finish(timedOut))
        assertFalse(requests.accepts(timedOut))
        assertFalse(requests.finish(timedOut))
        assertTrue(requests.accepts(checkNotNull(requests.begin())))
    }
}
