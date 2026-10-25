package kr.co.hanium.dreamup.walksafe.startup.capability

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.device.environment.*
import kr.co.hanium.dreamup.walksafe.session.readiness.StartupCapabilityReadinessEvidence

/** Owns capability refresh and its reentrant runtime-readiness transaction, not sensor evidence. */
internal class StartupCapabilityCoordinator(
    private val evaluation: StartupCapabilityEvaluation,
    private val evidence: () -> StartupCapabilityReadinessEvidence,
    private val lifecycle: () -> WalkSessionLifecycle,
    private val confirmation: StartupCapabilityConfirmation,
    private val ui: StartupCapabilityUi,
    private val runtime: ActiveReadinessPort,
    private val koreanTtsBlockDetail: String,
) {
    private var applyingRuntimeReadiness = false

    fun resolveCurrentStartupCapabilityDecision(
        metricDistanceOverride: Boolean? = evaluation.metricOverride,
    ): WalkSafeStartupCapabilityDecision {
        val decision = applyPostLoginDeviceFeatureRestrictions(evaluation.probeDecision(metricDistanceOverride))
        if (evaluation.bypass) {
            return decision.copy(
                tier = if (metricDistanceOverride == false) WalkSafeStartupCapabilityTier.LIMITED
                    else WalkSafeStartupCapabilityTier.FULL,
                unavailableRequirements = if (metricDistanceOverride == false)
                    listOf(WalkSafeStartupRequirement.METRIC_DISTANCE) else emptyList(),
                pendingRequirements = emptyList(),
                noticeKo = "개발 전용: 안내 시작 제한을 통과 처리합니다. 실제 카메라·Depth·위치 결과는 별도 점검 화면에서 확인하세요.",
            )
        }
        if (!evaluation.quickStart) return decision
        val requiredCapabilityUnavailable =
            WalkSafeStartupRequirement.ANDROID_VERSION in decision.unavailableRequirements ||
                WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS in decision.unavailableRequirements
        // This grants a runtime attempt, not a successful sensor or speech measurement.
        return decision.copy(
            tier = if (requiredCapabilityUnavailable) {
                WalkSafeStartupCapabilityTier.BLOCKED
            } else {
                WalkSafeStartupCapabilityTier.LIMITED
            },
            pendingRequirements = decision.pendingRequirements.filterNot {
                it in setOf(
                    WalkSafeStartupRequirement.METRIC_DISTANCE,
                    WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS,
                    WalkSafeStartupRequirement.ON_DEVICE_STT,
                )
            },
            noticeKo = if (requiredCapabilityUnavailable) {
                decision.noticeKo
            } else {
                "개발 전용: 초기 기기 성능 점검을 생략합니다. " +
                    "환경 확인과 실제 카메라·위치·장착 판정, 사용 중 재점검은 유지합니다."
            },
        )
    }

    fun applyPostLoginDeviceFeatureRestrictions(
        decision: WalkSafeStartupCapabilityDecision,
    ): WalkSafeStartupCapabilityDecision {
        if (!evaluation.quickStart && !evaluation.checkPassesFeatureGate) return decision
        val restrictedRequirements = buildSet {
            evaluation.disabledFeatures().forEach { feature ->
                when (feature) {
                    PostLoginDeviceCheckFeature.OBSTACLE_DETECTION ->
                        add(WalkSafeStartupRequirement.CAMERA)
                    PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE ->
                        add(WalkSafeStartupRequirement.METRIC_DISTANCE)
                    PostLoginDeviceCheckFeature.LOCATION_GUIDANCE ->
                        add(WalkSafeStartupRequirement.GPS)
                    // Wake-word failure does not replace the probe's actual microphone/STT result.
                    PostLoginDeviceCheckFeature.HANDS_FREE_VOICE -> Unit
                    PostLoginDeviceCheckFeature.VOICE_GUIDANCE ->
                        add(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS)
                    PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK ->
                        add(WalkSafeStartupRequirement.VIBRATION)
                }
            }
        }
        val stepTrackingRestricted = !evaluation.activityRecognitionPermission()
        if (restrictedRequirements.isEmpty() && !stepTrackingRestricted) return decision
        val unavailable = (decision.unavailableRequirements + restrictedRequirements).distinct()
        val pending = decision.pendingRequirements.filterNot(restrictedRequirements::contains)
        val tier = if (
            WalkSafeStartupRequirement.ANDROID_VERSION in unavailable ||
            WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS in unavailable ||
            // FP-009: a feature restriction must not reopen a capability-blocked start.
            WalkSafeStartupCapabilityResolver.blocksWalkStart(decision.unavailableRequirements) ||
            pending.isNotEmpty()
        ) {
            WalkSafeStartupCapabilityTier.BLOCKED
        } else {
            WalkSafeStartupCapabilityTier.LIMITED
        }
        return decision.copy(
            tier = tier,
            unavailableRequirements = unavailable,
            pendingRequirements = pending,
            noticeKo = if (
                WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS in unavailable
            ) {
                koreanTtsBlockDetail
            } else if (tier == WalkSafeStartupCapabilityTier.LIMITED) {
                "제한 기능: ${evaluation.disabledFeatureText()}. " +
                    "해당 기능만 사용할 수 없으며 나머지 기능은 사용할 수 있습니다."
            } else {
                decision.noticeKo
            },
        )
    }

    /** Restoring a saved binding may retire its callback before live probe evidence completes. */
    fun refreshAfterStoredBindingProbeChanged(bindingChanged: () -> Unit) {
        bindingChanged()
        refreshStartupCapabilityUi()
    }

    fun refreshStartupCapabilityUi() {
        if (!ui.ready()) return
        val previousDecision = evidence().currentDecision
        val decision = resolveCurrentStartupCapabilityDecision()
        val walkBeforeDecisionChange = lifecycle().snapshot()
        val dynamicallyChangeableRequirements = setOf(
            WalkSafeStartupRequirement.CAMERA,
            WalkSafeStartupRequirement.GPS,
            WalkSafeStartupRequirement.MICROPHONE,
            WalkSafeStartupRequirement.VIBRATION,
            WalkSafeStartupRequirement.ON_DEVICE_STT,
            WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS,
            WalkSafeStartupRequirement.METRIC_DISTANCE,
        )
        val changedUnavailableRequirements = if (previousDecision == null) {
            emptySet()
        } else {
            val previousUnavailable = previousDecision.unavailableRequirements.toSet()
            val currentUnavailable = decision.unavailableRequirements.toSet()
            (previousUnavailable - currentUnavailable) +
                (currentUnavailable - previousUnavailable)
        }
        val automaticallyRebindActiveFeatureDecision =
            previousDecision != null &&
                walkBeforeDecisionChange.state == WalkSessionState.ACTIVE &&
                confirmation.confirmed == previousDecision &&
                decision.mayConfirmAndStart &&
                decision.pendingRequirements.isEmpty() &&
                changedUnavailableRequirements.all(dynamicallyChangeableRequirements::contains)
        evidence().record(decision)
        ui.persist(decision)
        if (confirmation.confirmed != decision) {
            confirmation.confirmed = if (
                automaticallyRebindActiveFeatureDecision
            ) {
                decision
            } else {
                null
            }
        }
        if (previousDecision != null && previousDecision != decision) {
            confirmation.pending = false
            confirmation.retryRequiresUserAction = false
        }
        applyRuntimeReadinessIfActive(decision)
        val confirmed = confirmation.isConfirmed()
        val priorityUserDecision = ui.priority(decision)
        ui.updateOfficialEnvironment()
        val officialEnvironmentReady =
            ui.officialReadiness(lifecycle().snapshot().epoch) ==
                WalkSessionReadinessStatus.READY
        val phoneMountingReady =
            ui.mountingReadiness(lifecycle().snapshot().epoch) ==
                WalkSessionReadinessStatus.READY
        ui.render(decision, confirmed, priorityUserDecision, officialEnvironmentReady, phoneMountingReady)
        ui.updatePrewalk(
            epoch = lifecycle().snapshot().epoch,
            officialReady = officialEnvironmentReady,
            mountingReady = phoneMountingReady,
        )
        ui.updatePriority(decision)
        ui.updateFirstRun()
        ui.updateMounting()
        ui.updateFieldLog()
        ui.applyActionButtons()
        ui.advanceAfterCheck()
        ui.updateSafetySummary()
    }

    fun applyRuntimeReadinessIfActive(
        decision: WalkSafeStartupCapabilityDecision,
    ) {
        if (applyingRuntimeReadiness) return
        val current = lifecycle().snapshot()
        if (current.state != WalkSessionState.ACTIVE) return
        applyingRuntimeReadiness = true
        try {
            val readiness = runtime.capture(decision)
            if (runtime.debug && (!readiness.isReady || readiness.plan.mode != current.mode)) {
                runtime.diagnostic(
                    "event=WALK_RUNTIME_READINESS_BLOCKED mode=${readiness.plan.mode} " +
                        "requirements=" + readiness.plan.requiredRequirements.joinToString("+") {
                            "$it:${readiness.observations.getValue(it).status}"
                        } + " stale=" + readiness.staleRequirements.joinToString("+") { it.name }.ifEmpty { "NONE" },
                )
            }
            val transition = runtime.transition(
                WalkSessionEvent.RuntimeReadinessChanged(readiness),
            )
            if (
                transition.previous.state == WalkSessionState.ACTIVE &&
                transition.current.state == WalkSessionState.SAFE_STOP
            ) {
                runtime.persistInterruption()
                runtime.clearResume()
                runtime.invalidateEnvironment("runtime_readiness_changed")
                runtime.invalidateMounting("runtime_readiness_changed")
                runtime.cancelOutputs("runtime_readiness_changed")
                runtime.stopCameraFallback()
                runtime.stopDepth()
                // S03-6: raw readiness codes stay in the diagnostics; the user hears the lost
                // feature and the next action (FP-018 user_guidance, FP-043 design_rules[4]).
                runtime.navigationStatus(
                    "walk=runtime_readiness_safe_stop reasons=" +
                        readiness.blockingReasons.joinToString(","),
                )
                val reason = walkReadinessStopReasonKo(readiness)
                runtime.status("필수 기능 변경 · 안전 중지", reason)
                runtime.speak(
                    "보행 기능을 안전 중지했습니다. $reason",
                )
            }
        } finally {
            applyingRuntimeReadiness = false
        }
    }
}
