package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import kr.co.hanium.dreamup.walksafe.session.DeletionInventoryItem
import kr.co.hanium.dreamup.walksafe.session.DeletionItemState
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import kr.co.hanium.dreamup.walksafe.session.PurposeConsentSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPrivacyPresenterTest {
    @Test
    fun grantPendingNeverDisplaysActiveMobileTransferOrAnEnabledToggle() {
        val result = consent(IntegratedConsentItem.MOBILE_NETWORK_TRANSFER, PurposeConsentSyncState.GRANT_PENDING)
        assertEquals("모바일 데이터 사용: 허용 확인 중 (아직 활성 아님) · 현재 Wi-Fi만", result.text)
        assertFalse(result.enabled)
        assertTrue(result.contentDescription!!.contains("계정 로그인·인증·동의 변경·계정 삭제·외부 개인정보 권리 요청"))
    }

    @Test
    fun pendingWithdrawalAndFailClosedKeepAllFourConsentChangesDisabled() {
        for (state in listOf(PurposeConsentSyncState.WITHDRAWAL_PENDING, PurposeConsentSyncState.WITHDRAWAL_RETRY, PurposeConsentSyncState.FAIL_CLOSED)) {
            IntegratedConsentItem.entries.forEach { assertFalse(consent(it, state).enabled) }
        }
        assertEquals("서버 통신: 개인정보 처리 잠김", consent(IntegratedConsentItem.MOBILE_NETWORK_TRANSFER, PurposeConsentSyncState.FAIL_CLOSED).text)
    }

    @Test
    fun confirmedAndDraftChoicesRemainDistinct() {
        assertEquals("모바일 데이터 사용: 허용 · Wi-Fi와 이동통신", consent(IntegratedConsentItem.MOBILE_NETWORK_TRANSFER, PurposeConsentSyncState.CONFIRMED_GRANTED).text)
        val facts = SettingsPrivacyPresenter.IntegratedConsentFacts(
            states = IntegratedConsentItem.entries.associateWith { PurposeConsentSyncState.UNCONFIRMED },
            selected = IntegratedConsentItem.entries.associateWith { true },
            requestInFlight = false,
        )
        val result = SettingsPrivacyPresenter.consentItemPresentation(IntegratedConsentItem.MOBILE_NETWORK_TRANSFER, facts)
        assertEquals("이동통신망 전송: 허용 선택(서버 미확인·아직 활성 아님)", result.text)
        assertEquals(result.text, result.contentDescription)
        assertTrue(result.enabled)
        assertEquals("모바일 데이터 사용: 꺼짐 · Wi-Fi만", consent(IntegratedConsentItem.MOBILE_NETWORK_TRANSFER, PurposeConsentSyncState.UNCONFIRMED).text)
    }

    @Test
    fun anInFlightRequestBlocksConfirmedTogglesAndSaveOutsideTheConsentStage() {
        IntegratedConsentItem.entries.forEach {
            val result = SettingsPrivacyPresenter.consentButtonPresentation(it, SettingsPrivacyPresenter.ConsentFacts(PurposeConsentSyncState.CONFIRMED_GRANTED, true))
            assertFalse(result.enabled)
        }
        assertFalse(SettingsPrivacyPresenter.consentSavePresentation(false, FirstRunOnboardingStage.COMPLETE).enabled)
        assertFalse(SettingsPrivacyPresenter.consentSavePresentation(true, FirstRunOnboardingStage.INTEGRATED_CONSENT).enabled)
        assertTrue(SettingsPrivacyPresenter.consentSavePresentation(false, FirstRunOnboardingStage.INTEGRATED_CONSENT).enabled)
    }

    @Test
    fun trainingReuseDoesNotReplaceItsExistingAccessibilityDescription() {
        assertNull(consent(IntegratedConsentItem.TRAINING_REUSE, PurposeConsentSyncState.CONFIRMED_GRANTED).contentDescription)
    }

    @Test
    fun privacySummaryPreservesItemOrderAndConfirmedServerMeaning() {
        val states = IntegratedConsentItem.entries.associateWith { PurposeConsentSyncState.CONFIRMED_DENIED }
            .toMutableMap().apply { this[IntegratedConsentItem.RAW_SOURCE_COLLECTION] = PurposeConsentSyncState.CONFIRMED_GRANTED }
        assertEquals("개인정보 동의 서버 확인 상태\n진단 메타데이터 수집 허용 확인 · 자동신고 거부 확인 · 이동통신망 전송 거부 확인 · 학습 재사용 거부 확인", SettingsPrivacyPresenter.privacySummary(states))
    }

    @Test
    fun durableConfirmationCannotBeCancelledAndKeepsRecoveryLoginInstructions() {
        val result = SettingsPrivacyPresenter.deletionPresentation(deletion(AccountDeletionPhase.CONFIRM_REQUIRED).copy(durableConfirmationRecovery = true))
        assertTrue(result.status.contains("현장 게이트웨이 로그인이 필요합니다"))
        assertEquals("로그인 후 삭제 요청 준비 다시 시도", result.confirm.text)
        assertTrue(result.confirm.visible)
        assertFalse(result.cancelVisible)
        assertFalse(result.cancelEnabled)
        val ready = SettingsPrivacyPresenter.deletionPresentation(deletion(AccountDeletionPhase.CONFIRM_REQUIRED).copy(durableConfirmationRecovery = true, durableRecoverySessionReady = true))
        assertEquals("현재 로그인 계정 삭제 요청 다시 확인", ready.confirm.text)
    }

    @Test
    fun ordinaryConfirmationAllowsCancellationOnlyWhileNoRequestIsInFlight() {
        val facts = deletion(AccountDeletionPhase.CONFIRM_REQUIRED)
        val idle = SettingsPrivacyPresenter.deletionPresentation(facts)
        assertTrue(idle.cancelVisible)
        assertTrue(idle.cancelEnabled)
        val busy = SettingsPrivacyPresenter.deletionPresentation(facts.copy(requestInFlight = true))
        assertFalse(busy.confirm.enabled)
        assertFalse(busy.cancelEnabled)
    }

    @Test
    fun completedServerStatusDoesNotOfferNewRegistrationBeforeLocalCleanup() {
        val facts = deletion(AccountDeletionPhase.COMPLETED)
        val pending = SettingsPrivacyPresenter.deletionPresentation(facts)
        assertEquals("로컬 삭제 마무리 확인 중", pending.request.text)
        assertFalse(pending.request.enabled)
        assertFalse(pending.refreshVisible)
        val clean = SettingsPrivacyPresenter.deletionPresentation(facts.copy(terminalCleanupComplete = true))
        assertEquals("삭제 완료 확인 후 새 계정 등록 시작", clean.request.text)
        assertTrue(clean.request.enabled)
        assertFalse(SettingsPrivacyPresenter.deletionPresentation(facts.copy(terminalCleanupComplete = true, requestInFlight = true)).request.enabled)
    }

    @Test
    fun anIdleDeletionNeedsTheGatewaySessionBeforeRequesting() {
        val facts = deletion(AccountDeletionPhase.IDLE)
        assertFalse(SettingsPrivacyPresenter.deletionPresentation(facts).request.enabled)
        assertTrue(SettingsPrivacyPresenter.deletionPresentation(facts.copy(gatewaySessionPresent = true)).request.enabled)
    }

    @Test
    fun legalRetentionAndPartialFailureAreNotPresentedAsComplete() {
        assertEquals("법적 보존 항목이 있어 삭제 완료가 아닙니다.", SettingsPrivacyPresenter.deletionPresentation(deletion(AccountDeletionPhase.RESTRICTED)).status)
        assertEquals("삭제하지 못한 항목이 있어 완료가 아닙니다.", SettingsPrivacyPresenter.deletionPresentation(deletion(AccountDeletionPhase.PARTIAL_FAILURE)).status)
        val closed = SettingsPrivacyPresenter.deletionPresentation(deletion(AccountDeletionPhase.FAIL_CLOSED).copy(failClosedReason = "journal", journalPresent = true))
        assertTrue(closed.status.endsWith("오류: journal"))
        assertFalse(closed.refreshVisible)
    }

    @Test
    fun refreshNeedsAnExistingJournalAndATransientPhase() {
        val facts = deletion(AccountDeletionPhase.RETRY_WAIT).copy(journalPresent = true)
        assertTrue(SettingsPrivacyPresenter.deletionPresentation(facts).refreshEnabled)
        assertFalse(SettingsPrivacyPresenter.deletionPresentation(facts.copy(requestInFlight = true)).refreshEnabled)
        assertFalse(SettingsPrivacyPresenter.deletionPresentation(facts.copy(journalPresent = false)).refreshVisible)
        for (phase in listOf(AccountDeletionPhase.COMPLETED, AccountDeletionPhase.FAIL_CLOSED, AccountDeletionPhase.RESET_PENDING, AccountDeletionPhase.REENROLLMENT_REQUIRED)) {
            assertFalse(SettingsPrivacyPresenter.deletionPresentation(facts.copy(phase = phase)).refreshVisible)
        }
    }

    @Test
    fun recoveryAndPasswordRetryRemainDifferentUserActions() {
        val facts = deletion(AccountDeletionPhase.RETRY_WAIT)
        assertEquals("보안을 위해 비밀번호로 다시 로그인한 뒤 계정 삭제를 다시 요청하세요.", SettingsPrivacyPresenter.deletionPresentation(facts.copy(retryNeedsPasswordLogin = true)).status)
        assertEquals("삭제 요청 복구를 위해 현장 게이트웨이에 다시 로그인해야 합니다.", SettingsPrivacyPresenter.deletionPresentation(facts.copy(rev0ReauthenticationRequired = true, retryNeedsPasswordLogin = true)).status)
    }

    @Test
    fun initialReauthenticationAndRestartRecoveryOfferTheSamePasswordContinuation() {
        val initial = deletion(AccountDeletionPhase.RETRY_WAIT).copy(
            rev0ReauthenticationRequired = true, retryNeedsPasswordLogin = true,
            passwordRecoveryAvailable = true, journalPresent = true,
        )
        val restarted = deletion(AccountDeletionPhase.CONFIRM_REQUIRED).copy(
            durableConfirmationRecovery = true, passwordRecoveryAvailable = true,
        )
        for (facts in listOf(initial, restarted)) {
            val result = SettingsPrivacyPresenter.deletionPresentation(facts)
            assertTrue(result.passwordLogin.visible)
            assertTrue(result.passwordLogin.enabled)
            assertEquals("비밀번호 확인 후 삭제 요청 계속", result.passwordLogin.text)
            assertTrue(result.status.contains("같은 계정의 이메일과 비밀번호"))
            assertTrue(result.status.contains("교육 기록은 유지"))
        }
    }

    @Test
    fun aPasswordCheckDisablesDuplicateSubmissionAndKeepsTheFormVisible() {
        val facts = deletion(AccountDeletionPhase.RETRY_WAIT).copy(
            rev0ReauthenticationRequired = true, passwordRecoveryAvailable = true, journalPresent = true,
        )
        val busy = SettingsPrivacyPresenter.deletionPresentation(facts.copy(passwordRecoveryInFlight = true))
        assertTrue(busy.passwordLogin.visible)
        assertFalse(busy.passwordLogin.enabled)
        assertEquals("비밀번호 확인 중", busy.passwordLogin.text)
        assertFalse(busy.refreshEnabled)
        assertFalse(busy.confirm.enabled)
        assertFalse(busy.cancelEnabled)
        assertTrue(SettingsPrivacyPresenter.deletionPresentation(facts).refreshEnabled)
        assertFalse(SettingsPrivacyPresenter.deletionPresentation(facts.copy(requestInFlight = true)).passwordLogin.enabled)
    }

    @Test
    fun missingAuthorityAndReadyRecoverySessionsNeverOfferPasswordSubmission() {
        val facts = deletion(AccountDeletionPhase.RETRY_WAIT).copy(rev0ReauthenticationRequired = true)
        assertFalse(SettingsPrivacyPresenter.deletionPresentation(facts).passwordLogin.visible)
        assertFalse(SettingsPrivacyPresenter.deletionPresentation(facts.copy(
            passwordRecoveryAvailable = true, durableRecoverySessionReady = true,
        )).passwordLogin.visible)
        assertTrue(SettingsPrivacyPresenter.deletionPresentation(facts.copy(
            passwordRecoveryAvailable = true, durableRecoverySessionReady = true,
        )).status.contains("동일 요청을 다시 접수"))
        assertFalse(SettingsPrivacyPresenter.deletionPresentation(deletion(AccountDeletionPhase.IDLE).copy(
            passwordRecoveryAvailable = true,
        )).passwordLogin.visible)
    }

    @Test
    fun itemDetailsRetainDueTimeReasonRetryAndContactWithoutAuthorityMetadata() {
        val item = DeletionInventoryItem.entries.first()
        val details = SettingsPrivacyPresenter.deletionItemDescription(item, SettingsPrivacyPresenter.DeletionItemFacts(DeletionItemState.LEGAL_HOLD, "due", "law", "retry", "contact"))
        assertEquals("${item.labelKo}: 법적 보존, 기한 due, 사유 law, 예정 retry, 문의 contact", details)
    }

    private fun consent(item: IntegratedConsentItem, state: PurposeConsentSyncState) =
        SettingsPrivacyPresenter.consentButtonPresentation(item, SettingsPrivacyPresenter.ConsentFacts(state, false))

    private fun deletion(phase: AccountDeletionPhase) = SettingsPrivacyPresenter.DeletionFacts(
        phase, null, false, false, false, false, false, false, false, false, emptyMap(),
    )
}
