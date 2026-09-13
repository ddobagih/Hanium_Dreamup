package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.*
import org.junit.Test

class RuntimeSelectionPolicyTest {
    private val cpu = RuntimeCandidate(RuntimeBackend.CPU, 4)
    private val gpu = RuntimeCandidate(RuntimeBackend.GPU, 4)
    private val env = RuntimeComparisonEnvironment("a".repeat(64), RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH,
        12, 0, false, "ACTIVE", "TRACKING", 200, "live-v1")

    private fun block(order: RuntimeComparisonOrder, a: Double = 150.0, b: Double = 100.0) = RuntimeComparisonBlock(
        order, List(4) { RuntimePairedSample("case-${it % 3}", a, b, env, env) }, listOf(2.0, 1.0),
    )

    private fun live(backend: RuntimeBackend, latency: Double) = RuntimeLiveMetrics(
        env, backend, List(10) { latency }, 10_000, 10, 10, 0, 0, 33.0,
        3.0, 16.0, true, 10, true, true, 300, 1_000.0,
    )

    private fun summary(a: Double = 150.0, b: Double = 100.0) = RuntimeMeasurementSummary(
        block(RuntimeComparisonOrder.AB, a, b), block(RuntimeComparisonOrder.BA, a, b),
        block(RuntimeComparisonOrder.CONFIRMATION, a, b), live(RuntimeBackend.CPU, a), live(RuntimeBackend.GPU, b),
        RuntimeFixtureEvidence("fixture-v1", "b".repeat(64), 3, 3, true, RuntimeBackend.CPU, RuntimeBackend.GPU,
            requiredCaseIds = setOf("case-0", "case-1", "case-2"), completedCaseIds = setOf("case-0", "case-1", "case-2")), 1.0,
    )

    private fun evaluate(s: RuntimeMeasurementSummary = summary()) = RuntimeSelectionPolicy.evaluate(cpu, gpu, s, 1, 300_000)
    private fun notConfirmed(s: RuntimeMeasurementSummary) = assertNotEquals(RuntimeSelectionStatus.CONFIRMED, evaluate(s).status)

    @Test fun repeatedImprovementRequiresFullEvidenceAndCanBecomeProfile() {
        val result = evaluate()
        assertEquals(RuntimeSelectionStatus.CONFIRMED, result.status)
        assertEquals(gpu, result.selectedCandidate)
        val profile = requireNotNull(RuntimeTuningProfile.fromConfirmed(result, 123L))
        assertTrue(profile.isValid(env.bindingHash, 12))
        assertFalse(profile.isValid(env.bindingHash, 2))
        assertFalse(profile.isValid("c".repeat(64), 12))
        assertEquals(8, profile.measurement.comparedPairCount)
        assertEquals(4, profile.measurement.confirmedPairCount)
    }

    @Test fun baselineWinAndEquivalentKeepVerifiedBaseline() {
        for (s in listOf(summary(100.0, 150.0), summary(100.0, 100.0))) {
            val result = evaluate(s)
            assertEquals(RuntimeSelectionStatus.CONFIRMED, result.status)
            assertEquals(cpu, result.selectedCandidate)
            val profile = requireNotNull(RuntimeTuningProfile.fromConfirmed(result, 123))
            assertEquals(RuntimeBackend.CPU, profile.measurement.actualBackend)
            assertEquals(100.0, profile.measurement.selectedMedianMs, 0.0)
        }
    }

    @Test fun noiseCannotPromoteCandidateEvenWhenMadIsZero() {
        val s = summary(102.0, 100.0)
        assertEquals(cpu, evaluate(s).selectedCandidate)
        val noisy = summary().let { it.copy(ab = it.ab.copy(aaVariationMs = listOf(80.0))) }
        assertEquals(cpu, evaluate(noisy).selectedCandidate)
        val noConfirmation = summary().let { it.copy(confirmation = it.confirmation.copy(pairs = emptyList())) }
        notConfirmed(noConfirmation)
    }

    @Test fun oppositeOrderWinAndTimeDriftDoNotCancelOut() {
        notConfirmed(summary().copy(ba = block(RuntimeComparisonOrder.BA, 80.0, 120.0)))
        val s = summary()
        val hot = env.copy(thermalStatus = 3)
        notConfirmed(s.copy(ba = s.ba.copy(pairs = s.ba.pairs.map { it.copy(baselineEnvironment = hot, candidateEnvironment = hot) })))
        notConfirmed(s.copy(ab = s.ab.copy(aaVariationMs = listOf(100.0)), ba = block(RuntimeComparisonOrder.BA, 60.0, 200.0)))
    }

    @Test fun missingOrWrongOrderSamplesCannotSelect() {
        val s = summary()
        notConfirmed(s.copy(ab = s.ab.copy(pairs = s.ab.pairs.take(3))))
        notConfirmed(s.copy(ba = s.ba.copy(aaVariationMs = emptyList())))
        notConfirmed(s.copy(ab = s.ab.copy(order = RuntimeComparisonOrder.BA)))
        notConfirmed(s.copy(ab = s.ab.copy(pairs = s.ab.pairs.map { it.copy(speechActive = true) })))
        notConfirmed(s.copy(ab = s.ab.copy(pairs = s.ab.pairs.map { it.copy(candidateCompleted = false) })))
    }

    @Test fun fixedFixtureIsRequiredEvenForFastOrEmptyCameraScene() {
        val s = summary()
        notConfirmed(s.copy(fixture = s.fixture.copy(completedCaseCount = 2)))
        notConfirmed(s.copy(fixture = s.fixture.copy(requiredCaseCount = 0, completedCaseCount = 0)))
        notConfirmed(s.copy(fixture = s.fixture.copy(completedCaseIds = setOf("case-0", "case-1", "wrong-case"))))
        notConfirmed(s.copy(fixture = s.fixture.copy(requiredCaseIds = emptySet(), completedCaseIds = emptySet())))
        assertEquals(RuntimeSelectionStatus.REJECTED, evaluate(s.copy(fixture = s.fixture.copy(outputEquivalent = false))).status)
        notConfirmed(s.copy(candidateLive = s.candidateLive.copy(validCompletions = 0, captureToCompleteMs = emptyList())))
    }

    @Test fun unsupportedIsDifferentFromExecutionErrorOrSilentCpuFallback() {
        val s = summary()
        assertEquals(RuntimeSelectionStatus.UNSUPPORTED, evaluate(s.copy(fixture = s.fixture.copy(failure = RuntimeExecutionFailure.UNSUPPORTED))).status)
        assertEquals(RuntimeSelectionStatus.REJECTED, evaluate(s.copy(fixture = s.fixture.copy(failure = RuntimeExecutionFailure.EXECUTION_ERROR))).status)
        assertEquals(RuntimeSelectionStatus.REJECTED, evaluate(s.copy(fixture = s.fixture.copy(candidateActualBackend = RuntimeBackend.CPU))).status)
        notConfirmed(s.copy(candidateLive = s.candidateLive.copy(actualBackend = RuntimeBackend.CPU)))
    }

    @Test fun metricDepthRequiresCurrentPoseTrackingAndPositiveDepth() {
        val s = summary()
        for (live in listOf(s.candidateLive.copy(poseValid = false), s.candidateLive.copy(positiveDepthSamples = 0),
            s.candidateLive.copy(depthTimestampFresh = false), s.candidateLive.copy(adaptivePacing = false))) {
            notConfirmed(s.copy(candidateLive = live))
        }
        val paused = changeEnvironment(s, env.copy(arCoreState = "PAUSED"))
        notConfirmed(paused)
        val cameraOnly = changeEnvironment(s, env.copy(featureScope = RuntimeFeatureScope.CAMERA_TRACKING, arCoreState = "UNSUPPORTED"))
        assertEquals(RuntimeSelectionStatus.CONFIRMED, evaluate(cameraOnly.copy(
            baselineLive = cameraOnly.baselineLive.copy(poseValid = false, positiveDepthSamples = 0),
            candidateLive = cameraOnly.candidateLive.copy(poseValid = false, positiveDepthSamples = 0),
        )).status)
    }

    @Test fun anyLatencyTailCameraTrackingOrUiRegressionBlocksPromotion() {
        val s = summary()
        val regressions = listOf(
            s.candidateLive.copy(captureToCompleteMs = List(9) { 100.0 } + 900.0),
            s.candidateLive.copy(longestCompletionGapMs = 1_100.0),
            s.candidateLive.copy(cameraFrameIntervalMs = 50.0),
            s.candidateLive.copy(trackingCostMs = 10.0),
            s.candidateLive.copy(uiFrameDelayMs = 30.0),
            s.candidateLive.copy(submittedFrames = 11, staleFrames = 1),
            s.candidateLive.copy(submittedFrames = 11, droppedFrames = 1),
            s.candidateLive.copy(observationDurationMs = 11_000),
        )
        regressions.forEach { notConfirmed(s.copy(candidateLive = it)) }
    }

    @Test fun fixtureNoiseDoesNotExcuseLiveTailOrGapRegression() {
        val s = summary(150.0, 50.0)
        val noisy = s.copy(ab = s.ab.copy(aaVariationMs = listOf(80.0)),
            ba = s.ba.copy(aaVariationMs = listOf(80.0)), confirmation = s.confirmation.copy(aaVariationMs = listOf(80.0)))
        notConfirmed(noisy.copy(candidateLive = noisy.candidateLive.copy(captureToCompleteMs = List(9) { 50.0 } + 220.0)))
        notConfirmed(noisy.copy(candidateLive = noisy.candidateLive.copy(longestCompletionGapMs = 1_070.0)))
    }

    @Test fun queueSnapshotFollowsConfirmedBaselineForNextAttemptResume() {
        val queue = RuntimeSelectionPolicy.createCandidateQueue(12, cpu, true)
        queue.recordOutcome(requireNotNull(queue.nextCandidate(12)), RuntimeSelectionStatus.CONFIRMED, RuntimeBackend.GPU, gpu)
        val snapshot = queue.snapshot()
        assertEquals(gpu, snapshot.baseline)
        val resumed = RuntimeSelectionPolicy.createCandidateQueue(12, gpu, true, restored = snapshot)
        assertEquals(cpu, resumed.nextCandidate(12))
    }

    @Test fun finalRecreatedCandidateSettlesItsScreenAndUpdatesPersistedBaseline() {
        val queue = RuntimeSelectionPolicy.createCandidateQueue(12, cpu, true)
        queue.recordOutcome(requireNotNull(queue.nextCandidate(12)), RuntimeSelectionStatus.INCONCLUSIVE, RuntimeBackend.GPU)
        assertTrue(queue.hasPendingReevaluation())
        assertTrue(queue.recordFinalDecision(evaluate()))
        assertFalse(queue.hasPendingReevaluation())
        assertEquals(gpu, queue.snapshot().baseline)
        assertEquals(RuntimeSelectionStatus.CONFIRMED, queue.snapshot().outcomes.single().status)
    }

    @Test fun finalDecisionCannotBypassMissingScreenOrValidationOrActiveWork() {
        val queue = RuntimeSelectionPolicy.createCandidateQueue(12, cpu, true)
        assertFalse(queue.recordFinalDecision(evaluate()))
        val current = requireNotNull(queue.nextCandidate(12))
        assertFalse(queue.recordFinalDecision(evaluate()))
        queue.recordOutcome(current, RuntimeSelectionStatus.INCONCLUSIVE)
        assertFalse(queue.recordFinalDecision(evaluate().copy(status = RuntimeSelectionStatus.PARTIAL)))
        assertFalse(queue.recordFinalDecision(evaluate().copy(measurement = summary().copy(clockResolutionMs = Double.NaN))))
        assertFalse(queue.recordFinalDecision(evaluate(changeEnvironment(summary(), env.copy(availableProcessors = 8)))))
        assertEquals(cpu, queue.snapshot().baseline)
        assertTrue(queue.hasPendingReevaluation())
    }

    @Test fun invalidNumbersAndInconsistentCountersFailClosed() {
        val s = summary()
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            notConfirmed(s.copy(ab = s.ab.copy(pairs = s.ab.pairs.map { it.copy(baselineMs = value) })))
            notConfirmed(s.copy(candidateLive = s.candidateLive.copy(cameraFrameIntervalMs = value)))
            notConfirmed(s.copy(clockResolutionMs = value))
        }
        for (live in listOf(s.candidateLive.copy(observationDurationMs = 0), s.candidateLive.copy(submittedFrames = 9),
            s.candidateLive.copy(staleFrames = 1), s.candidateLive.copy(uniqueCameraFrames = 0),
            s.candidateLive.copy(validCompletions = 9, captureToCompleteMs = List(9) { 100.0 }))) {
            notConfirmed(s.copy(candidateLive = live))
        }
    }

    @Test fun malformedConfirmedDecisionAndProfileCannotBypassValidation() {
        val result = evaluate()
        assertNull(RuntimeTuningProfile.fromConfirmed(result.copy(status = RuntimeSelectionStatus.PARTIAL), 123))
        assertNull(RuntimeTuningProfile.fromConfirmed(result.copy(baselineCandidate = null), 123))
        assertNull(RuntimeTuningProfile.fromConfirmed(result.copy(selectedCandidate = cpu), 123))
        assertNull(RuntimeTuningProfile.fromConfirmed(result.copy(measurement = summary().copy(clockResolutionMs = Double.NaN)), 123))
        val p = requireNotNull(RuntimeTuningProfile.fromConfirmed(result, 123))
        assertFalse(p.copy(measurement = p.measurement.copy(selectedMedianMs = Double.NaN)).isValid(env.bindingHash, 12))
        assertFalse(p.copy(measurement = p.measurement.copy(confirmedPairCount = 3)).isValid(env.bindingHash, 12))
    }

    @Test fun initialCpuTieUsesFewerThreadsButPreviouslyConfirmedValueStays() {
        val fewer = RuntimeCandidate(RuntimeBackend.CPU, 3)
        val s = summary(100.0, 100.0).let { it.copy(fixture = it.fixture.copy(candidateActualBackend = RuntimeBackend.CPU),
            candidateLive = it.candidateLive.copy(actualBackend = RuntimeBackend.CPU)) }
        val initial = RuntimeSelectionPolicy.evaluate(cpu, fewer, s, 1, 10, false)
        assertEquals(fewer, initial.selectedCandidate)
        assertNotNull(RuntimeTuningProfile.fromConfirmed(initial, 123))
        assertEquals(cpu, RuntimeSelectionPolicy.evaluate(cpu, fewer, s, 1, 10).selectedCandidate)
    }

    @Test fun deadlineIsSharedCappedAndCannotBeRenewedOrOverflowed() {
        assertEquals(300_100, RuntimeSelectionPolicy.deadline(100, 999_999))
        assertEquals(Long.MAX_VALUE, RuntimeSelectionPolicy.deadline(Long.MAX_VALUE - 10))
        assertFalse(RuntimeSelectionPolicy.canStartCandidate(280_000, 300_000, 5_000, 20_000))
        assertFalse(RuntimeSelectionPolicy.canStartCandidate(0, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        assertTrue(RuntimeSelectionPolicy.canStartCandidate(1, 300_000, 50_000, 20_000))
        assertEquals(RuntimeSelectionStatus.PARTIAL, RuntimeSelectionPolicy.evaluate(cpu, gpu, summary(), 300_000, 300_000).status)
    }

    @Test fun completeIntegerDomainsIncludeNonPowersOfTwoWithoutDuplicates() {
        for (n in listOf(1, 2, 3, 8, 12, 17)) {
            val queue = RuntimeSelectionPolicy.createCandidateQueue(n, RuntimeSelectionPolicy.initialCpuCandidate(n), true)
            val found = mutableListOf<RuntimeCandidate>()
            repeat(n * 2) {
                val next = requireNotNull(queue.nextCandidate(n))
                found.add(next)
                queue.recordOutcome(next, RuntimeSelectionStatus.REJECTED)
            }
            assertEquals(n * 2, found.distinct().size)
            RuntimeBackend.entries.forEach { backend -> assertEquals((1..n).toSet(), found.filter { it.backend == backend }.map { it.numThreads }.toSet()) }
            assertNull(queue.nextCandidate(n))
        }
    }

    @Test fun gpuFirstThenCpuAndMeasuredFamilyNeighbors() {
        val queue = RuntimeSelectionPolicy.createCandidateQueue(12, cpu, true)
        assertEquals(gpu, queue.nextCandidate(12))
        queue.recordOutcome(gpu, RuntimeSelectionStatus.CONFIRMED, RuntimeBackend.GPU)
        assertEquals(cpu, queue.nextCandidate(12))
        queue.recordOutcome(cpu, RuntimeSelectionStatus.CONFIRMED, RuntimeBackend.GPU, gpu)
        assertEquals(RuntimeCandidate(RuntimeBackend.GPU, 3), queue.nextCandidate(12))
    }

    @Test fun partialGpuDoesNotStarveCpuOrRemainAtQueueHead() {
        val queue = RuntimeSelectionPolicy.createCandidateQueue(2, RuntimeSelectionPolicy.initialCpuCandidate(2), true)
        val first = requireNotNull(queue.nextCandidate(2))
        queue.recordOutcome(first, RuntimeSelectionStatus.PARTIAL)
        assertEquals(RuntimeBackend.CPU, queue.nextCandidate(2)?.backend)
        repeat(3) { val next = requireNotNull(queue.nextCandidate(2)); queue.recordOutcome(next, RuntimeSelectionStatus.REJECTED) }
        assertEquals(first, queue.nextCandidate(2))
        assertTrue(queue.hasPendingReevaluation())
    }

    @Test fun partialAndUntriedCandidatesHaveDifferentPendingMeaning() {
        val queue = RuntimeSelectionPolicy.createCandidateQueue(12, cpu, true)
        queue.recordOutcome(requireNotNull(queue.nextCandidate(12)), RuntimeSelectionStatus.CONFIRMED, RuntimeBackend.GPU)
        queue.recordOutcome(requireNotNull(queue.nextCandidate(12)), RuntimeSelectionStatus.REJECTED, RuntimeBackend.GPU)
        assertFalse(queue.hasPendingReevaluation())
        queue.recordOutcome(requireNotNull(queue.nextCandidate(12)), RuntimeSelectionStatus.PARTIAL, RuntimeBackend.GPU)
        assertTrue(queue.hasPendingReevaluation())
    }

    @Test fun preferredPartialRetryPrecedesUntriedLowPriorityCpuValues() {
        val base = RuntimeSelectionPolicy.initialCpuCandidate(2)
        val queue = RuntimeSelectionPolicy.createCandidateQueue(2, base, true)
        val firstGpu = requireNotNull(queue.nextCandidate(2))
        queue.recordOutcome(firstGpu, RuntimeSelectionStatus.CONFIRMED, RuntimeBackend.GPU, firstGpu)
        queue.recordOutcome(requireNotNull(queue.nextCandidate(2)), RuntimeSelectionStatus.REJECTED, RuntimeBackend.GPU)
        val partialGpu = requireNotNull(queue.nextCandidate(2))
        queue.recordOutcome(partialGpu, RuntimeSelectionStatus.PARTIAL, RuntimeBackend.GPU)
        assertEquals(partialGpu, queue.nextCandidate(2))
    }

    @Test fun savedGpuThreadCountAnchorsNeighborsEvenWhenInitialScreenIsPartial() {
        val base = RuntimeCandidate(RuntimeBackend.GPU, 7)
        val queue = RuntimeSelectionPolicy.createCandidateQueue(12, base, true)
        queue.recordOutcome(requireNotNull(queue.nextCandidate(12)), RuntimeSelectionStatus.PARTIAL)
        queue.recordOutcome(requireNotNull(queue.nextCandidate(12)), RuntimeSelectionStatus.PARTIAL)
        assertEquals(RuntimeCandidate(RuntimeBackend.GPU, 6), queue.nextCandidate(12))
    }

    @Test fun neighborsCanFailWithoutExcludingNonmonotonicWinner() {
        val queue = RuntimeSelectionPolicy.createCandidateQueue(8, cpu, false)
        val seen = mutableListOf<Int>()
        repeat(8) {
            val next = requireNotNull(queue.nextCandidate(8))
            seen.add(next.numThreads)
            queue.recordOutcome(next, RuntimeSelectionStatus.REJECTED)
        }
        assertEquals(listOf(4, 3, 5), seen.take(3))
        assertTrue(7 in seen)
    }

    @Test fun changedCpuAvailabilityInvalidatesCurrentQueueRatherThanClamping() {
        val queue = RuntimeSelectionPolicy.createCandidateQueue(12, cpu, true)
        queue.nextCandidate(12)
        assertNull(queue.nextCandidate(8))
        assertTrue(queue.hasPendingReevaluation())
        assertNull(queue.nextCandidate(12))
        assertEquals(1, RuntimeSelectionPolicy.initialCpuCandidate(0).numThreads)
    }

    @Test fun hugeCpuHintStaysLazyAndOverflowSafeAtBothEnds() {
        val base = RuntimeCandidate(RuntimeBackend.CPU, Int.MAX_VALUE)
        val queue = RuntimeSelectionPolicy.createCandidateQueue(Int.MAX_VALUE, base, false)
        assertEquals(base, queue.nextCandidate(Int.MAX_VALUE))
        queue.recordOutcome(base, RuntimeSelectionStatus.REJECTED)
        assertEquals(Int.MAX_VALUE - 1, queue.nextCandidate(Int.MAX_VALUE)?.numThreads)
        val snapshot = queue.snapshot().copy(cpuCursor = Int.MAX_VALUE)
        val resumed = RuntimeSelectionPolicy.createCandidateQueue(Int.MAX_VALUE, base, false, restored = snapshot)
        assertEquals(1, resumed.nextCandidate(Int.MAX_VALUE)?.numThreads)
        assertTrue(resumed.snapshot().outcomes.size < 4)
    }

    @Test fun resumeSkipsCompletedAndCarriesPartialWithoutPriorSamples() {
        val queue = RuntimeSelectionPolicy.createCandidateQueue(3, RuntimeSelectionPolicy.initialCpuCandidate(3), true)
        val first = requireNotNull(queue.nextCandidate(3))
        queue.recordOutcome(first, RuntimeSelectionStatus.REJECTED)
        val second = requireNotNull(queue.nextCandidate(3))
        queue.recordOutcome(second, RuntimeSelectionStatus.PARTIAL)
        val resumed = RuntimeSelectionPolicy.createCandidateQueue(3, RuntimeSelectionPolicy.initialCpuCandidate(3), true, restored = queue.snapshot())
        val next = requireNotNull(resumed.nextCandidate(3))
        assertNotEquals(first, next)
        assertNotEquals(second, next)
    }

    @Test fun unsupportedGpuSkipsEntireFamilyAndIsNotExecutionFailure() {
        val queue = RuntimeSelectionPolicy.createCandidateQueue(3, RuntimeSelectionPolicy.initialCpuCandidate(3), true)
        queue.recordOutcome(requireNotNull(queue.nextCandidate(3)), RuntimeSelectionStatus.UNSUPPORTED)
        repeat(3) {
            val next = requireNotNull(queue.nextCandidate(3))
            assertEquals(RuntimeBackend.CPU, next.backend)
            queue.recordOutcome(next, RuntimeSelectionStatus.REJECTED)
        }
        assertNull(queue.nextCandidate(3))
        assertFalse(queue.hasPendingReevaluation())
    }

    private fun changeEnvironment(s: RuntimeMeasurementSummary, e: RuntimeComparisonEnvironment): RuntimeMeasurementSummary {
        fun change(b: RuntimeComparisonBlock) = b.copy(pairs = b.pairs.map { it.copy(baselineEnvironment = e, candidateEnvironment = e) })
        return s.copy(ab = change(s.ab), ba = change(s.ba), confirmation = change(s.confirmation),
            baselineLive = s.baselineLive.copy(environment = e), candidateLive = s.candidateLive.copy(environment = e))
    }
}
