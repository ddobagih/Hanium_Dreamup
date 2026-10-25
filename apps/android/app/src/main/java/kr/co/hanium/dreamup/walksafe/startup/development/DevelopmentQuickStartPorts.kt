package kr.co.hanium.dreamup.walksafe.startup.development

import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
import kr.co.hanium.dreamup.walksafe.session.*

/** Android scheduling and existing status View. No mirrored status text or second clock. */
internal interface DevelopmentQuickStartPlatform {
    val enabled: Boolean
    val foreground: Boolean
    val destroyed: Boolean
    fun nowMs(): Long
    fun statusText(): String
    fun showStatus(message: String)
    fun remove(task: Runnable)
    fun postDelayed(task: Runnable, delayMs: Long)
    fun refreshFirstRunUi()
}

/** The actual process/account owner remains responsible for verification and login. */
internal interface DevelopmentQuickStartAccount {
    fun processSnapshot(): GatewaySessionProcessSnapshot
    fun deletionBlocksProcessing(): Boolean
    fun configuredOrigin(): String?
    fun requestInFlight(): Boolean
    fun credentialsAvailable(): Boolean
    fun accountNotice(): String?
    fun showAccountMessage(message: String)
    fun hasGatewaySession(): Boolean
    fun clearGatewaySessionWithoutRemoteLogout()
    fun prepareInitialEmailAccount()
    fun loginConfiguredAccount()
    /** Existing actor fields, speech-storage invalidation, permission auth and consent reapply. */
    fun bindAuthenticatedActor(actorId: String)
}

internal interface DevelopmentQuickStartDevice {
    fun missingPermissions(): List<String>
    fun permissionRequestInFlight(): Boolean
    fun requestPermissionsWithOwnedLease(permissions: List<String>)
    fun permissionLabel(permission: String): String
    fun ensureResourceMonitoring()
    fun ensureCapabilityProbeStarted()
}

internal interface DevelopmentQuickStartConsent {
    fun currentConfirmation(): IntegratedConsentConfirmation?
    fun requestInFlight(): Boolean
    fun bootstrapReady(): Boolean
    fun refreshFromServer()
    fun selectDevelopmentDraft(confirmation: IntegratedConsentConfirmation?)
    fun persistDraftWithoutAnnouncement()
}

internal interface DevelopmentQuickStartWalk {
    fun snapshot(): WalkSessionSnapshot
    fun runtimeActive(): Boolean
    fun gatewayStartPending(): Boolean
    fun handleExistingConfirmationAction()
    fun startFreshWalk(reason: String)
    fun resolveCapability(): WalkSafeStartupCapabilityDecision
    fun recordCapability(decision: WalkSafeStartupCapabilityDecision)
    fun captureStartReadiness(decision: WalkSafeStartupCapabilityDecision): WalkSessionReadinessSnapshot
    fun transition(event: WalkSessionEvent)
    fun confirmCapabilityForGatewayStart(decision: WalkSafeStartupCapabilityDecision)
    fun requestGatewayStart(token: WalkSessionConfirmationToken)
}
