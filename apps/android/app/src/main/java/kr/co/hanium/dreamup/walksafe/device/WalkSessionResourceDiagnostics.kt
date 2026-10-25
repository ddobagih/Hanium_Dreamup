package kr.co.hanium.dreamup.walksafe.device

/** Formats existing resource evidence without querying, refreshing its time, or logging it. */
fun WalkSessionDeviceResourceSnapshot.resourceDiagnosticsLine(): String {
    val checkedAt = WalkSessionResourceQuery.entries.joinToString("+") { query ->
        "${query.name}:${checkedAtElapsedRealtimeMs[query]?.toString() ?: "UNKNOWN"}"
    }
    val failed = WalkSessionResourceQuery.entries.filter { it in failedQueries }
        .joinToString("+") { it.name }.ifEmpty { "NONE" }
    val failures = WalkSessionResourceQuery.entries.joinToString("+") { query ->
        val failure = when {
            query in failedQueries -> resourceDiagnosticExceptionType(queryFailureReasons[query])
            query !in checkedAtElapsedRealtimeMs -> "UNKNOWN"
            else -> "NONE"
        }
        "${query.name}:$failure"
    }
    val failedSubscriptions = WalkSessionResourceSubscription.entries
        .filter { it in monitoring.failedSubscriptions }
        .joinToString("+") { it.name }.ifEmpty { "NONE" }
    val monitoringFailure = when {
        monitoring.failureReason != null -> resourceDiagnosticExceptionType(monitoring.failureReason)
        monitoring.state == WalkSessionResourceMonitoringState.SUBSCRIBED -> "NONE"
        else -> "UNKNOWN"
    }
    val battery = batteryObservation
    val batteryUnknownReasons = WalkSessionBatteryUnknownReason.entries
        .filter { it in battery.unknownReasons }
        .joinToString("+") { it.name }.ifEmpty { "NONE" }
    return "checked_at_ms=$checkedAt failed_queries=$failed query_failures=$failures " +
        "monitoring=${monitoring.state.name} failed_subscriptions=$failedSubscriptions " +
        "monitoring_failure=$monitoringFailure " +
        "subscribed_at_ms=${monitoring.subscribedAtElapsedRealtimeMs?.toString() ?: "UNKNOWN"} " +
        "last_event_at_ms=${monitoring.lastEventAtElapsedRealtimeMs?.toString() ?: "UNKNOWN"}" +
        " battery_level=${battery.level?.toString() ?: "UNKNOWN"}" +
        " battery_scale=${battery.scale?.toString() ?: "UNKNOWN"}" +
        " battery_current_now_ua=${battery.currentNowMicroamps?.toString() ?: "UNKNOWN"}" +
        " battery_current_average_ua=${battery.currentAverageMicroamps?.toString() ?: "UNKNOWN"}" +
        " battery_charge_status=${battery.chargeStatus.name}" +
        " battery_plugged_in=${battery.pluggedIn?.toString() ?: "UNKNOWN"}" +
        " battery_source=${battery.source.name}" +
        " battery_query_received_at_ms=${battery.queryReceivedAtElapsedRealtimeMs?.toString() ?: "UNKNOWN"}" +
        " battery_measured_at_ms=${battery.measuredAtElapsedRealtimeMs?.toString() ?: "UNKNOWN"}" +
        " battery_unknown_reasons=$batteryUnknownReasons"
}

private val resourceDiagnosticExceptionTypePattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

/** The probe stores simple type names; malformed free text must not become log content. */
private fun resourceDiagnosticExceptionType(value: String?): String =
    value?.takeIf(resourceDiagnosticExceptionTypePattern::matches) ?: "UNKNOWN"
