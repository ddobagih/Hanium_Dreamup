package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Sequence/ownership evidence only; no images, Android effects or native work are simulated as PASS. */
class JointRuntimeCheckSequenceTest {
    @Test fun debugGpuColdOpenIsNotSilentlyClampedByTheOuterTrial() = Fixture(true).run {
        val result = sequence.run()
        assertEquals(85_000L, budgets.first().deadlineMs - budgets.first().startedAtMs)
        assertTrue(budgets.all { it.deadlineMs <= deadline })
        result.references?.close(); result.fixtures?.close(); Unit
    }

    @Test fun candidateFailuresAndPolicyRejectionsAreDiagnosedWithoutChangingSelectionOrRelease() = Fixture().run {
        failMeasurements += 0
        rejectedMeasurements += 1
        val result = sequence.run()
        assertNotNull(result.plan)
        assertEquals(policy.candidates, measured)
        assertTrue(diagnostics.any { it.outcome == JointDiagnosticOutcome.FAILED && it.candidate == policy.candidates[0] })
        assertTrue(diagnostics.any { it.reason == JointDiagnosticReason.PROVISIONAL_TIMING_INCOMPLETE && it.candidate == policy.candidates[1] })
        assertTrue(diagnostics.any { it.outcome == JointDiagnosticOutcome.ACCEPTED })
        assertTrue(diagnostics.any { it.outcome == JointDiagnosticOutcome.SELECTED_UNVERIFIED })
        trials.zipWithNext().forEach { (old,next) -> assertTrue(events.indexOf("released-${old.id}") < events.indexOf("create-${next.id}")) }
        result.references!!.close(); result.fixtures!!.close()
    }

    @Test fun allRejectedCandidatesAreDiagnosedAndNeverProduceFakeSelection() = Fixture().run {
        rejectedMeasurements += policy.candidates.indices
        val result = sequence.run()
        assertNull(result.plan); assertEquals(0, referenceCalls)
        assertEquals(policy.candidates.size, diagnostics.count { it.reason == JointDiagnosticReason.PROVISIONAL_TIMING_INCOMPLETE })
        assertEquals(JointDiagnosticReason.NO_MEASURED_PROVISIONAL_CANDIDATE, diagnostics.last().reason)
        assertTrue(result.releaseConfirmed)
    }

    @Test fun diagnosticSinkFailureCannotChangeSelectionOrDrain() = Fixture().run {
        diagnosticFails = true
        val result = sequence.run()
        assertNotNull(result.plan); assertTrue(result.releaseConfirmed)
        result.references!!.close(); result.fixtures!!.close()
    }

    @Test fun policyRejectionCodesPreserveThermalPerformanceHeadroomAndRuntimeMismatch() {
        val changes: List<Pair<JointDiagnosticReason, (JointRuntimeProvisionalMeasurement) -> JointRuntimeProvisionalMeasurement>> = listOf(
            JointDiagnosticReason.PROVISIONAL_RUNTIME_UNVERIFIED to { it.copy(fallbackUsed = true) },
            JointDiagnosticReason.PROVISIONAL_PERFORMANCE_LIMIT to { it.copy(thermalStatus = 3) },
            JointDiagnosticReason.PROVISIONAL_RUNTIME_HEADROOM to { it.copy(primaryTurnaroundMs = listOf(700.0, 700.0)) },
        )
        for ((reason, transform) in changes) Fixture().run {
            changeMeasurement = transform
            val result = sequence.run()
            assertNull(result.plan)
            assertEquals(policy.candidates.size, diagnostics.count { it.reason == reason })
            assertTrue(diagnostics.filter { it.stage == JointDiagnosticStage.TIMING }.all { it.measurement != null })
        }
    }

    @Test fun referenceFailureAndExceptionMetadataNeverExposeArbitraryUserData() = Fixture().run {
        referenceFails = true
        val result = sequence.run()
        assertNull(result.plan); assertEquals(1, referenceCalls)
        assertTrue(diagnostics.any { it.stage == JointDiagnosticStage.REFERENCE && it.outcome == JointDiagnosticOutcome.FAILED })
        val sentinel = "SECRET_COORDINATES_TOKEN_/private/path"
        val event = jointDiagnosticFailure(JointDiagnosticStage.AUXILIARY_INIT, policy.candidates.first(),
            IllegalStateException(sentinel, UnsupportedOperationException(sentinel)))
        val text = formatJointRuntimeCandidateDiagnostic(event)
        assertFalse(text.contains(sentinel)); assertFalse(text.contains("/private"))
        assertEquals(JointDiagnosticException.ILLEGAL_STATE, event.exception)
        assertEquals(JointDiagnosticException.UNSUPPORTED, event.cause)
        assertEquals(JointDiagnosticReason.NATIVE_FAILED, event.reason)
        assertTrue(text.length < 1024)
        assertEquals(JointDiagnosticReason.UNKNOWN, jointDiagnosticReason("provisional_timing_incomplete" + sentinel))
    }

    @Test fun remoteRuntimeAndSampleResourceFailuresRemainDistinctFromPolicyRejections() {
        val remote = jointDiagnosticFailure(JointDiagnosticStage.AUXILIARY_INIT, null,
            kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeException("SECRET_USER_DATA"))
        assertTrue(remote.exception.name != "OTHER")
        val resource = jointDiagnosticFailure(JointDiagnosticStage.ACQUIRE, null,
            JointRuntimeSampleFailure(JointRuntimeSearchFailure(JointRuntimeSearchFailureKind.RESOURCE_BUDGET, "SECRET_USER_DATA")))
        assertTrue(resource.reason != JointDiagnosticReason.NATIVE_FAILED)
        assertFalse(formatJointRuntimeCandidateDiagnostic(remote).contains("SECRET_USER_DATA"))
        assertFalse(formatJointRuntimeCandidateDiagnostic(resource).contains("SECRET_USER_DATA"))
    }

    @Test fun failedGpuTrialsDoNotPreventAnExplicitLaterCpuCpuSuccess() = Fixture().run {
        failMeasurements += policy.candidates.indices.filter { index ->
            val c = policy.candidates[index]
            c.primary.backend != RuntimeBackend.CPU || c.auxiliary.backend != RuntimeBackend.CPU
        }
        val result = sequence.run()
        assertNotNull(result.plan)
        assertEquals(RuntimeBackend.CPU, result.plan!!.candidate.primary.backend)
        assertEquals(RuntimeBackend.CPU, result.plan!!.candidate.auxiliary.backend)
        assertTrue(diagnostics.any { it.outcome == JointDiagnosticOutcome.FAILED })
        assertTrue(result.releaseConfirmed)
        result.references!!.close(); result.fixtures!!.close()
    }

    @Test fun gpuTimingRunsBeforeTheOnlyCpuReferenceAndEveryNextTrialWaitsForThePreviousRelease() = Fixture().run {
        val result = sequence.run()
        assertNotNull(result.plan)
        assertTrue(result.releaseConfirmed)
        assertEquals(1, loads)
        assertEquals(1, referenceCalls)
        assertEquals(RuntimeBackend.GPU, measured.first().primary.backend)
        assertEquals(RuntimeBackend.GPU, measured.first().auxiliary.backend)
        assertEquals(policy.candidates, measured)
        val referenceStart = events.indexOf("reference")
        assertTrue(referenceStart > events.indexOfLast { it.startsWith("measure") })
        trials.zipWithNext().forEach { (old, next) -> assertTrue(events.indexOf("released-${old.id}") < events.indexOf("create-${next.id}")) }
        assertEquals(0, fixtures.closed)
        assertEquals(0, refs.closed)
        result.references!!.close(); result.fixtures!!.close()
        assertEquals(1, refs.closed); assertEquals(1, fixtures.closed)
    }

    @Test fun aFailedFirstGpuBootstrapDoesNotCreateACpuBaselinePrerequisiteForLaterGpuCandidates() = Fixture().run {
        failMeasurements += 0
        val result = sequence.run()
        assertNotNull(result.plan)
        assertEquals(policy.candidates, measured)
        assertTrue(measured.drop(1).any { it.primary.backend == RuntimeBackend.GPU || it.auxiliary.backend == RuntimeBackend.GPU })
        assertEquals(1, referenceCalls)
        assertEquals(1, trials.first().closes)
        result.references!!.close(); result.fixtures!!.close()
    }

    @Test fun noProvisionalSuccessNeverCreatesReferencesOrAValidatedSelection() = Fixture().run {
        failMeasurements += policy.candidates.indices
        val result = sequence.run()
        assertNull(result.plan); assertNull(result.references); assertNull(result.fixtures)
        assertEquals(0, referenceCalls); assertEquals(1, fixtures.closed)
        assertTrue(result.releaseConfirmed)
    }

    @Test fun perTrialDeadlineLeavesTenSecondsForReleaseAndSkipsNewTrialsBelowTwentyFiveSeconds() = Fixture().run {
        consumeTrialBudget = true
        val result = sequence.run()
        assertNotNull(result.plan)
        assertTrue(measured.size < policy.candidates.size)
        val provisionalEnd = deadline - JointRuntimeSearchPolicy.REFERENCE_MS - JointRuntimeSearchPolicy.FINAL_MS - JointRuntimeSearchPolicy.CLEANUP_MS
        budgets.filter { it.phase == JointRuntimeSearchPhase.PROVISIONAL }.forEach { budget ->
            assertTrue(budget.deadlineMs <= provisionalEnd - 10_000)
            assertTrue(budget.deadlineMs - budget.startedAtMs <= 45_000)
            assertTrue(provisionalEnd - 10_000 - budget.startedAtMs >= 25_000)
        }
        assertTrue(result.plan!!.budget.deadlineMs <= deadline - 10_000)
        result.references!!.close(); result.fixtures!!.close()
    }

    @Test fun unconfirmedReleaseStopsTheSearchAndOnlyARealLateReceiptCanAcknowledgeTheFence() = Fixture().run {
        holdRelease = true; expireCleanup = true
        val result = sequence.run()
        assertNull(result.plan); assertFalse(result.releaseConfirmed)
        assertEquals(1, trials.size); assertEquals(0, referenceCalls)
        assertEquals(1, fixtures.closed)
        assertEquals(false, gate.claim(false))
        assertEquals(0, lateAcknowledgements)
        trials.single().released.complete(Unit)
        assertEquals(1, lateAcknowledgements)
        assertTrue(gate.releaseConfirmed)
    }

    @Test fun anExceptionalHostDrainDoesNotBecomeAReleaseReceipt() = Fixture().run {
        releaseFailure = true
        val result = sequence.run()
        assertFalse(result.releaseConfirmed); assertEquals(1, trials.size)
        assertTrue(result.reason.contains("release_unconfirmed"))
        assertFalse(gate.releaseConfirmed); assertEquals(1, fixtures.closed)
    }

    @Test fun cancellationDuringAProvisionalOperationClosesTheHostAndDropsTheImagesWithoutReferences() = Fixture().run {
        beforeMeasurement = { index -> if (index == 0) sequence.cancel("cancelled_in_model_prepare") }
        val result = sequence.run()
        assertNull(result.plan); assertTrue(result.releaseConfirmed)
        assertEquals(1, trials.size); assertEquals(1, trials.single().cancels)
        assertEquals(1, trials.single().closes); assertEquals(0, referenceCalls)
        assertEquals(1, fixtures.closed)
    }

    @Test fun dirtyAuthorityAfterAReleasedCandidatePreventsEveryFollowingOpen() = Fixture().run {
        afterClose = { if (it == 0) dirtyAuthority = true }
        val result = sequence.run()
        assertNull(result.plan); assertTrue(result.releaseConfirmed)
        assertEquals(1, trials.size); assertEquals(0, referenceCalls)
        assertTrue(result.reason.contains("authority_io_unknown")); assertEquals(1, fixtures.closed)
    }

    @Test fun referenceFailureIsNotRetriedAndNeverReturnsAProvisionalCandidateAsValidated() = Fixture().run {
        referenceFails = true
        val result = sequence.run()
        assertNull(result.plan); assertEquals(1, referenceCalls)
        assertEquals(policy.candidates.size + 1, trials.size)
        assertTrue(result.releaseConfirmed); assertEquals(1, fixtures.closed)
    }

    @Test fun delayedOldTrialObserverCannotAcknowledgeANewerTrialsResource() = Fixture().run {
        deferObservedCallbacks = true
        beforeMeasurement = { index -> if (index == 1) {
            assertFalse(gate.releaseConfirmed)
            deferred.first().invoke()
            assertFalse(gate.releaseConfirmed)
        } }
        val result = sequence.run()
        assertNotNull(result.plan); assertTrue(result.releaseConfirmed)
        result.references!!.close(); result.fixtures!!.close()
    }

    @Test fun selectionClaimsOwnershipOnceAndALateDiscardCannotCloseTheClaimedResources() {
        val refs = Resource(); val images = Resource(); val owned = JointRuntimeSelectionOwnership(refs, images)
        val claimed = requireNotNull(owned.claim())
        assertNull(owned.claim()); assertFalse(owned.discardIfUnclaimed())
        assertEquals(0, refs.closed); assertEquals(0, images.closed)
        claimed.first.close(); claimed.second.close()
        assertEquals(1, refs.closed); assertEquals(1, images.closed)
    }

    @Test fun aStaleUnclaimedSelectionDropsBothResourcesExactlyOnce() {
        val refs = Resource(); val images = Resource(); val owned = JointRuntimeSelectionOwnership(refs, images)
        assertTrue(owned.discardIfUnclaimed()); assertFalse(owned.discardIfUnclaimed()); assertNull(owned.claim())
        assertEquals(1, refs.closed); assertEquals(1, images.closed)
    }

    private class Resource : Closeable { var closed = 0; override fun close() { closed++ } }
    private class Fixture(val debugCold: Boolean = false) {
        var now = 1_000L
        val deadline = 301_000L
        val policy = JointRuntimeSearchPolicy(now, deadline, 4)
        val gate = JointRuntimeCompletionGate(initiallyReleased = true)
        val fixtures = Resource(); val refs = Resource()
        val trials = mutableListOf<Trial>()
        val events = mutableListOf<String>()
        val budgets = mutableListOf<JointRuntimePhaseBudget>()
        val measured = mutableListOf<JointRuntimeCandidate>()
        val failMeasurements = mutableSetOf<Int>()
        val rejectedMeasurements = mutableSetOf<Int>()
        val diagnostics = mutableListOf<JointRuntimeCandidateDiagnostic>()
        var diagnosticFails = false
        var changeMeasurement: (JointRuntimeProvisionalMeasurement) -> JointRuntimeProvisionalMeasurement = { it }
        val releasedTokens = Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val deferred = mutableListOf<() -> Unit>()
        var loads = 0; var referenceCalls = 0; var lateAcknowledgements = 0
        var consumeTrialBudget = false; var holdRelease = false; var expireCleanup = false; var releaseFailure = false
        var referenceFails = false; var dirtyAuthority = false; var deferObservedCallbacks = false
        var beforeMeasurement: (Int) -> Unit = {}
        var afterClose: (Int) -> Unit = {}
        private fun acknowledged(token: Any) {
            if (gate.acknowledgeRelease(token)) lateAcknowledgements++
            if (releasedTokens.add(token)) events += "released-${trials.indexOfFirst { it.token === token }}"
        }
        val sequence = JointRuntimeCheckSequence(policy, deadline, gate, { now }, object : JointRuntimeCheckSequencePorts<Resource, Resource> {
            override fun sourceCurrent(): Boolean { check(!dirtyAuthority) { "authority_io_unknown" }; return true }
            override fun loadFixtures(): Resource { loads++; events += "fixtures"; return fixtures }
            override fun createTrial(fixtures: Resource, phase: JointRuntimeSearchPhase): JointRuntimeCheckSequenceTrial<Resource> {
                assertSame(this@Fixture.fixtures, fixtures)
                return Trial(trials.size, phase).also { trials += it; events += "create-${it.id}" }
            }
            override fun observeRelease(token: Any, released: CompletionStage<Unit>) {
                trials.last().token = token
                val callback = { acknowledged(token) }
                if (deferObservedCallbacks) deferred += callback
                else released.whenComplete { _, error -> if (error == null) callback() }
            }
            override fun releaseObserved(token: Any) = acknowledged(token)
            override fun diagnostic(event: JointRuntimeCandidateDiagnostic) { if (diagnosticFails) error("sentinel private data"); diagnostics += event }
            override fun progress(phase: JointRuntimeSearchPhase, candidate: JointRuntimeCandidate?) {}
        }, debugColdOpenExperiment = debugCold)
        inner class Trial(val id: Int, val phase: JointRuntimeSearchPhase) : JointRuntimeCheckSequenceTrial<Resource> {
            val released = CompletableFuture<Unit>()
            override val actualReleased: CompletionStage<Unit> get() = released
            var token: Any? = null; var closes = 0; var cancels = 0
            override fun provisional(candidate: JointRuntimeCandidate, budget: JointRuntimePhaseBudget): JointRuntimeProvisionalMeasurement {
                events += "measure-$id"; measured += candidate; budgets += budget
                beforeMeasurement(id)
                now = if (consumeTrialBudget) budget.deadlineMs else now + 3_000
                check(id !in failMeasurements) { "controlled_native_prepare_failure" }
                return changeMeasurement(JointRuntimeProvisionalMeasurement(candidate, if (id in rejectedMeasurements) 1 else 2_000, 2, 2, listOf(100.0, 150.0), listOf(200.0, 250.0),
                    candidate.primary, candidate.auxiliary, false, 0))
            }
            override fun references(budget: JointRuntimePhaseBudget): Resource {
                events += "reference"; referenceCalls++; budgets += budget; now += 10_000
                check(!referenceFails) { "reference_quality_failed" }
                return refs
            }
            override fun cancel(reason: String) { cancels++ }
            override fun close() {
                closes++; events += "close-$id"; now += 100
                if (expireCleanup) now = deadline + 1
                if (releaseFailure) released.completeExceptionally(IllegalStateException("unproven_host_drain"))
                else if (!holdRelease) released.complete(Unit)
                afterClose(id)
            }
        }
    }
}
