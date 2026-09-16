package kr.co.hanium.dreamup.walksafe.feedback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEndpointPolicyTest {
    @Test fun aShortPauseDoesNotSplitDestinationCommand() {
        val endpoint = SpeechEndpointPolicy(1000)
        assertFalse(endpoint.shouldStop(1400, 1200))
        assertFalse(endpoint.shouldStop(2200, 0))
        assertFalse(endpoint.shouldStop(2400, 1100))
        assertFalse(endpoint.shouldStop(3600, 0))
        assertTrue(endpoint.shouldStop(3900, 0))
    }
    @Test fun silentMicrophoneDoesNotRecordIndefinitely() {
        val endpoint = SpeechEndpointPolicy(1000)
        assertFalse(endpoint.shouldStop(5000, 0))
        assertTrue(endpoint.shouldStop(8100, 0))
    }
    @Test fun continuousOutdoorNoiseStillHasAnUpperLimit() {
        val endpoint = SpeechEndpointPolicy(1000)
        for (time in 1100L..12900L step 100L) assertFalse(endpoint.shouldStop(time, 1500))
        assertTrue(endpoint.shouldStop(13000, 1500))
    }
    @Test fun invalidClockStopsCapture() {
        assertTrue(SpeechEndpointPolicy(1000).shouldStop(999, 0))
    }
}
