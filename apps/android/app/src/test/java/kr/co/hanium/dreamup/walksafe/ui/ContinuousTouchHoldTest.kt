package kr.co.hanium.dreamup.walksafe.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousTouchHoldTest {
    @Test fun requiresFullThreeSeconds() {
        val hold = ContinuousTouchHold()
        assertFalse(hold.ready(9000))
        hold.begin(1000)
        assertFalse(hold.ready(3999))
        assertTrue(hold.ready(4000))
    }
    @Test fun interruptedOrRepeatedShortTouchesCannotUnlock() {
        val hold = ContinuousTouchHold()
        hold.begin(1000)
        hold.cancel()
        assertFalse(hold.ready(9000))
        hold.begin(10000)
        assertFalse(hold.ready(12999))
        assertTrue(hold.ready(13000))
    }
    @Test fun clockBeforeStartCannotUnlock() {
        val hold = ContinuousTouchHold()
        hold.begin(1000)
        assertFalse(hold.ready(999))
    }
}
