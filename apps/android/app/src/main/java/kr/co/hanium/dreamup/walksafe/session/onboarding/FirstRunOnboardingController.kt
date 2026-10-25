package kr.co.hanium.dreamup.walksafe.session.onboarding

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.session.*

/** Owns the actual onboarding snapshot and transition order; no platform work runs in construction. */
internal class FirstRunOnboardingController(
    private val profiles: PriorityUserProfileController,
    private val gateway: Gateway,
    private val education: Education,
    private val effects: Effects,
    private val digest: (ByteArray) -> String,
) {
    enum class AnnouncementPurpose { STATE_CHANGE, RESTORED_LOGIN }

    interface Gateway {
        fun storeInitialized(): Boolean
        fun fieldActorId(): String?
        fun clearSession()
        fun publishStageSnapshot(actorId: String, snapshot: () -> FirstRunOnboardingSnapshot)
        fun publishCompletedSnapshot(actorId: String, snapshot: FirstRunOnboardingSnapshot): Boolean
    }
    interface Education {
        fun cancelPendingFeedback()
        fun evidenceComplete(): Boolean
    }
    interface Effects {
        fun onboardingComplete(): Boolean
        fun quickStartEnabled(): Boolean
        fun quickStartActorOrNull(): String?
        fun reporterActorId(): String?
        fun setReporterActor(actorId: String)
        fun invalidateSpeechActorIfChanged(actorId: String)
        fun authenticateActor(actorId: String)
        fun reapplyActorBoundConsent()
        fun rawWalkWasActive(): Boolean
        fun deviceGatePassed(): Boolean
        fun invalidateMetric(reason: String)
        fun safetyStop(reason: String)
        fun walkRuntimeActive(): Boolean
        fun invalidateEnvironment(reason: String)
        fun invalidateMounting(reason: String)
        fun authenticatedFor(actorId: String): Boolean
        fun invalidateSpeechStorage()
        fun rememberActor(actorId: String)
        fun acknowledgeVoiceDisclosure(): Boolean
        fun startFirstRunDeviceCheckProbes()
        fun updateOnboardingUi()
        fun updatePriorityUi()
        fun refreshStartupIfInitialized()
        fun refreshConfirmedConsentAfterCompletion(actorId: String)
        fun focusStage(stage: FirstRunOnboardingStage)
        fun screenReaderActive(): Boolean
        fun foreground(): Boolean
        fun showStatus(status: String, detail: String)
        fun navigationDiagnostic(message: String)
        fun speakInteraction(message: String, purpose: AnnouncementPurpose = AnnouncementPurpose.STATE_CHANGE)
    }

    @Volatile private lateinit var state: FirstRunOnboardingSnapshot
    val initialized: Boolean get() = ::state.isInitialized
    var snapshot: FirstRunOnboardingSnapshot
        get() = state
        private set(value) { state = value }

    @Synchronized
    fun initializeEmailAccount(epoch: Long) {
        check(!initialized)
        snapshot = FirstRunOnboardingPolicy.initialEmailAccount(epoch)
    }

    @Synchronized
    fun restartEmailAccount(fallbackEpoch: () -> Long) {
        val epoch = if (initialized && snapshot.epoch < Long.MAX_VALUE) snapshot.epoch + 1L
            else fallbackEpoch().coerceAtLeast(1L)
        snapshot = FirstRunOnboardingPolicy.initialEmailAccount(epoch)
    }

    @Synchronized
    fun resetAfterConfirmedAccountDeletion(epoch: Long) {
        snapshot = FirstRunOnboardingPolicy.initialEmailAccount(epoch)
    }

    /** Disk reads retain the snapshot they started with; local intent always wins over late restore. */
    @Synchronized
    fun restoreSessionSnapshot(restored: FirstRunOnboardingSnapshot, expected: FirstRunOnboardingSnapshot? = null): Boolean {
        if (expected != null && initialized && snapshot !== expected && snapshot != restored) return false
        // A matching session may resume while keeping newer progress already held locally.
        if (effectiveSessionSnapshot(restored) !== restored) return true
        snapshot = restored
        return true
    }

    @Synchronized
    fun effectiveSessionSnapshot(restored: FirstRunOnboardingSnapshot?): FirstRunOnboardingSnapshot? {
        if (restored == null || !initialized) return restored
        val current = snapshot
        val sameActor = restored.flow == current.flow && restored.verifiedActorBinding != null &&
            restored.verifiedActorBinding == current.verifiedActorBinding
        return if (sameActor && (restored.epoch < current.epoch ||
            (restored.epoch == current.epoch && restored.revision < current.revision) ||
            (restored.epoch == current.epoch && restored.revision == current.revision && restored != current))) current else restored
    }

    @Synchronized
    fun recordEmailOtpEnrollment(receipt: FirstRunReceiptHash): Boolean {
        val transition = FirstRunOnboardingPolicy.recordEmailOtpEnrollment(snapshot, receipt)
        if (!transition.accepted) return false
        snapshot = transition.current
        return true
    }

    @Synchronized
    fun recordEmailAccountCreated(receipt: FirstRunReceiptHash, clearEnrollment: () -> Boolean): Boolean {
        val before = snapshot
        val transition = FirstRunOnboardingPolicy.recordEmailAccountCreated(before, receipt)
        if (!transition.accepted || !clearEnrollment() || snapshot !== before) return false
        snapshot = transition.current
        return true
    }

    class PreparedEmailLogin internal constructor(
        internal val owner: FirstRunOnboardingController,
        internal val before: FirstRunOnboardingSnapshot,
        val snapshot: FirstRunOnboardingSnapshot,
        val reauthenticatesExistingProgress: Boolean,
    )

    @Synchronized
    fun prepareVerifiedEmailLogin(actor: FirstRunOpaqueActorBinding, receipt: FirstRunReceiptHash,
        completedNativeProgress: FirstRunOnboardingSnapshot?): PreparedEmailLogin? {
        val before = snapshot
        val completed = completedNativeProgress?.takeIf { it.verifiedActorBinding == actor }
        val reauthenticates = completed != null || FirstRunOnboardingPolicy.mayReauthenticateVerifiedEmailActor(before, actor)
        val next = if (reauthenticates) completed?.let(::effectiveSessionSnapshot) ?: before else {
            val previousActor = before.verifiedActorBinding
            val loginStart = if (previousActor != null && previousActor != actor &&
                FirstRunOnboardingPolicy.mayReauthenticateVerifiedEmailActor(before, previousActor)) {
                if (before.epoch == Long.MAX_VALUE || before.revision == Long.MAX_VALUE) return null
                // 서버 검증된 다른 계정에는 이전 계정의 교육과 receipt를 이식하지 않는다.
                FirstRunOnboardingPolicy.initialEmailAccount(before.epoch + 1L)
            } else before
            val transition = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(loginStart, actor, receipt)
            if (!transition.accepted) return null
            transition.current
        }
        return PreparedEmailLogin(this, before, next, reauthenticates)
    }

    @Synchronized
    fun applyVerifiedEmailLogin(prepared: PreparedEmailLogin): Boolean {
        if (prepared.owner !== this) return false
        // Publishing the verified process session can synchronously restore the same snapshot.
        if (snapshot == prepared.snapshot) return true
        if (snapshot !== prepared.before) return false
        snapshot = prepared.snapshot
        return true
    }

    @Synchronized
    fun recordEmailPermissionObservation(): Boolean {
        val transition = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(snapshot, snapshot.epoch, snapshot.revision)
        if (!transition.accepted) return false
        snapshot = transition.current
        return true
    }

    @Synchronized
    fun completeEmailDeviceCheck(announcement: String,
        publish: (String, FirstRunOnboardingSnapshot) -> Boolean): kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckPassContinuation {
        val before = snapshot
        val transition = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(before, before.epoch, before.revision)
        if (!transition.accepted) return kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED
        val actor = transition.current.verifiedActorBinding?.value
        if (actor == null || !publish(actor, transition.current) ||
            (snapshot !== before && snapshot != transition.current)) {
            return kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckPassContinuation.SESSION_CHANGED
        }
        snapshot = transition.current
        onFirstRunOnboardingStateChanged(announcement, preservePostLoginDeviceCheck = true)
        return kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckPassContinuation.COMPLETED
    }

    @Synchronized
    fun completeIntegratedConsent(receipt: FirstRunReceiptHash, confirmationIsCurrent: () -> Boolean): Boolean {
        if (snapshot.stage != FirstRunOnboardingStage.INTEGRATED_CONSENT || !confirmationIsCurrent()) return false
        val before = snapshot
        val started = FirstRunOnboardingPolicy.beginAttempt(before, firstRunLocalRequest("integrated_consent"))
        val token = started.current.pendingAttempt ?: return false
        val evidence = FirstRunOnboardingEvidence.IntegratedConsent(receipt)
        val completed = FirstRunOnboardingPolicy.completeAttempt(started.current, token, evidence,
            FirstRunOnboardingEvidenceVerifier { presentedToken, presentedEvidence ->
                presentedToken === token && presentedEvidence === evidence && confirmationIsCurrent()
            })
        if (!completed.accepted || snapshot !== before) return false
        snapshot = completed.current
        onFirstRunOnboardingStateChanged("네 가지 동의 선택의 서버 저장을 확인했습니다. 가입 검증 단계로 이동합니다.")
        return true
    }

    fun requireFirstRunOnboardingComplete(
        reason: String,
        announce: Boolean = true,
    ): Boolean {
        if (effects.onboardingComplete()) return true
        val stage = if (initialized) {
            snapshot.stage.name.lowercase(Locale.US)
        } else {
            "uninitialized"
        }
        effects.navigationDiagnostic("firstRun=blocked reason=$reason stage=$stage")
        effects.showStatus(
            "첫 실행 등록 필요",
            "목적·연령·동의·본인 확인·로그인·권한·기기점검·안전교육을 순서대로 완료해야 합니다.",
        )
        if (announce && !effects.screenReaderActive()) {
            effects.speakInteraction("첫 실행 등록과 안전교육을 먼저 완료하세요.")
        }
        return false
    }

    fun firstRunLocalRequest(
        action: String,
    ): FirstRunOnboardingAttemptRequest {
        val snapshot = snapshot
        val requestIdentity = digest(
            (
                "FP010|request|$action|${snapshot.epoch}|${snapshot.revision}|" +
                    snapshot.stage.name
            ).toByteArray(),
        )
        val attemptIdentity = digest(
            (
                "FP010|attempt|$action|${snapshot.epoch}|${snapshot.revision}|" +
                    snapshot.stage.name
            ).toByteArray(),
        )
        return FirstRunOnboardingAttemptRequest.forSnapshot(
            snapshot = snapshot,
            requestId = "req_$requestIdentity",
            attemptId = "att_$attemptIdentity",
        )
    }

    fun firstRunLocalReceipt(action: String): FirstRunReceiptHash {
        val snapshot = snapshot
        return FirstRunReceiptHash.fromSha256Hex(
            digest(
                "FP010|$action|${snapshot.epoch}|${snapshot.revision}|${snapshot.stage.name}"
                    .toByteArray(),
            ),
        )
    }

    @Synchronized
    fun acknowledgeFirstRunPurposeAndSafety() {
        if (
            snapshot.stage !=
            FirstRunOnboardingStage.PURPOSE_AND_SAFETY
        ) return
        if (!effects.acknowledgeVoiceDisclosure()) {
            effects.showStatus(
                "안전 고지 저장 실패",
                "안전 고지 확인을 저장하지 못했습니다. 저장공간을 확인한 뒤 다시 시도하세요.",
            )
            return
        }
        val transition = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(
            snapshot = snapshot,
            request = firstRunLocalRequest("purpose_and_safety"),
            receiptHash = firstRunLocalReceipt("purpose_and_safety"),
        )
        if (!transition.accepted) return
        snapshot = transition.current
        onFirstRunOnboardingStateChanged(
            announcement = if (
                transition.current.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4
            ) {
                "목적과 안전 한계를 확인했습니다. 이제 권한과 기기 기능을 점검하세요."
            } else {
                "목적과 안전 한계를 확인했습니다. 연령 구간을 선택하세요."
            },
        )
    }

    @Synchronized
    fun selectFirstRunAgeBand(ageBand: FirstRunAgeBand) {
        if (
            snapshot.stage !=
            FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED
        ) return
        val transition = FirstRunOnboardingPolicy.recordAgeAndGuardianNeed(
            snapshot = snapshot,
            request = firstRunLocalRequest("age_and_guardian_need_${ageBand.name}"),
            ageBand = ageBand,
            receiptHash = firstRunLocalReceipt("age_and_guardian_need_${ageBand.name}"),
        )
        if (!transition.accepted) return
        snapshot = transition.current
        val announcement = if (
            transition.current.stage ==
            FirstRunOnboardingStage.BLOCKED_UNDER_14
        ) {
            "만 14세 미만은 가입하거나 보행 기능을 사용할 수 없습니다."
        } else {
            "연령 구간을 확인했습니다. 네 가지 동의 항목을 각각 선택하고 서버 저장을 확인하세요."
        }
        onFirstRunOnboardingStateChanged(announcement)
    }

    @Synchronized
    fun onFirstRunOnboardingStateChanged(
        announcement: String,
        preservePostLoginDeviceCheck: Boolean = false,
        announcementPurpose: AnnouncementPurpose = AnnouncementPurpose.STATE_CHANGE,
    ) {
        if (effects.quickStartEnabled()) {
            effects.quickStartActorOrNull()?.let { actorId ->
                effects.setReporterActor(actorId)
                profiles.actorId = actorId
                effects.invalidateSpeechActorIfChanged(actorId)
                effects.authenticateActor(actorId)
                effects.reapplyActorBoundConsent()
            }
            effects.updateOnboardingUi()
            effects.refreshStartupIfInitialized()
            return
        }
        val rawWalkWasActive = effects.rawWalkWasActive()
        val restoredDeviceCheckAdvanced = advanceEmailStagesWithRestoredDeviceCheck()
        if (!preservePostLoginDeviceCheck) {
            effects.invalidateMetric("first_run_stage_changed")
        }
        if (!effects.onboardingComplete()) {
            if (
                gateway.storeInitialized() &&
                snapshot.flow !=
                FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4
            ) {
                gateway.clearSession()
            }
            if (rawWalkWasActive) {
                effects.safetyStop(
                    "first_run_onboarding_incomplete",
                )
            }
        }
        bindFirstRunVerifiedActorForTraining()
        val firstRunActorId = snapshot.verifiedActorBinding?.value
        if (
            firstRunActorId != null &&
            completeFirstRunFp004TrainingIfReady(firstRunActorId, announcementPurpose)
        ) return
        if (firstRunActorId != null) {
            gateway.publishStageSnapshot(firstRunActorId) { snapshot }
        }
        effects.startFirstRunDeviceCheckProbes()
        effects.updateOnboardingUi()
        effects.refreshStartupIfInitialized()
        effects.focusStage(snapshot.stage)
        if (!effects.screenReaderActive()) {
            // Restored FP004 evidence can finish the device transition directly at COMPLETE.
            // Announce remaining education only when the final snapshot still requires it.
            val restoredDeviceCheckNeedsEducation = restoredDeviceCheckAdvanced && !snapshot.isComplete
            val effectiveAnnouncement = if (restoredDeviceCheckNeedsEducation) {
                "저장된 기기 점검 결과를 적용했습니다. 안전교육을 완료하세요."
            } else {
                announcement
            }
            effects.speakInteraction(
                effectiveAnnouncement,
                if (restoredDeviceCheckNeedsEducation) AnnouncementPurpose.STATE_CHANGE else announcementPurpose,
            )
        }
    }

    @Synchronized
    fun advanceEmailStagesWithRestoredDeviceCheck(): Boolean {
        if (
            snapshot.flow != FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 ||
            !effects.deviceGatePassed()
        ) return false
        var current = snapshot
        var advanced = false
        if (current.stage == FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION) {
            val transition = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
                snapshot = current,
                expectedEpoch = current.epoch,
                expectedRevision = current.revision,
            )
            if (!transition.accepted) return false
            current = transition.current
            advanced = true
        }
        if (current.stage == FirstRunOnboardingStage.DEVICE_CHECK) {
            val transition = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(
                snapshot = current,
                expectedEpoch = current.epoch,
                expectedRevision = current.revision,
            )
            if (!transition.accepted) return false
            current = transition.current
            advanced = true
        }
        if (advanced) snapshot = current
        return advanced
    }

    @Synchronized
    fun bindFirstRunVerifiedActorForTraining() {
        val actorId =
            snapshot.verifiedActorBinding?.value ?: return
        val actorChanged =
            effects.reporterActorId() != actorId || profiles.actorId != actorId
        if (actorChanged) {
            effects.invalidateEnvironment("first_run_actor_bound")
            effects.invalidateMounting("first_run_actor_bound")
        }
        if (gateway.fieldActorId() != actorId) {
            gateway.clearSession()
        }
        effects.setReporterActor(actorId)
        if (!effects.authenticatedFor(actorId)) {
            effects.invalidateSpeechStorage()
            effects.rememberActor(actorId)
        }
        profiles.restorePriorityUserOnboardingFromPrefs()
        val verifiedAgeBand = when (snapshot.ageBand) {
            FirstRunAgeBand.AGE_14_TO_17 -> PriorityUserAgeBand.AGE_14_TO_17
            FirstRunAgeBand.ADULT_18_PLUS -> PriorityUserAgeBand.ADULT_18_PLUS
            FirstRunAgeBand.VERIFIED_14_PLUS -> PriorityUserAgeBand.VERIFIED_14_PLUS
            FirstRunAgeBand.UNDER_14,
            null,
            -> return
        }
        val restored = profiles.policy.snapshot()
        val compatible = restored.ageBand == verifiedAgeBand ||
            (verifiedAgeBand == PriorityUserAgeBand.VERIFIED_14_PLUS &&
                restored.ageBand in setOf(PriorityUserAgeBand.AGE_14_TO_17, PriorityUserAgeBand.ADULT_18_PLUS))
        val effectiveAgeBand = if (compatible) restored.ageBand else verifiedAgeBand
        profiles.policy = PriorityUserOnboardingPolicy(
            PriorityUserOnboardingSnapshot(
                ageBand = effectiveAgeBand,
                guardianVerified =
                    effectiveAgeBand == PriorityUserAgeBand.AGE_14_TO_17 &&
                        ((compatible && restored.guardianVerified) ||
                            FirstRunOnboardingStage.GUARDIAN_APPROVAL in snapshot.completedReceiptHashes),
                phonePostureAcknowledged = compatible && restored.phonePostureAcknowledged,
                practiceNecessityReviewed = compatible && restored.practiceNecessityReviewed,
                educationAccepted = compatible && restored.educationAccepted,
                usageConditionsAcknowledged = compatible && restored.usageConditionsAcknowledged,
                appUsageReviewed = compatible && restored.appUsageReviewed,
                appUsageAccepted = compatible && restored.appUsageAccepted,
                educationReviewed = compatible && restored.educationReviewed,
                safePracticePlaceConfirmed =
                    compatible && restored.safePracticePlaceConfirmed,
                completedPractices = if (compatible) {
                    restored.completedPractices
                } else {
                    emptySet()
                },
                hazardResponseConfirmed = compatible && restored.hazardResponseConfirmed,
            ),
        )
        if (snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
            snapshot.isComplete &&
            !profiles.policy.snapshot().nativeEducationComplete
        ) {
            val restart = FirstRunOnboardingPolicy.restartFp004Training(snapshot)
            if (restart.accepted) {
                profiles.nativeCompletedRecord = null
                snapshot = restart.current
                onFirstRunOnboardingStateChanged(
                    "사전 연습을 완료해 주세요. 기존 안전 교육과 기기 점검 기록은 유지합니다.",
                    preservePostLoginDeviceCheck = true,
                )
            }
        }
    }

    @Synchronized
    fun completeFirstRunFp004TrainingIfReady(
        actorId: String,
        announcementPurpose: AnnouncementPurpose = AnnouncementPurpose.STATE_CHANGE,
    ): Boolean {
        if (
            snapshot.stage !=
            FirstRunOnboardingStage.FP004_TRAINING
        ) return false
        if (snapshot.verifiedActorBinding?.value != actorId) return false
        if (profiles.actorId != actorId) return false
        if (!effects.deviceGatePassed()) return false
        if (!education.evidenceComplete()) return false
        val before = snapshot
        val receipt = firstRunLocalReceipt(
            if (snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4) {
                "safety_and_usage_education_v2"
            } else {
                "fp004_training_complete"
            },
        )
        val started = FirstRunOnboardingPolicy.beginAttempt(
            snapshot = snapshot,
            request = firstRunLocalRequest("fp004_training_complete"),
        )
        val token = started.current.pendingAttempt ?: return false
        val evidence = FirstRunOnboardingEvidence.Fp004Training(receipt)
        val completed = FirstRunOnboardingPolicy.completeAttempt(
            snapshot = started.current,
            token = token,
            evidence = evidence,
            verifier = FirstRunOnboardingEvidenceVerifier {
                    presentedToken,
                    presentedEvidence,
                ->
                presentedToken === token &&
                    presentedEvidence === evidence &&
                    profiles.actorId == actorId &&
                    education.evidenceComplete() &&
                    effects.deviceGatePassed()
            },
        )
        if (!completed.accepted) return false
        if (completed.current.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
            !profiles.persistNativeCompletedOnboarding(completed.current)) {
            effects.showStatus("안전교육 완료 저장 실패", "완료 기록을 저장하지 못했습니다. 다시 동의해 저장을 재시도하세요.")
            return false
        }
        if (!gateway.publishCompletedSnapshot(actorId, snapshot = completed.current)) {
            effects.showStatus(
                "안전교육 완료 저장 실패",
                "로그인 세션이 변경되어 완료 상태를 적용하지 않았습니다. 로그인 상태를 확인한 뒤 다시 시도하세요.",
            )
            return false
        }
        if (snapshot !== before && snapshot != completed.current) return false
        snapshot = completed.current
        onFirstRunOnboardingStateChanged(
            "안전 교육과 사전 연습을 완료했습니다. 홈으로 이동합니다.",
            preservePostLoginDeviceCheck = true,
            announcementPurpose = announcementPurpose,
        )
        if (snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
            snapshot.isComplete && snapshot.verifiedActorBinding?.value == actorId) {
            runCatching { effects.refreshConfirmedConsentAfterCompletion(actorId) }
                .onFailure {
                    effects.navigationDiagnostic(
                        "integratedConsent=refresh_start_failed trigger=education_complete",
                    )
                }
        }
        return true
    }

    fun requireDeviceCheckForPriorityUserTraining(): Boolean {
        if (effects.deviceGatePassed()) return true
        effects.showStatus(
            "기기 점검 다시 필요",
            "안전교육과 조작 연습 전에 권한과 기기 기능 점검을 완료하세요.",
        )
        effects.speakInteraction("먼저 권한과 기기 기능 점검을 완료하세요.")
        effects.refreshStartupIfInitialized()
        return false
    }

    @Synchronized
    fun resetPriorityUserTraining() {
        profiles.nativeCompletedRecord = null
        education.cancelPendingFeedback()
        effects.invalidateEnvironment("priority_user_training_reset")
        effects.invalidateMounting("priority_user_training_reset")
        val actorId = profiles.actorId
        if (actorId == null || actorId != effects.reporterActorId()) {
            effects.speakInteraction("먼저 교육 대상 로그인 ID를 입력하고 확인하세요.")
            return
        }
        val persisted = if (profiles.beginPriorityUserProfileMutationOrFailClosed()) {
            profiles.policy.resetTraining()
            profiles.persistPriorityUserOnboardingOrFailClosed()
        } else {
            false
        }
        if (!persisted) {
            effects.showStatus(
                "교육 재실행 저장 실패",
                "안전한 재실행 상태를 저장하지 못해 현재 실행에서는 계정과 보행 기능을 열지 않습니다.",
            )
        }
        val firstRunRestart =
            FirstRunOnboardingPolicy.restartFp004Training(
                snapshot,
            )
        if (firstRunRestart.accepted) {
            snapshot = firstRunRestart.current
            onFirstRunOnboardingStateChanged(
                "안전교육을 다시 완료해야 합니다. 완료 전까지 보행 기능을 사용하지 않습니다.",
            )
        } else if (effects.walkRuntimeActive()) {
            effects.safetyStop(
                "priority_user_training_reset",
            )
            effects.showStatus(
                "교육 재실행 · 안전 중지",
                "교육과 네 가지 조작 연습을 다시 완료한 뒤 새 보행을 시작하세요.",
            )
        }
        effects.updatePriorityUi()
        if (!firstRunRestart.accepted) {
            effects.speakInteraction("안전 제한 안내부터 다시 시작합니다.")
        }
        effects.refreshStartupIfInitialized()
    }
    fun isTrainingDeliveryCurrent(generation: Long, currentGeneration: Long, actorId: String,
        policy: PriorityUserOnboardingPolicy): Boolean =
        effects.foreground() && generation == currentGeneration && actorId == effects.reporterActorId() &&
            actorId == profiles.actorId && policy === profiles.policy &&
            snapshot.stage == FirstRunOnboardingStage.FP004_TRAINING &&
            snapshot.verifiedActorBinding?.value == actorId && effects.deviceGatePassed()
}
