package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampAlignedWalkingCalibrationTest {
    @Test
    fun learnsTheSameStepsWhenDetectorCallbacksArriveAfterGnss() {
        val immediate = replay(walk(stepDeliveryDelayMs = 10L))
        val delayed = replay(walk(stepDeliveryDelayMs = 450L))

        assertEquals(1, immediate.size)
        assertEquals(immediate, delayed)
        assertEquals(22, delayed.single().stepCount)
        assertEquals(15.4, delayed.single().distanceM, 0.001)
        assertEquals(11_000L, delayed.single().durationMs)
    }

    @Test
    fun excludesStepsAfterTheFixEvenWhenTheirCallbacksArriveBeforeGnss() {
        val ordinary = replay(walk())
        val gnssDeliveredLater = replay(walk(fixDeliveryDelayMs = 500L))

        assertEquals(ordinary, gnssDeliveredLater)
        assertEquals(22, gnssDeliveredLater.single().stepCount)
    }

    @Test
    fun includesAStepExactlyAtTheFixOnlyAfterTheNextStepSealsTheEndpoint() {
        val deliveries = walk(firstStepMs = 1_000L, stepDeliveryDelayMs = 450L)
        val calibration = TimestampAlignedWalkingCalibration()
        val beforeSeal = deliveries.filter { it.receivedAtMs < 12_950L }
        assertTrue(replay(beforeSeal, calibration).isEmpty())

        val sealed = replay(deliveries.filter { it.receivedAtMs >= 12_950L }, calibration)

        assertEquals(1, sealed.size)
        assertEquals(22, sealed.single().stepCount)
    }

    @Test
    fun preservesOriginalFixReceiptWhileWaitingBeyondTheRawFreshnessLimit() {
        // Each fix arrives after 100 ms but is confirmed 700 ms after measurement.
        // Replacing its receipt time with processing time would wrongly reject it.
        val samples = replay(walk(stepDeliveryDelayMs = 450L))

        assertEquals(1, samples.size)
        assertTrue(samples.single().trustedPositionSegment)
    }

    @Test
    fun neverInventsIndividualTimestampsForCounterBatchesOrFallbackEvents() {
        listOf(1, 4).forEach { delta ->
            val deliveries = walk(lastSecond = 24).map { delivery ->
                if (delivery.stepTimestampMs == null) delivery
                else delivery.copy(individualDetector = false, deltaSteps = delta)
            }
            assertTrue(replay(deliveries).isEmpty())
        }
        val corruptDetectorBatch = walk().map { delivery ->
            if (delivery.stepTimestampMs == null) delivery else delivery.copy(deltaSteps = 2)
        }
        assertTrue(replay(corruptDetectorBatch).isEmpty())
    }

    @Test
    fun duplicateAndOutOfOrderStepCallbacksDiscardThePendingSegment() {
        listOf(5_750L, 5_250L).forEach { timestamp ->
            val deliveries = walk() + Delivery(receivedAtMs = 5_800L, stepTimestampMs = timestamp)
            assertTrue(replay(deliveries).isEmpty())
        }
    }

    @Test
    fun sourceTransitionCannotJoinDetectorStepsAcrossAnUnreliableEvent() {
        val deliveries = walk() + Delivery(
            receivedAtMs = 5_800L,
            stepTimestampMs = 5_800L,
            deltaSteps = 3,
            individualDetector = false,
        )

        assertTrue(replay(deliveries).isEmpty())
    }

    @Test
    fun missingStepsAcrossALongGapCannotBeCountedAsAContinuousWalk() {
        val deliveries = walk().filter { delivery ->
            delivery.stepTimestampMs == null || delivery.stepTimestampMs !in 5_750L..8_750L
        }

        assertTrue(replay(deliveries).isEmpty())
    }

    @Test
    fun excludesExcessivelyDelayedStepsAndExpiredPendingFixes() {
        assertTrue(replay(walk(stepDeliveryDelayMs = 2_001L)).isEmpty())
        // Within the step delivery limit, but too late to confirm the waiting fix.
        assertTrue(replay(walk(stepDeliveryDelayMs = 1_950L)).isEmpty())
    }

    @Test
    fun excludesFutureStepTimestampsWithoutReusingEarlierEvidence() {
        val deliveries = walk() + Delivery(receivedAtMs = 5_800L, stepTimestampMs = 5_900L)

        assertTrue(replay(deliveries).isEmpty())
    }

    @Test
    fun rejectedGnssAndOriginalQualityEvidenceBreakTheWholeSegment() {
        val original = fix(5)
        val rejected = listOf(
            original.copy(trustedRawFix = false),
            original.copy(horizontalAccuracyM = 4.0),
            original.copy(speedMps = null),
            original.copy(courseDegrees = null),
            original.copy(receivedAtElapsedRealtimeMs = original.elapsedRealtimeMs + 501L),
        )
        rejected.forEach { badFix ->
            val deliveries = walk().map { delivery ->
                if (delivery.fix?.elapsedRealtimeMs == original.elapsedRealtimeMs) {
                    delivery.copy(fix = badFix, receivedAtMs = badFix.receivedAtElapsedRealtimeMs)
                } else delivery
            }
            assertTrue(replay(deliveries).isEmpty())
        }
    }

    @Test
    fun duplicateOrOlderGnssFixCannotReuseThePreviousAnchor() {
        listOf(4_800L, 5_000L).forEach { timestamp ->
            val duplicateOrOlder = fix(4).copy(
                elapsedRealtimeMs = timestamp,
                receivedAtElapsedRealtimeMs = 5_150L,
            )
            val deliveries = walk() + Delivery(receivedAtMs = 5_150L, fix = duplicateOrOlder)
            assertTrue(replay(deliveries).isEmpty())
        }
    }

    @Test
    fun resetClearsBothQueuedFixesAndStepHistory() {
        val calibration = TimestampAlignedWalkingCalibration()
        val deliveries = walk() + Delivery(receivedAtMs = 5_800L)

        assertTrue(replay(deliveries, calibration).isEmpty())
        calibration.reset()
        assertEquals(1, replay(walk(), calibration).size)
    }

    @Test
    fun aFixWithoutAPrecedingIndividualStepCannotStartCalibration() {
        val deliveries = walk(firstStepMs = 1_250L)

        assertTrue(replay(deliveries).isEmpty())
    }

    @Test
    fun prunesOldHistoryWithoutLosingCountsInsideLongWalks() {
        val samples = replay(walk(lastSecond = 33))

        assertEquals(3, samples.size)
        assertTrue(samples.all { it.stepCount == 22 && it.durationMs == 11_000L })
    }

    @Test
    fun preservesStraightnessGatesAfterTimestampAlignment() {
        val deliveries = walk(lastSecond = 18).map { delivery ->
            val raw = delivery.fix ?: return@map delivery
            val second = ((raw.elapsedRealtimeMs - 1_000L) / 1_000L).toInt()
            if (second <= 9) delivery else delivery.copy(
                fix = fix(second, eastM = 12.6, northM = (second - 9) * 1.4, course = 0.0),
            )
        }

        assertTrue(replay(deliveries).isEmpty())
    }

    @Test
    fun boundedPendingBurstRecoversOnlyWithFreshBracketingSteps() {
        val calibration = TimestampAlignedWalkingCalibration()
        (0..100).forEach { offset ->
            val raw = fix(0).copy(
                elapsedRealtimeMs = 1_000L + offset,
                receivedAtElapsedRealtimeMs = 1_100L + offset,
            )
            assertTrue(calibration.onFix(raw).isEmpty())
        }
        val subsequentWalk = walk().map { delivery ->
            delivery.copy(
                receivedAtMs = delivery.receivedAtMs + 2_000L,
                stepTimestampMs = delivery.stepTimestampMs?.plus(2_000L),
                fix = delivery.fix?.let {
                    it.copy(
                        elapsedRealtimeMs = it.elapsedRealtimeMs + 2_000L,
                        receivedAtElapsedRealtimeMs = it.receivedAtElapsedRealtimeMs + 2_000L,
                    )
                },
            )
        }

        assertEquals(1, replay(subsequentWalk, calibration).size)
    }

    private data class Delivery(
        val receivedAtMs: Long,
        val stepTimestampMs: Long? = null,
        val fix: RawWalkingCalibrationFix? = null,
        val deltaSteps: Int = 1,
        val individualDetector: Boolean = true,
    )

    private fun replay(
        deliveries: List<Delivery>,
        calibration: TimestampAlignedWalkingCalibration = TimestampAlignedWalkingCalibration(),
    ): List<WalkingCalibrationSample> = deliveries.sortedBy { it.receivedAtMs }.flatMap { delivery ->
        when {
            delivery.fix != null -> calibration.onFix(delivery.fix)
            delivery.stepTimestampMs != null -> calibration.onStep(
                timestampMs = delivery.stepTimestampMs,
                deltaSteps = delivery.deltaSteps,
                individualStepDetector = delivery.individualDetector,
                receivedAtElapsedRealtimeMs = delivery.receivedAtMs,
            )
            else -> {
                calibration.reset()
                emptyList()
            }
        }
    }

    private fun walk(
        lastSecond: Int = 11,
        firstStepMs: Long = 750L,
        stepDeliveryDelayMs: Long = 10L,
        fixDeliveryDelayMs: Long = 100L,
    ): List<Delivery> {
        val deliveries = mutableListOf<Delivery>()
        var stepAtMs = firstStepMs
        while (stepAtMs <= 1_000L + lastSecond * 1_000L + 1_000L) {
            deliveries += Delivery(receivedAtMs = stepAtMs + stepDeliveryDelayMs, stepTimestampMs = stepAtMs)
            stepAtMs += 500L
        }
        (0..lastSecond).forEach { second ->
            val raw = fix(second).let {
                it.copy(receivedAtElapsedRealtimeMs = it.elapsedRealtimeMs + fixDeliveryDelayMs)
            }
            deliveries += Delivery(receivedAtMs = raw.receivedAtElapsedRealtimeMs, fix = raw)
        }
        return deliveries
    }

    private fun fix(
        second: Int,
        eastM: Double = second * 1.4,
        northM: Double = 0.0,
        course: Double = 90.0,
    ): RawWalkingCalibrationFix {
        val timestampMs = 1_000L + second * 1_000L
        return RawWalkingCalibrationFix(
            latitude = 37.5 + Math.toDegrees(northM / 6_371_000.0),
            longitude = 127.0 + Math.toDegrees(eastM / (6_371_000.0 * cos(Math.toRadians(37.5)))),
            horizontalAccuracyM = 1.0,
            elapsedRealtimeMs = timestampMs,
            receivedAtElapsedRealtimeMs = timestampMs + 100L,
            speedMps = 1.4,
            speedAccuracyMps = 0.1,
            courseDegrees = course,
            courseAccuracyDegrees = 5.0,
            trustedRawFix = true,
        )
    }
}
