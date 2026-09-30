package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-22: test-candidate thresholds must never be an implicit default of the preflight API. */
class RuntimeMetricPreflightProfileRequiredTest {
    @Test
    fun sessionApiHasNoConstructorThatSilentlySelectsTheTestCandidateProfile() {
        val constructors = RuntimeMetricPreflightSession::class.java.constructors
        assertTrue(constructors.isNotEmpty())
        constructors.forEach { constructor ->
            assertTrue(
                "every constructor must take the profile explicitly: $constructor",
                constructor.parameterTypes.contains(RuntimeMetricPreflightProfile::class.java),
            )
        }
    }

    @Test
    fun missingProfileFailsClosedAndPassingFramesCannotMakeItAvailable() {
        val session = RuntimeMetricPreflightSession(
            generation = 1L,
            startedAtElapsedRealtimeMs = 1_000L,
            depthSupport = RuntimeMetricDepthSupport.SUPPORTED,
            profile = null,
        )
        val initial = session.result()
        assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, initial.status)
        assertEquals(RuntimeMetricPreflightReason.PROFILE_UNAVAILABLE, initial.reason)
        assertNull(initial.metricDistanceAvailable)
        assertEquals(RuntimeMetricStartupDisposition.BLOCKED, initial.startupDisposition)
        assertEquals(1_000L, initial.completedAtElapsedRealtimeMs)

        repeat(40) { index ->
            val result = session.observe(
                RuntimeMetricFrameEvidence(
                    generation = 1L,
                    frameTimestampNanos = (index + 1) * 100_000_000L,
                    observedAtElapsedRealtimeMs = 1_000L + (index + 1) * 100L,
                    tracking = true,
                    metricDepthAvailable = true,
                    validMetricSamplesInRange = 1_000,
                ),
            )
            assertEquals(initial, result)
        }
        assertEquals(initial, session.expire(20_000L))
        assertEquals(initial, session.failTransiently(20_000L))
    }

    @Test
    fun explicitDepthUnsupportedRemainsAHardwareFactWithoutAProfile() {
        val result = RuntimeMetricPreflightSession(
            generation = 1L,
            startedAtElapsedRealtimeMs = 0L,
            depthSupport = RuntimeMetricDepthSupport.UNSUPPORTED,
            profile = null,
        ).result()
        assertEquals(RuntimeMetricPreflightStatus.UNSUPPORTED, result.status)
        assertEquals(RuntimeMetricPreflightReason.EXPLICIT_DEPTH_UNSUPPORTED, result.reason)
    }
}
