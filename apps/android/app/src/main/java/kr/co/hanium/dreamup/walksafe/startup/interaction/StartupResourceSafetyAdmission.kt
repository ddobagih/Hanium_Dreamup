package kr.co.hanium.dreamup.walksafe.startup.interaction

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.ui.DeviceCheckPresenter

/** Publishes only complete, chronological resource observations to the existing safety owner. */
internal class StartupResourceSafetyAdmission(
    private val facts: ResourceSafetyFacts,
    private val thermal: ResourceSafetyThermalObservation,
    private val coordinator: () -> WalkRuntimeSafetyCoordinator,
    private val clock: () -> Long,
) {
    fun observeWalkRuntimeResourceSafety(
        expectedEpoch: WalkRuntimeEpoch? = null,
        resources: WalkSessionDeviceResourceSnapshot? = null,
    ): Boolean {
        if (!facts.lifecycleInitialized || !facts.probeInitialized) {
            return true
        }
        val runtimeEpoch = expectedEpoch ?: facts.currentRuntimeEpoch()
            ?: return true
        val currentResources = resources ?: facts.resourceSnapshot()
        thermal.record(currentResources.thermalThrottled)
        if (facts.bypass) {
            return facts.epochCurrent(runtimeEpoch)
        }
        val receivedAtMs = clock()
        val checkedAt = currentResources.checkedAtElapsedRealtimeMs
        if (
            checkedAt.size != WalkSessionResourceQuery.entries.size ||
            checkedAt.values.any { it < 0L || it > receivedAtMs }
        ) return false
        val observedAtMs = checkedAt.values.minOrNull() ?: return false
        return coordinator().observe(
            currentResources.toRuntimeSafetyObservation(
                epoch = runtimeEpoch,
                observedAtElapsedRealtimeMs = observedAtMs,
                receivedAtElapsedRealtimeMs = receivedAtMs,
            ),
        ).let { decision ->
            // Starting sensors is not permission to publish quantitative safety output.
            decision.epoch == runtimeEpoch &&
                decision.disposition != WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED &&
                decision.disposition != WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION
        }
    }
}
