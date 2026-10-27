package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S09-36 (originals/09.txt:96): the activity no longer clears the trusted location for every hard
 * rejection. A measured quality failure retains only fresh, source-owned coordinates during the
 * existing bounded GPS recheck; other rejection paths still clear and pause.
 */
class MainActivityPositioningRejectStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun onlyAMeasuredQualityFailureWithFreshSourceOwnedFixesBypassesTheClearPath() {
        val handler = NavigationPolicySource.observation(source)
        val outlier = handler.indexOf(
            "gps().retainsPublishedPositionDuringGpsFailure(positioningSnapshot, elapsedRealtimeMs())",
        )
        val failClosed = handler.indexOf("if (hardRejected || filtered == null) {\n            clearTrustedLocation()")
        assertTrue(outlier in 0 until failClosed)
        val branch = handler.substring(outlier, failClosed)
        assertTrue(handler.substring(0, outlier).trimEnd().endsWith("if (hardRejected && navigationTestFix == null &&"))
        assertTrue(branch.contains("applyPositionConfidenceDecision(positioningSnapshot,"))
        assertTrue(branch.contains("            return\n"))
        assertFalse(branch.contains("clearTrustedLocation()"))
        assertFalse(branch.contains("pauseDirectionGuidance("))
        assertFalse(branch.contains("updateRouteGuidance("))
        assertFalse(branch.contains("latestTrustedLocation ="))
        MainActivityRefactorSource.composition(source, "continuousGpsSession")
        val gps = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/ContinuousGpsSession.kt").readText()
        val gate = MainActivityRefactorSource.member(gps, "retainsPublishedPositionDuringGpsFailure")
        assertTrue(gate.contains("snapshot.gnssUpdate?.disposition == GnssObservationDisposition.HARD_REJECTED"))
        assertTrue(gate.contains("snapshot.gnssUpdate.hardRejectReason.measuredQualityFailure() != null"))
        assertTrue(gate.contains("allowMissingAccuracyForWarming: Boolean = false"))
        assertTrue(gate.contains("if (source != this.source.current()) return false"))
        assertTrue(gate.contains("runtimeGpsQualityGate.current(nowMs)?.status != EnvironmentEvidenceStatus.PASS"))
        assertTrue(gate.contains("runtimeGpsQualityGate.pendingFailureDeadlineElapsedRealtimeMs() == null"))
        assertTrue(gate.contains("listOf(this.source.latestTrusted, this.source.latestRaw).all"))
        assertTrue(gate.contains("location.accuracyM in 0f..15f"))
        assertTrue(gate.contains("LocationTrustPolicy.freshOrNull(location, nowMs) != null"))
        val qualityFailures = gps.substringAfter("private fun GnssHardRejectReason?.measuredQualityFailure()")
            .substringBefore("/** Called only after")
        listOf("INVALID_ACCURACY", "NUMERICAL_FAILURE", "CORRUPT_JUMP").forEach {
            assertTrue(qualityFailures.contains("GnssHardRejectReason.$it ->"))
        }
        assertTrue(qualityFailures.contains("else -> null"))
    }
}
