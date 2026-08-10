package kr.co.hanium.dreamup.walksafe.network

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayActivityCallbackPolicyTest {
    @Test
    fun rotationAndDestroyDropOldViewAndSpeechCallbacks() {
        val viewMutations = AtomicInteger()
        val speechCalls = AtomicInteger()
        val callback = {
            viewMutations.incrementAndGet()
            speechCalls.incrementAndGet()
            Unit
        }

        assertFalse(
            runGatewayActivityCallbackIfCurrent(
                activityDestroyed = false,
                expectedLeaseIsCurrent = false,
                callback = callback,
            ),
        )
        assertFalse(
            runGatewayActivityCallbackIfCurrent(
                activityDestroyed = true,
                expectedLeaseIsCurrent = true,
                callback = callback,
            ),
        )
        assertEquals(0, viewMutations.get())
        assertEquals(0, speechCalls.get())

        assertTrue(
            runGatewayActivityCallbackIfCurrent(
                activityDestroyed = false,
                expectedLeaseIsCurrent = true,
                callback = callback,
            ),
        )
        assertEquals(1, viewMutations.get())
        assertEquals(1, speechCalls.get())
    }
}
