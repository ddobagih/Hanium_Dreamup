package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PdrStepQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateEstimator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMovementState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 종합분석 6.7-5: 소비 기능은 각자 만료를 검사하고 공통 상태의 stateSince를 신선도로 쓰지 않는다.
 */
class MainActivityMotionConsumerExpiryStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun stateSinceStaysAtTheFirstApprovalSoItCannotServeAsMovingFreshness() {
        val estimator = UserMotionStateEstimator()
        for (at in 1_000L..6_000L step 500L) {
            estimator.observeSteps(1, PdrStepQuality.HIGH, at, at)
            estimator.observeSpeed(WalkingSpeedObservation(1.2, 0.1, at), UserMotionLocationEvidence(at, 2.0, false), at)
            // Main publishes a snapshot on every callback.
            assertEquals(UserMovementState.MOVING, estimator.snapshot(at).movement)
        }
        val walking = estimator.snapshot(6_000L)
        assertEquals(UserMovementState.MOVING, walking.movement)
        // Five seconds of continuous walking: stateSince is old, the evaluation and evidence are fresh.
        assertTrue(6_000L - requireNotNull(walking.stateSinceElapsedRealtimeMs) > 2_000L)
        assertEquals(6_000L, walking.evaluatedAtElapsedRealtimeMs)
        assertEquals(6_000L, walking.stepObservedAtElapsedRealtimeMs)
    }

    @Test
    fun walkingCalibrationGatesMovingEvidenceOnSnapshotExpiryNotStateSince() {
        val calibration = functionBlock("private fun attemptStepCalibration(")
        val gate = calibration.substringAfter("val freshMovingEvidence =").substringBefore("val disposition")
        assertFalse(gate.contains("latestPositionStationarySinceMs"))
        assertTrue(gate.contains("snapshot.userMotion") || calibration.contains("val userMotion = snapshot.userMotion"))
        assertTrue(gate.contains("UserMovementState.MOVING"))
        assertTrue(gate.contains("evaluatedAtElapsedRealtimeMs"))
        // GNSS-speed-only MOVING must not count as walking evidence.
        assertTrue(gate.contains("stepObservedAtElapsedRealtimeMs != null"))
        assertFalse(gate.contains("stateSinceElapsedRealtimeMs"))
    }

    @Test
    fun fieldCheckpointReevaluatesTheCurrentMotionBeforeUsingTheCachedState() {
        val checkpoint = functionBlock("private fun markPositionFieldCheckpoint(")
        val refresh = checkpoint.indexOf("applyUserMotionState(positioningCoordinator.currentUserMotion(nowMs))")
        val use = checkpoint.indexOf("latestPositionStationaryState != PositionStationaryState.STATIONARY")
        assertTrue(refresh >= 0 && use > refresh)
    }

    private fun functionBlock(marker: String): String {
        val markerIndex = source.indexOf(marker)
        require(markerIndex >= 0) { "missing function marker: $marker" }
        val openingBrace = source.indexOf('{', source.indexOf(')', markerIndex))
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(markerIndex, index + 1)
                }
            }
        }
        error("missing closing brace: $marker")
    }
}
