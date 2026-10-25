package kr.co.hanium.dreamup.walksafe.ui

import android.view.View
import android.widget.Button
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import kr.co.hanium.dreamup.walksafe.session.DeletionInventoryItem
import kr.co.hanium.dreamup.walksafe.session.DeletionItemState
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import kr.co.hanium.dreamup.walksafe.session.PurposeConsentSyncState

/** Displays captured consent/deletion facts; it never mutates consent or deletion authority. */
internal class SettingsPrivacyPresenter(
    private val settings: SettingsPrivacyPanel,
    private val onboarding: OnboardingReadinessPanel,
) {
    data class ConsentFacts(val state: PurposeConsentSyncState, val requestInFlight: Boolean)

    data class IntegratedConsentFacts(
        val states: Map<IntegratedConsentItem, PurposeConsentSyncState>,
        val selected: Map<IntegratedConsentItem, Boolean>,
        val requestInFlight: Boolean,
    )

    data class DeletionItemFacts(
        val state: DeletionItemState,
        val dueAt: String,
        val reasonCode: String?,
        val nextRetryAt: String?,
        val contactUrl: String?,
    )

    data class DeletionFacts(
        val phase: AccountDeletionPhase,
        val failClosedReason: String?,
        val durableConfirmationRecovery: Boolean,
        val rev0ReauthenticationRequired: Boolean,
        val durableRecoverySessionReady: Boolean,
        val retryNeedsPasswordLogin: Boolean,
        val terminalCleanupComplete: Boolean,
        val requestInFlight: Boolean,
        val gatewaySessionPresent: Boolean,
        val journalPresent: Boolean,
        val items: Map<DeletionInventoryItem, DeletionItemFacts>,
        val deviceVerification: kr.co.hanium.dreamup.walksafe.network.DeviceDeletionVerification? = null,
        val installationKeyPreparation: kr.co.hanium.dreamup.walksafe.session.deletion.runtime.AccountDeletionInstallationKeyPreparationState? = null,
        val passwordRecoveryAvailable: Boolean = false,
        val passwordRecoveryInFlight: Boolean = false,
    )

    data class ButtonPresentation(
        val text: String,
        val enabled: Boolean,
        val contentDescription: String? = null,
        val visible: Boolean = true,
    )

    data class DeletionPresentation(
        val status: String,
        val request: ButtonPresentation,
        val confirm: ButtonPresentation,
        val cancelVisible: Boolean,
        val cancelEnabled: Boolean,
        val refreshVisible: Boolean,
        val refreshEnabled: Boolean,
        val items: Map<DeletionInventoryItem, String>,
        val passwordLogin: ButtonPresentation,
    )

    private lateinit var publishedMobilePreference: SettingsPrivacyPanel
    private lateinit var publishedGatewayInput: SettingsPrivacyPanel
    private lateinit var publishedGatewayAction: SettingsPrivacyPanel
    private lateinit var publishedHomeAction: SettingsPrivacyPanel

    fun publishMobilePreferenceTarget() { publishedMobilePreference = settings }
    fun publishGatewayInputTarget() { publishedGatewayInput = settings }
    fun publishGatewayActionTarget() { publishedGatewayAction = settings }
    fun publishHomeActionTarget() { publishedHomeAction = settings }
    fun mobilePreferenceTarget(): android.widget.Button = publishedMobilePreference.mobileNetworkPreferenceButton
    fun gatewayInputTarget(): android.widget.EditText = publishedGatewayInput.backendFieldTokenInput
    fun gatewayActionTarget(): android.widget.Button = publishedGatewayAction.backendAuthApplyButton
    fun homeActionTarget(): android.widget.Button = publishedHomeAction.nativeSettingsHomeButton
    fun deletionRecoveryEmailTarget(): android.widget.EditText = settings.deletionRecoveryEmailInput
    fun deletionRecoveryPasswordTarget(): android.widget.EditText = settings.deletionRecoveryPasswordInput

    fun showDeletionRecoveryMessage(message: String) {
        settings.deletionRecoveryNoticeText.apply {
            text = message
            contentDescription = message
            visibility = View.VISIBLE
        }
    }

    fun renderGatewayControls(surfaceVisible: Boolean, requestInFlight: Boolean,
        recoverySessionReady: Boolean, recoverySessionReserved: Boolean, recoveryOnly: Boolean,
        hasGatewaySession: () -> Boolean) {
        val token = gatewayInputTarget()
        val action = gatewayActionTarget()
        GatewayControlsRenderer.render(object : GatewayControlsRenderer.Target {
            override fun showToken(visible: Boolean) { token.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE }
            override fun showAction(visible: Boolean) { action.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE }
            override fun clearToken() { token.text?.clear() }
            override fun setCaption(message: String) { action.text = message }
            override fun setActionEnabled(enabled: Boolean) { action.isEnabled = enabled }
            override fun actionEnabled(): Boolean = action.isEnabled
            override fun setTokenEnabled(enabled: Boolean) { token.isEnabled = enabled }
        }, surfaceVisible, requestInFlight, recoverySessionReady, recoverySessionReserved, recoveryOnly, hasGatewaySession)
    }

    fun renderConsentButton(item: IntegratedConsentItem, facts: ConsentFacts) {
        val button = when (item) {
            IntegratedConsentItem.RAW_SOURCE_COLLECTION -> settings.reportPrivacyConsentButton
            IntegratedConsentItem.AUTOMATIC_REPORTING -> settings.automaticReportConsentButton
            IntegratedConsentItem.MOBILE_NETWORK_TRANSFER -> settings.mobileNetworkPreferenceButton
            IntegratedConsentItem.TRAINING_REUSE -> settings.trainingReuseConsentButton
        }
        val presentation = consentButtonPresentation(item, facts)
        button.text = presentation.text
        // Training reuse did not replace its content description in the existing flow.
        presentation.contentDescription?.let { button.contentDescription = it }
        button.isEnabled = presentation.enabled
    }

    fun renderPrivacySummary(states: Map<IntegratedConsentItem, PurposeConsentSyncState>) {
        val message = privacySummary(states)
        settings.privacyConsentStatusText.text = message
        settings.privacyConsentStatusText.contentDescription = message
    }

    fun renderConsentItems(facts: IntegratedConsentFacts) {
        IntegratedConsentItem.entries.forEach { item ->
            val presentation = consentItemPresentation(item, facts)
            val button = onboarding.firstRunIntegratedConsentButtons.getValue(item)
            button.text = presentation.text
            button.contentDescription = presentation.contentDescription
            button.isEnabled = presentation.enabled
        }
    }

    fun renderConsentSave(requestInFlight: Boolean, stage: FirstRunOnboardingStage) {
        val presentation = consentSavePresentation(requestInFlight, stage)
        onboarding.firstRunIntegratedConsentSaveButton.apply {
            text = presentation.text
            contentDescription = presentation.contentDescription
            isEnabled = presentation.enabled
        }
    }

    fun renderAccountDeletion(facts: DeletionFacts) {
        val presentation = deletionPresentation(facts)
        settings.deletionPasswordRecoveryControls.visibility = if (presentation.passwordLogin.visible) View.VISIBLE else View.GONE
        settings.deletionRecoveryPasswordButton.render(presentation.passwordLogin)
        settings.deletionRecoveryEmailInput.isEnabled = presentation.passwordLogin.enabled
        settings.deletionRecoveryPasswordInput.isEnabled = presentation.passwordLogin.enabled
        if (!presentation.passwordLogin.visible) {
            settings.deletionRecoveryNoticeText.text = ""
            settings.deletionRecoveryNoticeText.contentDescription = null
            settings.deletionRecoveryNoticeText.visibility = View.GONE
            settings.deletionRecoveryEmailInput.text?.clear()
            settings.deletionRecoveryPasswordInput.text?.clear()
        }
        settings.accountDeletionStatusText.apply {
            text = presentation.status + (when (facts.installationKeyPreparation) {
                kr.co.hanium.dreamup.walksafe.session.deletion.runtime.AccountDeletionInstallationKeyPreparationState.REGISTERED -> "\n기기 삭제 확인 준비: 완료."
                kr.co.hanium.dreamup.walksafe.session.deletion.runtime.AccountDeletionInstallationKeyPreparationState.UNCONFIRMED -> "\n기기 삭제 확인 준비: 결과 미확인. 계정 삭제 처리와 별도로 기기 확인이 필요합니다."
                kr.co.hanium.dreamup.walksafe.session.deletion.runtime.AccountDeletionInstallationKeyPreparationState.UNAVAILABLE -> "\n기기 삭제 확인 준비: 확인 정보 없음. 계정 삭제 요청은 제한하지 않으며 기기 확인 결과는 따로 표시합니다."
                null -> ""
            }) + (facts.deviceVerification?.let { view ->
                "\n기기 삭제 확인: 대상 ${view.targetCount}개, 삭제 ${view.deletedCount}개, 이미 없음 ${view.notFoundCount}개, " +
                    "실패 ${view.failedCount}개, 확인 대기 ${view.pendingCount}개, 확인 불가 ${view.unverifiableCount}개." +
                    if (view.unverifiableCount > 0L) "\n서버 처리와 별개로 기기 삭제를 확인하지 못했습니다. 해당 기기가 다시 연결되면 삭제를 재확인하거나 그 기기에서 앱을 제거해 주세요." else ""
            } ?: "")
            contentDescription = text
        }
        settings.accountDeletionRequestButton.render(presentation.request)
        settings.accountDeletionConfirmButton.apply {
            text = presentation.confirm.text
            visibility = if (presentation.confirm.visible) View.VISIBLE else View.GONE
            isEnabled = presentation.confirm.enabled
        }
        settings.accountDeletionCancelButton.apply {
            visibility = if (presentation.cancelVisible) View.VISIBLE else View.GONE
            isEnabled = presentation.cancelEnabled
        }
        settings.accountDeletionRefreshButton.apply {
            visibility = if (presentation.refreshVisible) View.VISIBLE else View.GONE
            isEnabled = presentation.refreshEnabled
        }
        DeletionInventoryItem.entries.forEach { item ->
            val view = settings.accountDeletionItemTexts.getValue(item)
            val details = presentation.items[item]
            if (details == null) {
                view.visibility = View.GONE
            } else {
                view.text = details
                view.contentDescription = details
                view.visibility = View.VISIBLE
            }
        }
    }

    private fun Button.render(presentation: ButtonPresentation) {
        text = presentation.text
        contentDescription = presentation.contentDescription
        isEnabled = presentation.enabled
    }

    companion object {
        private fun editable(state: PurposeConsentSyncState, requestInFlight: Boolean): Boolean =
            !requestInFlight && state !in setOf(
                PurposeConsentSyncState.GRANT_PENDING,
                PurposeConsentSyncState.WITHDRAWAL_PENDING,
                PurposeConsentSyncState.WITHDRAWAL_RETRY,
                PurposeConsentSyncState.FAIL_CLOSED,
            )

        fun consentButtonPresentation(item: IntegratedConsentItem, facts: ConsentFacts): ButtonPresentation {
            val state = facts.state
            val text = when (item) {
                IntegratedConsentItem.RAW_SOURCE_COLLECTION -> when (state) {
                    PurposeConsentSyncState.CONFIRMED_GRANTED -> "진단 메타데이터 수집 동의 켜짐 · 끄기"
                    PurposeConsentSyncState.GRANT_PENDING -> "진단 메타데이터 수집 동의 확인 중 (아직 활성 아님)"
                    PurposeConsentSyncState.WITHDRAWAL_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_RETRY -> "진단 메타데이터 수집 동의 철회 확인 중"
                    PurposeConsentSyncState.FAIL_CLOSED -> "진단 메타데이터 수집 동의 변경 불가"
                    PurposeConsentSyncState.UNCONFIRMED,
                    PurposeConsentSyncState.CONFIRMED_DENIED -> "진단 메타데이터 수집 동의 꺼짐 · 켜기"
                }
                IntegratedConsentItem.AUTOMATIC_REPORTING -> when (state) {
                    PurposeConsentSyncState.CONFIRMED_GRANTED -> "자동 신고 동의 켜짐 · 끄기"
                    PurposeConsentSyncState.GRANT_PENDING -> "자동 신고 동의 확인 중 (아직 활성 아님)"
                    PurposeConsentSyncState.WITHDRAWAL_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_RETRY -> "자동 신고 동의 철회 확인 중"
                    PurposeConsentSyncState.FAIL_CLOSED -> "자동 신고 동의 변경 불가"
                    PurposeConsentSyncState.UNCONFIRMED,
                    PurposeConsentSyncState.CONFIRMED_DENIED -> "자동 신고 동의 꺼짐 · 켜기"
                }
                IntegratedConsentItem.MOBILE_NETWORK_TRANSFER -> when (state) {
                    PurposeConsentSyncState.CONFIRMED_GRANTED -> "모바일 데이터 사용: 허용 · Wi-Fi와 이동통신"
                    PurposeConsentSyncState.GRANT_PENDING -> "모바일 데이터 사용: 허용 확인 중 (아직 활성 아님) · 현재 Wi-Fi만"
                    PurposeConsentSyncState.WITHDRAWAL_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_RETRY -> "모바일 데이터 사용: 끄는 중 · 현재 Wi-Fi만"
                    PurposeConsentSyncState.FAIL_CLOSED -> "서버 통신: 개인정보 처리 잠김"
                    PurposeConsentSyncState.UNCONFIRMED,
                    PurposeConsentSyncState.CONFIRMED_DENIED -> "모바일 데이터 사용: 꺼짐 · Wi-Fi만"
                }
                IntegratedConsentItem.TRAINING_REUSE -> when (state) {
                    PurposeConsentSyncState.CONFIRMED_GRANTED -> "학습 재사용 동의 철회"
                    PurposeConsentSyncState.GRANT_PENDING -> "학습 재사용 동의 서버 확인 중 (아직 활성 아님)"
                    PurposeConsentSyncState.WITHDRAWAL_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_RETRY -> "학습 재사용 동의 철회 서버 확인 중"
                    PurposeConsentSyncState.FAIL_CLOSED -> "학습 재사용 동의 처리 잠김"
                    PurposeConsentSyncState.UNCONFIRMED,
                    PurposeConsentSyncState.CONFIRMED_DENIED -> "학습 재사용 동의"
                }
            }
            val description = when (item) {
                IntegratedConsentItem.MOBILE_NETWORK_TRANSFER ->
                    "$text. 검색·경로·서버 음성·신고 통신에 적용됩니다. " +
                        "계정 로그인·인증·동의 변경·계정 삭제·외부 개인정보 권리 요청에는 모바일 데이터가 사용될 수 있습니다."
                IntegratedConsentItem.TRAINING_REUSE -> null
                else -> text
            }
            return ButtonPresentation(text, editable(state, facts.requestInFlight), description)
        }

        private fun itemLabel(item: IntegratedConsentItem): String = when (item) {
            IntegratedConsentItem.RAW_SOURCE_COLLECTION -> "진단 메타데이터 수집"
            IntegratedConsentItem.AUTOMATIC_REPORTING -> "자동신고"
            IntegratedConsentItem.MOBILE_NETWORK_TRANSFER -> "이동통신망 전송"
            IntegratedConsentItem.TRAINING_REUSE -> "학습 재사용"
        }

        fun privacySummary(states: Map<IntegratedConsentItem, PurposeConsentSyncState>): String {
            val summary = IntegratedConsentItem.entries.joinToString(separator = " · ") { item ->
                val state = when (states.getValue(item)) {
                    PurposeConsentSyncState.UNCONFIRMED -> "서버 미확인"
                    PurposeConsentSyncState.CONFIRMED_DENIED -> "거부 확인"
                    PurposeConsentSyncState.CONFIRMED_GRANTED -> "허용 확인"
                    PurposeConsentSyncState.GRANT_PENDING -> "허용 확인 중(아직 활성 아님)"
                    PurposeConsentSyncState.WITHDRAWAL_PENDING -> "철회 저장 중"
                    PurposeConsentSyncState.WITHDRAWAL_RETRY -> "철회 재시도 대기"
                    PurposeConsentSyncState.FAIL_CLOSED -> "개인정보 처리 잠김"
                }
                "${itemLabel(item)} $state"
            }
            return "개인정보 동의 서버 확인 상태\n$summary"
        }

        fun consentItemPresentation(item: IntegratedConsentItem, facts: IntegratedConsentFacts): ButtonPresentation {
            val state = facts.states.getValue(item)
            val selected = facts.selected.getValue(item)
            val syncState = when (state) {
                PurposeConsentSyncState.UNCONFIRMED -> if (selected) {
                    "허용 선택(서버 미확인·아직 활성 아님)"
                } else {
                    "거부 선택(서버 미확인)"
                }
                PurposeConsentSyncState.CONFIRMED_DENIED -> "거부(서버 확인)"
                PurposeConsentSyncState.CONFIRMED_GRANTED -> "허용(서버 확인)"
                PurposeConsentSyncState.GRANT_PENDING -> "허용 선택 저장 중(아직 활성 아님)"
                PurposeConsentSyncState.WITHDRAWAL_PENDING -> "거부 선택 저장 중(철회 확인 전)"
                PurposeConsentSyncState.WITHDRAWAL_RETRY -> "거부 선택 재시도 대기(철회 확인 전)"
                PurposeConsentSyncState.FAIL_CLOSED -> "개인정보 처리 잠김"
            }
            val text = "${itemLabel(item)}: $syncState"
            return ButtonPresentation(text, editable(state, facts.requestInFlight), text)
        }

        fun consentSavePresentation(requestInFlight: Boolean, stage: FirstRunOnboardingStage): ButtonPresentation {
            val text = if (requestInFlight) "서버 저장 확인 중" else "네 가지 선택을 서버에 저장하고 확인"
            return ButtonPresentation(text, !requestInFlight && stage == FirstRunOnboardingStage.INTEGRATED_CONSENT, text)
        }

        fun deletionPresentation(facts: DeletionFacts): DeletionPresentation {
            val phase = facts.phase
            val failClosedReason = facts.failClosedReason
            val durableConfirmationRecovery = facts.durableConfirmationRecovery
            val rev0ReauthenticationRequired = facts.rev0ReauthenticationRequired
            val durableRecoverySessionReady = facts.durableRecoverySessionReady
            val passwordVisible = facts.passwordRecoveryAvailable && !durableRecoverySessionReady &&
                (durableConfirmationRecovery || rev0ReauthenticationRequired)
            val passwordBusy = facts.passwordRecoveryInFlight || facts.requestInFlight
            val status = if (passwordVisible) {
                if (passwordBusy) "계정 확인 중입니다. 잠시 기다려 주세요."
                else "삭제 요청을 이어가려면 같은 계정의 이메일과 비밀번호를 입력하세요. 교육 기록은 유지됩니다."
            } else when (phase) {
                AccountDeletionPhase.IDLE -> "계정 삭제 요청 없음"
                AccountDeletionPhase.CONFIRM_REQUIRED -> if (durableConfirmationRecovery) {
                    if (durableRecoverySessionReady) {
                        "삭제 확인이 안전하게 보존됐습니다. 현재 로그인 계정의 삭제 요청을 다시 확인하세요."
                    } else {
                        "삭제 확인이 안전하게 보존됐습니다. 삭제 요청 복구를 위해 현장 게이트웨이 로그인이 필요합니다."
                    }
                } else {
                    "계정 삭제 범위와 기한을 확인하고 다시 확인하세요."
                }
                AccountDeletionPhase.REQUEST_PENDING -> if (rev0ReauthenticationRequired) {
                    if (durableRecoverySessionReady) {
                        "삭제 요청 복구 로그인이 확인됐습니다. 동일 요청을 다시 접수하고 있습니다."
                    } else {
                        "삭제 요청 접수 여부를 확인할 수 없어 삭제 복구 로그인이 필요합니다."
                    }
                } else {
                    "계정 삭제 요청을 서버에 확인 중입니다."
                }
                AccountDeletionPhase.IN_PROGRESS -> "계정과 개인정보 삭제를 처리 중입니다."
                AccountDeletionPhase.RETRY_WAIT -> if (rev0ReauthenticationRequired) {
                    if (durableRecoverySessionReady) {
                        "삭제 요청 복구 로그인이 확인됐습니다. 동일 요청을 다시 접수하고 있습니다."
                    } else {
                        "삭제 요청 복구를 위해 현장 게이트웨이에 다시 로그인해야 합니다."
                    }
                } else if (facts.retryNeedsPasswordLogin) {
                    "보안을 위해 비밀번호로 다시 로그인한 뒤 계정 삭제를 다시 요청하세요."
                } else {
                    "삭제 상태 확인을 재시도해야 합니다."
                }
                AccountDeletionPhase.RESTRICTED -> "법적 보존 항목이 있어 삭제 완료가 아닙니다."
                AccountDeletionPhase.PARTIAL_FAILURE -> "삭제하지 못한 항목이 있어 완료가 아닙니다."
                AccountDeletionPhase.COMPLETED -> "모든 삭제 항목의 완료 또는 자료 없음이 확인됐습니다."
                AccountDeletionPhase.FAIL_CLOSED -> if (failClosedReason == null) {
                    "삭제 상태를 신뢰할 수 없어 개인정보 기능을 잠갔습니다."
                } else {
                    "삭제 상태를 신뢰할 수 없어 개인정보 기능을 잠갔습니다. 오류: $failClosedReason"
                }
                AccountDeletionPhase.RESET_PENDING -> "새 등록을 위한 로컬 초기화를 완료하지 못했습니다."
                AccountDeletionPhase.REENROLLMENT_REQUIRED -> "새 계정과 새 동의로 등록해야 합니다."
            }
            val requestText = if (phase == AccountDeletionPhase.COMPLETED && facts.terminalCleanupComplete) {
                "삭제 완료 확인 후 새 계정 등록 시작"
            } else if (phase == AccountDeletionPhase.COMPLETED) {
                "로컬 삭제 마무리 확인 중"
            } else {
                "계정 삭제 요청"
            }
            val requestEnabled = !facts.requestInFlight && !facts.passwordRecoveryInFlight && (
                (phase == AccountDeletionPhase.COMPLETED && facts.terminalCleanupComplete) ||
                    (phase == AccountDeletionPhase.IDLE && facts.gatewaySessionPresent)
                )
            val confirming = phase == AccountDeletionPhase.CONFIRM_REQUIRED
            val confirmText = if (durableConfirmationRecovery) {
                if (durableRecoverySessionReady) "현재 로그인 계정 삭제 요청 다시 확인" else "로그인 후 삭제 요청 준비 다시 시도"
            } else {
                "계정 삭제 확인"
            }
            val refreshable = facts.journalPresent && phase !in setOf(
                AccountDeletionPhase.COMPLETED,
                AccountDeletionPhase.FAIL_CLOSED,
                AccountDeletionPhase.RESET_PENDING,
                AccountDeletionPhase.REENROLLMENT_REQUIRED,
            )
            val items = facts.items.mapValues { (item, itemFacts) -> deletionItemDescription(item, itemFacts) }
            return DeletionPresentation(
                status = status,
                request = ButtonPresentation(requestText, requestEnabled, requestText),
                confirm = ButtonPresentation(confirmText, !facts.requestInFlight && !facts.passwordRecoveryInFlight, visible = confirming),
                cancelVisible = confirming && !durableConfirmationRecovery,
                cancelEnabled = !durableConfirmationRecovery && !facts.requestInFlight && !facts.passwordRecoveryInFlight,
                refreshVisible = refreshable,
                refreshEnabled = refreshable && !facts.requestInFlight && !facts.passwordRecoveryInFlight,
                items = items,
                passwordLogin = ButtonPresentation(
                    text = if (passwordBusy) "비밀번호 확인 중" else "비밀번호 확인 후 삭제 요청 계속",
                    enabled = passwordVisible && !passwordBusy,
                    visible = passwordVisible,
                ),
            )
        }

        fun deletionItemDescription(item: DeletionInventoryItem, facts: DeletionItemFacts): String {
            val stateLabel = when (facts.state) {
                DeletionItemState.PENDING -> "삭제 요청 대기"
                DeletionItemState.IN_PROGRESS -> "삭제 중"
                DeletionItemState.EXTERNAL_PENDING -> "외부 시스템 삭제 대기"
                DeletionItemState.RETRY_WAIT -> "재시도 예정"
                DeletionItemState.FAILED -> "삭제 실패"
                DeletionItemState.LEGAL_HOLD -> "법적 보존"
                DeletionItemState.COMPLETED -> "삭제 완료"
                DeletionItemState.NOT_APPLICABLE -> "자료 없음 확인"
            }
            return buildList {
                add("${item.labelKo}: $stateLabel")
                add("기한 ${facts.dueAt}")
                facts.reasonCode?.let { add("사유 $it") }
                facts.nextRetryAt?.let { add("예정 $it") }
                facts.contactUrl?.let { add("문의 $it") }
            }.joinToString(", ")
        }
    }
}
