package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricDistanceContinuityPolicyTest {
    @Test
    fun ordinaryDistancesDoNotRequirePoseAndAcceptedFrozenFramesRemainAccepted() {
        val run = History()
        val first = sample(0L, 4f).copy(cameraPoseEvidence = null)
        assertTrue(run.observe(first).accepted)
        val repeated = run.observe(first)
        assertTrue(repeated.accepted)
        assertTrue(repeated.observationsToAppend.isEmpty())
        assertFalse(run.observe(first.copy(distanceM = 0.5f)).accepted)
        assertFalse(run.observe(first.copy(confidence = Float.NaN)).accepted)
        assertTrue(run.observe(sample(500L, 3f).copy(cameraPoseEvidence = null)).accepted)
        assertEquals(listOf(4f, 3f), run.distances())
    }

    @Test
    fun isolatedAnomalyIsRejectedAndReturnToOriginalDistanceRecovers() {
        val run = History()
        run.observe(sample(0L, 4f))
        val anomaly = sample(500L, 0.5f)
        assertFalse(run.observe(anomaly).accepted)
        assertFalse(run.observe(anomaly).accepted)
        assertFalse(run.observe(sample(250L, 0.5f)).accepted)
        val recovered = run.observe(sample(1_000L, 4f))
        assertTrue(recovered.accepted)
        assertFalse(recovered.resetHistory)
        assertEquals(listOf(4f, 4f), run.distances())
    }

    @Test
    fun twoConsistentNewDistancesResetOldHistoryInsteadOfCreatingFalseTtc() {
        val run = History()
        run.observe(sample(0L, 4f))
        assertFalse(run.observe(sample(500L, 0.6f)).accepted)
        val confirmed = run.observe(sample(1_000L, 0.5f))
        assertTrue(confirmed.accepted)
        assertTrue(confirmed.resetHistory)
        assertEquals(listOf(0.6f, 0.5f), run.distances())
        val repeated = run.observe(sample(1_000L, 0.5f))
        assertTrue(repeated.accepted)
        assertFalse(repeated.resetHistory)
        assertEquals(listOf(0.6f, 0.5f), run.distances())
    }

    @Test
    fun confirmedFastApproachAndEstablishedPredictionRetainAllUniqueSamples() {
        val run = History()
        assertTrue(run.observe(sample(0L, 7f)).accepted)
        assertFalse(run.observe(sample(500L, 5f)).accepted)
        val confirmed = run.observe(sample(1_000L, 3f))
        assertTrue(confirmed.accepted)
        assertFalse(confirmed.resetHistory)
        assertEquals(2, confirmed.observationsToAppend.size)
        assertTrue(run.observe(sample(1_500L, 1f)).accepted)
        assertEquals(listOf(7f, 5f, 3f, 1f), run.distances())
    }

    @Test
    fun fastApproachConfirmationUsesElapsedTimeInsteadOfRawDistanceDifferences() {
        val run = History()
        run.observe(sample(0L, 7f))
        run.observe(sample(500L, 5f))
        assertTrue(run.observe(sample(1_250L, 2f)).accepted)
        assertEquals(listOf(7f, 5f, 2f), run.distances())

        val inconsistent = History()
        inconsistent.observe(sample(0L, 7f))
        inconsistent.observe(sample(500L, 5f))
        assertFalse(inconsistent.observe(sample(1_000L, 2f)).accepted)
        assertEquals(listOf(7f), inconsistent.distances())
    }

    @Test
    fun establishedPredictionRejectsUnexplainedDistanceJump() {
        val run = History()
        listOf(7f, 6f, 5f).forEachIndexed { index, distance ->
            run.observe(sample(index * 500L, distance))
        }
        assertFalse(run.observe(sample(1_500L, 1f)).accepted)
        assertEquals(listOf(7f, 6f, 5f), run.distances())
    }

    @Test
    fun largeChangeConfirmationRequiresSameSourceAnchorAndValidSynchronizedPose() {
        val invalidCurrent: List<(DistanceObservation) -> DistanceObservation> = listOf(
            { it.copy(source = DepthSource.ARCORE_FULL_DEPTH) },
            { it.copy(confidence = 0.54f) },
            { it.copy(cameraPoseEvidence = null) },
            { it.copy(cameraPoseEvidence = it.cameraPoseEvidence!!.copy(referenceId = 2L)) },
            { it.copy(cameraPoseEvidence = it.cameraPoseEvidence!!.copy(timestampMs = 999L)) },
            { it.copy(cameraPoseEvidence = it.cameraPoseEvidence!!.copy(positionX = Float.NaN)) },
            { it.copy(cameraPoseEvidence = it.cameraPoseEvidence!!.copy(forwardZ = 0f)) },
            { it.copy(cameraPoseEvidence = it.cameraPoseEvidence!!.copy(forwardX = 0.6f, forwardZ = -0.8f)) },
            { it.copy(cameraPoseEvidence = it.cameraPoseEvidence!!.copy(positionZ = -2f)) },
            { it.copy(cameraPoseEvidence = it.cameraPoseEvidence!!.copy(positionX = 0.3f)) },
        )
        invalidCurrent.forEachIndexed { index, invalidate ->
            val run = History()
            run.observe(sample(0L, 4f))
            run.observe(sample(500L, 0.6f))
            assertFalse("invalid evidence $index", run.observe(invalidate(sample(1_000L, 0.5f))).accepted)
            assertEquals(listOf(4f), run.distances())
        }
    }

    @Test
    fun missingEvidenceOnThePendingOrPreviousSampleCannotBeRepairedByOnlyTheNewestPose() {
        for (missingIndex in 0..1) {
            val run = History()
            listOf(4f, 0.6f, 0.5f).forEachIndexed { index, distance ->
                val observation = sample(index * 500L, distance).let {
                    if (index == missingIndex) it.copy(cameraPoseEvidence = null) else it
                }
                val decision = run.observe(observation)
                if (index == 2) assertFalse(decision.accepted)
            }
            assertEquals(listOf(4f), run.distances())
        }
    }

    @Test
    fun confirmationRequiresDistinctObservationsOneHundredToFifteenHundredMillisecondsApart() {
        listOf(599L, 2_001L).forEach { currentAt ->
            val run = History()
            run.observe(sample(0L, 4f))
            run.observe(sample(500L, 0.6f))
            assertFalse(run.observe(sample(currentAt, 0.5f)).accepted)
        }
        listOf(600L, 2_000L).forEach { currentAt ->
            val run = History()
            run.observe(sample(0L, 4f))
            run.observe(sample(500L, 0.6f))
            assertTrue(run.observe(sample(currentAt, 0.5f)).accepted)
        }
    }

    @Test
    fun establishedPredictionAlsoRequiresPoseEvidence() {
        val run = History()
        listOf(7f, 6f, 5f).forEachIndexed { index, distance ->
            run.observe(sample(index * 500L, distance).copy(cameraPoseEvidence = null))
        }
        assertFalse(run.observe(sample(1_500L, 3f)).accepted)
        assertEquals(listOf(7f, 6f, 5f), run.distances())
    }

    @Test
    fun smallForwardLengthErrorsDoNotPermitMoreThanFiveDegreesOfRotation() {
        val run = History()
        fun scaledPose(at: Long, distance: Float, angleDegrees: Double): DistanceObservation {
            val angle = Math.toRadians(angleDegrees)
            val observation = sample(at, distance)
            return observation.copy(cameraPoseEvidence = observation.cameraPoseEvidence!!.copy(
                forwardX = (Math.sin(angle) * 1.0004).toFloat(),
                forwardZ = (-Math.cos(angle) * 1.0004).toFloat(),
            ))
        }
        run.observe(scaledPose(0L, 4f, 0.0))
        run.observe(scaledPose(500L, 0.6f, 0.0))
        assertFalse(run.observe(scaledPose(1_000L, 0.5f, 5.2)).accepted)
        assertEquals(listOf(4f), run.distances())
    }

    private class History {
        private val policy = MetricDistanceContinuityPolicy()
        private val accepted = mutableListOf<DistanceObservation>()

        fun observe(observation: DistanceObservation): MetricDistanceContinuityDecision =
            policy.evaluate(observation, accepted, maxDepthJumpM = 1.2f).also { decision ->
                if (decision.resetHistory) accepted.clear()
                accepted += decision.observationsToAppend
            }

        fun distances(): List<Float> = accepted.map { it.distanceM }
    }

    private fun sample(at: Long, distanceM: Float) = DistanceObservation(
        timestampMs = at,
        distanceM = distanceM,
        source = DepthSource.ARCORE_RAW_DEPTH,
        confidence = 0.9f,
        cameraPoseEvidence = CameraPoseEvidence(
            referenceId = 1L, timestampMs = at,
            positionX = 0f, positionY = 0f, positionZ = 0f,
            forwardX = 0f, forwardY = 0f, forwardZ = -1f,
        ),
    )
}
