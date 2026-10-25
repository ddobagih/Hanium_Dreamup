package kr.co.hanium.dreamup.walksafe.voice
import org.junit.Assert.*
import org.junit.Test
class VoiceInputRouteGuardTest {
    @Test fun unknownRouteCanEstablishOnceButLossAndReturnStayInvalid() {
        val guard = VoiceInputRouteGuard()
        assertFalse(guard.observe(null)); assertFalse(guard.observe(7)); assertFalse(guard.observe(7))
        assertTrue(guard.observe(null)); assertTrue(guard.observe(7))
        assertFalse(VoiceInputRouteGuard().observe(7))
    }
    @Test fun differentInputInvalidatesButOutputDeviceIsNotAnInputObservation() {
        val guard = VoiceInputRouteGuard()
        assertFalse(guard.observe(7)); assertTrue(guard.observe(8)); assertTrue(guard.observe(7))
    }
}
