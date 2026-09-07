package kr.co.hanium.dreamup.walksafe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferredOfflineSpeechRequestTest {
    @Test
    fun installedPlatformWinsAndHoldsLeaseThroughBackendCleanup() {
        val lease = VoiceMicrophoneLease()
        val requests = PreferredOfflineSpeechRequest(lease)
        val id = checkNotNull(requests.begin())
        assertEquals(OfflineSpeechSelection.PLATFORM, requests.select(id, true, true, true))
        val other = Any()
        assertFalse(lease.acquire(other))
        assertTrue(requests.finish(id) { assertFalse(lease.acquire(other)) })
        assertTrue(lease.acquire(other))
    }

    @Test
    fun fallbackDoesNotPreAcquireTheLeaseAlreadyOwnedByItsPcmSource() {
        val lease = VoiceMicrophoneLease()
        val requests = PreferredOfflineSpeechRequest(lease)
        val id = checkNotNull(requests.begin())
        assertEquals(OfflineSpeechSelection.VOSK, requests.select(id, false, true, true))
        val pcmOwner = Any()
        assertTrue(lease.acquire(pcmOwner))
        assertTrue(requests.finish(id) { lease.release(pcmOwner) })
        assertTrue(lease.acquire(Any()))
    }

    @Test
    fun busyMicrophoneDoesNotSilentlySwitchToAnotherRecorder() {
        val lease = VoiceMicrophoneLease()
        val handsFree = Any()
        assertTrue(lease.acquire(handsFree))
        val requests = PreferredOfflineSpeechRequest(lease)
        val id = checkNotNull(requests.begin())
        assertEquals(OfflineSpeechSelection.BUSY, requests.select(id, true, true, true))
        assertTrue(requests.finish(id) {})
        assertFalse(lease.acquire(Any()))
        lease.release(handsFree)
        val next = checkNotNull(requests.begin())
        assertEquals(OfflineSpeechSelection.PLATFORM, requests.select(next, true, true, true))
    }

    @Test
    fun permissionRevokedDuringProbePreventsEitherEngineFromCapturing() {
        val lease = VoiceMicrophoneLease()
        val requests = PreferredOfflineSpeechRequest(lease)
        val id = checkNotNull(requests.begin())
        assertEquals(OfflineSpeechSelection.PERMISSION_DENIED, requests.select(id, true, true, false))
        assertTrue(requests.finish(id) {})
        assertTrue(lease.acquire(Any()))
        assertNotNull(requests.begin())
    }

    @Test
    fun unavailableEnginesDoNotPermanentlyBlockTheNextRequest() {
        val requests = PreferredOfflineSpeechRequest(VoiceMicrophoneLease())
        val id = checkNotNull(requests.begin())
        assertEquals(OfflineSpeechSelection.UNAVAILABLE, requests.select(id, false, false, true))
        assertTrue(requests.finish(id) {})
        val next = checkNotNull(requests.begin())
        assertEquals(OfflineSpeechSelection.VOSK, requests.select(next, false, true, true))
    }

    @Test
    fun cancellationDuringSupportProbeRejectsLateReadinessAndKeepsNewRequestOwned() {
        val lease = VoiceMicrophoneLease()
        val requests = PreferredOfflineSpeechRequest(lease)
        val cancelled = checkNotNull(requests.begin())
        requests.cancel {}
        val current = checkNotNull(requests.begin())
        assertEquals(OfflineSpeechSelection.PLATFORM, requests.select(current, true, true, true))
        assertEquals(OfflineSpeechSelection.STALE, requests.select(cancelled, true, true, true))
        var staleCleanup = false
        assertFalse(requests.finish(cancelled) { staleCleanup = true })
        assertFalse(staleCleanup)
        assertFalse(lease.acquire(Any()))
        assertTrue(requests.accepts(current, OfflineSpeechEngine.PLATFORM))
    }

    @Test
    fun resultErrorTimeoutAndUnknownCommandFeedbackFailureAllAllowSecondInput() {
        for (terminal in listOf("result", "error", "timeout", "unknown_command", "feedback_failure")) {
            val lease = VoiceMicrophoneLease()
            val requests = PreferredOfflineSpeechRequest(lease)
            val first = checkNotNull(requests.begin())
            assertEquals(OfflineSpeechSelection.PLATFORM, requests.select(first, true, true, true))
            var completions = 0
            assertTrue(terminal, requests.finish(first) { completions += 1 })
            assertFalse(terminal, requests.finish(first) { completions += 1 })
            assertEquals(terminal, 1, completions)
            val second = checkNotNull(requests.begin())
            assertEquals(terminal, OfflineSpeechSelection.PLATFORM, requests.select(second, true, true, true))
            assertTrue(terminal, requests.accepts(second))
        }
    }

    @Test
    fun endOfSpeechIsNotTerminalAndCannotUnlockMicrophone() {
        val lease = VoiceMicrophoneLease()
        val requests = PreferredOfflineSpeechRequest(lease)
        val id = checkNotNull(requests.begin())
        assertEquals(OfflineSpeechSelection.PLATFORM, requests.select(id, true, true, true))
        assertTrue(requests.accepts(id, OfflineSpeechEngine.PLATFORM))
        assertNull(requests.begin())
        assertFalse(lease.acquire(Any()))
        assertTrue(requests.finish(id) {})
        assertNotNull(requests.begin())
    }

    @Test
    fun backgroundCancellationReleasesCurrentLeaseWithoutClosingFutureRequests() {
        val lease = VoiceMicrophoneLease()
        val requests = PreferredOfflineSpeechRequest(lease)
        val id = checkNotNull(requests.begin())
        assertEquals(OfflineSpeechSelection.PLATFORM, requests.select(id, true, true, true))
        requests.cancel {}
        assertFalse(requests.accepts(id))
        val next = checkNotNull(requests.begin())
        assertEquals(OfflineSpeechSelection.PLATFORM, requests.select(next, true, true, true))
        requests.close {}
        assertFalse(requests.accepts(next))
        assertNull(requests.begin())
        assertTrue(lease.acquire(Any()))
    }

    @Test
    fun cleanupExceptionStillReleasesLeaseAndRetiresRequestExactlyOnce() {
        val lease = VoiceMicrophoneLease()
        val requests = PreferredOfflineSpeechRequest(lease)
        val id = checkNotNull(requests.begin())
        assertEquals(OfflineSpeechSelection.PLATFORM, requests.select(id, true, true, true))
        val failure = runCatching { requests.finish(id) { error("cleanup") } }
        assertTrue(failure.isFailure)
        assertFalse(requests.finish(id) {})
        assertTrue(lease.acquire(Any()))
    }
}
