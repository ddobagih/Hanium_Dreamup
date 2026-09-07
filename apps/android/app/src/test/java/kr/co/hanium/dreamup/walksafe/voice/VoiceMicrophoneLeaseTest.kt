package kr.co.hanium.dreamup.walksafe.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMicrophoneLeaseTest {
    @Test
    fun buttonAndHandsFreeCannotOwnCaptureTogether() {
        val lease = VoiceMicrophoneLease()
        val button = Any()
        val handsFree = Any()

        assertTrue(lease.acquire(button))
        assertFalse(lease.acquire(button))
        assertFalse(lease.acquire(handsFree))
        lease.release(button)
        assertTrue(lease.acquire(handsFree))
    }

    @Test
    fun lateReleaseFromPreviousCaptureCannotReleaseTheCurrentMicrophone() {
        val lease = VoiceMicrophoneLease()
        val previous = Any()
        val current = Any()

        assertTrue(lease.acquire(previous))
        lease.release(previous)
        assertTrue(lease.acquire(current))
        lease.release(previous)
        assertFalse(lease.acquire(Any()))
        lease.release(current)
        assertTrue(lease.acquire(Any()))
    }
}
