package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeMetricSingleFramePreflightTest {
    @Test
    fun oneCurrentTrackingFrameWithOneValidSampleIsEnough() {
        val result = session().observe(frame())

        assertEquals(RuntimeMetricPreflightStatus.AVAILABLE, result.status)
        assertEquals(true, result.metricDistanceAvailable)
        assertEquals(1, result.distinctFrameCount)
        assertEquals(1, result.passingFrameCount)
        assertEquals(0L, result.observationSpanMs)
    }

    @Test
    fun earlierMissingDepthDoesNotDelayTheFirstValidCurrentFrame() {
        val session = session()
        repeat(8) { index ->
            session.observe(frame(index = index, samples = 0))
        }

        val result = session.observe(frame(index = 8))

        assertEquals(RuntimeMetricPreflightStatus.AVAILABLE, result.status)
        assertEquals(1, result.distinctFrameCount)
        assertEquals(1, result.passingFrameCount)
        assertEquals(0L, result.observationSpanMs)
    }

    @Test
    fun missingDepthZeroSamplesAndUntrackedFramesCannotQualify() {
        listOf(
            frame(samples = 0),
            frame().copy(tracking = false),
            frame(samples = 0).copy(metricDepthAvailable = false),
            frame().copy(frameTimestampNanos = 0L),
        ).forEach { invalid ->
            val result = session().observe(invalid)
            assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, result.status)
            assertNull(result.metricDistanceAvailable)
        }
    }

    @Test
    fun missingProfileAndForeignGenerationStillCannotQualify() {
        val missingProfile = session(profile = null).observe(frame())
        assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, missingProfile.status)
        assertEquals(RuntimeMetricPreflightReason.PROFILE_UNAVAILABLE, missingProfile.reason)
        assertNull(missingProfile.metricDistanceAvailable)

        val foreign = session().observe(frame().copy(generation = 2L))
        assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, foreign.status)
        assertEquals(RuntimeMetricPreflightReason.GENERATION_MISMATCH, foreign.reason)
        assertNull(foreign.metricDistanceAvailable)
    }

    @Test
    fun aRegressedFrameCannotRecoverAnEarlierMissingDepthObservation() {
        val session = session()
        val first = frame(samples = 0)
        session.observe(first)

        val result = session.observe(frame(index = 1).copy(frameTimestampNanos = first.frameTimestampNanos - 1L))

        assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, result.status)
        assertEquals(RuntimeMetricPreflightReason.INVALID_FRAME_ORDER, result.reason)
        assertNull(result.metricDistanceAvailable)
    }

    @Test
    fun aFirstPassingFrameAtTheDeadlineIsStillTooLate() {
        val result = session().observe(frame().copy(
            observedAtElapsedRealtimeMs = START_MS + RuntimeMetricPreflightPolicy.MAX_DURATION_MS,
        ))

        assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, result.status)
        assertEquals(RuntimeMetricPreflightReason.TIMEOUT, result.reason)
        assertNull(result.metricDistanceAvailable)
    }

    @Test
    fun zeroSpanIsLimitedToSingleFrameProfilesAndDoesNotAdmitZeroSamples() {
        val profile = RuntimeMetricPreflightPolicy.testCandidateProfile
        assertEquals(0L, profile.minimumObservationSpanMs)
        assertEquals(1, profile.minimumDistinctFrames)
        assertEquals(1, profile.minimumValidSamplesPerPassingFrame)
        assertEquals(100, profile.minimumPassingPercent)
        assertThrows(IllegalArgumentException::class.java) { profile.copy(minimumDistinctFrames = 2) }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(minimumObservationSpanMs = -1L) }
        assertThrows(IllegalArgumentException::class.java) {
            profile.copy(minimumObservationSpanMs = profile.maximumDurationMs + 1L)
        }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(minimumValidSamplesPerPassingFrame = 0) }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(minimumValidDistanceMeters = 0.0) }
    }

    private fun session(profile: RuntimeMetricPreflightProfile? = RuntimeMetricPreflightPolicy.testCandidateProfile) =
        RuntimeMetricPreflightSession(
            generation = 1L,
            startedAtElapsedRealtimeMs = START_MS,
            depthSupport = RuntimeMetricDepthSupport.SUPPORTED,
            profile = profile,
        )

    private fun frame(index: Int = 0, samples: Int = 1) = RuntimeMetricFrameEvidence(
        generation = 1L,
        frameTimestampNanos = 1_000_000_000L + index * 33_000_000L,
        observedAtElapsedRealtimeMs = START_MS + index * 33L,
        tracking = true,
        metricDepthAvailable = true,
        validMetricSamplesInRange = samples,
    )

    private companion object {
        const val START_MS = 1_000L
    }
}
