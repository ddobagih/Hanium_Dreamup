package kr.co.hanium.dreamup.walksafe.device

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMetricPreflightTest {
    @Test
    fun metricSampleRangeIsInclusiveFromPointTwoToEightMeters() {
        assertTrue(RuntimeMetricPreflightPolicy.isValidMetricDistanceMeters(0.2))
        assertTrue(RuntimeMetricPreflightPolicy.isValidMetricDistanceMeters(8.0))
        assertFalse(RuntimeMetricPreflightPolicy.isValidMetricDistanceMeters(0.199))
        assertFalse(RuntimeMetricPreflightPolicy.isValidMetricDistanceMeters(8.001))
        assertFalse(RuntimeMetricPreflightPolicy.isValidMetricDistanceMeters(Double.NaN))
        assertFalse(RuntimeMetricPreflightPolicy.isValidMetricDistanceMeters(Double.POSITIVE_INFINITY))
    }

    @Test
    fun tenIncreasingFramesOverOneSecondWithExactlyEightyPercentPassingAreAvailable() {
        val session = supportedSession()

        repeat(10) { index ->
            session.observe(frame(index = index, passing = index < 8))
        }

        val result = session.result()
        assertEquals(RuntimeMetricPreflightStatus.AVAILABLE, result.status)
        assertEquals(RuntimeMetricPreflightReason.STABLE_METRIC_DEPTH, result.reason)
        assertEquals(RuntimeMetricStartupDisposition.FULL_ELIGIBLE, result.startupDisposition)
        assertEquals(true, result.metricDistanceAvailable)
        assertEquals(10, result.distinctFrameCount)
        assertEquals(8, result.passingFrameCount)
        assertTrue(result.observationSpanMs >= 1_000L)
    }

    @Test
    fun allTerminalResultsIgnoreLateCallbacks() {
        val availableSession = supportedSession()
        repeat(10) { index ->
            availableSession.observe(frame(index = index, passing = true))
        }
        val unsupportedSession = RuntimeMetricPreflightSession(
            generation = 2L,
            startedAtElapsedRealtimeMs = START_MS,
            depthSupport = RuntimeMetricDepthSupport.UNSUPPORTED,
        )
        val unknownSession = supportedSession(generation = 3L)
        unknownSession.failTransiently(START_MS + 500L)

        listOf(availableSession, unsupportedSession, unknownSession).forEach { session ->
            val terminalResult = session.result()

            assertLateCallbacksAreNoOps(session, terminalResult)
        }
    }

    @Test
    fun startupFailuresAgeOutButTheRecentWindowStillRequiresEightyPercentPassing() {
        val session = supportedSession()
        repeat(30) { index -> session.observe(frame(index, passing = false)) }
        assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, session.result().status)

        repeat(7) { index -> session.observe(frame(30 + index, passing = true)) }
        val insufficient = session.result()
        assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, insufficient.status)
        assertEquals(10, insufficient.distinctFrameCount)
        assertEquals(7, insufficient.passingFrameCount)

        val available = session.observe(frame(37, passing = true))
        assertEquals(RuntimeMetricPreflightStatus.AVAILABLE, available.status)
        assertEquals(10, available.distinctFrameCount)
        assertEquals(8, available.passingFrameCount)
        assertTrue(available.observationSpanMs >= 1_000L)
    }

    @Test
    fun recentWindowRetainsAtLeastOneSecondAtCameraFrameRate() {
        val session = supportedSession()
        repeat(90) { index ->
            session.observe(frame(index, passing = false).copy(
                frameTimestampNanos = FIRST_FRAME_NS + index * 33_000_000L,
                observedAtElapsedRealtimeMs = START_MS + index * 33L,
            ))
        }
        val result = session.result()
        assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, result.status)
        assertEquals(32, result.distinctFrameCount)
        assertEquals(1_023L, result.observationSpanMs)
        assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, session.expire(START_MS + 10_000L).status)
    }

    @Test
    fun observeAndExpireUseTheSameFailClosedDeadlineBoundary() {
        val deadlineMs = START_MS + RuntimeMetricPreflightPolicy.MAX_DURATION_MS

        listOf(-1L, 0L, 1L).forEach { offsetMs ->
            val nowMs = deadlineMs + offsetMs
            val observed = supportedSession().observe(
                frame(index = 0, passing = false).copy(
                    observedAtElapsedRealtimeMs = nowMs,
                ),
            )
            val expired = supportedSession().expire(nowMs)

            if (offsetMs < 0L) {
                assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, observed.status)
                assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, expired.status)
                assertNull(observed.completedAtElapsedRealtimeMs)
                assertNull(expired.completedAtElapsedRealtimeMs)
            } else {
                listOf(observed, expired).forEach { result ->
                    assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, result.status)
                    assertEquals(RuntimeMetricPreflightReason.TIMEOUT, result.reason)
                    assertEquals(nowMs, result.completedAtElapsedRealtimeMs)
                }
            }
        }
    }

    @Test
    fun exactDeadlineOutcomeDoesNotDependOnObserveOrExpireCallOrder() {
        val observeThenExpire = supportedSession().also { session ->
            repeat(9) { index -> session.observe(frame(index = index, passing = true)) }
        }
        val expireThenObserve = supportedSession().also { session ->
            repeat(9) { index -> session.observe(frame(index = index, passing = true)) }
        }
        val deadlineFrame = frame(index = 9, passing = true).copy(
            observedAtElapsedRealtimeMs = START_MS + RuntimeMetricPreflightPolicy.MAX_DURATION_MS,
        )

        val observeFirst = observeThenExpire.observe(deadlineFrame)
        val observeFirstAfterExpire = observeThenExpire.expire(
            START_MS + RuntimeMetricPreflightPolicy.MAX_DURATION_MS,
        )
        val expireFirst = expireThenObserve.expire(
            START_MS + RuntimeMetricPreflightPolicy.MAX_DURATION_MS,
        )
        val expireFirstAfterObserve = expireThenObserve.observe(deadlineFrame)

        assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, observeFirst.status)
        assertEquals(RuntimeMetricPreflightReason.TIMEOUT, observeFirst.reason)
        assertEquals(observeFirst, observeFirstAfterExpire)
        assertEquals(observeFirst, expireFirst)
        assertEquals(expireFirst, expireFirstAfterObserve)
    }

    @Test
    fun fewerThanEightyPercentPassingNeverBecomesAvailableAndTimesOutBlocked() {
        val session = supportedSession()

        repeat(10) { index ->
            session.observe(frame(index = index, passing = index < 7))
        }

        assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, session.result().status)
        val result = session.expire(nowElapsedRealtimeMs = START_MS + 10_000L)
        assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, result.status)
        assertEquals(RuntimeMetricPreflightReason.TIMEOUT, result.reason)
        assertEquals(RuntimeMetricStartupDisposition.BLOCKED, result.startupDisposition)
        assertNull(result.metricDistanceAvailable)
    }

    @Test
    fun tenPassingFramesWithoutOneSecondObservationRemainBlockedUntilTimeout() {
        val session = supportedSession()

        repeat(10) { index ->
            session.observe(
                frame(index = index, passing = true).copy(
                    frameTimestampNanos = FIRST_FRAME_NS + index * 100_000_000L,
                    observedAtElapsedRealtimeMs = START_MS + index * 100L,
                ),
            )
        }

        assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, session.result().status)
        assertEquals(
            RuntimeMetricPreflightStatus.UNKNOWN,
            session.expire(START_MS + 10_000L).status,
        )
    }

    @Test
    fun aPassingFrameNeedsTrackingMetricDepthAndThirtyValidSamples() {
        val session = supportedSession()
        val failures = listOf(
            frame(index = 0, passing = true).copy(tracking = false),
            frame(index = 1, passing = true).copy(metricDepthAvailable = false, validMetricSamplesInRange = 0),
            frame(index = 2, passing = true).copy(validMetricSamplesInRange = 29),
        )

        failures.forEach(session::observe)
        session.observe(frame(index = 3, passing = true).copy(validMetricSamplesInRange = 30))

        val result = session.result()
        assertEquals(4, result.distinctFrameCount)
        assertEquals(1, result.passingFrameCount)
    }

    @Test
    fun zeroAndDuplicateTimestampsAreSkippedUntilDistinctFramesArrive() {
        val session = supportedSession()
        val zeroTimestamp = frame(index = 0, passing = true).copy(frameTimestampNanos = 0L)
        val first = frame(index = 0, passing = true).copy(observedAtElapsedRealtimeMs = START_MS + 1L)
        val duplicate = first.copy(observedAtElapsedRealtimeMs = START_MS + 2L)

        assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, session.observe(zeroTimestamp).status)
        assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, session.observe(first).status)
        val duplicateResult = session.observe(duplicate)

        assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, duplicateResult.status)
        assertEquals(RuntimeMetricPreflightReason.AWAITING_STABLE_EVIDENCE, duplicateResult.reason)
        assertEquals(1, duplicateResult.distinctFrameCount)
        assertEquals(1, duplicateResult.passingFrameCount)
    }

    @Test
    fun outOfOrderTimestampStillFailsClosed() {
        val session = supportedSession()
        val first = frame(index = 0, passing = true)
        session.observe(first)

        val result = session.observe(
            frame(index = 1, passing = true).copy(
                frameTimestampNanos = first.frameTimestampNanos - 1L,
            ),
        )

        assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, result.status)
        assertEquals(RuntimeMetricPreflightReason.INVALID_FRAME_ORDER, result.reason)
        assertEquals(RuntimeMetricStartupDisposition.BLOCKED, result.startupDisposition)
    }

    @Test
    fun malformedFrameEvidenceFailsClosed() {
        val malformedFrames = listOf(
            frame(index = 0, passing = true).copy(frameTimestampNanos = -1L),
            frame(index = 0, passing = true).copy(observedAtElapsedRealtimeMs = START_MS - 1L),
            frame(index = 0, passing = true).copy(validMetricSamplesInRange = -1),
            frame(index = 0, passing = true).copy(
                metricDepthAvailable = false,
                validMetricSamplesInRange = 1,
            ),
        )

        malformedFrames.forEach { malformed ->
            val result = supportedSession().observe(malformed)

            assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, result.status)
            assertEquals(RuntimeMetricPreflightReason.INVALID_FRAME_EVIDENCE, result.reason)
        }
    }

    @Test
    fun explicitUnsupportedIsTheOnlyLimitedUnsupportedOutcome() {
        val unsupported = RuntimeMetricPreflightSession(
            generation = 1L,
            startedAtElapsedRealtimeMs = START_MS,
            depthSupport = RuntimeMetricDepthSupport.UNSUPPORTED,
        ).result()
        val unknown = RuntimeMetricPreflightSession(
            generation = 2L,
            startedAtElapsedRealtimeMs = START_MS,
            depthSupport = RuntimeMetricDepthSupport.UNKNOWN,
        ).expire(START_MS + 10_000L)

        assertEquals(RuntimeMetricPreflightStatus.UNSUPPORTED, unsupported.status)
        assertEquals(RuntimeMetricPreflightReason.EXPLICIT_DEPTH_UNSUPPORTED, unsupported.reason)
        assertEquals(RuntimeMetricStartupDisposition.LIMITED, unsupported.startupDisposition)
        assertEquals(false, unsupported.metricDistanceAvailable)
        assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, unknown.status)
        assertEquals(RuntimeMetricStartupDisposition.BLOCKED, unknown.startupDisposition)
        assertNull(unknown.metricDistanceAvailable)
    }

    @Test
    fun timeoutTransientFailureAndLifecycleCancellationAreUnknownAndBlocked() {
        val timeout = supportedSession().expire(START_MS + 10_000L)
        val transient = supportedSession().failTransiently(START_MS + 500L)
        val cancelled = supportedSession().cancelForLifecycle(START_MS + 500L)

        listOf(timeout, transient, cancelled).forEach { result ->
            assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, result.status)
            assertEquals(RuntimeMetricStartupDisposition.BLOCKED, result.startupDisposition)
            assertNull(result.metricDistanceAvailable)
        }
        assertEquals(RuntimeMetricPreflightReason.TIMEOUT, timeout.reason)
        assertEquals(RuntimeMetricPreflightReason.TRANSIENT_FAILURE, transient.reason)
        assertEquals(RuntimeMetricPreflightReason.LIFECYCLE_CANCELLED, cancelled.reason)
    }

    @Test
    fun mismatchedGenerationCannotContributeEvidence() {
        val deadlineMs = START_MS + RuntimeMetricPreflightPolicy.MAX_DURATION_MS

        listOf(START_MS + 321L, deadlineMs, deadlineMs + 1L).forEach { observedAtMs ->
            val result = supportedSession(generation = 9L).observe(
                frame(index = 0, passing = true).copy(
                    generation = 8L,
                    observedAtElapsedRealtimeMs = observedAtMs,
                ),
            )

            assertEquals(RuntimeMetricPreflightStatus.UNKNOWN, result.status)
            assertEquals(RuntimeMetricPreflightReason.GENERATION_MISMATCH, result.reason)
            assertEquals(0, result.distinctFrameCount)
            assertNull(result.completedAtElapsedRealtimeMs)
        }
    }

    @Test
    fun aNewGenerationDoesNotReusePriorGenerationEvidence() {
        val first = supportedSession(generation = 1L)
        repeat(10) { index -> first.observe(frame(index = index, passing = true, generation = 1L)) }
        assertEquals(RuntimeMetricPreflightStatus.AVAILABLE, first.result().status)

        val second = supportedSession(generation = 2L)

        assertEquals(2L, second.result().generation)
        assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, second.result().status)
        assertEquals(0, second.result().distinctFrameCount)
    }

    @Test
    fun evidenceAndResultModelsCannotRetainRawImageOrDepthArrays() {
        val modelClasses = listOf(
            RuntimeMetricFrameEvidence::class.java,
            RuntimeMetricPreflightResult::class.java,
        )

        modelClasses.flatMap { it.declaredFields.toList() }.forEach { field ->
            assertFalse(field.type.isArray)
            assertFalse(field.name.equals("image", ignoreCase = true))
            assertFalse(field.name.contains("buffer", ignoreCase = true))
            assertFalse(field.name.contains("rawDepth", ignoreCase = true))
        }
    }

    @Test
    fun evaluatorTransitionsAreSynchronizedAcrossGlAndMainThreads() {
        val methods = listOf("result", "observe", "expire", "failTransiently", "cancelForLifecycle")

        methods.forEach { name ->
            val method = RuntimeMetricPreflightSession::class.java.declaredMethods.single {
                it.name == name
            }
            assertTrue("$name must be synchronized", Modifier.isSynchronized(method.modifiers))
        }
    }

    private fun assertLateCallbacksAreNoOps(
        session: RuntimeMetricPreflightSession,
        terminalResult: RuntimeMetricPreflightResult,
    ) {
        val afterDeadlineMs =
            START_MS + RuntimeMetricPreflightPolicy.MAX_DURATION_MS + 1L
        val lateResults = listOf(
            session.observe(
                frame(
                    index = 10,
                    passing = true,
                    generation = session.generation + 1L,
                ).copy(observedAtElapsedRealtimeMs = afterDeadlineMs),
            ),
            session.observe(
                frame(
                    index = 10,
                    passing = true,
                    generation = session.generation,
                ).copy(observedAtElapsedRealtimeMs = afterDeadlineMs),
            ),
            session.failTransiently(afterDeadlineMs),
            session.cancelForLifecycle(afterDeadlineMs),
            session.expire(afterDeadlineMs),
        )

        lateResults.forEach { result -> assertEquals(terminalResult, result) }
        assertEquals(terminalResult, session.result())
    }

    private fun supportedSession(generation: Long = 1L) = RuntimeMetricPreflightSession(
        generation = generation,
        startedAtElapsedRealtimeMs = START_MS,
        depthSupport = RuntimeMetricDepthSupport.SUPPORTED,
    )

    private fun frame(
        index: Int,
        passing: Boolean,
        generation: Long = 1L,
    ) = RuntimeMetricFrameEvidence(
        generation = generation,
        frameTimestampNanos = FIRST_FRAME_NS + index * 125_000_000L,
        observedAtElapsedRealtimeMs = START_MS + index * 125L,
        tracking = passing,
        metricDepthAvailable = passing,
        validMetricSamplesInRange = if (passing) 30 else 0,
    )

    private companion object {
        const val START_MS = 5_000L
        const val FIRST_FRAME_NS = 1_000_000_000L
    }
}
