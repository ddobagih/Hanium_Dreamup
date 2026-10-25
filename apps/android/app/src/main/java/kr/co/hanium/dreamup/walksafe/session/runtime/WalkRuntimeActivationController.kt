package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyDisposition
import kr.co.hanium.dreamup.walksafe.session.*

/** Admission orchestrator; camera/AR/View operations stay at their platform owner. */
internal class WalkRuntimeActivationController(
    private val admission: WalkActivationAdmission,
    private val readiness: WalkResumeReadiness,
    private val effects: WalkActivationEffects,
    private val services: WalkActivationServices,
    private val camera: WalkActivationCamera,
    private val gateway: GatewayWalkSessionController,
    private val resume: WalkSessionResumeController,
) {
    fun activateWalkSessionRuntime(): Boolean {
        if (!admission.runtimeActive()) {
            effects.authorityLost("walk_lease_missing_before_runtime")
            return false
        }
        val runtimeEpoch = admission.runtimeEpochOrNull()
        if (runtimeEpoch == null) {
            effects.authorityLost("walk_epoch_missing_before_runtime")
            return false
        }
        if (admission.rejectUnsafeDetector(runtimeEpoch, admission.snapshot().state) { epoch ->
                if (admission.isRuntimeEpochCurrent(epoch)) {
                    effects.safetyStop("detector_stalled")
                }
            }
        ) return false
        if (!effects.persistInterruptionMarker()) {
            effects.foregroundRecheck("interruption_marker_write_failed")
            resume.requireUserRetry()
            effects.updateStatus("보행 재개 확인 필요", "중단 상태를 저장하지 못해 보행 안내를 시작하지 않았습니다. 다시 시도해 주세요.")
            return false
        }
        if (admission.beginSafetyEpoch(runtimeEpoch).disposition ==
            WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED
        ) {
            if (admission.stopCleanupExhausted()) effects.showRestartNotice()
            return false
        }
        services.ensureResourceMonitoring()
        // S14-43/FP-045: a failed resource monitor is never assumed normal; retry after a recheck.
        if (!admission.developmentBypassEnabled() &&
            services.resourceMonitoringBlocksReadiness()
        ) {
            effects.foregroundRecheck("resource_monitoring_unavailable")
            resume.requireUserRetry()
            effects.updateStatus("보행 재개 확인 필요", "배터리·저장공간·발열 감시를 시작하지 못해 보행 안내를 시작하지 않았습니다. 다시 시도해 주세요.")
            return false
        }
        if (!services.observeResourceSafety(runtimeEpoch)) return false
        gateway.scheduleGatewayWalkRenewal()
        resume.clearPromptAfterRuntimeAdmission()
        if (!services.activateOfficialEnvironment()) return false
        if (!services.activatePhoneMounting()) return false
        startAdmittedNavigationBeforeCamera(runtimeEpoch)
        if (camera.analysisFeaturesEnabled()) {
            camera.beginHandoff(runtimeEpoch)
        } else {
            camera.cancelHandoff()
        }
        camera.stopEnvironmentPreflight(
            releaseMountingSensor = false,
            onReleased = {
                // Let the queued destination continuation and location callbacks run before
                // synchronous native ARCore creation/resume work occupies the main thread.
                camera.postAfterRelease {
                    camera.startRuntimeAfterRelease(runtimeEpoch)
                }
            },
        )
        // Admission is not completion: camera release and fresh runtime evidence may arrive later.
        return true
    }

    private fun startAdmittedNavigationBeforeCamera(expectedEpoch: WalkRuntimeEpoch) {
        if (!admission.runtimeActive() || !admission.isForeground() ||
            !admission.isRuntimeEpochCurrent(expectedEpoch) ||
            !readiness.isConfirmed() || !admission.navigationOutputsAllowed() ||
            admission.permissionBlocksAutomaticStart() && !admission.developmentBypassEnabled()
        ) return
        val decision = readiness.decision() ?: return
        val action = admission.snapshot().readiness?.plan?.action ?: return
        val readiness = readiness.capture(decision, action)
        if (readiness.epoch != expectedEpoch || !readiness.isReady ||
            !readiness.isReadyAt(admission.nowMs()) ||
            !admission.isRuntimeEpochCurrent(expectedEpoch) || !admission.runtimeActive()
        ) return
        services.startNavigation()
        services.startHandsFreeVoice()
        services.recordGuidancePreparationStage("NAVIGATION_ADMITTED_BEFORE_CAMERA")
        services.refreshReadinessUi()
    }
}
