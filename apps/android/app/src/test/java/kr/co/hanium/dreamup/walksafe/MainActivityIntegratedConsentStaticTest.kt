package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityIntegratedConsentStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun v11BootstrapAndCasAreBoundAcrossInitialSaveAndExactRetry() {
        val refresh = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun refreshIntegratedConsentFromServer",
        )
        val bootstrap = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startIntegratedConsentBootstrap",
        )
        val persist = source.substringAfter(
            "private fun persistIntegratedConsentDraft(announce: Boolean)",
        ).substringBefore("private fun retryPendingIntegratedConsentMutation")
        val retry = source.substringAfter(
            "private fun retryPendingIntegratedConsentMutation",
        ).substringBefore("private fun startIntegratedConsentRequest")

        assertTrue(refresh.contains("bootstrapOnMissing = true"))
        assertTrue(refresh.contains("session = gatewaySession"))
        assertTrue(bootstrap.contains("isCurrentGatewaySession(gatewaySession)"))
        assertTrue(bootstrap.contains("bootstrap.clientRevisionFloor"))
        assertTrue(bootstrap.contains("bootstrap.expectedPreviousBackendReceiptSha256"))
        assertTrue(bootstrap.contains("IntegratedConsentBootstrapStatus.READY"))
        assertTrue(persist.contains("integratedConsentBootstrapReady"))
        assertTrue(persist.contains("expectedPreviousBackendReceiptSha256 ="))
        assertTrue(retry.contains("mutation.expectedPreviousBackendReceiptSha256"))
        val request = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startIntegratedConsentRequest",
        )
        assertTrue(request.contains("integrated_consent_actor_reconsent_required"))
        assertTrue(request.contains("privacy_consent_previous_receipt_conflict"))
        val sessionChange = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun onGatewayProcessSessionChanged",
        )
        assertTrue(sessionChange.contains("integratedConsentConfirmedActorSha256"))
        assertTrue(sessionChange.contains("integratedConsentActorSha256(currentSession)"))
        assertTrue(source.contains("session.lease.backendAccountGeneration"))
        assertTrue(retry.contains("mutation.actorSha256"))
    }

    @Test
    fun knownV1PolicyMigratesToDeniedReconsentWithoutDroppingStableSecrets() {
        val migration = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun migrateKnownIntegratedConsentPolicyAtStartup",
        )

        assertTrue(migration.contains("PREVIOUS_INTEGRATED_CONSENT_POLICY_VERSION"))
        assertTrue(migration.contains("PREF_RAW_SOURCE_FIELD_LOG_BLOCKED"))
        assertTrue(migration.contains("resetForPolicyReconsent()"))
        assertTrue(migration.contains(".remove(PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION)"))
        assertFalse(migration.contains(".remove(PREF_INTEGRATED_CONSENT_CONTROL_SECRET)"))
        assertFalse(migration.contains(".remove(PREF_INTEGRATED_CONSENT_CLIENT_REVISION)"))
    }

    @Test
    fun signupShowsAccessibleDetailedConsentNoticeAtReadableSize() {
        val controls = source.substringAfter("val accountConsentDisclosure =")
            .substringBefore("accountConsentChecks.clear()")

        assertTrue(controls.contains("14일"))
        assertTrue(controls.contains("30일"))
        assertTrue(controls.contains("3년"))
        assertTrue(controls.contains("PAUSED"))
        assertTrue(controls.contains("END는 자동 전송"))
        assertTrue(controls.contains("영상·음성·이미지·정확한 위치"))
        assertTrue(controls.contains("본인인증이나 공적 연령 인증이 아닙니다"))
        assertTrue(controls.contains("거부하면"))
        assertTrue(controls.contains("출시 전 처리방침/약관 URL 확정 필요"))
        assertTrue(controls.contains("contentDescription = accountConsentDisclosure"))
        assertTrue(controls.contains("textSize = 18f"))
        assertTrue(controls.contains("View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(source.contains("[선택] 신고·진단용 raw v2 자료 처리 동의"))
    }

    @Test
    fun consentAndNetworkGatesPrecedeDrainAndEveryAcquiredLeaseIsReleased() {
        val process = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun processReportCandidate",
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                process,
                "integratedConsentSession.currentConfirmationOrNull()",
                "!consentConfirmation.selections.rawSourceCollection",
                "!explicitRequest && !consentConfirmation.selections.automaticReporting",
                "reportQueueStore.enqueue(",
            ),
        )

        val capture = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun captureReportQueueDrainTriggerBeforeTransition",
        )
        assertTrue(capture.contains("reportPrivacyConsentSession.isGranted()"))
        assertTrue(capture.contains("integratedConsentSession.currentConfirmationOrNull("))
        assertTrue(capture.contains("AndroidNetworkTransferPolicy.isAllowed("))
        assertTrue(capture.contains("currentIntegratedConsentBinding() ?: return null"))

        val context = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun reportQueueDrainContext",
        )
        assertTrue(context.contains("currentConfirmation == trigger.consentConfirmation"))
        assertTrue(context.contains("trigger.networkBinding.isSameNetworkBinding(currentBinding)"))
        assertTrue(context.contains("AndroidNetworkTransferPolicy.isAllowed("))

        val coordinator = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/report/" +
                "ReportQueueDrainCoordinator.kt",
        ).readText()
        val startNext = ReportStaticSourceInspector.functionBlock(coordinator, "fun startNext")
        assertTrue(startNext.contains("finally"))
        assertTrue(startNext.contains("policy.release(lease)"))
        assertTrue(
            ReportStaticSourceInspector.blockAfter(startNext, "cancelBlock =")
                .contains("policy.release(lease)"),
        )
    }

    @Test
    fun consentMutationRequiresAndForwardsTheCurrentGatewaySession() {
        val persist = source.substringAfter(
            "private fun persistIntegratedConsentDraft(announce: Boolean)",
        ).substringBefore("private fun retryPendingIntegratedConsentMutation")
        val retry = source.substringAfter(
            "private fun retryPendingIntegratedConsentMutation",
        ).substringBefore("private fun startIntegratedConsentRequest")
        for (function in listOf(persist, retry)) {
            val session = function.indexOf("gatewaySessionOrNull(")
            val save = function.indexOf("integratedConsentClient.saveCall(")
            assertTrue(session >= 0)
            assertTrue(save > session)
            assertTrue(
                function.substring(save).contains("session = gatewaySession"),
            )
        }

        val client = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/network/" +
                "AndroidIntegratedConsentClient.kt",
        ).readText()
        val saveCall = client.substringAfter("fun saveCall(")
            .substringBefore("private fun requestCall(")
        assertTrue(saveCall.contains("session: GatewayFieldSession"))
        assertTrue(saveCall.contains("gatewayBaseUrl == session.gatewayBaseUrl"))
        assertTrue(saveCall.contains("session = session"))
        assertTrue(client.contains("session?.requestHeaders()?.forEach(::setRequestProperty)"))
    }

    @Test
    fun pendingDraftNeverDisplaysAsBackendConfirmedConsent() {
        listOf(
            "updateReportPrivacyConsentUi",
            "updateAutomaticReportConsentUi",
            "updateMobileNetworkPreferenceUi",
            "updateTrainingReuseConsentUi",
        ).forEach { name ->
            val function = ReportStaticSourceInspector.functionBlock(
                source,
                "private fun $name",
            )
            assertTrue(function.contains("integratedConsentSession.status("))
            assertTrue(function.contains("PurposeConsentSyncState.CONFIRMED_GRANTED"))
            assertTrue(function.contains("PurposeConsentSyncState.GRANT_PENDING"))
            assertTrue(function.contains("아직 활성 아님"))
            assertTrue(!function.contains("integratedConsentDraft."))
        }

        val integrated = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun updateIntegratedConsentUi",
        )
        assertTrue(integrated.contains("허용(서버 확인)"))
        assertTrue(integrated.contains("허용 선택 저장 중(아직 활성 아님)"))
        assertTrue(integrated.contains("button.text = \"\$label: \$syncState\""))
    }

    @Test
    fun draftSummaryDoesNotMasqueradeAsServerConfirmation() {
        val summary = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun updateFirstRunConsentSummaryUi",
        )
        assertTrue(summary.contains("선택 초안:"))
        assertTrue(summary.contains("서버 미확인"))
        assertTrue(summary.contains("서버 확인 중"))
        assertTrue(summary.contains("서버 확인 완료:"))
        assertTrue(summary.contains("서버 철회 재시도 대기"))
        assertTrue(summary.contains("서버 처리 잠김"))
        assertTrue(
            summary.indexOf("PurposeConsentSyncState.FAIL_CLOSED in states") <
                summary.indexOf("integratedConsentRequestInFlight"),
        )
        assertTrue(
            summary.indexOf("integratedConsentRequestInFlight") <
                summary.indexOf("PurposeConsentSyncState.WITHDRAWAL_RETRY in states"),
        )
    }

    @Test
    fun draftSummaryRefreshesOnEveryConsentUiRefresh() {
        val update = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun updateIntegratedConsentUi",
        )
        assertTrue(update.contains("updateFirstRunConsentSummaryUi()"))
    }

    @Test
    fun gatewayConnectionFailureIsHandledInsteadOfKillingTheProcess() {
        val request = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startIntegratedConsentRequest",
        )

        // ConnectException 은 IOException 계열이라 RuntimeException 으로 잡히지 않는다.
        // Gateway 가 닿지 않을 때 실행자 스레드에서 빠져나가면 프로세스가 종료된다.
        assertFalse(request.contains("catch (_: RuntimeException)"))
        assertTrue(request.contains("catch (error: Exception)"))
        assertTrue(request.contains("integratedConsent=blocked:server_confirmation_failed"))
    }
}
