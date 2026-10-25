package kr.co.hanium.dreamup.walksafe.session.readiness

import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.session.*

internal interface WalkReadinessBlockSources {
    fun onboardingComplete(): Boolean
    fun developmentBypassEnabled(): Boolean
    fun observedPermissions(): ObservedPermissionSnapshot
    fun voiceFeatureEnabled(): Boolean
    fun offlineTtsAllowed(decision: WalkSafeStartupCapabilityDecision): Boolean
    fun priorityOnboarding(decision: WalkSafeStartupCapabilityDecision): PriorityUserOnboardingDecision
    fun quickStartEnabled(): Boolean
    fun reporterUserId(): String?
    fun gatewayReadyForCurrentActor(): Boolean
    fun officialEnvironmentBlockReason(): String?
    fun phoneMountingBlockReason(): String?
    fun walkState(): WalkSessionState
    fun missingRequiredPermissions(action: WalkSessionAction): List<String>
    fun permissionGuidanceLabels(missing: List<String>): String
    fun runningDetectorBlocksEntry(): Boolean
    fun cameraEnabled(): Boolean
    fun detectorLoadAttempted(): Boolean
    fun detectorAvailable(): Boolean
    fun locationFeatureEnabled(): Boolean
}
