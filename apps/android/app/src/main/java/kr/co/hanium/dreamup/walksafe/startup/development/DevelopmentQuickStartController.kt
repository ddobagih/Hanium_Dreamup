package kr.co.hanium.dreamup.walksafe.startup.development

import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*

/** Development-only orchestration. Actual verified owners and SDK observations remain external. */
internal class DevelopmentQuickStartController(
    private val platform: DevelopmentQuickStartPlatform,
    private val account: DevelopmentQuickStartAccount,
    private val device: DevelopmentQuickStartDevice,
    private val consent: DevelopmentQuickStartConsent,
    private val walk: DevelopmentQuickStartWalk,
) {
    private var developmentQuickStartRequested = false
    private var developmentQuickStartDeadlineMs = 0L
    private var developmentQuickStartLoginAttempted = false
    private var developmentQuickStartAuthenticatedActorId: String? = null
    private var developmentQuickStartPermissionsRequested = false
    private var developmentQuickStartConsentRequested = false
    private val developmentQuickStartRunnable = Runnable { advanceDevelopmentQuickStart() }
    val requested: Boolean get() = developmentQuickStartRequested

    fun noteAuthenticatedActor(actorId: String) { developmentQuickStartAuthenticatedActorId = actorId }
    fun showAccountStatusIfEnabled(message: String) {
        if (platform.enabled) platform.showStatus(message)
    }

    fun developmentQuickStartSessionOrNull(): GatewayFieldSession? {
        if (!platform.enabled) return null
        val process = account.processSnapshot()
        val session = process.session ?: return null
        return session.takeIf {
            !process.storageBlocked && !process.deletionRecoveryOnly &&
                !account.deletionBlocksProcessing() &&
                it.sessionScope == GatewaySessionScope.GENERAL &&
                it.actorId == developmentQuickStartAuthenticatedActorId &&
                it.verificationState == GatewaySessionVerificationState.VERIFIED &&
                it.isUsableFor(it.actorId) &&
                it.gatewayBaseUrl == account.configuredOrigin()
        }
    }

    fun loginDevelopmentQuickStartAccount() {
        if (!platform.enabled || !platform.foreground ||
            account.requestInFlight()
        ) return
        if (developmentQuickStartSessionOrNull() != null) return
        developmentQuickStartLoginAttempted = true
        if (!account.credentialsAvailable()) {
            account.showAccountMessage("계정 화면에 이메일과 비밀번호를 입력한 뒤 빠른 시작을 다시 눌러 주세요.")
            return
        }
        developmentQuickStartAuthenticatedActorId = null
        if (account.hasGatewaySession()) account.clearGatewaySessionWithoutRemoteLogout()
        account.prepareInitialEmailAccount()
        platform.showStatus("로컬 서버의 실제 테스트 계정에 연결하고 있습니다.")
        account.loginConfiguredAccount()
    }

    fun startDevelopmentQuickStart() {
        if (!platform.enabled || !platform.foreground) return
        if (walk.snapshot().state == WalkSessionState.PAUSED) {
            developmentQuickStartRequested = false
            platform.remove(developmentQuickStartRunnable)
            walk.handleExistingConfirmationAction()
            return
        }
        if (walk.runtimeActive()) {
            platform.showStatus("개발 테스트가 실행 중입니다. 목적지·음성·신고 기능을 사용할 수 있습니다.")
            return
        }
        if (walk.gatewayStartPending()) {
            platform.showStatus("서버의 테스트 보행 시작 응답을 기다리고 있습니다.")
            return
        }
        developmentQuickStartRequested = true
        developmentQuickStartDeadlineMs = platform.nowMs() + 45_000L
        developmentQuickStartLoginAttempted = false
        developmentQuickStartPermissionsRequested = false
        developmentQuickStartConsentRequested = false
        advanceDevelopmentQuickStart()
    }

    fun advanceDevelopmentQuickStart() {
        if (!platform.enabled || !developmentQuickStartRequested ||
            platform.destroyed
        ) return
        fun finish(message: String) {
            developmentQuickStartRequested = false
            platform.remove(developmentQuickStartRunnable)
            platform.showStatus(message)
            platform.refreshFirstRunUi()
        }
        fun awaitStep(message: String) {
            platform.showStatus(message)
            platform.remove(developmentQuickStartRunnable)
            platform.postDelayed(developmentQuickStartRunnable, 500L)
            platform.refreshFirstRunUi()
        }
        if (platform.nowMs() >= developmentQuickStartDeadlineMs) {
            finish("테스트 준비 시간 초과. ${platform.statusText()} 다시 시도하세요.")
            return
        }
        if (!platform.foreground) {
            awaitStep("권한 확인 또는 앱 복귀를 기다리고 있습니다.")
            return
        }
        val session = developmentQuickStartSessionOrNull()
        if (session == null) {
            if (!developmentQuickStartLoginAttempted && !account.requestInFlight()) {
                loginDevelopmentQuickStartAccount()
            }
            if (!account.requestInFlight()) {
                finish(account.accountNotice() ?: "테스트 계정 연결 실패. 로컬 서버 연결을 확인하고 다시 시도하세요.")
            } else {
                awaitStep("로컬 서버의 실제 테스트 계정 로그인을 확인하고 있습니다.")
            }
            return
        }
        account.bindAuthenticatedActor(session.actorId)
        val missingPermissions = device.missingPermissions()
        if (missingPermissions.isNotEmpty()) {
            if (!developmentQuickStartPermissionsRequested) {
                developmentQuickStartPermissionsRequested = true
                device.requestPermissionsWithOwnedLease(missingPermissions)
                awaitStep("실제 카메라·위치·마이크·보행 센서 권한을 허용하세요.")
            } else if (device.permissionRequestInFlight()) {
                awaitStep("Android 권한 선택을 기다리고 있습니다.")
            } else {
                finish("필요한 권한이 없습니다: ${missingPermissions.joinToString { device.permissionLabel(it) }}. 권한을 허용한 뒤 다시 시도하세요.")
            }
            return
        }
        device.ensureResourceMonitoring()
        device.ensureCapabilityProbeStarted()
        val currentConsent = consent.currentConfirmation()
        if (currentConsent?.selections?.rawSourceCollection != true ||
            currentConsent?.selections?.automaticReporting != true
        ) {
            if (consent.requestInFlight()) {
                awaitStep("실제 테스트 서버의 사진·위치·자동 신고 동의를 확인하고 있습니다.")
            } else if (currentConsent == null && !consent.bootstrapReady()) {
                consent.refreshFromServer()
                awaitStep("테스트 서버에서 현재 동의 상태를 가져오고 있습니다.")
            } else if (!developmentQuickStartConsentRequested) {
                developmentQuickStartConsentRequested = true
                consent.selectDevelopmentDraft(currentConsent)
                consent.persistDraftWithoutAnnouncement()
                awaitStep("사진·위치·자동 신고 테스트 설정을 서버에 저장하고 있습니다.")
            } else {
                finish("테스트 서버의 신고 동의 확인을 완료하지 못했습니다. 연결 상태를 확인하고 다시 시도하세요.")
            }
            return
        }
        var currentWalk = walk.snapshot()
        if (currentWalk.state in setOf(WalkSessionState.SAFE_STOP, WalkSessionState.ENDED)) {
            walk.startFreshWalk("development_quick_start")
            currentWalk = walk.snapshot()
        }
        if (currentWalk.state == WalkSessionState.PAUSED) {
            finish("보행이 일시정지되었습니다. 화면의 기존 재점검·보행 재개 버튼으로 계속하세요.")
            return
        }
        if (currentWalk.state == WalkSessionState.ACTIVE) {
            finish("개발 기능 테스트 실행 중입니다.")
            return
        }
        val decision = walk.resolveCapability()
        walk.recordCapability(decision)
        val readiness = walk.captureStartReadiness(decision)
        if (!decision.mayConfirmAndStart || !readiness.isReady) {
            awaitStep("실제 실행 준비: ${readiness.blockingReasons.joinToString().ifBlank { decision.noticeKo }}")
            return
        }
        walk.transition(WalkSessionEvent.InitialCheckCompleted(readiness))
        val token = walk.snapshot().confirmationToken
        if (token == null) {
            finish("테스트 보행 시작 상태를 만들지 못했습니다. 다시 시도하세요.")
            return
        }
        walk.confirmCapabilityForGatewayStart(decision)
        developmentQuickStartRequested = false
        platform.showStatus("실제 서버 보행을 시작합니다. 거리·위험·신고 결과는 실제 카메라와 센서로 확인합니다.")
        walk.requestGatewayStart(token)
        platform.refreshFirstRunUi()
    }
}
