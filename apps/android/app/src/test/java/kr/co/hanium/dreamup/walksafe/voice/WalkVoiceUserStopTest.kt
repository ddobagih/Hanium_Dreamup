package kr.co.hanium.dreamup.walksafe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S04-30: the notification's "음성 입력 중지" reaches the owner and survives refresh/re-entry for that walk. */
class WalkVoiceUserStopTest {
    private class Controller : WalkVoiceSessionController {
        override fun start(): Boolean = true
        override fun stop() = Unit
    }

    @Test
    fun userStopReachesTheOwnerOnlyForTheAttachedController() {
        val relay = WalkVoiceUserStopRelay()
        val attached = Controller()
        val stale = Controller()
        val delivered = mutableListOf<WalkVoiceSessionController>()
        relay.setListener { delivered += it }
        assertFalse(relay.report(stale, attached))
        assertFalse(relay.report(null, attached))
        assertTrue(relay.report(attached, attached))
        assertEquals(listOf<WalkVoiceSessionController>(attached), delivered)
        relay.setListener(null)
        assertFalse("no owner, nothing delivered", relay.report(attached, attached))
    }

    @Test
    fun aFailingOwnerDoesNotStopTheServiceFromStopping() {
        val relay = WalkVoiceUserStopRelay()
        val attached = Controller()
        relay.setListener { error("owner failed") }
        assertTrue(relay.report(attached, attached))
    }

    @Test
    fun theHoldSurvivesRefreshesAndReentryOfTheSameWalkAndANewWalkReleasesIt() {
        val hold = WalkVoiceUserStopHold()
        assertFalse(hold.blocksAutomaticStart("walk-A"))
        hold.recordUserStop("walk-A")
        repeat(3) { assertTrue("refresh/onResume/recreation #$it", hold.blocksAutomaticStart("walk-A")) }
        assertFalse("a new walk id releases the hold", hold.blocksAutomaticStart("walk-B"))
        assertFalse("released for good", hold.blocksAutomaticStart("walk-A"))
    }
}
