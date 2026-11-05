package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.DetectorLoadFailureCode

/** Fixed metadata only. Never carries exception messages, model/owner IDs or input data. */
internal enum class JointDiagnosticStage { PROVISIONAL, ACQUIRE, PRIMARY_INIT, AUXILIARY_INIT, PRIMARY_SAMPLE, AUXILIARY_SAMPLE, TIMING, REFERENCE, RELEASE, TERMINAL }
internal enum class JointDiagnosticOutcome { ACCEPTED, REJECTED, FAILED, SELECTED_UNVERIFIED }
internal enum class JointDiagnosticReason {
    NONE, UNKNOWN, RESOURCE_BUDGET, NATIVE_FAILED, TIMED_OUT, CANCELLED, PRIMARY_UNAVAILABLE,
    PROVISIONAL_RUNTIME_UNVERIFIED, PROVISIONAL_TIMING_INCOMPLETE,
    PROVISIONAL_PERFORMANCE_LIMIT, PROVISIONAL_RUNTIME_HEADROOM,
    NO_MEASURED_PROVISIONAL_CANDIDATE, REFERENCE_ACCURACY_UNCONFIRMED,
    SAMPLE_HOST_RELEASE_UNCONFIRMED, SAMPLE_CHECK_SOURCE_RETIRED,
    REFERENCE_BUDGET_RESERVED, FINAL_PLAN_UNAVAILABLE,
}
internal data class JointRuntimeCandidateDiagnostic(
    val stage: JointDiagnosticStage,
    val outcome: JointDiagnosticOutcome,
    val candidate: JointRuntimeCandidate? = null,
    val reason: JointDiagnosticReason = JointDiagnosticReason.NONE,
    val exception: JointDiagnosticException = JointDiagnosticException.NONE,
    val cause: JointDiagnosticException = JointDiagnosticException.NONE,
    val primaryFailure: DetectorLoadFailureCode? = null,
    val measurement: JointRuntimeProvisionalMeasurement? = null,
)
internal enum class JointDiagnosticException { NONE, VISION_RUNTIME, JOINT_SAMPLE, TIMEOUT, CANCELLED, ILLEGAL_STATE, ILLEGAL_ARGUMENT, EXECUTION, UNSUPPORTED, OUT_OF_MEMORY, SECURITY, OTHER }

internal fun jointDiagnosticException(error: Throwable?): JointDiagnosticException = when (error) {
    null -> JointDiagnosticException.NONE
    is kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeException -> JointDiagnosticException.VISION_RUNTIME
    is JointRuntimeSampleFailure -> JointDiagnosticException.JOINT_SAMPLE
    is java.util.concurrent.TimeoutException -> JointDiagnosticException.TIMEOUT
    is java.util.concurrent.CancellationException -> JointDiagnosticException.CANCELLED
    is java.util.concurrent.ExecutionException -> JointDiagnosticException.EXECUTION
    is UnsupportedOperationException -> JointDiagnosticException.UNSUPPORTED
    is IllegalArgumentException -> JointDiagnosticException.ILLEGAL_ARGUMENT
    is IllegalStateException -> JointDiagnosticException.ILLEGAL_STATE
    is OutOfMemoryError -> JointDiagnosticException.OUT_OF_MEMORY
    is SecurityException -> JointDiagnosticException.SECURITY
    else -> JointDiagnosticException.OTHER
}

/** Exact allowlist, never prefix/suffix extraction from arbitrary native exception text. */
internal fun jointDiagnosticReason(code: String?): JointDiagnosticReason = when (code) {
    "provisional_runtime_unverified" -> JointDiagnosticReason.PROVISIONAL_RUNTIME_UNVERIFIED
    "provisional_timing_incomplete" -> JointDiagnosticReason.PROVISIONAL_TIMING_INCOMPLETE
    "provisional_performance_limit" -> JointDiagnosticReason.PROVISIONAL_PERFORMANCE_LIMIT
    "provisional_runtime_headroom" -> JointDiagnosticReason.PROVISIONAL_RUNTIME_HEADROOM
    "no_measured_provisional_candidate" -> JointDiagnosticReason.NO_MEASURED_PROVISIONAL_CANDIDATE
    "reference_accuracy_unconfirmed" -> JointDiagnosticReason.REFERENCE_ACCURACY_UNCONFIRMED
    "sample_host_release_unconfirmed" -> JointDiagnosticReason.SAMPLE_HOST_RELEASE_UNCONFIRMED
    "sample_check_source_retired" -> JointDiagnosticReason.SAMPLE_CHECK_SOURCE_RETIRED
    "reference_budget_reserved" -> JointDiagnosticReason.REFERENCE_BUDGET_RESERVED
    "final_plan_unavailable" -> JointDiagnosticReason.FINAL_PLAN_UNAVAILABLE
    else -> JointDiagnosticReason.UNKNOWN
}

internal fun jointDiagnosticFailure(stage: JointDiagnosticStage, candidate: JointRuntimeCandidate?, error: Throwable,
    primaryFailure: DetectorLoadFailureCode? = null): JointRuntimeCandidateDiagnostic {
    val known = jointDiagnosticReason(error.message)
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    var timeout = false
    var cancelled = false
    repeat(16) {
        val value = current ?: return@repeat
        if (!seen.add(value)) { current = null; return@repeat }
        timeout = timeout || value is java.util.concurrent.TimeoutException
        cancelled = cancelled || value is java.util.concurrent.CancellationException || value is InterruptedException
        current = value.cause
    }
    val reason = when {
        primaryFailure != null -> JointDiagnosticReason.PRIMARY_UNAVAILABLE
        error is JointRuntimeSampleFailure && error.failure.kind == JointRuntimeSearchFailureKind.RESOURCE_BUDGET -> JointDiagnosticReason.RESOURCE_BUDGET
        known != JointDiagnosticReason.UNKNOWN -> known
        timeout -> JointDiagnosticReason.TIMED_OUT
        cancelled -> JointDiagnosticReason.CANCELLED
        else -> JointDiagnosticReason.NATIVE_FAILED
    }
    return JointRuntimeCandidateDiagnostic(stage, JointDiagnosticOutcome.FAILED, candidate, reason,
        jointDiagnosticException(error), jointDiagnosticException(error.cause), primaryFailure)
}

internal fun formatJointRuntimeCandidateDiagnostic(event: JointRuntimeCandidateDiagnostic): String {
    fun number(value: Long?) = value?.takeIf { it in 0..300_000L }?.toString() ?: "NA"
    fun runtime(value: kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate?) =
        if (value == null) "NA" else value.backend.name + ":" + value.numThreads.takeIf { it in 1..4 }
    fun maximum(values: List<Double>?) = values?.take(65)?.maxOrNull()?.takeIf { it.isFinite() && it in 0.0..300_000.0 }?.toLong()
    val m = event.measurement
    return "event=JOINT_CANDIDATE stage=${event.stage} outcome=${event.outcome} reason=${event.reason}" +
        " exception_class=${event.exception} cause_class=${event.cause} primary_failure=${event.primaryFailure ?: "NA"}" +
        " requested_primary=${runtime(event.candidate?.primary)} requested_auxiliary=${runtime(event.candidate?.auxiliary)}" +
        " primary_interval_ms=${number(event.candidate?.primaryIntervalMs)} auxiliary_interval_ms=${number(event.candidate?.auxiliaryIntervalMs)}" +
        " actual_primary=${runtime(m?.actualPrimary)} actual_auxiliary=${runtime(m?.actualAuxiliary)} fallback=${m?.fallbackUsed ?: "NA"}" +
        " duration_ms=${number(m?.durationMs)} primary_submitted=${number(m?.primarySubmittedFrames?.toLong())}" +
        " auxiliary_submitted=${number(m?.auxiliarySubmittedFrames?.toLong())} primary_completed=${number(m?.primaryTurnaroundMs?.size?.toLong())}" +
        " auxiliary_completed=${number(m?.auxiliaryTurnaroundMs?.size?.toLong())} primary_max_ms=${number(maximum(m?.primaryTurnaroundMs))}" +
        " auxiliary_max_ms=${number(maximum(m?.auxiliaryTurnaroundMs))} thermal=${m?.thermalStatus?.takeIf { it in -1..6 } ?: "NA"}"
}
