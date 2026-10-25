package kr.co.hanium.dreamup.walksafe.session.gateway

import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.onboarding.FirstRunOnboardingController
import kr.co.hanium.dreamup.walksafe.session.onboarding.PriorityUserProfileController

/** Orders retirement and publication among the existing actors; owns no second session state. */
internal class ActorSessionLifecycleCoordinator(
    private val gateway: Gateway,
    private val routes: Routes,
    private val reports: Reports,
    private val raw: Raw,
    private val consent: Consent,
    private val speech: Speech,
    private val permissions: Permissions,
    private val device: Device,
    private val privacy: Privacy,
    private val runtime: Runtime,
    private val firstRunSource: () -> FirstRunOnboardingController,
    private val profilesSource: () -> PriorityUserProfileController,
) {
    interface Gateway {
        fun snapshot(): GatewaySessionProcessSnapshot
        fun processSessionChanged(snapshot: GatewaySessionProcessSnapshot)
        fun fenceCapacityGeneration(generation: Long)
        fun requestCapacity(reason: String)
        fun publishFirstRun(generation: Long, actorId: String, snapshot: FirstRunOnboardingSnapshot): Boolean
        fun retryPendingRenewal()
    }
    interface Routes {
        fun retireRequest(snapshot: GatewaySessionProcessSnapshot): Boolean
        fun presentRetiredRequest()
        fun reconcileAccount(snapshot: GatewaySessionProcessSnapshot)
    }
    interface Reports {
        fun cancelAuthorityExpiry()
        fun invalidatePendingExplicit()
        fun withdrawPrivacy()
        fun cancelQueueDrain()
        fun clearAuthorityUiFence()
        fun initialized(): Boolean
        fun authorityChanged()
        fun loadIfIdleAndAuthorized()
        fun scheduleAuthorityExpiry()
    }
    interface Raw {
        fun initialized(): Boolean
        fun clearUploadIdentity()
        fun cancelActiveUpload()
        fun revalidate()
        fun clearDetectionWindowInTask()
        fun resetEnrollmentInTask(session: GatewayFieldSession, generation: Long, deviceCheck: PostLoginDeviceCheckSnapshot)
    }
    interface Consent {
        fun onActorBindingChanged(session: GatewayFieldSession?)
        fun fenceAuthorityChanged(snapshot: GatewaySessionProcessSnapshot): Boolean
        fun reapplyActorBound()
        fun cachedConfirmationPresent(): Boolean
        fun noPendingMutation(): Boolean
        fun refreshFromServer()
    }
    interface Speech {
        fun retireGatewayAuthorityIfChanged(snapshot: GatewaySessionProcessSnapshot)
        fun invalidateStorage()
        fun invalidatePermissionActorIfChanged(actorId: String)
    }
    interface Permissions {
        var reporterActorId: String?
        fun remember(actorId: String)
        fun authenticate(actorId: String)
        fun expireAuthentication()
        fun invalidateRecoveryScope()
        fun enforceAccountEligibility()
    }
    interface Device {
        fun bindSession(actorId: String?, generation: Long?)
        fun snapshot(): PostLoginDeviceCheckSnapshot
        fun passesGate(): Boolean
        fun disabledFeatureText(): String
        fun hasRestrictions(): Boolean
        fun clearLiveStatus()
        fun announceProgress(message: String)
    }
    interface Privacy {
        fun callbackOwnerLive(): Boolean
        fun resumeDeletionIfNeeded(sessionPresent: Boolean)
    }
    interface Runtime {
        fun voiceGatewayChanged(snapshot: GatewaySessionProcessSnapshot)
        fun cancelEducation()
        fun cancelActivityOriginalUploads()
        fun backendUiReady(): Boolean
        fun runOnUiThread(action: Runnable)
        fun dispatchObserverInlineIfMain(action: Runnable)
        fun postDecor(action: Runnable)
        fun refreshActorScreens()
        fun scheduleTransfers()
        fun startLocationIfAllowed()
    }
    private val firstRun get() = firstRunSource()
    private val profiles get() = profilesSource()

    fun onGatewayProcessSessionChanged(
        snapshot: GatewaySessionProcessSnapshot,
        consentAuthorityChanged: Boolean,
    ) {
        if (routes.retireRequest(snapshot)) {
            routes.presentRetiredRequest()
        }
        if (snapshot.generation == gateway.snapshot().generation) {
            routes.reconcileAccount(snapshot)
        }
        gateway.processSessionChanged(snapshot)
        reports.cancelAuthorityExpiry()
        reports.invalidatePendingExplicit()
        consent.onActorBindingChanged(snapshot.session)
        runtime.voiceGatewayChanged(snapshot)
        reports.cancelQueueDrain()
        if (raw.initialized()) {
            raw.clearUploadIdentity()
            raw.cancelActiveUpload()
            raw.revalidate()
            raw.clearDetectionWindowInTask()
        }
        reports.clearAuthorityUiFence()
        if (reports.initialized()) {
            reports.authorityChanged()
        }
        if (!privacy.callbackOwnerLive()) return
        gateway.fenceCapacityGeneration(snapshot.generation)
        privacy.resumeDeletionIfNeeded(snapshot.session != null)
        val session = snapshot.session
        val restoredFirstRun = snapshot.restoredFirstRunSnapshot
        val effectiveFirstRun = firstRun.effectiveSessionSnapshot(restoredFirstRun)
        val reporterActorId = effectiveFirstRun?.reporterActorBinding?.value
        val verifiedEmailActorId = effectiveFirstRun?.takeIf {
            it.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 && !it.isComplete
        }?.verifiedActorBinding?.value
        val actorId = reporterActorId ?: verifiedEmailActorId
        val verifiedActorSession = session?.takeIf { current ->
            actorId == current.actorId &&
                !snapshot.deletionRecoveryOnly &&
                !snapshot.storageBlocked &&
                current.verificationState == GatewaySessionVerificationState.VERIFIED &&
                current.isUsableFor(current.actorId)
        }
        device.bindSession(
            actorId = verifiedActorSession?.actorId,
            generation = snapshot.generation.takeIf { verifiedActorSession != null },
        )
        if (verifiedActorSession == null) {
            runtime.cancelEducation()
            permissions.reporterActorId = null
            profiles.actorId = null
            profiles.policy = PriorityUserOnboardingPolicy()
        }
        if (
            verifiedActorSession != null &&
            effectiveFirstRun != null &&
            actorId != null &&
            actorId == verifiedActorSession.actorId
        ) {
            if (restoredFirstRun != null) firstRun.restoreSessionSnapshot(restoredFirstRun)
            val appliedFirstRun = firstRun.snapshot
            permissions.reporterActorId = actorId
            speech.invalidateStorage()
            permissions.remember(actorId)
            if (
                profiles.policyInitialized &&
                profiles.actorId != actorId
            ) {
                profiles.restorePriorityUserOnboardingFromPrefs()
            }
            if (
                appliedFirstRun.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
                appliedFirstRun.ageBand == FirstRunAgeBand.VERIFIED_14_PLUS &&
                profiles.policy.snapshot().ageBand !=
                PriorityUserAgeBand.VERIFIED_14_PLUS
            ) {
                profiles.actorId = actorId
                profiles.policy = PriorityUserOnboardingPolicy(
                    PriorityUserOnboardingSnapshot(
                        ageBand = PriorityUserAgeBand.VERIFIED_14_PLUS,
                    ),
                )
            }
            if (
                appliedFirstRun.isComplete &&
                reporterActorId == actorId &&
                !snapshot.storageBlocked &&
                verifiedActorSession.verificationState ==
                GatewaySessionVerificationState.VERIFIED &&
                verifiedActorSession.isUsableFor(actorId) &&
                profiles.actorId == actorId &&
                profiles.policy.accountBlockReason() == null
            ) {
                speech.invalidatePermissionActorIfChanged(actorId)
                permissions.authenticate(actorId)
            } else {
                speech.invalidateStorage()
                permissions.expireAuthentication()
            }
        } else {
            speech.invalidateStorage()
            permissions.expireAuthentication()
        }
        consent.reapplyActorBound()
        permissions.invalidateRecoveryScope()
        if (verifiedActorSession != null) {
            permissions.enforceAccountEligibility()
        }
        if (
            session != null &&
            !snapshot.deletionRecoveryOnly &&
            !snapshot.storageBlocked &&
            session.verificationState == GatewaySessionVerificationState.VERIFIED &&
            session.isUsableFor(session.actorId)
        ) {
            gateway.requestCapacity("verified_session_startup")
        }
        if (runtime.backendUiReady()) {
            runtime.runOnUiThread(Runnable {
                if (!privacy.callbackOwnerLive()) return@Runnable
                runtime.refreshActorScreens()
            })
        }
        if (reports.initialized()) {
            reports.authorityChanged()
            reports.loadIfIdleAndAuthorized()
        }
        reports.scheduleAuthorityExpiry()
        val restoredDeviceCheckBinding = device.snapshot().bindingOrNull
        if (
            restoredDeviceCheckBinding != null &&
            device.snapshot().passesFeatureGate &&
            firstRun.snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
            firstRun.snapshot.stage in setOf(
                FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION,
                FirstRunOnboardingStage.DEVICE_CHECK,
            )
        ) {
            runtime.postDecor(Runnable {
                if (
                    device.snapshot().bindingOrNull == restoredDeviceCheckBinding &&
                    device.passesGate() &&
                    firstRun.snapshot.stage in setOf(
                        FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION,
                        FirstRunOnboardingStage.DEVICE_CHECK,
                    ) &&
                    firstRun.advanceEmailStagesWithRestoredDeviceCheck()
                ) {
                    firstRun.onFirstRunOnboardingStateChanged(
                        announcement =
                            "저장된 기기 점검 결과를 적용했습니다. 안전교육을 완료하세요.",
                        preservePostLoginDeviceCheck = true,
                    )
                }
            })
        }
        runtime.scheduleTransfers()
        runtime.startLocationIfAllowed()
        if (
            consentAuthorityChanged &&
            consent.cachedConfirmationPresent() &&
            consent.noPendingMutation() &&
            verifiedActorSession != null
        ) {
            consent.refreshFromServer()
        }
    }

    fun dispatchGatewayProcessSessionChanged(snapshot: GatewaySessionProcessSnapshot) {
        speech.retireGatewayAuthorityIfChanged(snapshot)
        val current = gateway.snapshot()
        var consentAuthorityChanged = false
        if (snapshot.generation == current.generation && snapshot.session === current.session &&
            snapshot.storageBlocked == current.storageBlocked && snapshot.deletionRecoveryOnly == current.deletionRecoveryOnly
        ) {
            consentAuthorityChanged = consent.fenceAuthorityChanged(snapshot)
            if (consentAuthorityChanged) {
                reports.withdrawPrivacy()
                reports.cancelQueueDrain()
                runtime.cancelActivityOriginalUploads()
                if (raw.initialized()) raw.cancelActiveUpload()
                consent.reapplyActorBound()
            }
        }
        val deliver = Runnable {
            if (!privacy.callbackOwnerLive()) return@Runnable
            val latest = gateway.snapshot()
            if (snapshot.generation != latest.generation || snapshot.session !== latest.session ||
                snapshot.restoredFirstRunSnapshot != latest.restoredFirstRunSnapshot ||
                snapshot.storageBlocked != latest.storageBlocked || snapshot.deletionRecoveryOnly != latest.deletionRecoveryOnly
            ) return@Runnable
            onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)
            gateway.retryPendingRenewal()
        }
        runtime.dispatchObserverInlineIfMain(deliver)
    }

    fun completePostLoginDeviceCheckPassEffects(completed: PostLoginDeviceCheckSnapshot): PostLoginDeviceCheckPassContinuation {
        val state = completed.state
        val currentRestrictions = device.disabledFeatureText()
        val hasCurrentRestrictions = device.hasRestrictions()
        val advancesFirstRun =
            firstRun.snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
                firstRun.snapshot.stage == FirstRunOnboardingStage.DEVICE_CHECK
        if (advancesFirstRun) {
            device.clearLiveStatus()
        } else {
            device.announceProgress(
                if (state == PostLoginDeviceCheckState.FULL && !hasCurrentRestrictions) {
                    "기기 점검을 모두 통과했습니다."
                } else if (state == PostLoginDeviceCheckState.FULL) {
                    "기기 사양 점검을 통과했습니다. 현재 권한으로 제한되는 기능: " +
                        currentRestrictions
                } else {
                    "기기 점검을 제한 모드로 통과했습니다. 제한 기능: " +
                        currentRestrictions
                },
            )
        }
        if (raw.initialized()) {
            val process = gateway.snapshot()
            val currentSession = process.session
            if (
                currentSession != null &&
                !process.storageBlocked &&
                !process.deletionRecoveryOnly
            ) {
                val deviceCheck = device.snapshot()
                raw.resetEnrollmentInTask(currentSession, process.generation, deviceCheck)
                runtime.scheduleTransfers()
            }
        }
        if (advancesFirstRun) {
            val continuation = firstRun.completeEmailDeviceCheck(
                announcement = if (
                    state == PostLoginDeviceCheckState.FULL &&
                    !hasCurrentRestrictions
                ) {
                    "기기 점검을 모두 통과했습니다. 휴대폰 사용 자세에 동의한 뒤 안전교육을 완료하세요."
                } else if (state == PostLoginDeviceCheckState.FULL) {
                    "기기 사양 점검을 통과했습니다. 현재 권한으로 제한되는 기능은 " +
                        "${currentRestrictions}입니다. 안전교육을 완료하세요."
                } else {
                    "기기 점검을 제한 모드로 통과했습니다. 제한 기능은 " +
                        "${currentRestrictions}입니다. 안전교육을 완료하세요."
                },
            ) { actorId, completedSnapshot ->
                gateway.publishFirstRun(gateway.snapshot().generation, actorId, completedSnapshot)
            }
            if (continuation != PostLoginDeviceCheckPassContinuation.COMPLETED) return continuation
        } else if (
            firstRun.snapshot.stage == FirstRunOnboardingStage.FP004_TRAINING &&
            profiles.policy.snapshot().trainingComplete
        ) {
            firstRun.snapshot.verifiedActorBinding?.value?.let {
                firstRun.completeFirstRunFp004TrainingIfReady(it)
            }
        }
        return PostLoginDeviceCheckPassContinuation.COMPLETED
    }
}
