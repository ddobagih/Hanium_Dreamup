package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNonMetricObstacleAdvisoryPolicyTest {
    private val gate = NonMetricAdvisoryGate(
        cameraPermissionGranted = true,
        cameraFallbackRunning = true,
        detectorAvailable = true,
        imuFresh = true,
        tmapRouteActive = false,
    )

    @Test
    fun requiresThreeFreshFramesAndReportsOnlyScreenRelativeDirection() {
        val policy = AndroidNonMetricObstacleAdvisoryPolicy()
        val candidate = detection(centerX = 0.18f)

        assertNull(policy.evaluate(listOf(candidate), gate, frameId = 1L, nowMs = 1_000L))
        assertNull(policy.evaluate(listOf(candidate), gate, frameId = 2L, nowMs = 1_400L))
        val action = policy.evaluate(listOf(candidate), gate, frameId = 3L, nowMs = 1_700L)

        requireNotNull(action)
        assertEquals(NonMetricObstacleDirection.LEFT, action.direction)
        assertTrue(action.message.contains("왼쪽"))
        assertTrue(action.message.contains("킥보드"))
        assertFalse(action.message.contains("TMAP", ignoreCase = true))
        assertTrue(action.validUntilMs > action.observedAtMs)
        listOf(
            "미터",
            "m 앞",
            "걸음",
            "STOP",
            "HIGH",
            "조향",
            "따라",
            "신고",
            "진동",
            "안전",
        ).forEach { forbidden ->
            assertFalse(action.message.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun routeStateDoesNotGateOrResetThreeFrameStability() {
        val policy = AndroidNonMetricObstacleAdvisoryPolicy()
        val candidate = detection(centerX = 0.5f)

        assertNull(policy.evaluate(listOf(candidate), gate, frameId = 1L, nowMs = 1_000L))
        assertNull(
            policy.evaluate(
                listOf(candidate),
                gate.copy(tmapRouteActive = true),
                frameId = 2L,
                nowMs = 1_400L,
            ),
        )
        val action = policy.evaluate(listOf(candidate), gate, frameId = 3L, nowMs = 1_700L)

        requireNotNull(action)
        assertEquals(NonMetricObstacleDirection.CENTER, action.direction)
    }

    @Test
    fun missingFrameResetsStabilityAndNormalTactileBlockStaysInformational() {
        val policy = AndroidNonMetricObstacleAdvisoryPolicy()
        val tactile = detection(centerX = 0.5f, className = "normal_tactile_block")

        assertNull(policy.evaluate(listOf(tactile), gate, 1L, 1_000L))
        assertNull(policy.evaluate(emptyList(), gate, 2L, 1_300L))
        assertNull(policy.evaluate(listOf(tactile), gate, 3L, 1_600L))
        assertNull(policy.evaluate(listOf(tactile), gate, 4L, 2_000L))
        val action = policy.evaluate(listOf(tactile), gate, 5L, 2_300L)

        requireNotNull(action)
        assertTrue(action.message.contains("점자블록 후보"))
        assertFalse(action.message.contains("따라"))
    }

    @Test
    fun sameClassAndDirectionStillRequiresSpatialContinuity() {
        val policy = AndroidNonMetricObstacleAdvisoryPolicy()
        val first = detection(centerX = 0.41f, width = 0.10f)
        val moved = detection(centerX = 0.59f, width = 0.10f)

        assertNull(policy.evaluate(listOf(first), gate, 1L, 1_000L))
        assertNull(policy.evaluate(listOf(moved), gate, 2L, 1_400L))
        assertNull(policy.evaluate(listOf(moved), gate, 3L, 1_800L))
        assertEquals(
            NonMetricObstacleDirection.CENTER,
            policy.evaluate(listOf(moved), gate, 4L, 2_100L)?.direction,
        )
    }

    @Test
    fun repeatedFrameIdDoesNotCountAsThreeDistinctFrames() {
        val policy = AndroidNonMetricObstacleAdvisoryPolicy()
        val candidate = detection(centerX = 0.5f)

        assertNull(policy.evaluate(listOf(candidate), gate, frameId = 1L, nowMs = 1_000L))
        assertNull(policy.evaluate(listOf(candidate), gate, frameId = 1L, nowMs = 1_400L))
        assertNull(policy.evaluate(listOf(candidate), gate, frameId = 1L, nowMs = 1_800L))
        assertNull(policy.evaluate(listOf(candidate), gate, frameId = 2L, nowMs = 2_100L))
        assertEquals(
            NonMetricObstacleDirection.CENTER,
            policy.evaluate(listOf(candidate), gate, frameId = 3L, nowMs = 2_400L)?.direction,
        )
    }

    @Test
    fun rejectsMissingCapabilityDistantBoxesAndNonObstacleClasses() {
        val policy = AndroidNonMetricObstacleAdvisoryPolicy()
        val closeObstacle = detection(centerX = 0.5f)

        assertNull(policy.evaluate(listOf(closeObstacle), gate.copy(imuFresh = false), 1L, 1_000L))
        assertNull(policy.evaluate(listOf(closeObstacle), gate.copy(cameraFallbackRunning = false), 2L, 1_400L))
        assertNull(policy.evaluate(listOf(closeObstacle), gate.copy(cameraPermissionGranted = false), 31L, 1_900L))
        assertNull(policy.evaluate(listOf(closeObstacle), gate.copy(detectorAvailable = false), 32L, 2_000L))
        assertNull(
            policy.evaluate(
                listOf(detection(centerX = 0.5f, y = 0.05f, height = 0.2f)),
                gate,
                4L,
                2_200L,
            ),
        )
        assertNull(
            policy.evaluate(
                listOf(detection(centerX = 0.5f, className = "damaged_tactile_block")),
                gate,
                5L,
                2_600L,
            ),
        )
        assertNull(
            policy.evaluate(
                listOf(detection(centerX = 0.5f, className = "tactile_damage_area")),
                gate,
                6L,
                3_000L,
            ),
        )
    }

    @Test
    fun supportedObstacleClassesUseShortKoreanCategoryLabels() {
        val policy = AndroidNonMetricObstacleAdvisoryPolicy(
            NonMetricObstacleAdvisoryConfig(
                minStableFrames = 1,
                minStableMs = 0L,
                globalIntervalMs = 0L,
                perKeyIntervalMs = 0L,
            ),
        )
        // Wording now comes from ObstacleLabels, so a phone without distance says the same words
        // as one with it. Vehicles share "차량"; static obstacles keep their own name.
        val expectedLabels = mapOf(
            "person" to "사람",
            "bicycle" to "자전거",
            "car" to "차량",
            "motorcycle" to "차량",
            "bus" to "차량",
            "truck" to "차량",
            "curb_step" to "턱",
            "uneven_sidewalk" to "고르지 않은 보도",
            "e_scooter_obstruction" to "킥보드",
        )

        expectedLabels.forEach { (className, label) ->
            policy.reset()
            val action = policy.evaluate(
                detections = listOf(detection(centerX = 0.5f, className = className)),
                gate = gate,
                frameId = 1L,
                nowMs = 1_000L,
            )
            requireNotNull(action)
            assertTrue(action.message.contains("$label 후보"))
        }
    }

    @Test
    fun usesBoundedGlobalAndPerDirectionCooldowns() {
        val policy = AndroidNonMetricObstacleAdvisoryPolicy(
            NonMetricObstacleAdvisoryConfig(
                minStableFrames = 3,
                minStableMs = 700L,
                globalIntervalMs = 2_000L,
                perKeyIntervalMs = 5_000L,
                actionTtlMs = 1_200L,
            ),
        )
        val center = detection(centerX = 0.5f)
        val right = detection(centerX = 0.82f, className = "person")

        assertNull(policy.evaluate(listOf(center), gate, 1L, 1_000L))
        assertNull(policy.evaluate(listOf(center), gate, 2L, 1_400L))
        val first = policy.evaluate(listOf(center), gate, 3L, 1_700L)
        requireNotNull(first)
        assertEquals(NonMetricObstacleDirection.CENTER, first.direction)
        assertTrue(policy.claimDelivery(first))
        assertTrue(policy.confirmDelivery(first, 1_700L))
        assertNull(policy.evaluate(listOf(right), gate, 4L, 2_000L))
        assertNull(policy.evaluate(listOf(right), gate, 5L, 2_400L))
        assertNull(policy.evaluate(listOf(right), gate, 6L, 2_700L))
        val second = policy.evaluate(listOf(right), gate, 7L, 3_800L)
        requireNotNull(second)
        assertEquals(NonMetricObstacleDirection.RIGHT, second.direction)
        assertTrue(policy.claimDelivery(second))
        assertTrue(policy.confirmDelivery(second, 3_800L))
        assertNull(policy.evaluate(listOf(center), gate, 8L, 5_000L))
        assertNull(policy.evaluate(listOf(center), gate, 9L, 5_400L))
        assertNull(policy.evaluate(listOf(center), gate, 10L, 5_700L))
        val third = policy.evaluate(listOf(center), gate, 11L, 6_800L)
        requireNotNull(third)
        assertEquals(NonMetricObstacleDirection.CENTER, third.direction)
        assertTrue(policy.claimDelivery(third))
        assertTrue(policy.confirmDelivery(third, 6_800L))
    }

    @Test
    fun equalSeverityAdvisoriesUseBoundedFifoIncludingTheActiveDelivery() {
        val policy = immediatePolicy()
        val detections = listOf(
            detection(centerX = 0.5f, className = "e_scooter_obstruction"),
            detection(centerX = 0.5f, className = "person"),
            detection(centerX = 0.5f, className = "bicycle"),
            detection(centerX = 0.5f, className = "car"),
        )

        val first = policy.evaluate(detections, gate, 1L, 1_000L)
        requireNotNull(first)
        assertEquals("e_scooter_obstruction|CENTER", first.key)
        assertTrue(policy.claimDelivery(first))
        assertNull(policy.evaluate(detections, gate, 2L, 1_001L))

        assertTrue(policy.confirmDelivery(first, 1_002L))
        val second = policy.evaluate(detections.drop(1), gate, 3L, 1_003L)
        requireNotNull(second)
        assertEquals("person|CENTER", second.key)
        assertTrue(policy.claimDelivery(second))
        assertTrue(policy.confirmDelivery(second, 1_004L))
        val third = policy.evaluate(detections.drop(2), gate, 4L, 1_005L)
        requireNotNull(third)
        assertEquals("bicycle|CENTER", third.key)
    }

    @Test
    fun failedDeliveryDoesNotConsumeCooldown() {
        val policy = immediatePolicy(globalIntervalMs = 4_000L, perKeyIntervalMs = 8_000L)
        val candidate = detection(centerX = 0.5f)
        val failed = policy.evaluate(listOf(candidate), gate, 1L, 1_000L)
        requireNotNull(failed)

        assertTrue(policy.claimDelivery(failed))
        assertTrue(policy.rejectDelivery(failed))
        val retry = policy.evaluate(listOf(candidate), gate, 2L, 1_001L)

        requireNotNull(retry)
        assertEquals(failed.key, retry.key)
        assertFalse(policy.confirmDelivery(failed, 1_001L))
    }

    @Test
    fun gateLossDropsTheActiveReservationAndQueue() {
        val policy = immediatePolicy()
        val first = policy.evaluate(
            listOf(detection(centerX = 0.5f), detection(centerX = 0.5f, className = "person")),
            gate,
            1L,
            1_000L,
        )
        requireNotNull(first)
        assertTrue(policy.claimDelivery(first))

        assertNull(policy.evaluate(emptyList(), gate.copy(imuFresh = false), 2L, 1_001L))
        assertFalse(policy.confirmDelivery(first, 1_002L))
    }

    @Test
    fun candidateLossDropsTheActiveDelivery() {
        val policy = immediatePolicy()
        val candidate = detection(centerX = 0.5f)
        val first = policy.evaluate(listOf(candidate), gate, 1L, 1_000L)
        requireNotNull(first)
        assertTrue(policy.claimDelivery(first))
        assertTrue(policy.isDeliveryCurrent(first))

        assertNull(policy.evaluate(emptyList(), gate, 2L, 1_001L))
        assertFalse(policy.isDeliveryCurrent(first))
        assertFalse(policy.confirmDelivery(first, 1_002L))
    }

    @Test
    fun candidateLossBeforeClaimInvalidatesTheReservation() {
        val policy = immediatePolicy()
        val candidate = detection(centerX = 0.5f)
        val reserved = policy.evaluate(listOf(candidate), gate, 1L, 1_000L)
        requireNotNull(reserved)
        assertTrue(policy.isDeliveryCurrent(reserved))

        assertNull(policy.evaluate(emptyList(), gate, 2L, 1_001L))

        assertFalse(policy.isDeliveryCurrent(reserved))
        assertFalse(policy.claimDelivery(reserved))
    }

    @Test
    fun reservationExpiredBeforeClaimIsDropped() {
        val policy = immediatePolicy(actionTtlMs = 100L)
        val candidate = detection(centerX = 0.5f)
        val expired = policy.evaluate(listOf(candidate), gate, 1L, 1_000L)
        requireNotNull(expired)

        val replacement = policy.evaluate(listOf(candidate), gate, 2L, 1_101L)

        requireNotNull(replacement)
        assertFalse(policy.claimDelivery(expired))
        assertEquals(1_101L, replacement.observedAtMs)
        assertTrue(policy.claimDelivery(replacement))
        assertTrue(policy.confirmDelivery(replacement, 1_102L))
    }

    private fun immediatePolicy(
        globalIntervalMs: Long = 0L,
        perKeyIntervalMs: Long = 10_000L,
        actionTtlMs: Long = 1_500L,
    ): AndroidNonMetricObstacleAdvisoryPolicy = AndroidNonMetricObstacleAdvisoryPolicy(
        NonMetricObstacleAdvisoryConfig(
            minStableFrames = 1,
            minStableMs = 0L,
            globalIntervalMs = globalIntervalMs,
            perKeyIntervalMs = perKeyIntervalMs,
            actionTtlMs = actionTtlMs,
            pendingQueueCapacity = 3,
        ),
    )

    private fun detection(
        centerX: Float,
        className: String = "e_scooter_obstruction",
        y: Float = 0.42f,
        width: Float = 0.24f,
        height: Float = 0.48f,
    ): DetectionCandidate = DetectionCandidate(
        className = className,
        detectionConfidence = 0.86f,
        bboxNorm = RectNorm(
            x = centerX - width / 2f,
            y = y,
            width = width,
            height = height,
        ),
    )
}
