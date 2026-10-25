package kr.co.hanium.dreamup.walksafe.device

/** Retains a current API-support observation, never a distance frame or permission to emit it. */
internal fun retainedMetricDepthCapabilityAfterLifecycle(
    reason: String,
    previousCapability: Boolean?,
    depthState: PostLoginMetricDepthState,
    onboardingComplete: Boolean,
    deviceCheckCurrent: Boolean,
    cameraChecksDeferred: Boolean,
    supportRefreshPending: Boolean,
): Boolean? {
    if (reason !in setOf("app_paused", "first_run_stage_changed", "new_walk") ||
        !onboardingComplete || !deviceCheckCurrent || cameraChecksDeferred ||
        supportRefreshPending
    ) return null
    // A lifecycle event cannot revive a result already invalidated by a runtime failure.
    return when {
        depthState == PostLoginMetricDepthState.SUPPORTED && previousCapability == true -> true
        depthState == PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED && previousCapability == false -> false
        else -> null
    }
}
