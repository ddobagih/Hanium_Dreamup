package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityIntegratedConsentStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private val consentSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/consent/IntegratedConsentController.kt").readText()
    private val authorityComposition = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/privacy/PrivacyAuthorityComposition.kt").readText()

    private val accountPresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPresenter.kt",
    ).readText()

    private val accountPanelSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPanel.kt",
    ).readText()

    private val settingsPresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/SettingsPrivacyPresenter.kt",
    ).readText()

    private val firstRunPresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt",
    ).readText()

    @Test
    fun cachedConsentIsHeldUntilFreshBackendAuthorityAndLateSessionResultsAreFenced() {
        val restore = MainActivityRefactorSource.function(
            source, "private fun restorePrivacyControlStateFromPrefs",
        )
        val refresh = MainActivityRefactorSource.function(
            source, "private fun refreshIntegratedConsentFromServer",
        )
        val hold = MainActivityRefactorSource.function(
            source, "private fun holdIntegratedConsentAuthorityForRefresh",
        )
        val request = MainActivityRefactorSource.function(
            source, "private fun startIntegratedConsentRequest",
        )
        val bootstrap = MainActivityRefactorSource.function(
            source, "private fun startIntegratedConsentBootstrap",
        )
        assertTrue(restore.contains("effects.withdrawReportPrivacy()"))
        assertTrue(restore.contains("consent.restoreConsentState()"))
        assertTrue(authorityComposition.contains("override fun restoreConsentState() { integratedConsentController.value.restoreConsentState() }"))
        assertTrue(authorityComposition.contains("override fun withdrawReportPrivacy() { reportPrivacyConsentSession.withdraw() }"))
        assertTrue(MainActivityRefactorSource.member(consentSource, "restoreConsentState").contains("integratedConsentBootstrapReady = false"))
        assertFalse(restore.contains("reportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent()"))
        assertTrue(refresh.contains("holdIntegratedConsentAuthorityForRefresh()"))
        assertTrue(hold.contains("integratedConsentSession.invalidateBackendAuthority()"))
        assertTrue(hold.contains("reports.withdrawAuthority()"))
        assertTrue(request.contains("isCurrentIntegratedConsentSession(expectedSession, processGeneration)"))
        assertTrue(request.contains("GatewaySessionProcessCoordinator.withCurrentGeneralSession("))
        assertTrue(request.contains("synchronized(integratedConsentLock)"))
        assertTrue(bootstrap.contains("isCurrentIntegratedConsentSession(gatewaySession, processGeneration)"))
        val dispatch = MainActivityRefactorSource.function(
            source, "private fun dispatchGatewayProcessSessionChanged",
        )
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            dispatch,
            "consent.fenceAuthorityChanged(snapshot)",
            "reports.withdrawPrivacy()",
            "val deliver = Runnable",
        ))
        val fence = MainActivityRefactorSource.member(consentSource, "fenceGatewayAuthorityChange")
        assertTrue(fence.contains("previous.generation != snapshot.generation"))
        assertTrue(fence.contains("previous.session !== snapshot.session"))
        assertTrue(fence.contains("integratedConsentSession.invalidateBackendAuthority()"))
        val composition = MainActivityRefactorSource.composition(source, "actorSessionLifecycleCoordinator")
        assertTrue(composition.contains("override fun fenceAuthorityChanged(snapshot: GatewaySessionProcessSnapshot) = integratedConsentController.fenceGatewayAuthorityChange(snapshot)"))
        assertTrue(composition.contains("override fun withdrawPrivacy() { reportPrivacyConsentSession.withdraw() }"))
        assertTrue(dispatch.contains("if (consentAuthorityChanged)"))
    }

    @Test
    fun v11BootstrapAndCasAreBoundAcrossInitialSaveAndExactRetry() {
        val refresh = MainActivityRefactorSource.function(
            source,
            "private fun refreshIntegratedConsentFromServer",
        )
        val bootstrap = MainActivityRefactorSource.function(
            source,
            "private fun startIntegratedConsentBootstrap",
        )
        val persist = MainActivityRefactorSource.function(source, "fun persistIntegratedConsentDraft(")
        val retry = MainActivityRefactorSource.function(source, "fun retryPendingIntegratedConsentMutation(")

        assertTrue(refresh.contains("bootstrapOnMissing = true"))
        assertTrue(refresh.contains("session = gatewaySession"))
        assertTrue(refresh.contains("gateway.refreshSessionOrNull()"))
        assertFalse(refresh.contains("gatewaySessionOrNull("))
        assertFalse(refresh.contains("gateway.sessionOrNull("))
        val assembly = MainActivityRefactorSource.composition(source, "integratedConsentController")
        assertTrue(assembly.contains("gateway = ports.gateway"))
        val participants = MainActivityRefactorSource.factory(source, "createPrivacyConsent")
        assertTrue(participants.contains("privacyAuthority.createIntegratedConsentController"))
        assertTrue(participants.contains("gateway = object : IntegratedConsentController.Gateway"))
        assertTrue(participants.contains("override fun refreshSessionOrNull() = integratedConsentRefreshSessionOrNull()"))
        val refreshSession = MainActivityRefactorSource.function(
            source,
            "private fun integratedConsentRefreshSessionOrNull",
        )
        assertTrue(refreshSession.contains("isGatewaySessionReadyForCurrentActor(session)"))
        assertTrue(refreshSession.contains("FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4"))
        assertTrue(refreshSession.contains("!it.isComplete"))
        assertTrue(refreshSession.contains("verifiedActorBinding?.value"))
        assertTrue(refreshSession.contains("processSnapshot.session === session"))
        assertTrue(refreshSession.contains("!processSnapshot.storageBlocked"))
        assertTrue(refreshSession.contains("!processSnapshot.deletionRecoveryOnly"))
        assertTrue(refreshSession.contains("GatewaySessionScope.GENERAL"))
        assertTrue(refreshSession.contains("GatewaySessionVerificationState.VERIFIED"))
        assertTrue(refreshSession.contains("session.isUsableFor(onboardingActorId)"))
        assertTrue(refreshSession.contains("session.gatewayBaseUrl == configuredGatewayOriginOrNull()"))
        assertTrue(refreshSession.contains("clearExpiredGatewaySessionIfCurrent(session)"))
        assertFalse(refreshSession.contains("clearGatewaySession("))
        assertTrue(bootstrap.contains("gateway.isCurrentSession(gatewaySession)"))
        assertTrue(bootstrap.contains("bootstrap.clientRevisionFloor"))
        assertTrue(bootstrap.contains("bootstrap.expectedPreviousBackendReceiptSha256"))
        assertTrue(bootstrap.contains("IntegratedConsentBootstrapStatus.READY"))
        assertTrue(persist.contains("integratedConsentBootstrapReady"))
        assertTrue(persist.contains("expectedPreviousBackendReceiptSha256 ="))
        assertTrue(retry.contains("mutation.expectedPreviousBackendReceiptSha256"))
        val request = MainActivityRefactorSource.function(
            source,
            "private fun startIntegratedConsentRequest",
        )
        assertTrue(request.contains("integrated_consent_actor_reconsent_required"))
        assertTrue(request.contains("privacy_consent_previous_receipt_conflict"))
        val sessionChange = actorSessionChange(source)
        assertTrue(sessionChange.contains("consent.onActorBindingChanged(snapshot.session)"))
        val actorChange = MainActivityRefactorSource.member(consentSource, "onActorBindingChanged")
        assertTrue(actorChange.contains("integratedConsentConfirmedActorSha256"))
        assertTrue(actorChange.contains("integratedConsentActorSha256(currentSession)"))
        assertTrue(consentSource.contains("session.lease.backendAccountGeneration"))
        assertTrue(retry.contains("mutation.actorSha256"))
    }

    @Test
    fun knownV1PolicyMigratesToDeniedReconsentWithoutDroppingStableSecrets() {
        val migration = MainActivityRefactorSource.function(
            source,
            "private fun migrateKnownIntegratedConsentPolicyAtStartup",
        )

        assertTrue(migration.contains("KNOWN_PREVIOUS_INTEGRATED_CONSENT_POLICY_VERSIONS"))
        assertTrue(migration.contains("PREF_RAW_SOURCE_FIELD_LOG_BLOCKED"))
        val reset = MainActivityRefactorSource.function(
            source,
            "private fun resetIntegratedConsentForReconsent",
        )
        val unsettled = MainActivityRefactorSource.member(consentSource, "hasUnsettledIntegratedConsentControl")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            migration,
            "if (hasUnsettledIntegratedConsentControl())",
            "if (!resetIntegratedConsentForReconsent()) return false",
            "val migrated = sensitivePrefs.edit()",
        ))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            reset,
            "if (hasUnsettledIntegratedConsentControl()) return false",
            "val changed = integratedConsentSession.resetForPolicyReconsent()",
            "return changed || (",
        ))
        assertTrue(reset.contains("integratedConsentSession.currentConfirmationOrNull() == null"))
        assertTrue(reset.contains("integratedConsentSession.pendingMutationOrNull() == null"))
        assertTrue(reset.contains("IntegratedConsentItem.entries.all"))
        assertTrue(reset.contains("integratedConsentSession.status(it) == PurposeConsentSyncState.UNCONFIRMED"))
        listOf(
            "sensitivePrefs.isBlocked()",
            "pendingIntegratedConsentMutation != null",
            "integratedConsentSession.pendingMutationOrNull() != null",
            "sensitivePrefs.contains(PREF_PENDING_INTEGRATED_CONSENT_MUTATION)",
            "sensitivePrefs.contains(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED)",
            "PurposeConsentSyncState.WITHDRAWAL_PENDING",
            "PurposeConsentSyncState.WITHDRAWAL_RETRY",
        ).forEach { guard -> assertTrue("Missing unsettled consent guard: $guard", unsettled.contains(guard)) }
        assertFalse(migration.contains(".remove(PREF_PENDING_INTEGRATED_CONSENT_MUTATION)"))
        assertFalse(migration.contains(".remove(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED)"))
        assertTrue(migration.contains(".remove(PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION)"))
        assertFalse(migration.contains(".remove(PREF_INTEGRATED_CONSENT_CONTROL_SECRET)"))
        assertFalse(migration.contains(".remove(PREF_INTEGRATED_CONSENT_CLIENT_REVISION)"))
    }

    @Test
    fun theIntegratedConsentDocumentDescribesTheAfterWalkTransfer() {
        // S15-30 / owner decision 3(b): the consent document states the same timing as the signup text.
        val document = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/IntegratedConsentPolicy.kt",
        ).readText().substringAfter("const val INTEGRATED_CONSENT_DISCLOSURE_KO =")
            .substringBefore("enum class IntegratedConsentItem")
        assertTrue(document.contains("보행을 끝낸"))
        assertTrue(document.contains("충전 중일 때만"))
        assertTrue(document.contains("보행 중에는 보내지 않고"))
        assertFalse(document.contains("PAUSED"))
        assertFalse(document.contains("END"))
    }

    @Test
    fun signupShowsAccessibleDetailedConsentNoticeAtReadableSize() {
        val controls = accountPanelSource.substringAfter("val accountConsentDisclosure =")
            .substringBefore("val consentClauseStops")

        assertTrue(controls.contains("14일"))
        assertTrue(controls.contains("30일"))
        assertTrue(controls.contains("3년"))
        // S15-30 / owner decision 3(b): transfers start only after the walk (RAW also on Wi-Fi while charging).
        assertTrue(controls.contains("보행을 끝낸 뒤"))
        assertTrue(controls.contains("충전 중일 때만"))
        assertTrue(controls.contains("보행 중·일시정지 중에는"))
        assertTrue(controls.contains("후보별 취소는 없으며"))
        assertFalse(controls.contains("PAUSED"))
        assertTrue(controls.contains("영상·음성·이미지·정확한 위치"))
        assertTrue(controls.contains("본인인증이나 공적 연령 인증이 아니"))
        assertTrue(controls.contains("거부하면"))
        assertFalse(controls.contains("출시 전 처리방침/약관 URL 확정 필요"))
        assertTrue(controls.contains("contentDescription = accountConsentDisclosure"))
        assertTrue(controls.contains("textSize = 18f"))
        assertTrue(controls.contains("View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(accountPanelSource.contains("[선택] 원본·진단수집 raw v2 자료 처리 동의"))
        assertTrue(controls.contains("직접 신고에는 영향이 없습니다"))
        val composition = MainActivityRefactorSource.function(source, "private fun buildContentView")
        assertTrue(composition.contains("val accountPanel = AccountAccessPanel("))
        assertTrue(composition.contains("nativeRegistrationComposition.bindAccountInputs(accountPanel)"))
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        assertTrue(MainActivityRefactorSource.member(registration, "bindAccountInputs").contains("accountSource().bindPanel(panel)"))
        assertTrue(accountPresenterSource.contains("private val accountConsentDisclosureText: TextView get() = requireNotNull(panel).accountConsentDisclosureText"))
        assertTrue(accountPresenterSource.contains("private val accountConsentChecks get() = panel?.accountConsentChecks ?: emptyMap()"))
        assertTrue(composition.contains("onConsentChanged = {"))
        assertTrue(composition.contains("refreshAccountConsentSummary()"))
        assertTrue(composition.contains("refreshAccountActionAvailability()"))
        assertTrue(accountPanelSource.contains("setOnCheckedChangeListener { button, _ ->"))
        assertTrue(accountPanelSource.contains("if (key in unavailableOptionalDocuments && button.isChecked)"))
        assertTrue(accountPanelSource.contains("button.isChecked = false"))
        assertTrue(accountPanelSource.contains("actions.onConsentChanged()"))
        val summary = ReportStaticSourceInspector.functionBlock(accountPresenterSource, "fun refreshAccountConsentSummary")
        assertTrue(summary.contains("currentAccountConsentSelections()"))
        assertTrue(summary.contains("selections.requiredGranted"))
        assertTrue(summary.contains("accountConsentAllCheck.isChecked = allGranted"))
        assertTrue(MainActivityRefactorSource.function(source, "private fun refreshAccountConsentSummary").contains("accountAccessPresenter.refreshAccountConsentSummary(nativeUiPublication.isPublished(NativeUiPublication.Target.ACCOUNT_CONSENT_ALL_CHECK))"))
        assertTrue(MainActivityRefactorSource.function(source, "private fun refreshAccountActionAvailability").contains("accountAccessPresenter.refreshAccountActionAvailability("))
    }

    @Test
    fun consentAndNetworkGatesPrecedeDrainAndEveryAcquiredLeaseIsReleased() {
        val process = MainActivityRefactorSource.function(
            source,
            "private fun processReportCandidate",
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                process,
                "integratedConsentSession.currentConfirmationOrNull()",
                "!explicitRequest && !consentConfirmation.selections.automaticReporting",
                "queue.enqueue(",
            ),
        )
        assertFalse(process.contains("rawSourceCollection"))

        val capture = MainActivityRefactorSource.function(
            source,
            "private fun buildReportQueueDrainTrigger",
        )
        assertTrue(capture.contains("reportPrivacyConsentSession.isGranted()"))
        assertTrue(capture.contains("integratedConsentSession.currentConfirmationOrNull("))
        assertFalse(capture.contains("IntegratedConsentItem.RAW_SOURCE_COLLECTION"))
        assertTrue(capture.contains("AndroidNetworkTransferPolicy.isAllowed("))
        assertTrue(capture.contains("network.currentBinding() ?: return null"))

        val context = MainActivityRefactorSource.function(
            source,
            "private fun reportQueueDrainContext",
        )
        assertTrue(context.contains("currentConfirmation == trigger.consentConfirmation"))
        assertTrue(context.contains("trigger.networkBinding.isSameNetworkBinding(currentBinding)"))
        assertTrue(context.contains("AndroidNetworkTransferPolicy.isAllowed("))
        assertFalse(context.contains("IntegratedConsentItem.RAW_SOURCE_COLLECTION"))

        val coordinator = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/report/" +
                "ReportQueueDrainCoordinator.kt",
        ).readText()
        val legacyEntry = MainActivityRefactorSource.member(coordinator, "startNext")
        assertTrue(legacyEntry.contains("startNextWithResult(contextProvider, transport, beforeDeleteAfterReceipt)"))
        val startNext = MainActivityRefactorSource.member(coordinator, "startNextWithResult")
        assertTrue(startNext.contains("finally"))
        assertTrue(startNext.contains("policy.release(lease)"))
        assertTrue(
            ReportStaticSourceInspector.blockAfter(startNext, "cancelBlock =")
                .contains("policy.release(lease)"),
        )
    }

    @Test
    fun consentMutationRequiresAndForwardsTheCurrentGatewaySession() {
        val persist = MainActivityRefactorSource.function(source, "fun persistIntegratedConsentDraft(")
        val retry = MainActivityRefactorSource.function(source, "fun retryPendingIntegratedConsentMutation(")
        for (function in listOf(persist, retry)) {
            val session = function.indexOf("gateway.sessionOrNull(")
            val save = function.indexOf("remote.saveCall(")
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
        val buttonPresentation = ReportStaticSourceInspector.functionBlock(
            settingsPresenterSource, "fun consentButtonPresentation",
        )
        val buttonRenderer = ReportStaticSourceInspector.functionBlock(
            settingsPresenterSource, "fun renderConsentButton",
        )
        listOf(
            "updateReportPrivacyConsentUi" to "RAW_SOURCE_COLLECTION",
            "updateAutomaticReportConsentUi" to "AUTOMATIC_REPORTING",
            "updateMobileNetworkPreferenceUi" to "MOBILE_NETWORK_TRANSFER",
            "updateTrainingReuseConsentUi" to "TRAINING_REUSE",
        ).forEach { (name, item) ->
            val function = MainActivityRefactorSource.function(
                source,
                "private fun $name",
            )
            assertTrue(function.contains("integratedConsentSession.status("))
            assertTrue(function.contains("integratedConsentSession.status(IntegratedConsentItem.$item)"))
            assertTrue(function.contains("settingsPrivacyPresenter.renderConsentButton("))
            assertTrue(function.contains("SettingsPrivacyPresenter.ConsentFacts("))
            assertTrue(function.contains("integratedConsentController.screenState().requestInFlight"))
            val itemPresentation = buttonPresentation.substringAfter("IntegratedConsentItem.$item -> when (state) {")
                .substringBefore("\n                IntegratedConsentItem.")
            assertTrue(itemPresentation.contains("PurposeConsentSyncState.CONFIRMED_GRANTED"))
            assertTrue(itemPresentation.contains("PurposeConsentSyncState.GRANT_PENDING"))
            assertTrue(itemPresentation.contains("아직 활성 아님"))
            assertTrue(buttonRenderer.contains("IntegratedConsentItem.$item ->"))
            assertTrue(!function.contains("integratedConsentDraft."))
        }

        val integrated = MainActivityRefactorSource.function(
            source,
            "private fun updateIntegratedConsentUi",
        )
        assertTrue(integrated.contains("settingsPrivacyPresenter.renderConsentItems(SettingsPrivacyPresenter.IntegratedConsentFacts("))
        assertTrue(integrated.contains("states = IntegratedConsentItem.entries.associateWith(integratedConsentSession::status)"))
        val itemPresentation = ReportStaticSourceInspector.functionBlock(
            settingsPresenterSource, "fun consentItemPresentation",
        )
        val itemRenderer = ReportStaticSourceInspector.functionBlock(
            settingsPresenterSource, "fun renderConsentItems",
        )
        assertTrue(itemPresentation.contains("허용(서버 확인)"))
        assertTrue(itemPresentation.contains("허용 선택 저장 중(아직 활성 아님)"))
        assertTrue(itemPresentation.contains("val text = \"\${itemLabel(item)}: \$syncState\""))
        assertTrue(itemRenderer.contains("consentItemPresentation(item, facts)"))
        assertTrue(itemRenderer.contains("button.text = presentation.text"))
    }

    @Test
    fun draftSummaryDoesNotMasqueradeAsServerConfirmation() {
        val summary = MainActivityRefactorSource.function(
            source,
            "private fun updateFirstRunConsentSummaryUi",
        )
        assertTrue(summary.contains("firstRunOnboardingPresenter.updateFirstRunConsentSummaryUi("))
        assertTrue(summary.contains("FirstRunOnboardingPresenter.ConsentSummaryFacts(items.size, draftGranted, serverSummary)"))
        val rendered = ReportStaticSourceInspector.functionBlock(
            firstRunPresenterSource, "fun updateFirstRunConsentSummaryUi",
        )
        assertTrue(rendered.contains("선택 초안:"))
        assertTrue(rendered.contains("\${facts.itemCount}개"))
        assertTrue(rendered.contains("\${facts.draftGranted}개 허용"))
        assertTrue(rendered.contains("\${facts.serverSummary}"))
        assertTrue(rendered.contains("firstRunConsentCountText.contentDescription = firstRunConsentCountText.text"))
        assertTrue(summary.contains("서버 미확인"))
        assertTrue(summary.contains("서버 확인 중"))
        assertTrue(summary.contains("서버 확인 완료:"))
        assertTrue(summary.contains("서버 철회 재시도 대기"))
        assertTrue(summary.contains("서버 처리 잠김"))
        assertTrue(
            summary.indexOf("PurposeConsentSyncState.FAIL_CLOSED in states") <
                summary.indexOf("integratedConsentController.screenState().requestInFlight"),
        )
        assertTrue(
            summary.indexOf("integratedConsentController.screenState().requestInFlight") <
                summary.indexOf("PurposeConsentSyncState.WITHDRAWAL_RETRY in states"),
        )
    }

    @Test
    fun draftSummaryRefreshesOnEveryConsentUiRefresh() {
        val update = MainActivityRefactorSource.function(
            source,
            "private fun updateIntegratedConsentUi",
        )
        assertTrue(update.contains("updateFirstRunConsentSummaryUi()"))
    }

    @Test
    fun gatewayConnectionFailureIsHandledInsteadOfKillingTheProcess() {
        val request = MainActivityRefactorSource.function(
            source,
            "private fun startIntegratedConsentRequest",
        )

        // ConnectException 은 IOException 계열이라 RuntimeException 으로 잡히지 않는다.
        // Gateway 가 닿지 않을 때 실행자 스레드에서 빠져나가면 프로세스가 종료된다.
        assertFalse(request.contains("catch (_: RuntimeException)"))
        assertTrue(request.contains("catch (error: Exception)"))
        assertTrue(request.contains("integratedConsent=blocked:server_confirmation_failed"))
    }

    private fun actorSessionChange(mainSource: String): String {
        MainActivityRefactorSource.composition(mainSource, "actorSessionLifecycleCoordinator")
        val main = MainActivityRefactorSource.factory(mainSource, "onGatewayProcessSessionChanged")
        // Whole declaration comparison preserves guards, argument forwarding and effect order.
        val expectedMain = """
private fun onGatewayProcessSessionChanged(
        snapshot: GatewaySessionProcessSnapshot,
        consentAuthorityChanged: Boolean,
    ) {
        actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)
        refreshGuidanceDiagnosticAuthority()
    }
        """.trimIndent()
        org.junit.Assert.assertEquals("Main gateway callback must invoke the owner before diagnostic refresh", expectedMain.filterNot(Char::isWhitespace),
            main.filterNot(Char::isWhitespace))
        val call = main.indexOf("actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)")
        org.junit.Assert.assertTrue(call >= 0)
        org.junit.Assert.assertTrue(main.indexOf("refreshGuidanceDiagnosticAuthority()") > call)
        return MainActivityRefactorSource.member(java.io.File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/ActorSessionLifecycleCoordinator.kt",
        ).readText(), "onGatewayProcessSessionChanged")
    }


    @Test
    fun actorSessionChangeRejectsNonExecutingOrReorderedCallers() {
        val mainSource = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        actorSessionChange(mainSource)
        val caller = MainActivityRefactorSource.factory(mainSource, "onGatewayProcessSessionChanged")
        val call = "actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)"
        org.junit.Assert.assertTrue(caller.contains(call))
        val variants = linkedMapOf(
            "conditional" to caller.replace(call, "if (false) { $call }"),
            "uninvoked lambda" to caller.replace(call, "{ $call }"),
            "comment" to caller.replace(call, "/* $call */"),
            "additional effect" to caller.replace(call, "$call\nunexpectedEffect()"),
            "omitted" to caller.replace(call, ""),
            "early return" to caller.replace(call, "return\n$call"),
        )

        variants["reordered effects"] = caller.replace(call, "").replace(
            "refreshGuidanceDiagnosticAuthority()", "refreshGuidanceDiagnosticAuthority()\n" + call,
        )

        val accepted = variants.filter { (_, changed) ->
            val mainSource = mainSource.replace(caller, changed)
            runCatching { actorSessionChange(mainSource) }.isSuccess
        }.keys
        org.junit.Assert.assertTrue("Non-equivalent Main callers accepted: $accepted", accepted.isEmpty())
    }

}
