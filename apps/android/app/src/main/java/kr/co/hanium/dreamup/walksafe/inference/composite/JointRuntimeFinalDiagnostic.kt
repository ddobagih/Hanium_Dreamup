package kr.co.hanium.dreamup.walksafe.inference.composite

enum class JointFinalStage { HOST_OPEN, REFERENCE_CLAIM, PRIMARY_STRICT, AUXILIARY_STRICT, SOAK, QUALIFICATION, DRAIN, PREPARED, ADOPTION, PUBLISH }
object JointRuntimeFinalDiagnostic {
    fun line(stage: JointFinalStage, outcome: String, error: Throwable? = null, fixture: String? = null): String {
        val kind = generateSequence(error) { it.cause }.take(8).filterIsInstance<JointRuntimeSampleFailure>().firstOrNull()?.failure?.kind?.name
            ?: if (error == null) "NONE" else when(error) { is java.util.concurrent.TimeoutException -> "TIMEOUT"; is IllegalStateException -> "STATE"; is IllegalArgumentException -> "ARGUMENT"; else -> "OTHER" }
        val reasons = mapOf(
            "sample_primary_raw_count" to "PRIMARY_RAW_COUNT",
            "sample_primary_raw_nonfinite" to "PRIMARY_RAW_NONFINITE",
            "sample_auxiliary_raw_count" to "AUXILIARY_RAW_COUNT",
            "sample_auxiliary_raw_nonfinite" to "AUXILIARY_RAW_NONFINITE",
            "CPU1 references already have a final consumer" to "CPU1_REFERENCES_ALREADY_HAVE_A_FINAL_CONSUMER",
            "Final measurement is incomplete" to "FINAL_MEASUREMENT_IS_INCOMPLETE",
            "Final native identity differs" to "FINAL_NATIVE_IDENTITY_DIFFERS",
            "Final qualification does not belong to the transferred native pair" to "FINAL_QUALIFICATION_DOES_NOT_BELONG_TO_THE_TRANSFERRED_NATIVE_PAIR",
            "Final strict fixture evidence differs" to "FINAL_STRICT_FIXTURE_EVIDENCE_DIFFERS",
            "final_host_drain_deadline" to "FINAL_HOST_DRAIN_DEADLINE",
            "final_pair_failed" to "FINAL_PAIR_FAILED",
            "final_pair_rejected" to "FINAL_PAIR_REJECTED",
            "final_pair_retired_before_qualification" to "FINAL_PAIR_RETIRED_BEFORE_QUALIFICATION",
            "final_ready_deadline" to "FINAL_READY_DEADLINE",
            "reference_binding_or_lifetime" to "REFERENCE_BINDING_OR_LIFETIME",
            "sample_acquire_deadline" to "SAMPLE_ACQUIRE_DEADLINE",
            "sample_attempt_cancelled" to "SAMPLE_ATTEMPT_CANCELLED",
            "sample_attempt_retired" to "SAMPLE_ATTEMPT_RETIRED",
            "sample_auxiliary_actual_runtime" to "SAMPLE_AUXILIARY_ACTUAL_RUNTIME",
            "sample_auxiliary_admission" to "SAMPLE_AUXILIARY_ADMISSION",
            "sample_auxiliary_instance_changed" to "SAMPLE_AUXILIARY_INSTANCE_CHANGED",
            "sample_auxiliary_not_drained" to "SAMPLE_AUXILIARY_NOT_DRAINED",
            "sample_auxiliary_raw_invalid" to "SAMPLE_AUXILIARY_RAW_INVALID",
            "sample_cleanup_deadline" to "SAMPLE_CLEANUP_DEADLINE",
            "sample_durable_model_ref" to "SAMPLE_DURABLE_MODEL_REF",
            "sample_fixture_input_changed" to "SAMPLE_FIXTURE_INPUT_CHANGED",
            "sample_measurement_budget" to "SAMPLE_MEASUREMENT_BUDGET",
            "sample_pair_handle_options" to "SAMPLE_PAIR_HANDLE_OPTIONS",
            "sample_pair_identity" to "SAMPLE_PAIR_IDENTITY",
            "sample_phase_clock_rollback" to "SAMPLE_PHASE_CLOCK_ROLLBACK",
            "sample_phase_deadline" to "SAMPLE_PHASE_DEADLINE",
            "sample_primary_actual_runtime" to "SAMPLE_PRIMARY_ACTUAL_RUNTIME",
            "sample_primary_incomplete" to "SAMPLE_PRIMARY_INCOMPLETE",
            "sample_primary_input_digest" to "SAMPLE_PRIMARY_INPUT_DIGEST",
            "sample_primary_instance_changed" to "SAMPLE_PRIMARY_INSTANCE_CHANGED",
            "sample_primary_raw_invalid" to "SAMPLE_PRIMARY_RAW_INVALID",
            "sample_source_retired_before_acquire" to "SAMPLE_SOURCE_RETIRED_BEFORE_ACQUIRE",
            "sample_thermal_unobserved" to "SAMPLE_THERMAL_UNOBSERVED",
            "sample_work_finished" to "SAMPLE_WORK_FINISHED"
        )
        val reason = generateSequence(error) { it.cause }.take(8).mapNotNull { reasons[it.message] }.firstOrNull() ?: "UNCLASSIFIED"
        val safeFixture = fixture?.takeIf { it in JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS } ?: "NA"
        return "event=JOINT_FINAL stage=${stage.name} outcome=${if(outcome in setOf("STARTED","SUCCEEDED","FAILED")) outcome else "FAILED"} kind=$kind reason=$reason fixture=$safeFixture"
    }
    fun emit(sink: (String) -> Unit, value: String) { runCatching { sink(value) } }
}
