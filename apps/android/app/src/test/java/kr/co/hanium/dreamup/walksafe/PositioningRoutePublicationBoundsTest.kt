package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningSessionController
import org.junit.Assert.*
import org.junit.Test

/** Actual production predicate. This does not invoke a platform location callback. */
class PositioningRoutePublicationBoundsTest {
    private val session = MainActivityControllerTestFixture.controller(MainActivity(), "positioningSessionController") as PositioningSessionController
    private fun decision(quality: PositionQuality = PositionQuality.LOW,
        failure: PositionConfidenceFailureReason? = null) = PositionConfidenceDecision(
        quality, quality, PositionConfidenceTransition.STABLE, false, null, 0, failureReason = failure)

    @Test fun ordinaryGpsAndFilteredLowPrecisionAreAvailableWithoutRelabelling() {
        for (meters in listOf(8.0, 10.0, 15.0)) {
            assertTrue(session.mayPublishPositioningRouteFix(PositionQuality.LOW, decision(), meters, meters))
            assertEquals(PositionQuality.LOW, decision().quality)
        }
    }
    @Test fun missingRawOrFilteredEvidenceCannotPublish() {
        assertFalse(session.mayPublishPositioningRouteFix(PositionQuality.LOW, decision(), null, 10.0))
        assertFalse(session.mayPublishPositioningRouteFix(PositionQuality.LOW, decision(), 10.0, null))
    }
    @Test fun nonfiniteNegativeAndBeyondFifteenMeterInputsStayRejected() {
        for (bad in listOf(-0.001, 15.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(session.mayPublishPositioningRouteFix(PositionQuality.LOW, decision(), bad, 10.0))
            assertFalse(session.mayPublishPositioningRouteFix(PositionQuality.LOW, decision(), 10.0, bad))
        }
    }
    @Test fun aPrecisionLabelCannotHideUnusableRawData() {
        for (quality in listOf(PositionQuality.HIGH, PositionQuality.MEDIUM)) {
            assertFalse(session.mayPublishPositioningRouteFix(quality, decision(quality), 2.0, 20.0))
        }
    }
    @Test fun unavailableAndTimeFailuresCannotPublish() {
        assertFalse(session.mayPublishPositioningRouteFix(PositionQuality.UNAVAILABLE,
            decision(PositionQuality.UNAVAILABLE), 10.0, 10.0))
        for (failure in PositionConfidenceFailureReason.entries) {
            assertFalse(session.mayPublishPositioningRouteFix(PositionQuality.LOW, decision(failure = failure), 10.0, 10.0))
        }
    }
}
