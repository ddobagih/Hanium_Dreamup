package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S09-36 (originals/09.txt:96): the activity no longer clears the trusted location for every hard
 * rejection. Only a single outlier against a fresh estimate is kept; every other rejection keeps the
 * existing fail-closed path (clear, pause).
 */
class MainActivityPositioningRejectStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun onlyASingleOutlierAgainstAFreshEstimateBypassesTheClearPath() {
        val handler = source.substringAfter("private fun handleLocationUpdate(")
        val outlier = handler.indexOf(
            "if (hardRejected && navigationTestFix == null && positioningSnapshot.singleOutlierAgainstFreshEstimate) {",
        )
        val failClosed = handler.indexOf("if (hardRejected || filtered == null) {\n            clearTrustedLocation()")
        assertTrue(outlier in 0 until failClosed)
        val branch = handler.substring(outlier, failClosed)
        assertTrue(branch.contains("applyPositionConfidenceDecision(positioningSnapshot)"))
        assertTrue(branch.contains("            return\n"))
        assertFalse(branch.contains("clearTrustedLocation()"))
        assertFalse(branch.contains("pauseDirectionGuidance("))
        assertFalse(branch.contains("updateRouteGuidance("))
        assertFalse(branch.contains("latestTrustedLocation ="))
    }
}
