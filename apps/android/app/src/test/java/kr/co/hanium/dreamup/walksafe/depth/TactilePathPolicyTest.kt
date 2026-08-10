package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TactilePathPolicyTest {
    @Suppress("DEPRECATION")
    @Test
    fun depthOnlyCompatibilityPolicyCannotAuthorizeTactileGuidance() {
        val geometry = ObjectGeometry(
            className = "normal_tactile_block",
            detectionConfidence = 0.99f,
            bboxNorm = RectNorm(0.3f, 0.4f, 0.4f, 0.4f),
            polygonNorm = emptyList(),
            maskAreaNorm = 0.16f,
            centerNorm = Point2(0.5f, 0.6f),
            bottomContactNorm = null,
        )

        val guidance = TactilePathPolicy().buildGuidance(geometry)

        assertEquals(MessageLevel.NONE, guidance.messageLevel)
        assertNull(guidance.message)
    }
}
