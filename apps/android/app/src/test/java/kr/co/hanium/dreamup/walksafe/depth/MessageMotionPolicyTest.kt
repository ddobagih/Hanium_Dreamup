package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMotionPolicyTest {
    @Test
    fun omittedMotionEstimatePreservesRelativeApproachGuidance() {
        val result = MetricDepthDecision(
            className = "person",
            source = DepthSource.ARCORE_RAW_DEPTH,
            riskDistanceM = 2f,
            trend = Trend.APPROACHING,
            confidenceFinal = 0.65f,
        )

        val decision = evaluate(result)

        assertEquals(MessageLevel.WARNING, decision.userFacing.messageLevel)
        assertTrue(decision.userFacing.message!!.contains("거리가 줄어들고 있습니다"))
        assertFalse(decision.userFacing.message!!.contains("다가오는"))
        assertFalse(decision.userFacing.message!!.contains("정지해"))
    }

    @Test
    fun lateralMovementNamesCameraSideWithoutClaimingObjectApproach() {
        val sides = listOf(
            ObjectMovementDirection.LEFT to "왼쪽",
            ObjectMovementDirection.RIGHT to "오른쪽",
        )
        for ((direction, side) in sides) {
            for (trend in listOf(Trend.STABLE, Trend.APPROACHING)) {
                val decision = evaluate(obstacle(direction).copy(trend = trend))
                val message = decision.userFacing.message!!

                assertEquals(MessageLevel.WARNING, decision.userFacing.messageLevel)
                assertEquals(MessagePurpose.OBSTACLE_WARNING, decision.purpose)
                assertTrue(message, message.contains("카메라 기준 ${side}으로 이동하는 것으로 보입니다"))
                assertFalse(message, message.contains("사용자 쪽으로 다가오는"))
                assertFalse(message, message.contains("정지해 있는"))
                assertFalse(message, message.contains(if (side == "왼쪽") "오른쪽" else "왼쪽"))
                assertTrue(message, message.endsWith("멈출 준비를 하세요."))
            }
        }
    }

    @Test
    fun objectApproachingUserUsesObjectMotionExplanation() {
        val decision = evaluate(
            obstacle(ObjectMovementDirection.TOWARD_USER).copy(
                trend = Trend.APPROACHING,
                objectMotion = ObjectMotion.OBJECT_APPROACHING,
            ),
        )

        val message = decision.userFacing.message!!
        assertTrue(message, message.contains("이 물체가 사용자 쪽으로 다가오는 것으로 보입니다"))
        assertFalse(message, message.contains("현재 이 물체에 가까워지고"))
        assertFalse(message, message.contains("정지해 있는"))
    }

    @Test
    fun userApproachingStationaryObjectDoesNotAttributeMotionToObject() {
        val decision = evaluate(
            obstacle(ObjectMovementDirection.STATIONARY).copy(
                trend = Trend.APPROACHING,
                objectMotion = ObjectMotion.USER_APPROACHING_STATIONARY,
            ),
        )

        val message = decision.userFacing.message!!
        assertTrue(message, message.contains("정지해 있는 것으로 보이며"))
        assertTrue(message, message.contains("현재 이 물체에 가까워지고 있습니다"))
        assertFalse(message, message.contains("사용자 쪽으로 다가오는"))
        assertFalse(message, message.contains("이동하는 것으로"))
    }

    @Test
    fun relativeClosingSpeedWithoutObjectDirectionDoesNotClaimObjectApproach() {
        val decision = evaluate(
            obstacle().copy(
                trend = Trend.APPROACHING,
                motionEstimate = ObjectMotionEstimate(relativeClosingSpeedMps = 0.6f, confidence = 0.95f),
            ),
        )

        val message = decision.userFacing.message!!
        assertTrue(message, message.contains("거리가 줄어들고 있습니다"))
        assertFalse(message, message.contains("다가오는"))
        assertFalse(message, message.contains("정지해"))
        assertFalse(message, message.contains("카메라 기준"))
    }

    @Test
    fun recedingObjectUsesAwayPhraseWhileNearbyObstacleWarningRemains() {
        val decision = evaluate(obstacle(ObjectMovementDirection.AWAY_FROM_USER).copy(trend = Trend.RECEDING))

        assertEquals(MessageLevel.WARNING, decision.userFacing.messageLevel)
        assertEquals(MessagePurpose.OBSTACLE_WARNING, decision.purpose)
        assertTrue(decision.userFacing.message!!.contains("사용자에게서 멀어지는 방향으로 이동하는 것으로 보입니다"))
        assertFalse(decision.userFacing.message!!.contains("다가오는"))
        assertTrue(decision.userFacing.message!!.endsWith("멈출 준비를 하세요."))
    }

    @Test
    fun supportedDirectionsUseExplicitClaimsWhileUnknownAndOtherKeepBaseline() {
        val baseline = evaluate(obstacle()).userFacing
        for (direction in listOf(
            ObjectMovementDirection.UNKNOWN,
            ObjectMovementDirection.OTHER,
        )) {
            assertEquals(direction.name, baseline, evaluate(obstacle(direction)).userFacing)
        }
        assertTrue(
            evaluate(obstacle(ObjectMovementDirection.STATIONARY)).userFacing.message!!
                .contains("정지해 있는 것으로 보입니다."),
        )
        assertTrue(
            evaluate(obstacle(ObjectMovementDirection.TOWARD_USER)).userFacing.message!!
                .contains("이 물체가 사용자 쪽으로 이동하는 것으로 보입니다."),
        )
    }

    @Test
    fun allDirectionsPreserveInclusiveStopAndWarningDistanceBoundaries() {
        val cases = listOf(
            1.2f to MessageLevel.STOP,
            1.2001f to MessageLevel.WARNING,
            2.5f to MessageLevel.WARNING,
            2.5001f to MessageLevel.NONE,
        )
        for (direction in ObjectMovementDirection.values()) {
            for ((distance, expected) in cases) {
                val decision = evaluate(obstacle(direction).copy(riskDistanceM = distance))
                assertEquals("$direction at $distance m", expected, decision.userFacing.messageLevel)
                assertNoSpokenDistanceOrStepCount(decision.userFacing)
                if (expected == MessageLevel.NONE) {
                    assertNull(decision.userFacing.message)
                } else {
                    val action = if (expected == MessageLevel.STOP) {
                        "멈추세요. 주변을 확인하세요."
                    } else {
                        "멈출 준비를 하세요."
                    }
                    assertTrue(decision.userFacing.message!!.endsWith(action))
                }
            }
        }
    }

    @Test
    fun allDirectionsPreserveConfidenceBoundaryForStopWithinWarningDistance() {
        for (direction in ObjectMovementDirection.values()) {
            val result = obstacle(direction).copy(riskDistanceM = 2.5f)
            assertEquals(
                direction.name,
                MessageLevel.WARNING,
                evaluate(result.copy(confidenceFinal = 0.7499f)).userFacing.messageLevel,
            )
            assertEquals(
                direction.name,
                MessageLevel.STOP,
                evaluate(result.copy(confidenceFinal = 0.75f)).userFacing.messageLevel,
            )
        }
    }

    @Test
    fun allDirectionsPreserveInclusiveTtcBoundariesAndSilentAwareLevel() {
        val cases = listOf(
            3_000L to MessageLevel.STOP,
            3_001L to MessageLevel.WARNING,
            10_000L to MessageLevel.WARNING,
            10_001L to MessageLevel.AWARE,
            30_000L to MessageLevel.AWARE,
            30_001L to MessageLevel.NONE,
        )
        for (direction in ObjectMovementDirection.values()) {
            for ((ttcMs, expected) in cases) {
                val decision = evaluate(
                    obstacle(direction).copy(
                        riskDistanceM = 4f,
                        trend = Trend.APPROACHING,
                        timeToCollisionMs = ttcMs,
                    ),
                )
                assertEquals("$direction at TTC $ttcMs", expected, decision.userFacing.messageLevel)
                if (expected == MessageLevel.AWARE || expected == MessageLevel.NONE) {
                    assertEquals(MessagePurpose.NONE, decision.purpose)
                    assertNull(decision.userFacing.message)
                } else {
                    assertEquals(MessagePurpose.OBSTACLE_WARNING, decision.purpose)
                    assertNotNull(decision.userFacing.message)
                }
            }
        }
    }

    @Test
    fun allDirectionsPreserveApproachingDistanceBoundaryWithoutTtc() {
        for (direction in ObjectMovementDirection.values()) {
            val result = obstacle(direction).copy(trend = Trend.APPROACHING)
            assertEquals(
                direction.name,
                MessageLevel.WARNING,
                evaluate(result.copy(riskDistanceM = 3f)).userFacing.messageLevel,
            )
            assertEquals(
                direction.name,
                MessageLevel.NONE,
                evaluate(result.copy(riskDistanceM = 3.0001f)).userFacing.messageLevel,
            )
        }
    }

    @Test
    fun movementDirectionAndTtcDoNotReplaceApproachingTrendRequirement() {
        for (direction in ObjectMovementDirection.values()) {
            val decision = evaluate(obstacle(direction).copy(riskDistanceM = 4f, timeToCollisionMs = 1_000L))

            assertEquals(direction.name, MessageLevel.NONE, decision.userFacing.messageLevel)
            assertNull(decision.userFacing.message)
        }
    }

    @Test
    fun confidentMotionCannotBypassSourceSpecificMetricConfidenceThresholds() {
        val thresholds = listOf(
            DepthSource.ARCORE_RAW_DEPTH to 0.55f,
            DepthSource.ARCORE_FULL_DEPTH to 0.60f,
            DepthSource.WEBXR_DEPTH to 0.62f,
        )
        for ((source, threshold) in thresholds) {
            val result = obstacle(ObjectMovementDirection.LEFT).copy(source = source)
            val weak = evaluate(result.copy(confidenceFinal = threshold - 0.0001f))
            val admitted = evaluate(result.copy(confidenceFinal = threshold))

            assertEquals(source.name, MessageLevel.CAUTION, weak.userFacing.messageLevel)
            assertEquals(MessagePurpose.APPROACH_CAUTION, weak.purpose)
            assertNull(weak.userFacing.stepsAhead)
            assertFalse(weak.userFacing.message!!.contains("카메라 기준"))
            assertEquals(source.name, MessageLevel.WARNING, admitted.userFacing.messageLevel)
            assertNoSpokenDistanceOrStepCount(weak.userFacing)
            assertNoSpokenDistanceOrStepCount(admitted.userFacing)
            assertTrue(admitted.userFacing.message!!.contains("카메라 기준 왼쪽"))
            assertTrue(admitted.userFacing.message!!.endsWith("멈출 준비를 하세요."))
        }
    }

    @Test
    fun confidentMotionAndShortTtcCannotBypassWeakMetricConfidenceFloor() {
        val result = obstacle(ObjectMovementDirection.TOWARD_USER).copy(
            riskDistanceM = 1f,
            trend = Trend.APPROACHING,
            timeToCollisionMs = 1_000L,
            objectMotion = ObjectMotion.OBJECT_APPROACHING,
        )
        val below = evaluate(result.copy(confidenceFinal = 0.3499f))
        val atFloor = evaluate(result.copy(confidenceFinal = 0.35f))

        assertEquals(MessageLevel.NONE, below.userFacing.messageLevel)
        assertNull(below.userFacing.message)
        assertEquals(MessageLevel.CAUTION, atFloor.userFacing.messageLevel)
        assertNull(atFloor.userFacing.stepsAhead)
        assertFalse(atFloor.userFacing.message!!.contains("다가오는"))
        assertFalse(atFloor.userFacing.message!!.contains("멈추세요"))
    }

    @Test
    fun pseudoDepthKeepsRelativeCautionAndConfidenceFloorDespiteMetricMotionEvidence() {
        val result = obstacle(ObjectMovementDirection.TOWARD_USER).copy(
            source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
            riskDistanceM = 1f,
            trend = Trend.APPROACHING,
            timeToCollisionMs = 1_000L,
            objectMotion = ObjectMotion.OBJECT_APPROACHING,
        )
        val below = evaluate(result.copy(confidenceFinal = 0.3499f))
        val atFloor = evaluate(result.copy(confidenceFinal = 0.35f))

        assertEquals(MessageLevel.NONE, below.userFacing.messageLevel)
        assertNull(below.userFacing.message)
        assertEquals(MessageLevel.CAUTION, atFloor.userFacing.messageLevel)
        assertEquals(MessagePurpose.APPROACH_CAUTION, atFloor.purpose)
        assertNull(atFloor.userFacing.stepsAhead)
        assertTrue(atFloor.userFacing.message!!.contains("거리가 줄어드는 것 같습니다"))
        assertFalse(atFloor.userFacing.message!!.contains("다가오는"))
        assertFalse(atFloor.userFacing.message!!.contains("멈추세요"))
    }

    @Test
    fun confidentMotionDoesNotMakeMonocularMetricDepthTrustedForSteps() {
        for (direction in ObjectMovementDirection.values()) {
            val decision = evaluate(
                obstacle(direction).copy(
                    source = DepthSource.MONOCULAR_METRIC_DEPTH,
                    riskDistanceM = 1f,
                    confidenceFinal = 0.99f,
                    trend = Trend.APPROACHING,
                    timeToCollisionMs = 1_000L,
                ),
            )

            assertEquals(direction.name, MessageLevel.NONE, decision.userFacing.messageLevel)
            assertNull(decision.userFacing.stepsAhead)
            assertNull(decision.userFacing.message)
        }
    }

    @Test
    fun invalidMetricDistanceCannotBecomeWarningFromMotionOrTtc() {
        for (distance in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val decision = evaluate(
                obstacle(ObjectMovementDirection.TOWARD_USER).copy(
                    riskDistanceM = distance,
                    confidenceFinal = 0.99f,
                    trend = Trend.APPROACHING,
                    timeToCollisionMs = 1_000L,
                    objectMotion = ObjectMotion.OBJECT_APPROACHING,
                ),
            )

            assertEquals("distance=$distance", MessageLevel.NONE, decision.userFacing.messageLevel)
            assertNull(decision.userFacing.stepsAhead)
            assertNull(decision.userFacing.message)
        }
    }

    @Test
    fun directionChangeOnSameTrackDoesNotBypassSpeechRateLimit() {
        val policy = MessagePolicy()
        val left = obstacle(ObjectMovementDirection.LEFT)
        val right = obstacle(ObjectMovementDirection.RIGHT)

        val first = policy.evaluate(left, nowMs = 0L)
        val suppressed = policy.evaluate(right, nowMs = 2_499L)
        val allowed = policy.evaluate(
            right.copy(motionEstimate = right.motionEstimate.copy(observedAtMs = 2_500L)),
            nowMs = 2_500L,
        )

        assertTrue(first.userFacing.message!!.contains("왼쪽"))
        assertEquals(MessageLevel.WARNING, suppressed.userFacing.messageLevel)
        assertNull(suppressed.userFacing.message)
        assertTrue(suppressed.reason.startsWith("rate limited"))
        assertTrue(allowed.userFacing.message!!.contains("오른쪽"))
    }

    private fun assertNoSpokenDistanceOrStepCount(userFacing: UserFacingDepth) {
        // Obstacle alerts retain action and measured motion; route distance guidance is separate.
        assertNull(userFacing.stepsAhead)
        userFacing.message?.let { message ->
            assertFalse(message, Regex("""\d+(?:\.\d+)?\s*(?:보|걸음|m|미터)""").containsMatchIn(message))
        }
    }

    private fun evaluate(result: MetricDepthDecision): MessagePolicyDecision {
        // Boundary cases start without a previous warning or speech timestamp.
        return MessagePolicy().evaluate(result, nowMs = 0L)
    }

    private fun obstacle(direction: ObjectMovementDirection = ObjectMovementDirection.UNKNOWN): MetricDepthDecision {
        return MetricDepthDecision(
            className = "person",
            source = DepthSource.ARCORE_RAW_DEPTH,
            riskDistanceM = 2f,
            trend = Trend.STABLE,
            confidenceFinal = 0.65f,
            trackKey = "person-motion",
            motionEstimate = ObjectMotionEstimate(
                referenceId = 1L,
                observedAtMs = 0L,
                elapsedMs = 1_000L,
                objectVelocityInAnchorMps = Vec3(0f, 0f, -0.6f),
                relativeVelocityInAnchorMps = Vec3(0f, 0f, -0.6f),
                cameraVelocityInAnchorMps = Vec3(0f, 0f, 0f),
                objectSpeedMps = 0.6f,
                relativeClosingSpeedMps = 0.6f,
                direction = direction,
                confidence = 0.95f,
            ),
        )
    }
}
