package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.navigation.CROSSWALK_REFERENCE_NOTICE_KO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePolicyTest {
    @Test
    fun normalTactileBlockWaitsForTmapAlignedRoutePolicy() {
        val policy = MessagePolicy(stepLengthM = 0.6f)

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "normal_tactile_block",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 1.2f,
                trend = Trend.STABLE,
                confidenceFinal = 0.8f,
                trackKey = "tactile-1",
            ),
            nowMs = 0L,
        )

        assertEquals(MessagePurpose.NONE, decision.purpose)
        assertEquals(MessageLevel.NONE, decision.userFacing.messageLevel)
        assertNull(decision.userFacing.stepsAhead)
        assertNull(decision.userFacing.message)
    }

    @Test
    fun damagedTactileBlockIsReportOnlyWithoutUserWarning() {
        val policy = MessagePolicy()

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "damaged_tactile_block",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 0.8f,
                trend = Trend.STABLE,
                confidenceFinal = 0.9f,
                trackKey = "damage-1",
            ),
            nowMs = 0L,
        )

        assertEquals(MessagePurpose.REPORT_ONLY, decision.purpose)
        assertEquals(MessageLevel.NONE, decision.userFacing.messageLevel)
        assertNull(decision.userFacing.message)
    }

    @Test
    fun metricSourceThresholdDowngradesWeakMetricToCautionWithoutSteps() {
        val policy = MessagePolicy()

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "person",
                source = DepthSource.WEBXR_DEPTH,
                riskDistanceM = 1.0f,
                trend = Trend.STABLE,
                confidenceFinal = 0.58f,
                trackKey = "person-weak-webxr",
            ),
            nowMs = 0L,
        )

        assertEquals(MessagePurpose.APPROACH_CAUTION, decision.purpose)
        assertEquals(MessageLevel.CAUTION, decision.userFacing.messageLevel)
        assertNull(decision.userFacing.stepsAhead)
        assertTrue(
            decision.userFacing.message?.endsWith("속도를 늦추고 주변을 확인하세요.") == true,
        )
    }

    @Test
    fun pseudoApproachCautionUsesApprovedActionSuffix() {
        val policy = MessagePolicy()

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "person",
                source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
                riskDistanceM = null,
                trend = Trend.APPROACHING,
                confidenceFinal = 0.80f,
                trackKey = "person-pseudo-approaching",
            ),
            nowMs = 0L,
        )

        assertEquals(MessagePurpose.APPROACH_CAUTION, decision.purpose)
        assertEquals(MessageLevel.CAUTION, decision.userFacing.messageLevel)
        assertNull(decision.userFacing.stepsAhead)
        assertTrue(
            decision.userFacing.message?.endsWith("속도를 늦추고 주변을 확인하세요.") == true,
        )
    }

    @Test
    fun monocularMetricSourceDoesNotUseStepsEvenWhenDistanceExists() {
        val policy = MessagePolicy()

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "person",
                source = DepthSource.MONOCULAR_METRIC_DEPTH,
                riskDistanceM = 1.0f,
                trend = Trend.STABLE,
                confidenceFinal = 0.95f,
                trackKey = "person-model-depth",
            ),
            nowMs = 0L,
        )

        assertEquals(MessageLevel.NONE, decision.userFacing.messageLevel)
        assertNull(decision.userFacing.stepsAhead)
        assertNull(decision.userFacing.message)
    }

    @Test
    fun normalTactileBlockWithUntrustedMetricSourceDoesNotBypassRoutePolicy() {
        val policy = MessagePolicy(stepLengthM = 0.6f)

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "normal_tactile_block",
                source = DepthSource.MONOCULAR_METRIC_DEPTH,
                riskDistanceM = 1.2f,
                trend = Trend.STABLE,
                confidenceFinal = 0.95f,
                trackKey = "tactile-model-depth",
            ),
            nowMs = 0L,
        )

        assertFalse(DepthSource.MONOCULAR_METRIC_DEPTH.trustedForStepGuidance)
        assertEquals(MessagePurpose.NONE, decision.purpose)
        assertEquals(MessageLevel.NONE, decision.userFacing.messageLevel)
        assertNull(decision.userFacing.stepsAhead)
        assertNull(decision.userFacing.message)
    }

    @Test
    fun broadTactileClassDoesNotBecomeObstacleWarning() {
        val policy = MessagePolicy(stepLengthM = 0.6f)

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "tactile_block",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 0.8f,
                trend = Trend.STABLE,
                confidenceFinal = 0.9f,
                trackKey = "generic-tactile",
            ),
            nowMs = 0L,
        )

        assertEquals(MessagePurpose.NONE, decision.purpose)
        assertEquals(MessageLevel.NONE, decision.userFacing.messageLevel)
        assertNull(decision.userFacing.message)
    }

    @Test
    fun crosswalkUsesPathGuidanceNotObstacleWarning() {
        val policy = MessagePolicy(stepLengthM = 0.6f)

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "crosswalk",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 3.0f,
                trend = Trend.STABLE,
                confidenceFinal = 0.8f,
                trackKey = "crosswalk-1",
            ),
            nowMs = 0L,
        )

        assertEquals(MessagePurpose.PATH_GUIDANCE, decision.purpose)
        assertEquals(MessageLevel.INFO, decision.userFacing.messageLevel)
        assertEquals(CROSSWALK_REFERENCE_NOTICE_KO, decision.userFacing.message)
        assertNull(decision.userFacing.stepsAhead)
        assertFalse(decision.userFacing.message!!.contains("멈추세요"))
    }

    @Test
    fun zebraUsesTheSameFixedCrosswalkReferenceNotice() {
        val policy = MessagePolicy()

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "zebra",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 1.0f,
                trend = Trend.STABLE,
                confidenceFinal = 0.8f,
                trackKey = "zebra-1",
            ),
            nowMs = 0L,
        )

        assertEquals(MessagePurpose.PATH_GUIDANCE, decision.purpose)
        assertEquals(MessageLevel.INFO, decision.userFacing.messageLevel)
        assertEquals(CROSSWALK_REFERENCE_NOTICE_KO, decision.userFacing.message)
        assertNull(decision.userFacing.stepsAhead)
    }

    @Test
    fun nonCrosswalkDetectorKeepsExistingObstacleWarning() {
        val policy = MessagePolicy()

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "person",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 1.0f,
                trend = Trend.STABLE,
                confidenceFinal = 0.8f,
                trackKey = "person-crosswalk-regression",
            ),
            nowMs = 0L,
        )

        assertEquals(MessagePurpose.OBSTACLE_WARNING, decision.purpose)
        assertEquals(MessageLevel.STOP, decision.userFacing.messageLevel)
        assertTrue(decision.userFacing.message?.endsWith("멈추세요. 주변을 확인하세요.") == true)
        assertFalse(decision.userFacing.message == CROSSWALK_REFERENCE_NOTICE_KO)
    }

    @Test
    fun trafficLightDoesNotCreateUserWarningUntilColorPolicyExists() {
        val policy = MessagePolicy(stepLengthM = 0.6f)

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "traffic light",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 0.8f,
                trend = Trend.APPROACHING,
                confidenceFinal = 0.95f,
                trackKey = "traffic-light-1",
                timeToCollisionMs = 2_000L,
            ),
            nowMs = 0L,
        )

        assertEquals(MessagePurpose.NONE, decision.purpose)
        assertEquals(MessageLevel.NONE, decision.userFacing.messageLevel)
        assertNull(decision.userFacing.message)
        assertEquals("traffic light color guidance deferred", decision.reason)
    }

    @Test
    fun rateLimitSuppressesRepeatedSpeechForSameTrack() {
        val policy = MessagePolicy()
        val result = MetricDepthDecision(
            className = "person",
            source = DepthSource.ARCORE_RAW_DEPTH,
            riskDistanceM = 1.0f,
            trend = Trend.STABLE,
            confidenceFinal = 0.8f,
            trackKey = "person-1",
        )

        val first = policy.evaluate(result, nowMs = 0L)
        val second = policy.evaluate(result, nowMs = 1_000L)

        assertNotNull(first.userFacing.message)
        assertNull(second.userFacing.message)
        assertEquals(MessageLevel.STOP, second.userFacing.messageLevel)
        assertTrue(second.reason.startsWith("rate limited"))
    }

    @Test
    fun hysteresisKeepsWarningJustOutsideDistanceThreshold() {
        val policy = MessagePolicy()

        val first = policy.evaluate(
            MetricDepthDecision(
                className = "person",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 2.4f,
                trend = Trend.STABLE,
                confidenceFinal = 0.65f,
                trackKey = "person-hysteresis",
            ),
            nowMs = 0L,
        )
        val second = policy.evaluate(
            MetricDepthDecision(
                className = "person",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 2.7f,
                trend = Trend.STABLE,
                confidenceFinal = 0.65f,
                trackKey = "person-hysteresis",
            ),
            nowMs = 3_000L,
        )

        assertEquals(MessageLevel.WARNING, first.userFacing.messageLevel)
        assertEquals(MessagePurpose.OBSTACLE_WARNING, second.purpose)
        assertEquals(MessageLevel.WARNING, second.userFacing.messageLevel)
        assertNotNull(second.userFacing.message)
    }

    @Test
    fun approachingTtcStopsAtThreeSeconds() {
        val policy = MessagePolicy()

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "person",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 4.0f,
                trend = Trend.APPROACHING,
                confidenceFinal = 0.8f,
                trackKey = "person-ttc-warning",
                timeToCollisionMs = 3_000L,
            ),
            nowMs = 0L,
        )

        assertEquals(MessagePurpose.OBSTACLE_WARNING, decision.purpose)
        assertEquals(MessageLevel.STOP, decision.userFacing.messageLevel)
    }

    @Test
    fun approachingTtcWarnsBetweenThreeAndTenSeconds() {
        val policy = MessagePolicy()

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "person",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 4.0f,
                trend = Trend.APPROACHING,
                confidenceFinal = 0.8f,
                trackKey = "person-ttc-warning",
                timeToCollisionMs = 7_000L,
            ),
            nowMs = 0L,
        )

        assertEquals(MessagePurpose.OBSTACLE_WARNING, decision.purpose)
        assertEquals(MessageLevel.WARNING, decision.userFacing.messageLevel)
        assertTrue(decision.userFacing.message?.endsWith("멈출 준비를 하세요.") == true)
    }

    @Test
    fun approachingTtcAwareBetweenTenAndThirtySecondsIsInternalOnly() {
        val policy = MessagePolicy()

        val decision = policy.evaluate(
            MetricDepthDecision(
                className = "person",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 4.0f,
                trend = Trend.APPROACHING,
                confidenceFinal = 0.8f,
                trackKey = "person-ttc-aware",
                timeToCollisionMs = 20_000L,
            ),
            nowMs = 0L,
        )

        assertEquals(MessagePurpose.NONE, decision.purpose)
        assertEquals(MessageLevel.AWARE, decision.userFacing.messageLevel)
        assertNull(decision.userFacing.message)
    }
}
