package kr.co.hanium.dreamup.walksafe.inference.unknown.tuning

import kr.co.hanium.dreamup.walksafe.inference.*
import org.junit.Assert.*
import org.junit.Test

class UnknownRuntimeTuningTest {
    private val cpu = RuntimeCandidate(RuntimeBackend.CPU, 4)
    private val gpu = RuntimeCandidate(RuntimeBackend.GPU, 4)
    private val binding = UnknownRuntimeBinding(
        primarySha256 = "a".repeat(64), fastSha256 = "b".repeat(64),
        primaryPreprocessing = "primary-rgb-768-v1", fastPreprocessing = "fast-letterbox-768-v1",
        primaryDecoder = "primary-decoder-v1", fastDecoder = "fast-mask-threshold-v1",
        primaryRuntime = gpu, scheduleVersion = "primary-priority-v1", deviceIdentity = "test-device-v1",
        runtimeVersion = "test-runtime-v1", environmentVersion = "tracking-depth-v1",
        fixtureVersion = "unknown-positive-empty-threshold-v1", fixtureSha256 = "c".repeat(64),
        featureScope = RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH,
    )
    private val primaryLoad = UnknownPrimaryLoad(10_000, List(10) { 80.0 }, 1_000.0, 0)
    private val caseIds = setOf("positive", "empty", "threshold")

    @Test
    fun everyWorkloadAndEnvironmentIdentityChangeInvalidatesAnExistingProfile() {
        val profile = requireNotNull(confirm())
        val originalHash = requireNotNull(binding.hashOrNull())
        assertEquals(originalHash, binding.copy().hashOrNull())
        assertTrue(profile.isValid(binding, 12))
        val changes = linkedMapOf(
            "primary SHA" to binding.copy(primarySha256 = "d".repeat(64)),
            "FastSAM SHA" to binding.copy(fastSha256 = "d".repeat(64)),
            "primary preprocessing" to binding.copy(primaryPreprocessing = "primary-rgb-768-v2"),
            "FastSAM preprocessing" to binding.copy(fastPreprocessing = "fast-letterbox-768-v2"),
            "primary decoder" to binding.copy(primaryDecoder = "primary-decoder-v2"),
            "FastSAM decoder and threshold" to binding.copy(fastDecoder = "fast-mask-threshold-v2"),
            "primary backend" to binding.copy(primaryRuntime = cpu),
            "primary threads" to binding.copy(primaryRuntime = gpu.copy(numThreads = 3)),
            "schedule" to binding.copy(scheduleVersion = "primary-priority-v2"),
            "device" to binding.copy(deviceIdentity = "test-device-v2"),
            "runtime" to binding.copy(runtimeVersion = "test-runtime-v2"),
            "environment" to binding.copy(environmentVersion = "tracking-depth-v2"),
            "fixture version" to binding.copy(fixtureVersion = "unknown-positive-empty-threshold-v2"),
            "fixture SHA" to binding.copy(fixtureSha256 = "d".repeat(64)),
            "feature scope" to binding.copy(featureScope = RuntimeFeatureScope.CAMERA_TRACKING),
        )
        changes.forEach { (name, changed) ->
            assertNotEquals(name, originalHash, changed.hashOrNull())
            assertFalse(name, profile.isValid(changed, 12))
        }
    }

    @Test
    fun bindingRejectsMalformedIdentityAndDistinguishesAdjacentFieldBoundaries() {
        val invalid = listOf(
            binding.copy(primarySha256 = "a".repeat(63)),
            binding.copy(fastSha256 = "B".repeat(64)),
            binding.copy(fixtureSha256 = "g".repeat(64)),
            binding.copy(primaryPreprocessing = " "), binding.copy(fastPreprocessing = ""),
            binding.copy(primaryDecoder = ""), binding.copy(fastDecoder = ""),
            binding.copy(scheduleVersion = ""), binding.copy(deviceIdentity = ""),
            binding.copy(runtimeVersion = ""), binding.copy(environmentVersion = ""),
            binding.copy(fixtureVersion = "x".repeat(4_097)),
        )
        invalid.forEach { assertNull(it.hashOrNull()) }
        assertNotEquals(
            binding.copy(primaryDecoder = "ab", fastDecoder = "c").hashOrNull(),
            binding.copy(primaryDecoder = "a", fastDecoder = "bc").hashOrNull(),
        )
    }

    @Test
    fun fullPairedAndLiveEvidenceSelectsTheCandidateThroughTheExistingPolicy() {
        val summary = summary()
        val expected = RuntimeTuningProfile.fromConfirmed(
            RuntimeSelectionPolicy.evaluate(cpu, gpu, summary, 1, 300_000), 123,
        )
        val profile = requireNotNull(confirm(summary))
        assertEquals(gpu, profile.candidate)
        assertEquals(expected, profile.runtime)
        assertEquals(primaryLoad, profile.primaryBaseline)
        assertEquals(primaryLoad, profile.primarySelected)
        assertTrue(profile.isValid(binding, 12))
        assertFalse(profile.isValid(binding, 3))
    }

    @Test
    fun everyNonConfirmedPolicyOutcomeCannotIssueAnUnknownProfile() {
        val valid = summary()
        val partial = valid.copy(confirmation = valid.confirmation.copy(pairs = emptyList()))
        val inconclusive = valid.copy(ba = block(RuntimeComparisonOrder.BA, environment(), 80.0, 120.0))
        val rejected = valid.copy(fixture = valid.fixture.copy(outputEquivalent = false))
        val unsupported = valid.copy(fixture = valid.fixture.copy(failure = RuntimeExecutionFailure.UNSUPPORTED))
        mapOf(
            RuntimeSelectionStatus.PARTIAL to partial,
            RuntimeSelectionStatus.INCONCLUSIVE to inconclusive,
            RuntimeSelectionStatus.REJECTED to rejected,
            RuntimeSelectionStatus.UNSUPPORTED to unsupported,
        ).forEach { (status, evidence) ->
            assertEquals(status, RuntimeSelectionPolicy.evaluate(cpu, gpu, evidence, 1, 300_000).status)
            assertNull(status.name, confirm(evidence))
        }
    }

    @Test
    fun wrapperPreservesFallbackFixtureAndLiveDepthRejectionGates() {
        val valid = summary()
        val invalid = linkedMapOf(
            "silent CPU fallback" to valid.copy(fixture = valid.fixture.copy(candidateActualBackend = RuntimeBackend.CPU)),
            "wrong live backend" to valid.copy(candidateLive = valid.candidateLive.copy(actualBackend = RuntimeBackend.CPU)),
            "missing fixture case" to valid.copy(fixture = valid.fixture.copy(completedCaseIds = setOf("positive", "empty"))),
            "missing real completions" to valid.copy(candidateLive = valid.candidateLive.copy(validCompletions = 0, captureToCompleteMs = emptyList())),
            "stale depth" to valid.copy(candidateLive = valid.candidateLive.copy(depthTimestampFresh = false)),
            "invalid pose" to valid.copy(candidateLive = valid.candidateLive.copy(poseValid = false)),
            "missing depth" to valid.copy(candidateLive = valid.candidateLive.copy(positiveDepthSamples = 0)),
            "live tail regression" to valid.copy(candidateLive = valid.candidateLive.copy(captureToCompleteMs = List(9) { 100.0 } + 900.0)),
            "speech contaminates sample" to valid.copy(ab = valid.ab.copy(pairs = valid.ab.pairs.map { it.copy(speechActive = true) })),
        )
        invalid.forEach { (name, evidence) -> assertNull(name, confirm(evidence)) }
    }

    @Test
    fun bindingFixtureScopeAndDeadlineMustMatchBeforeConfirmation() {
        val valid = summary()
        assertNull(confirm(valid.copy(fixture = valid.fixture.copy(version = "other-fixture"))))
        assertNull(confirm(valid.copy(fixture = valid.fixture.copy(hash = "d".repeat(64)))))
        assertNull(confirm(summary(environment = environment().copy(bindingHash = "d".repeat(64)))))
        assertNull(confirm(summary(environment = environment().copy(featureScope = RuntimeFeatureScope.CAMERA_TRACKING))))
        assertNull(confirm(valid, nowMs = 300_000))
        assertNull(confirm(valid, nowMs = 300_001))
        assertNull(confirm(valid, measuredAtMs = 0))
        assertEquals(300_000L, UnknownRuntimeTuning.MAX_BUDGET_MS)
    }

    @Test
    fun profileRevalidationRejectsCorruptedPolicyEvidenceAndPrimaryRegression() {
        val profile = requireNotNull(confirm())
        assertFalse(profile.copy(policyVersion = "obsolete-composition").isValid(binding, 12))
        assertFalse(profile.copy(runtime = profile.runtime.copy(policyVersion = "obsolete-selection")).isValid(binding, 12))
        assertFalse(profile.copy(runtime = profile.runtime.copy(measurement = profile.runtime.measurement.copy(confirmedPairCount = 3))).isValid(binding, 12))
        assertFalse(profile.copy(primarySelected = primaryLoad.copy(longestCompletionGapMs = 1_002.0)).isValid(binding, 12))
    }

    @Test
    fun profileCannotBeConfirmedOrReusedWhenFixedPrimaryThreadsAreUnavailable() {
        val oversizedPrimary = binding.copy(primaryRuntime = gpu.copy(numThreads = 16))
        val evidence = summary(environment = environment(oversizedPrimary))
        assertNull(confirm(evidence, workload = oversizedPrimary))

        val profile = requireNotNull(confirm())
        val lowerThreadCandidate = RuntimeCandidate(RuntimeBackend.CPU, 2)
        val lowerThreadProfile = requireNotNull(UnknownRuntimeTuning.confirm(
            binding, cpu, lowerThreadCandidate,
            summary().let { it.copy(
                fixture = it.fixture.copy(candidateActualBackend = RuntimeBackend.CPU),
                candidateLive = it.candidateLive.copy(actualBackend = RuntimeBackend.CPU),
            ) }, primaryLoad, primaryLoad, 1, 300_000, 123,
        ))
        assertTrue(profile.isValid(binding, 4))
        assertFalse(lowerThreadProfile.isValid(binding, 2))
    }

    @Test
    fun auxiliaryGainCannotCompensateForPrimaryThroughputTailGapOrFreshnessLoss() {
        val regressions = linkedMapOf(
            "completion rate" to primaryLoad.copy(durationMs = 10_001),
            "latency tail" to primaryLoad.copy(captureToCompleteMs = List(9) { 80.0 } + 81.01),
            "completion gap" to primaryLoad.copy(longestCompletionGapMs = 1_001.01),
            "stale or dropped fraction" to primaryLoad.copy(staleOrDropped = 1),
        )
        regressions.forEach { (name, primaryCandidate) ->
            assertFalse(name, UnknownRuntimeTuning.primaryNotWorse(primaryLoad, primaryCandidate))
            assertNull(name, confirm(primaryCandidate = primaryCandidate))
        }
    }

    @Test
    fun primaryComparisonUsesRatesAndAcceptsOnlyTheOneMillisecondTimingTolerance() {
        val reference = primaryLoad.copy(staleOrDropped = 1)
        val sameRates = UnknownPrimaryLoad(20_000, List(20) { 81.0 }, 1_001.0, 2)
        assertTrue(UnknownRuntimeTuning.primaryNotWorse(reference, sameRates))
        assertFalse(UnknownRuntimeTuning.primaryNotWorse(reference, sameRates.copy(staleOrDropped = 3)))
        assertFalse(UnknownRuntimeTuning.primaryNotWorse(reference, sameRates.copy(durationMs = 20_001)))
    }

    @Test
    fun onlyMeasuredReferenceVariationCanExplainPrimaryComparisonNoise() {
        val repeatedReference = primaryLoad.copy(
            repeatedLatencyVariationMs = 25.0, repeatedGapVariationMs = 50.0,
            repeatedRateVariationPerMs = 0.0002, repeatedStaleFractionVariation = 0.1,
        )
        val withinReferenceNoise = UnknownPrimaryLoad(12_000, List(10) { 105.0 }, 1_050.0, 1)
        assertTrue(UnknownRuntimeTuning.primaryNotWorse(repeatedReference, withinReferenceNoise))
        assertFalse(UnknownRuntimeTuning.primaryNotWorse(repeatedReference, withinReferenceNoise.copy(durationMs = 13_000)))
        assertFalse(UnknownRuntimeTuning.primaryNotWorse(repeatedReference, withinReferenceNoise.copy(captureToCompleteMs = List(10) { 105.01 })))
        assertFalse(UnknownRuntimeTuning.primaryNotWorse(repeatedReference, withinReferenceNoise.copy(longestCompletionGapMs = 1_050.01)))
        assertFalse(UnknownRuntimeTuning.primaryNotWorse(repeatedReference, withinReferenceNoise.copy(staleOrDropped = 2)))
        assertFalse(UnknownRuntimeTuning.primaryNotWorse(primaryLoad, withinReferenceNoise.copy(
            repeatedLatencyVariationMs = 10_000.0, repeatedGapVariationMs = 10_000.0,
            repeatedRateVariationPerMs = 1.0, repeatedStaleFractionVariation = 1.0,
        )))
    }

    @Test
    fun invalidRepeatVariationCannotWidenTheNonRegressionGate() {
        val invalid = listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).flatMap { value ->
            listOf(primaryLoad.copy(repeatedLatencyVariationMs = value), primaryLoad.copy(repeatedGapVariationMs = value),
                primaryLoad.copy(repeatedRateVariationPerMs = value), primaryLoad.copy(repeatedStaleFractionVariation = value))
        } + primaryLoad.copy(repeatedStaleFractionVariation = 1.01)
        invalid.forEach {
            assertFalse(UnknownRuntimeTuning.primaryNotWorse(it, primaryLoad))
            assertFalse(UnknownRuntimeTuning.primaryNotWorse(primaryLoad, it))
        }
    }

    @Test
    fun missingOrInvalidPrimaryEvidenceFailsClosedOnEitherSide() {
        val invalid = listOf(
            primaryLoad.copy(durationMs = 9_999), primaryLoad.copy(captureToCompleteMs = List(9) { 80.0 }),
            primaryLoad.copy(captureToCompleteMs = emptyList()), primaryLoad.copy(staleOrDropped = -1),
            primaryLoad.copy(staleOrDropped = 11), primaryLoad.copy(longestCompletionGapMs = 0.0),
            primaryLoad.copy(longestCompletionGapMs = Double.NaN),
        ) + listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).map {
            primaryLoad.copy(captureToCompleteMs = List(9) { 80.0 } + it)
        }
        invalid.forEach {
            assertFalse(UnknownRuntimeTuning.primaryNotWorse(it, primaryLoad))
            assertFalse(UnknownRuntimeTuning.primaryNotWorse(primaryLoad, it))
        }
    }

    @Test
    fun baselineWinRetainsItsPrimaryEvidenceAndCpuTieCannotRetuneTheFixedBaseline() {
        val profile = requireNotNull(confirm(summary(100.0, 150.0), primaryCandidate = primaryLoad.copy(durationMs = 1)))
        assertEquals(cpu, profile.candidate)
        assertEquals(primaryLoad, profile.primarySelected)

        val fewerThreads = cpu.copy(numThreads = 2)
        val tie = summary(100.0, 100.0).let { it.copy(
            fixture = it.fixture.copy(candidateActualBackend = RuntimeBackend.CPU),
            candidateLive = it.candidateLive.copy(actualBackend = RuntimeBackend.CPU),
        ) }
        assertEquals(cpu, requireNotNull(UnknownRuntimeTuning.confirm(
            binding, cpu, fewerThreads, tie, primaryLoad, primaryLoad, 1, 300_000, 123,
        )).candidate)
    }

    @Test
    fun warmupRequiresThreeRecentPositiveStableTimingsWithoutFixedDelay() {
        assertFalse(UnknownRuntimeTuning.warmupStable(emptyList()))
        assertFalse(UnknownRuntimeTuning.warmupStable(listOf(100.0, 100.0)))
        assertTrue(UnknownRuntimeTuning.warmupStable(listOf(95.0, 100.0, 105.0)))
        assertFalse(UnknownRuntimeTuning.warmupStable(listOf(94.0, 100.0, 106.0)))
        assertTrue(UnknownRuntimeTuning.warmupStable(listOf(0.5, 1.0, 1.5)))
        assertTrue(UnknownRuntimeTuning.warmupStable(listOf(5_000.0, 1_000.0, 95.0, 100.0, 105.0)))
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(UnknownRuntimeTuning.warmupStable(listOf(100.0, invalid, 100.0)))
        }
    }

    @Test
    fun rawEquivalenceChecksBothOutputsWithAbsoluteAndRelativeTolerance() {
        val reference = arrayOf(floatArrayOf(0f, 100f, -100f), floatArrayOf(0.1f, -0.1f))
        val withinTolerance = arrayOf(floatArrayOf(0.00009f, 100.10f, -100.10f), floatArrayOf(0.1001f, -0.1001f))
        assertTrue(UnknownRuntimeTuning.equivalentRaw(reference, withinTolerance))
        assertFalse(UnknownRuntimeTuning.equivalentRaw(reference, arrayOf(floatArrayOf(0.00011f, 100f, -100f), reference[1])))
        assertFalse(UnknownRuntimeTuning.equivalentRaw(reference, arrayOf(floatArrayOf(0f, 100.11f, -100f), reference[1])))
        assertFalse(UnknownRuntimeTuning.equivalentRaw(reference, arrayOf(reference[0], floatArrayOf(0.101f, -0.1f))))
    }

    @Test
    fun rawEquivalenceRejectsMissingOutputsShapeMismatchAndNonFiniteValues() {
        val reference = arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f))
        val invalid = listOf(
            emptyArray<FloatArray>(), arrayOf(reference[0]), arrayOf(reference[0], reference[1], floatArrayOf(4f)),
            arrayOf(floatArrayOf(), reference[1]), arrayOf(floatArrayOf(1f), reference[1]),
            arrayOf(reference[0], floatArrayOf()),
        ) + listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).map {
            arrayOf(floatArrayOf(it, 2f), reference[1])
        }
        invalid.forEach {
            assertFalse(UnknownRuntimeTuning.equivalentRaw(reference, it))
            assertFalse(UnknownRuntimeTuning.equivalentRaw(it, reference))
        }
    }

    @Test
    fun streamBaselinesStaySeparateWhenLatencyAndCompletionCadenceDiffer() {
        val control = UnknownRuntimeLoadControl()
        assertFalse(addWindow(control, 0, primaryMs = 100, auxiliaryMs = 600, primaryCount = 40))
        repeat(4) {
            assertFalse(addWindow(control, (it + 1) * 20_000L, primaryMs = 100, auxiliaryMs = 600))
        }
    }

    @Test
    fun eitherStreamNeedsThreeFullDegradedWindowsBeforeSettingPending() {
        for (degradePrimary in listOf(true, false)) {
            val control = UnknownRuntimeLoadControl()
            assertFalse(addWindow(control, 0, 100, 600))
            val primaryMs = if (degradePrimary) 200L else 100L
            val auxiliaryMs = if (degradePrimary) 600L else 900L
            assertFalse(addWindow(control, 20_000, primaryMs, auxiliaryMs))
            assertFalse(addWindow(control, 40_000, primaryMs, auxiliaryMs))
            addSamples(control, 60_000, primaryMs, auxiliaryMs)
            assertFalse(control.observe(79_999, KEY, true, true))
            assertTrue(control.observe(80_000, KEY, true, true))
        }
    }

    @Test
    fun healthyPeerCompletionsCannotHideAStreamThatStopsReturningResults() {
        for (primaryStopped in listOf(true, false)) {
            val control = UnknownRuntimeLoadControl()
            repeat(10) {
                assertFalse(control.observe(it * 1_900L, KEY, true, true,
                    primaryCompletion = if (primaryStopped) null else RuntimeLoadCompletion(100),
                    auxiliaryCompletion = if (primaryStopped) RuntimeLoadCompletion(600) else null,
                ))
            }
            assertTrue(control.observe(20_000, KEY, true, true))
        }
    }

    @Test
    fun inactiveEnvironmentAndUnobservedGapsCannotInventPendingEvidence() {
        val inactive = UnknownRuntimeLoadControl()
        repeat(5) { assertFalse(inactive.observe(it * 20_000L, KEY, false, true)) }
        val idle = UnknownRuntimeLoadControl()
        repeat(5) { assertFalse(idle.observe(it * 20_000L, KEY, true, false)) }
        val gap = UnknownRuntimeLoadControl()
        assertFalse(gap.observe(0, KEY, true, true))
        assertFalse(gap.observe(200_000, KEY, true, true))
    }

    @Test
    fun pauseBreaksAnUnfinishedDegradationStreakButPreservesPendingUntilReset() {
        val control = UnknownRuntimeLoadControl()
        addWindow(control, 0, 100, 600)
        assertFalse(addWindow(control, 20_000, 100, 900))
        assertFalse(addWindow(control, 40_000, 100, 900))
        control.pause()
        assertFalse(addWindow(control, 60_000, 100, 900))
        assertFalse(addWindow(control, 80_000, 100, 900))
        assertTrue(addWindow(control, 100_000, 100, 900))
        control.pause()
        assertTrue(addWindow(control, 120_000, 100, 600))
        control.reset()
        assertFalse(addWindow(control, 140_000, 100, 900))
        assertFalse(addWindow(control, 160_000, 100, 900))
    }

    @Test
    fun bindingChangeClearsOldPendingAndLearnsBothNewBaselines() {
        val control = UnknownRuntimeLoadControl()
        addWindow(control, 0, 100, 600)
        repeat(3) { addWindow(control, (it + 1) * 20_000L, 100, 900) }
        assertTrue(control.observe(80_000, KEY, true, true))
        assertFalse(addWindow(control, 80_000, 200, 1_200, key = "other-binding"))
        repeat(3) { assertFalse(addWindow(control, 100_000 + it * 20_000L, 200, 1_200, key = "other-binding")) }
    }

    private fun environment(workload: UnknownRuntimeBinding = binding) = RuntimeComparisonEnvironment(
        requireNotNull(workload.hashOrNull()), workload.featureScope, 12, 0, false,
        "ACTIVE", "TRACKING", 200, "unknown-combined-live-v1",
    )

    private fun block(order: RuntimeComparisonOrder, env: RuntimeComparisonEnvironment, a: Double, b: Double) =
        RuntimeComparisonBlock(order, List(4) {
            RuntimePairedSample(caseIds.elementAt(it % caseIds.size), a, b, env, env)
        }, listOf(2.0, 1.0))

    private fun summary(a: Double = 150.0, b: Double = 100.0, environment: RuntimeComparisonEnvironment = environment()): RuntimeMeasurementSummary {
        fun live(backend: RuntimeBackend, latency: Double) = RuntimeLiveMetrics(
            environment, backend, List(10) { latency }, 10_000, 10, 10, 0, 0, 33.0,
            3.0, 16.0, true, 10, true, true, 300, 1_000.0,
        )
        return RuntimeMeasurementSummary(
            block(RuntimeComparisonOrder.AB, environment, a, b), block(RuntimeComparisonOrder.BA, environment, a, b),
            block(RuntimeComparisonOrder.CONFIRMATION, environment, a, b),
            live(RuntimeBackend.CPU, a), live(RuntimeBackend.GPU, b),
            RuntimeFixtureEvidence(binding.fixtureVersion, binding.fixtureSha256, 3, 3, true,
                RuntimeBackend.CPU, RuntimeBackend.GPU, requiredCaseIds = caseIds, completedCaseIds = caseIds), 1.0,
        )
    }

    private fun confirm(
        evidence: RuntimeMeasurementSummary = summary(),
        primaryCandidate: UnknownPrimaryLoad = primaryLoad,
        workload: UnknownRuntimeBinding = binding,
        nowMs: Long = 1,
        measuredAtMs: Long = 123,
    ) = UnknownRuntimeTuning.confirm(workload, cpu, gpu, evidence, primaryLoad, primaryCandidate,
        nowMs, 300_000, measuredAtMs)

    private fun addWindow(
        control: UnknownRuntimeLoadControl, startMs: Long, primaryMs: Long, auxiliaryMs: Long,
        primaryCount: Int = 10, key: String = KEY,
    ): Boolean {
        addSamples(control, startMs, primaryMs, auxiliaryMs, primaryCount, key)
        return control.observe(startMs + 20_000, key, true, true)
    }

    private fun addSamples(
        control: UnknownRuntimeLoadControl, startMs: Long, primaryMs: Long, auxiliaryMs: Long,
        primaryCount: Int = 10, key: String = KEY,
    ) {
        repeat(primaryCount) { sample ->
            control.observe(startMs + sample * (19_000L / primaryCount), key, true, true,
                primaryCompletion = RuntimeLoadCompletion(primaryMs),
                auxiliaryCompletion = if (sample % (primaryCount / 10) == 0) RuntimeLoadCompletion(auxiliaryMs) else null,
            )
        }
    }

    companion object { private const val KEY = "unknown-binding:GPU:4" }
}
