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
    fun spokenLabelsUseCorrectParticlesWithoutDistanceOrDirection() {
        val labels = listOf(
            Triple("person", "사람이", "사람과"),
            Triple("bicycle", "자전거가", "자전거와"),
            Triple("traffic_cone", "안전 고깔이", "안전 고깔과"),
            Triple("unnamed-obstacle", "물체가", "물체와"),
        )
        for ((className, subject, companion) in labels) {
            val input = MetricDepthDecision(className, DepthSource.ARCORE_RAW_DEPTH,
                1f, Trend.APPROACHING, 0.9f, cameraScreenLateralPosition = CameraScreenLateralPosition.LEFT)
            assertEquals("멈추세요. $subject 감지됐습니다. 주변을 확인하세요.",
                MessagePolicy().buildUserFacing(input, 0L).message)
            assertEquals("가까이에 $subject 있을 수 있습니다. 속도를 늦추고 주변을 확인하세요.",
                MessagePolicy().buildUserFacing(input.copy(confidenceFinal = 0.4f), 0L).message)
            assertEquals("$companion 가까워지는 것으로 보입니다. 속도를 늦추고 주변을 확인하세요.",
                MessagePolicy().buildUserFacing(input.copy(
                    source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH, riskDistanceM = null), 0L).message)
        }
    }

    @Test
    fun shortTtcStopDoesNotClaimTheObjectIsNearby() {
        val input = MetricDepthDecision("person", DepthSource.ARCORE_RAW_DEPTH,
            8f, Trend.APPROACHING, 0.9f, timeToCollisionMs = 2_000L)
        val result = MessagePolicy().buildUserFacing(input, 0L)
        assertEquals(MessageLevel.STOP, result.messageLevel)
        assertEquals("멈추세요. 사람이 감지됐습니다. 주변을 확인하세요.", result.message)
    }

    @Test
    fun screenPositionAndMotionDoNotDelayStopSpeech() {
        val base = MetricDepthDecision(
            className = "person", source = DepthSource.ARCORE_RAW_DEPTH, riskDistanceM = 1f,
            trend = Trend.STABLE, confidenceFinal = 0.9f,
        )
        val leftMovingRight = MessagePolicy().buildUserFacing(base.copy(
            cameraScreenLateralPosition = CameraScreenLateralPosition.LEFT,
            motionEstimate = measuredMotion(ObjectMovementDirection.RIGHT),
        ), nowMs = 0L).message!!
        val rightMovingLeft = MessagePolicy().buildUserFacing(base.copy(
            cameraScreenLateralPosition = CameraScreenLateralPosition.RIGHT,
            motionEstimate = measuredMotion(ObjectMovementDirection.LEFT),
        ), nowMs = 0L).message!!

        assertEquals("멈추세요. 사람이 감지됐습니다. 주변을 확인하세요.", leftMovingRight)
        assertEquals(leftMovingRight, rightMovingLeft)
        for (message in listOf(leftMovingRight, rightMovingLeft)) {
            assertFalse(message.contains("전방"))
            assertFalse(message.contains("다가오"))
        }
    }

    @Test
    fun metricPseudoAndWeakMessagesOmitPositionWhenUnknown() {
        val metric = MetricDepthDecision(
            className = "person", source = DepthSource.ARCORE_RAW_DEPTH, riskDistanceM = 1f,
            trend = Trend.APPROACHING, confidenceFinal = 0.9f,
        )
        val pseudo = metric.copy(
            source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH, riskDistanceM = null,
        )
        val weak = metric.copy(
            source = DepthSource.WEBXR_DEPTH, trend = Trend.STABLE, confidenceFinal = 0.58f,
        )
        for (decision in listOf(metric, pseudo, weak)) {
            val message = MessagePolicy().buildUserFacing(decision, nowMs = 0L).message!!
            assertFalse(message.contains("카메라 화면"))
            assertFalse(message.contains("전방"))
            assertFalse(message.contains("이 물체가 사용자 쪽으로 다가오"))
        }
    }

    @Test
    fun pseudoAndWeakMessagesKeepCautionWithoutCameraSide() {
        val pseudo = MetricDepthDecision(
            className = "person", source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
            riskDistanceM = null, trend = Trend.APPROACHING, confidenceFinal = 0.8f,
            cameraScreenLateralPosition = CameraScreenLateralPosition.LEFT,
        )
        val weak = pseudo.copy(
            source = DepthSource.WEBXR_DEPTH, riskDistanceM = 1f,
            trend = Trend.STABLE, confidenceFinal = 0.58f,
            cameraScreenLateralPosition = CameraScreenLateralPosition.RIGHT,
        )
        val pseudoMessage = MessagePolicy().buildUserFacing(pseudo, nowMs = 0L).message!!
        val weakMessage = MessagePolicy().buildUserFacing(weak, nowMs = 0L).message!!

        assertEquals("사람과 가까워지는 것으로 보입니다. 속도를 늦추고 주변을 확인하세요.", pseudoMessage)
        assertEquals("가까이에 사람이 있을 수 있습니다. 속도를 늦추고 주변을 확인하세요.", weakMessage)
        assertFalse(pseudoMessage.contains("전방"))
        assertFalse(weakMessage.contains("전방"))
        assertFalse(pseudoMessage.contains("이 물체가 사용자 쪽으로 다가오"))
    }

    @Test
    fun screenSideRequiresValidRotationAndBothNormalizedCoordinates() {
        val cases = listOf(
            Point2(0.2f, 0.5f) to CameraScreenLateralPosition.LEFT,
            Point2(0.5f, 0.5f) to CameraScreenLateralPosition.CENTER,
            Point2(0.8f, 0.5f) to CameraScreenLateralPosition.RIGHT,
        )
        for ((uprightCenter, expected) in cases) for (turns in 0..3) {
            val sensorCenter = uprightPointToSensor(uprightCenter, turns)!!
            assertEquals(expected, geometryAt(sensorCenter, turns).cameraScreenLateralPosition())
        }
        for (turns in listOf(null, -1, 4)) {
            assertEquals(CameraScreenLateralPosition.UNKNOWN,
                geometryAt(Point2(0.2f, 0.5f), turns).cameraScreenLateralPosition())
        }
        for (center in listOf(
            Point2(Float.NaN, 0.5f), Point2(0.5f, Float.POSITIVE_INFINITY),
            Point2(-0.1f, 0.5f), Point2(0.5f, 1.1f),
        )) {
            assertEquals(CameraScreenLateralPosition.UNKNOWN,
                geometryAt(center, 0).cameraScreenLateralPosition())
        }
    }

    private fun geometryAt(center: Point2, turns: Int?): ObjectGeometry = ObjectGeometry(
        className = "person", detectionConfidence = 0.9f,
        bboxNorm = RectNorm(0f, 0f, 1f, 1f), polygonNorm = emptyList(),
        maskAreaNorm = 0.1f, centerNorm = center, bottomContactNorm = null,
        imageQuarterTurns = turns,
    )

    @Test
    fun oldSpatialMotionCannotDescribeANewFrameAsAnApproachingObject() {
        val result = MetricDepthDecision(
            className = "person", source = DepthSource.ARCORE_RAW_DEPTH, riskDistanceM = 1f,
            trend = Trend.APPROACHING, confidenceFinal = 0.9f,
            objectMotion = ObjectMotion.OBJECT_APPROACHING,
            motionEstimate = measuredMotion(ObjectMovementDirection.TOWARD_USER),
        )
        val stale = MessagePolicy().buildUserFacing(result, nowMs = 1L)
        assertEquals(MessageLevel.STOP, stale.messageLevel)
        assertTrue(stale.message!!.contains("멈추세요"))
        assertFalse(stale.message!!.contains("다가오는"))
    }

    @Test
    fun stationaryObstacleAndIncomingObjectKeepSameActionFirstGuidance() {
        val approaching = MetricDepthDecision(
            className = "car",
            source = DepthSource.ARCORE_RAW_DEPTH,
            riskDistanceM = 2f,
            trend = Trend.APPROACHING,
            confidenceFinal = 0.9f,
        )
        val stationary = MessagePolicy().buildUserFacing(
            approaching.copy(
                objectMotion = ObjectMotion.USER_APPROACHING_STATIONARY,
                motionEstimate = measuredMotion(ObjectMovementDirection.STATIONARY),
            ), 0L,
        )
        val incoming = MessagePolicy().buildUserFacing(
            approaching.copy(
                objectMotion = ObjectMotion.OBJECT_APPROACHING,
                motionEstimate = measuredMotion(ObjectMovementDirection.TOWARD_USER),
            ), 0L,
        )
        val unknown = MessagePolicy().buildUserFacing(approaching, 0L)

        assertEquals("멈추세요. 차량이 감지됐습니다. 주변을 확인하세요.", stationary.message)
        assertEquals(stationary.message, incoming.message)
        assertEquals(stationary.message, unknown.message)
        assertFalse(unknown.message!!.contains("정지해"))
        assertFalse(unknown.message!!.contains("다가오"))
        assertEquals(stationary.messageLevel, incoming.messageLevel)
        assertEquals(stationary.stepsAhead, incoming.stepsAhead)
    }

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
        assertTrue(decision.userFacing.message?.startsWith("멈추세요. ") == true)
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
    private fun measuredMotion(direction: ObjectMovementDirection) = ObjectMotionEstimate(
        referenceId = 1L,
        observedAtMs = 0L,
        elapsedMs = 1_000L,
        objectVelocityInAnchorMps = Vec3(0f, 0f, -0.6f),
        relativeVelocityInAnchorMps = Vec3(0f, 0f, -0.6f),
        cameraVelocityInAnchorMps = Vec3(0f, 0f, 0f),
        objectSpeedMps = 0.6f,
        relativeClosingSpeedMps = 0.6f,
        direction = direction,
        confidence = 0.9f,
    )

}
