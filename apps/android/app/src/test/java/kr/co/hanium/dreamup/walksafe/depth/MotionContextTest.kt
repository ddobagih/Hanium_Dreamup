package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionContextTest {
    @Test
    fun routeBearingAlignmentIsNeutralWhenUnknown() {
        assertEquals(1f, routeBearingAlignmentQuality(null, 90f), 0.001f)
        assertEquals(1f, routeBearingAlignmentQuality(90f, null), 0.001f)
    }

    @Test
    fun routeBearingAlignmentHandlesWrapAround() {
        assertEquals(1f, routeBearingAlignmentQuality(350f, 10f), 0.001f)
        assertEquals(0.35f, routeBearingAlignmentQuality(0f, 180f), 0.001f)
    }

    @Test
    fun safeMotionQualityUsesRouteAlignmentAsAuxiliaryGate() {
        val context = MotionContext(motionQuality = 0.9f, routeAlignmentQuality = 0.35f)

        assertEquals(0.35f, context.safeMotionQuality, 0.001f)
    }
}
