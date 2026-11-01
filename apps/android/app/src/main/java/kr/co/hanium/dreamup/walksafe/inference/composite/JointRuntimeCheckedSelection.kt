package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthoritySource
import java.io.Closeable
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Timing-ranked, still unverified. Only final strict checks and soak may issue a validated choice. */
class JointRuntimeCheckedSelection internal constructor(
    val source: DeviceCheckAuthoritySource,
    val sessionGeneration: Long,
    val bindingHash: String,
    val plan: JointRuntimeFinalPlan,
    references: JointRuntimeReferenceSet,
    fixtures: JointRuntimeSampleFixtures,
) : Closeable {
    private val resources = JointRuntimeSelectionOwnership(references, fixtures)

    class Claim internal constructor(val plan: JointRuntimeFinalPlan, val bindingHash: String,
        val references: JointRuntimeReferenceSet, val fixtures: JointRuntimeSampleFixtures) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) try { references.close() } finally { fixtures.close() } }
    }
    fun claimForFinal(expectedSource: DeviceCheckAuthoritySource, expectedSession: Long, nowMs: Long): Claim? {
        if (source !== expectedSource || sessionGeneration != expectedSession || nowMs < plan.budget.startedAtMs ||
            nowMs >= plan.budget.deadlineMs) return null
        val owned = resources.claim() ?: return null
        return Claim(plan, bindingHash, owned.first, owned.second)
    }
    fun discardIfUnclaimed(): Boolean = resources.discardIfUnclaimed()
    /** Once claimed, only the returned Claim owns cleanup. A late callback cannot revoke it. */
    override fun close() { discardIfUnclaimed() }
}

/** Same ownership primitive with SDK-free resources for cancellation/late-publication tests. */
internal class JointRuntimeSelectionOwnership<R : Closeable, F : Closeable>(references: R, fixtures: F) {
    private var resources: Pair<R, F>? = references to fixtures
    @Synchronized fun claim(): Pair<R, F>? = resources.also { resources = null }
    fun discardIfUnclaimed(): Boolean {
        val owned = synchronized(this) { resources.also { resources = null } } ?: return false
        try { owned.first.close() } finally { owned.second.close() }
        return true
    }
}

internal interface JointRuntimeCheckSequenceTrial<R : Closeable> : Closeable {
    val actualReleased: CompletionStage<Unit>
    fun provisional(candidate: JointRuntimeCandidate, budget: JointRuntimePhaseBudget): JointRuntimeProvisionalMeasurement
    fun references(budget: JointRuntimePhaseBudget): R
    fun cancel(reason: String)
}
internal interface JointRuntimeCheckSequencePorts<F : Closeable, R : Closeable> {
    fun sourceCurrent(): Boolean
    fun loadFixtures(): F
    /** Construction is native-free; only provisional/references may begin native work. */
    fun createTrial(fixtures: F, phase: JointRuntimeSearchPhase): JointRuntimeCheckSequenceTrial<R>
    /** Must retain only the completion gate/token and a metadata-only late-release publisher. */
    fun observeRelease(token: Any, released: CompletionStage<Unit>)
    fun releaseObserved(token: Any)
    fun progress(phase: JointRuntimeSearchPhase, candidate: JointRuntimeCandidate?)
}
internal data class JointRuntimeCheckSequenceResult<F : Closeable, R : Closeable>(
    val plan: JointRuntimeFinalPlan?, val fixtures: F?, val references: R?, val reason: String,
    val releaseConfirmed: Boolean,
)

/** Serial ownership: the next trial is impossible until both host drain and actual death are ACKed. */
internal class JointRuntimeCheckSequence<F : Closeable, R : Closeable>(
    private val policy: JointRuntimeSearchPolicy,
    private val deadlineMs: Long,
    private val gate: JointRuntimeCompletionGate,
    private val nowMs: () -> Long,
    private val ports: JointRuntimeCheckSequencePorts<F, R>,
) {
    private val cancelled = AtomicBoolean()
    @Volatile private var currentTrial: JointRuntimeCheckSequenceTrial<R>? = null
    fun cancel(reason: String) { cancelled.set(true); currentTrial?.cancel(reason) }
    private fun admit() { check(!cancelled.get() && nowMs() < deadlineMs && ports.sourceCurrent()) { "sample_check_source_retired" } }

    fun run(): JointRuntimeCheckSequenceResult<F, R> {
        var fixtures: F? = null
        var references: R? = null
        var transferred = false
        try {
            admit(); fixtures = ports.loadFixtures(); admit()
            val measured = mutableListOf<JointRuntimeProvisionalMeasurement>()
            for (candidate in policy.candidates) {
                admit()
                val phase = policy.provisionalBudget(nowMs()) ?: break
                val workEnd = phase.deadlineMs - JointRuntimeSearchPolicy.CLEANUP_MS
                if (workEnd - nowMs() < MINIMUM_TRIAL_REMAINING_MS) break
                val budget = JointRuntimePhaseBudget(phase.phase, nowMs(), minOf(workEnd, nowMs() + MAXIMUM_TRIAL_MS))
                ports.progress(phase.phase, candidate)
                val trial = ports.createTrial(fixtures, phase.phase)
                val token = Any()
                var measurement: JointRuntimeProvisionalMeasurement? = null
                var failure: Throwable? = null
                try {
                    register(trial, token)
                    measurement = trial.provisional(candidate, budget)
                } catch (error: Throwable) { failure = error }
                finally { release(trial, token, phase.deadlineMs) }
                admit() // Dirty storage, stale source and unknown release stop the whole search.
                if (failure == null) measurement?.let { policy.assessProvisional(it).measurement?.let(measured::add) }
            }
            check(measured.isNotEmpty()) { "no_measured_provisional_candidate" }
            admit()
            val referenceBudget = requireNotNull(policy.beginReference(nowMs())) { "reference_budget_reserved" }
            ports.progress(referenceBudget.phase, null)
            val referenceTrial = ports.createTrial(fixtures, referenceBudget.phase)
            val token = Any()
            var failure: Throwable? = null
            try { register(referenceTrial, token); references = referenceTrial.references(referenceBudget) }
            catch (error: Throwable) { failure = error }
            finally { release(referenceTrial, token, minOf(deadlineMs, referenceBudget.deadlineMs + JointRuntimeSearchPolicy.CLEANUP_MS)) }
            admit()
            if (failure != null) throw requireNotNull(failure)
            check(policy.finishReference(references != null, nowMs())) { "reference_accuracy_unconfirmed" }
            val plan = requireNotNull(policy.beginFinal(nowMs(), measured)) { "final_plan_unavailable" }
            transferred = true
            return JointRuntimeCheckSequenceResult(plan, fixtures, references, "sample_candidate_selected_unverified", gate.releaseConfirmed)
        } catch (error: Throwable) {
            return JointRuntimeCheckSequenceResult(null, null, null, (error.message ?: "sample_check_failed").take(160), gate.releaseConfirmed)
        } finally {
            if (!transferred) try { references?.close() } finally { fixtures?.close() }
        }
    }
    private fun register(trial: JointRuntimeCheckSequenceTrial<R>, token: Any) {
        currentTrial = trial
        check(gate.acquireResource(token)) { "sample_check_terminal" }
        ports.observeRelease(token, trial.actualReleased)
        admit()
    }
    private fun release(trial: JointRuntimeCheckSequenceTrial<R>, token: Any, phaseEnd: Long) {
        try {
            runCatching { trial.close() }
            val cleanupEnd = minOf(deadlineMs, phaseEnd, nowMs() + JointRuntimeSearchPolicy.CLEANUP_MS)
            trial.actualReleased.toCompletableFuture().get((cleanupEnd - nowMs()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
            // Publish through the same metadata observer, including the late-ACK path, rather
            // than racing its CompletableFuture listener or claiming release on a timer.
            ports.releaseObserved(token)
            check(gate.releaseConfirmed) { "sample_host_release_unconfirmed" }
        } catch (error: Throwable) { throw IllegalStateException("sample_host_release_unconfirmed", error) }
        finally { if (currentTrial === trial) currentTrial = null }
    }
    private companion object {
        const val MINIMUM_TRIAL_REMAINING_MS = 25_000L
        const val MAXIMUM_TRIAL_MS = 45_000L
    }
}
