package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Continues only the current walk after release; native lifecycle effects remain platform-owned. */
internal class WalkRuntimeCameraAdmissionController(
    private val admission: WalkRuntimeCameraAdmission,
    private val effects: WalkRuntimeCameraEffects,
) {
    fun startWalkSessionRuntimeAfterCameraRelease(
        expectedEpoch: WalkRuntimeEpoch,
    ) {
        if (
            !admission.runtimeActive() ||
            !admission.isRuntimeEpochCurrent(expectedEpoch)
        ) {
            effects.cancelHandoff(expectedEpoch)
            return
        }
        if (admission.fallbackCleanupPending()) {
            effects.releaseFallback { startWalkSessionRuntimeAfterCameraRelease(expectedEpoch) }
            return
        }
        if (!admission.cameraEnabled()) {
            startWalkSessionRuntimeWithoutCamera()
            effects.completeResumeActivation(expectedEpoch)
            return
        }
        if (admission.cameraReleaseBlocked()) {
            effects.cancelHandoff()
            effects.safetyStop("camera_release_timed_out")
            return
        }
        effects.resumeCameraLifecycleIfInitialized()
        effects.resumeOrPauseSurfaceForCurrentArSession()
        effects.startOrientationIfInitialized()
        effects.syncScreenPolicy()
        if (admission.fallbackRequested()) {
            if (!admission.fallbackRunning()) effects.bindFallback()
        } else if (admission.arSessionMissing()) {
            effects.clearPendingInstallStartIfSet()
            effects.ensurePermissionsThenStart()
        }
        effects.startNavigation()
        effects.startHandsFreeVoice()
        effects.refreshReadinessUi()
        effects.completeResumeActivation(expectedEpoch)
    }

    fun startWalkSessionRuntimeWithoutCamera() {
        effects.cancelHandoff()
        effects.pauseSurfaceIfInitialized()
        effects.retainLocationCompassOrStop()
        effects.startNavigation()
        effects.startHandsFreeVoice()
        effects.syncScreenPolicy()
        effects.refreshReadinessUi()
    }
}
