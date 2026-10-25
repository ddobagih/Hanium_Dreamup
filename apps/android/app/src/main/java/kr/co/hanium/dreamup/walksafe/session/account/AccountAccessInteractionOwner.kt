package kr.co.hanium.dreamup.walksafe.session.account

import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckBinding
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingFlow
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.ui.AccountAccessPresenter

/** Coordinates account entry and logout using the existing account/session/progress owners. */
internal class AccountAccessInteractionOwner(
    private val gateway: Gateway,
    private val onboarding: Onboarding,
    private val identity: Identity,
    private val admission: Admission,
    private val runtime: Runtime,
    private val screen: Screen,
    private val enrollment: Enrollment,
    private val ui: Ui,
    private val refreshConsentSpeechContext: () -> Unit,
    private val stopConsentSpeechOutsideStage: (FirstRunOnboardingSnapshot) -> Unit,
) {
    interface Gateway {
        fun snapshot(): GatewaySessionProcessSnapshot
        fun backendRestorePending(): Boolean
        fun currentFieldSession(): GatewayFieldSession?
        fun clear(logoutRemote: Boolean, expectedSession: GatewayFieldSession?, expectedProcessGeneration: Long? = null): Long?
    }
    interface Onboarding {
        val snapshot: FirstRunOnboardingSnapshot
        fun restartEmailAccount()
        fun currentDeviceBinding(): PostLoginDeviceCheckBinding?
        fun resetPriorityProfile(clearCompletedRecord: Boolean)
    }
    interface Identity {
        fun clearReporterActor()
        fun invalidateSpeechStorageBinding()
        fun explicitLogout()
        fun authenticationExpired()
        fun forgetStoredReporterActor()
    }
    interface Admission {
        fun cancelPrewalkPreparation()
        fun deletionRecoveryRequired(): Boolean
        fun fenceAccountDeletionRuntime()
        fun purgeAndRotatePositionScope(): Boolean
    }
    interface Runtime {
        fun walkActive(): Boolean
        fun invalidateOfficialEnvironment(reason: String)
        fun invalidatePhoneMounting(reason: String)
        fun cancelPriorityTraining()
        fun enterWalkSafetyStop(reason: String)
    }
    interface Screen {
        fun controlsPublished(): Boolean
        var notice: String?
        fun resetSignupPresentation()
        fun clearLegacyLoginInputIfInitialized()
        fun clearPublishedAccountInputs()
        fun renderAccountEntry()
        fun renderAccess(facts: AccountAccessPresenter.AccessFacts)
    }
    interface Enrollment {
        fun storageBlocked(): Boolean
        fun hasPartial(): Boolean
    }
    interface Ui {
        fun showAccountMessage(message: String)
        fun navigationStatus(message: String)
        fun speakInteraction(message: String)
        fun refreshLoginButton()
        fun refreshBackendAuthButton()
        fun refreshReportPrivacy()
        fun refreshPriorityOnboarding()
        fun refreshFirstRun()
        fun refreshStartupIfInitialized()
    }

    fun onAccountLogoutClicked() {
        admission.cancelPrewalkPreparation()
        if (admission.deletionRecoveryRequired()) {
            admission.fenceAccountDeletionRuntime()
            ui.navigationStatus("login=blocked account_deletion_recovery")
            ui.speakInteraction("삭제 확인이 보존된 동안에는 계정 신원을 지울 수 없습니다. 삭제 요청 준비를 먼저 완료하세요.")
            return
        }
        if (!admission.purgeAndRotatePositionScope()) {
            ui.navigationStatus("login=blocked position_field_purge_incomplete")
            ui.speakInteraction("위치 평가 기록 삭제가 완료되지 않아 로그아웃과 계정 전환을 중단했습니다.")
            return
        }
        val walkWasActive = runtime.walkActive()
        runtime.invalidateOfficialEnvironment("priority_user_account_logged_out")
        runtime.invalidatePhoneMounting("priority_user_account_logged_out")
        runtime.cancelPriorityTraining()
        val currentSession = gateway.currentFieldSession()
        val clearStarted = gateway.clear(
            logoutRemote = currentSession != null || gateway.backendRestorePending(),
            expectedSession = currentSession,
        )
        if (clearStarted == null) {
            ui.showAccountMessage("로그인 확인이 진행 중입니다. 완료된 뒤 다시 로그아웃하세요.")
            return
        }
        identity.clearReporterActor()
        onboarding.resetPriorityProfile(clearCompletedRecord = true)
        identity.invalidateSpeechStorageBinding()
        identity.explicitLogout()
        identity.forgetStoredReporterActor()
        screen.clearLegacyLoginInputIfInitialized()
        if (walkWasActive) runtime.enterWalkSafetyStop("priority_user_account_logged_out")
        onboarding.restartEmailAccount()
        screen.notice = "로그아웃했습니다. 이메일과 비밀번호를 다시 입력해 로그인하세요."
        screen.resetSignupPresentation()
        screen.clearPublishedAccountInputs()
        ui.refreshLoginButton()
        ui.refreshBackendAuthButton()
        ui.refreshReportPrivacy()
        ui.refreshPriorityOnboarding()
        ui.refreshFirstRun()
        ui.navigationStatus("login=logged_out independent_preferences_retained")
        ui.speakInteraction("로그아웃했습니다. 운영체제 권한과 신고 동의, 통신 설정은 그대로 유지됩니다.")
        ui.refreshStartupIfInitialized()
    }

    fun onAccountSessionButtonClicked() {
        val process = gateway.snapshot()
        if (process.deletionRecoveryOnly || process.session?.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY) {
            ui.showAccountMessage("계정 삭제 복구 전용 로그인이 진행 중입니다. 계정 삭제 절차에서 계속하세요.")
            return
        }
        if (process.storageBlocked) {
            ui.showAccountMessage("로그인 저장소가 안전 차단 상태입니다. 재로그인 전에 앱 지원 담당자에게 문의하세요.")
            return
        }
        if (gateway.backendRestorePending()) {
            onAccountLogoutClicked()
            return
        }
        val preservesAdvancedProgress =
            onboarding.snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
                onboarding.snapshot.verifiedActorBinding != null &&
                onboarding.currentDeviceBinding() == null
        if (!preservesAdvancedProgress) {
            onAccountLogoutClicked()
            return
        }
        val staleSession = process.session
        if (staleSession == null) {
            screen.notice = "로그인 세션이 만료되었습니다. 같은 이메일과 비밀번호로 다시 로그인하세요."
            ui.refreshFirstRun()
            return
        }
        val clearStarted = gateway.clear(
            logoutRemote = true,
            expectedSession = staleSession,
            expectedProcessGeneration = process.generation,
        )
        if (clearStarted == null) {
            ui.showAccountMessage("이전 로그인 상태를 아직 정리할 수 없습니다. 진행 중인 계정 작업을 마친 뒤 다시 시도하세요.")
            return
        }
        identity.clearReporterActor()
        onboarding.resetPriorityProfile(clearCompletedRecord = false)
        identity.invalidateSpeechStorageBinding()
        identity.authenticationExpired()
        screen.notice = "온보딩 진도는 유지했습니다. 같은 이메일과 비밀번호로 다시 로그인하세요."
        ui.refreshFirstRun()
        ui.refreshStartupIfInitialized()
    }

    fun updateEmailAccountAccessUi(snapshot: FirstRunOnboardingSnapshot) {
        if (!screen.controlsPublished()) return
        refreshConsentSpeechContext()
        stopConsentSpeechOutsideStage(snapshot)
        screen.renderAccountEntry()
        val emailFlow = snapshot.flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4
        val accountStage = snapshot.stage in setOf(
            FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT,
            FirstRunOnboardingStage.ACCOUNT_CREATED,
            FirstRunOnboardingStage.VERIFIED_LOGIN,
        )
        val expectedActorId = snapshot.verifiedActorBinding?.value
        val liveBinding = onboarding.currentDeviceBinding()
        val authenticated = emailFlow && expectedActorId != null && liveBinding?.actorId == expectedActorId && !accountStage
        val reauthenticationRequired = emailFlow && expectedActorId != null && !accountStage && !authenticated
        val process = gateway.snapshot()
        val deletionRecoveryBlocksReauthentication = reauthenticationRequired &&
            (process.deletionRecoveryOnly || process.session?.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY)
        val storageBlocksReauthentication = reauthenticationRequired && process.storageBlocked
        val staleGeneralSession = reauthenticationRequired && process.session != null &&
            !deletionRecoveryBlocksReauthentication && !storageBlocksReauthentication
        screen.renderAccess(AccountAccessPresenter.AccessFacts(
            emailFlow = emailFlow,
            accountStage = accountStage,
            authenticated = authenticated,
            reauthenticationRequired = reauthenticationRequired,
            deletionRecoveryBlocksReauthentication = deletionRecoveryBlocksReauthentication,
            storageBlocksReauthentication = storageBlocksReauthentication,
            staleGeneralSession = staleGeneralSession,
            backendDeviceRestorePending = gateway.backendRestorePending(),
            processStorageBlocked = process.storageBlocked,
            processDeletionRecoveryOnly = process.deletionRecoveryOnly,
            processRequestInFlight = process.inFlightOperationId != null,
            creating = snapshot.stage == FirstRunOnboardingStage.ACCOUNT_CREATED,
            verifiedLogin = snapshot.stage == FirstRunOnboardingStage.VERIFIED_LOGIN,
            otpEnrollmentStage = snapshot.stage == FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT,
            isComplete = snapshot.isComplete,
            emailEnrollmentStorageBlocked = enrollment.storageBlocked(),
            hasEnrollmentPartial = enrollment.hasPartial(),
        ))
    }
}
