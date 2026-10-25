package kr.co.hanium.dreamup.walksafe.fieldlog

import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupRequirement
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentPreflightPhase
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

/** Converts existing structured observations without keeping UI sentences, addresses or coordinates. */
internal object GuidanceDiagnosticMetadata {
    data class Event(val name: String, val fields: Map<String, Any?>)
    private val code = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val status = Regex("^(navigation|position|compass|route)=([A-Za-z][A-Za-z0-9_]{0,63})(?: |$)")
    private val prewalkStages = setOf("MEASUREMENT_REQUESTED", "OWNER_CANCELLED", "MEASUREMENT_FAILED_OR_TIMED_OUT",
        "WAIT_DIRECTION_CALIBRATION", "LOCATION_ACCESS_UNAVAILABLE", "WAIT_ENVIRONMENT_OR_MOUNTING", "WAIT_STARTUP_DECISION", "WAIT_CONFIRMATION_OR_READINESS", "POST_START_CONFIRMATION")
    private val reason = Regex("(?:^| )reason=([A-Za-z][A-Za-z0-9_]{0,63})(?: |$)")

    fun navigationStatus(text: String): Event? {
        val match = status.find(text) ?: return null
        return Event("route_terminal", mapOf("stage" to match.groupValues[2],
            "reason" to reason.find(text)?.groupValues?.get(1)))
    }

    fun structured(name: String, values: Map<String, Any?>): Event? = when (name) {
        "route_guidance_generated", "route_guidance_started" -> Event("tts", mapOf(
            "stage" to if (name == "route_guidance_generated") "GENERATED" else "START_CHECKED",
            "cue_id" to values["cue_id"], "elapsed_ms" to values["elapsed_realtime_ms"],
            "current" to (values["result"]?.let { it == "started" }),
            "reason" to values["compass_reason"], "expression_level" to values["expression_level"],
            "source" to values["compass_source"], "age_ms" to values["compass_age_ms"],
            "heading_accuracy_deg" to values["heading_accuracy_deg"],
            "authorization_kind" to values["clock_reference_kind"],
        ))
        "route_compass_state_changed" -> Event("direction_grace", mapOf(
            "stage" to "OBSERVED", "reason" to values["reason"],
            "gate_reason" to values["compass_rejected_check"],
            "source" to values["compass_source"], "age_ms" to values["compass_input_age_ms"],
            "heading_accuracy_deg" to values["heading_accuracy_deg"],
        ))
        "route_alignment" -> Event("route_terminal", mapOf(
            "stage" to "ALIGNMENT", "reason" to values["reason"],
            "accuracy_m" to values["position_uncertainty_m"],
            "source" to values["compass_source"], "age_ms" to values["compass_age_ms"],
        ))
        else -> null
    }

    /** Pure gate facts, never a readiness decision. Confirmation current means token presence. */
    fun prewalk(
        stage: String,
        walkState: WalkSessionState,
        preflightPhase: OfficialEnvironmentPreflightPhase,
        environment: WalkSessionReadinessStatus?,
        mounting: WalkSessionReadinessStatus?,
        pendingRequirements: Collection<WalkSafeStartupRequirement>,
        unavailableRequirements: Collection<WalkSafeStartupRequirement>,
        decisionMayStart: Boolean?,
        tokenPresent: Boolean,
        confirmationPosted: Boolean,
        confirmationPending: Boolean,
        confirmed: Boolean,
    ): List<Event> {
        if (stage !in prewalkStages) return emptyList()
        val events = mutableListOf(
            Event("route_request", mapOf("stage" to "PREWALK_STATUS", "reason" to stage,
                "mode" to walkState.name, "gate_reason" to preflightPhase.name)),
            Event("route_terminal", mapOf("stage" to "PREWALK_ENVIRONMENT", "reason" to (environment?.name ?: "NOT_OBSERVED"))),
            Event("route_terminal", mapOf("stage" to "PREWALK_MOUNTING", "reason" to (mounting?.name ?: "NOT_OBSERVED"))),
            Event("route_terminal", mapOf("stage" to "PREWALK_CONFIRMATION", "reason" to if (confirmed) "CONFIRMED" else "UNCONFIRMED",
                "gate_reason" to when (decisionMayStart) { null -> "DECISION_ABSENT"; true -> "MAY_START"; false -> "BLOCKED" },
                "mode" to if (confirmationPosted) "POSTED" else "NOT_POSTED",
                "current" to tokenPresent, "request_pending" to confirmationPending)),
        )
        val unavailable = unavailableRequirements.toSet()
        for (requirement in (pendingRequirements + unavailable).distinct().sortedBy { it.ordinal }) {
            events += Event("route_terminal", mapOf("stage" to "PREWALK_CAPABILITY", "gate_reason" to requirement.name,
                "reason" to if (requirement in unavailable) "UNAVAILABLE" else "PENDING"))
        }
        return events
    }

    fun preparation(stage: String, elapsedMs: Long): Event? = stage.takeIf(code::matches)?.let {
        Event("route_request", mapOf("stage" to it, "elapsed_ms" to elapsedMs))
    }
}

/** Unchanged sampling is sparse; a real state transition is always retained immediately. */
internal class GuidanceDiagnosticTransitionFilter(private val stableIntervalMs: Long = 10_000L) {
    private val lock = Any()
    private val last = linkedMapOf<String, Pair<String, Long>>()
    fun admit(channel: String, signature: String, atMs: Long): Boolean = synchronized(lock) {
        if (atMs < 0) return false
        val previous = last[channel]
        if (previous?.first == signature && atMs - previous.second in 0 until stableIntervalMs) return false
        if (last.size >= 16 && channel !in last) last.remove(last.keys.first())
        last[channel] = signature to atMs
        true
    }
}
