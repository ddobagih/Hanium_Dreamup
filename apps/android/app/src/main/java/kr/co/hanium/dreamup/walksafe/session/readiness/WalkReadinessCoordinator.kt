package kr.co.hanium.dreamup.walksafe.session.readiness

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*

/** Ordered collection from existing domain producers; no app-wide field reader or lifecycle copy. */
internal class WalkReadinessCoordinator(
    private val currentLifecycle: () -> WalkSessionLifecycle,
    private val capability: StartupCapabilityReadinessEvidence,
    private val resources: ReadinessResourceSource,
    private val official: OfficialReadinessSource,
    private val mounting: MountingReadinessSource,
    private val producers: WalkReadinessProducers,
    private val bypass: () -> Boolean,
    private val clock: () -> Long,
    private val androidApi: Int,
) {
    private fun lifecycle() = currentLifecycle()
    fun capture(
        decision: WalkSafeStartupCapabilityDecision,
        action: WalkSessionAction,
        recordResourceDiagnostics: Boolean = false,
        checkEntryPermissions: Boolean = true,
    ): WalkSessionReadinessSnapshot {
        val epoch = lifecycle().snapshot().epoch
        val captureStartedAt = clock()
        var effectiveDecision = capability.reusableOrRefresh(decision, captureStartedAt)
        effectiveDecision = producers.features.prepareDetector(effectiveDecision, action, producers.firstRun::isComplete)
        val plan = WalkSessionReadinessPlan(
            action = action,
            mode = effectiveDecision.effectiveWalkSessionMode(),
            checkEntryPermissions = checkEntryPermissions,
        )
        resources.retryFailedMonitoring()
        val deviceResources = resources.snapshot()
        if (recordResourceDiagnostics) {
            resources.recordDiagnostic("readiness_recheck", deviceResources)
        }
        val officialAssessment = if (plan.requires(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT)) {
            official.assessment(epoch)
        } else {
            null
        }
        val mountingAssessment = if (plan.requires(WalkSessionReadinessRequirement.PHONE_MOUNTING)) {
            mounting.assessment(epoch)
        } else {
            null
        }
        val activeRuntimePolicy = lifecycle().isRuntimeEpochCurrent(epoch)
        val preparationScope = lifecycle().snapshot()
        val allowModelPreparationPending = !activeRuntimePolicy &&
            preparationScope.epoch == epoch &&
            preparationScope.state in setOf(WalkSessionState.READY, WalkSessionState.PAUSED) &&
            action in setOf(WalkSessionAction.START_WALK, WalkSessionAction.RESUME_WALK)
        val decisionEvidence = capability.supportingEvidence()
        val collector = WalkSessionReadinessCollector(epoch = epoch, plan = plan)
        plan.requiredRequirements.forEach { requirement ->
            val collectedAt = clock()
            val (status, reason) = if (
                bypass() &&
                requirement !in setOf(
                    WalkSessionReadinessRequirement.GATEWAY,
                    WalkSessionReadinessRequirement.ROUTE_SERVICE,
                )
            ) {
                WalkSessionReadinessStatus.READY to "debug_guidance_start_bypass"
            } else producers.evaluate(requirement, effectiveDecision, epoch, officialAssessment, mountingAssessment,
                deviceResources, allowModelPreparationPending)
            val supportingEvidence = when {
                bypass() -> emptyList()
                requirement in setOf(
                    WalkSessionReadinessRequirement.CAMERA,
                    WalkSessionReadinessRequirement.LOCATION,
                    WalkSessionReadinessRequirement.METRIC_DISTANCE,
                    WalkSessionReadinessRequirement.VOICE_OUTPUT,
                ) -> listOf(decisionEvidence)
                requirement == WalkSessionReadinessRequirement.DEVICE_RESOURCES ->
                    listOf(decisionEvidence) + deviceResources.checkedAtElapsedRealtimeMs.map { (query, checkedAt) ->
                        WalkSessionReadinessEvidence(
                            source = "device_resources:${query.name.lowercase(Locale.US)}",
                            observedAtElapsedRealtimeMs = checkedAt,
                            maxAgeMs = WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS,
                            sourceVersion = "android_api:${androidApi}",
                        )
                    }
                // ACTIVE policies own retries/output suppression; raw preflight expiry must not
                // turn mounting correction or SUPPRESS_OUTPUTS_AND_RETRY into a whole-walk stop.
                activeRuntimePolicy -> emptyList()
                requirement == WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT ->
                    officialAssessment?.let { assessment ->
                        assessment.readinessEvidence
                            .filterKeys { it in assessment.enabledMeasuredFactors }
                            .values.toList()
                    }.orEmpty()
                requirement == WalkSessionReadinessRequirement.PHONE_MOUNTING && producers.features.cameraEnabled() ->
                    mountingAssessment?.readinessEvidence.orEmpty()
                else -> emptyList()
            }
            val observedAt = when {
                requirement in setOf(
                    WalkSessionReadinessRequirement.FIRST_RUN_ONBOARDING,
                    WalkSessionReadinessRequirement.PRIORITY_USER_ONBOARDING,
                    WalkSessionReadinessRequirement.ROUTE_SERVICE,
                ) -> null
                activeRuntimePolicy -> collectedAt
                requirement == WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT ->
                    officialAssessment?.assessedAtElapsedRealtimeMs ?: collectedAt
                requirement == WalkSessionReadinessRequirement.PHONE_MOUNTING ->
                    mountingAssessment?.assessedAtElapsedRealtimeMs ?: collectedAt
                else -> collectedAt
            }
            val evidenceVersion = when (requirement) {
                WalkSessionReadinessRequirement.FIRST_RUN_ONBOARDING,
                WalkSessionReadinessRequirement.PRIORITY_USER_ONBOARDING,
                -> null
                WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT -> officialAssessment?.profileId
                WalkSessionReadinessRequirement.PHONE_MOUNTING -> mountingAssessment?.profileId
                WalkSessionReadinessRequirement.MODEL -> producers.features.modelKey()
                WalkSessionReadinessRequirement.DEVICE_RESOURCES -> "android_api:${androidApi}"
                WalkSessionReadinessRequirement.GATEWAY ->
                    producers.gateway.evidenceVersion()
                WalkSessionReadinessRequirement.ROUTE_SERVICE ->
                    producers.endpoint.origin.takeIf(String::isNotBlank)
                else -> decisionEvidence.sourceVersion
            }
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = status,
                    reason = reason,
                    observedAtElapsedRealtimeMs = observedAt,
                    maxAgeMs = observedAt?.let { WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS },
                    evidenceVersion = evidenceVersion,
                    supportingEvidence = supportingEvidence,
                ),
            )
        }
        return collector.build(assessedAtElapsedRealtimeMs = clock())
    }
}
