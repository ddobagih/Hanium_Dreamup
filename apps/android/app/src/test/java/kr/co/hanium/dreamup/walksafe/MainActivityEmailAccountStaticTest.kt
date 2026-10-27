package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityEmailAccountStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    private val accountPresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPresenter.kt",
    ).readText()

    private val accountPanelSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPanel.kt",
    ).readText()

    private val onboardingPanelSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt",
    ).readText()

    private val firstRunPresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt",
    ).readText()

    private val nativePresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeScreenPresenter.kt",
    ).readText()

    @Test
    fun emailDobPasswordOtpAndSixConsentControlsAreActuallyComposed() {
        assertTrue(accountPanelSource.contains("accountEmailInput = EditText(context).apply"))
        assertTrue(accountPanelSource.contains("accountDateOfBirthInput = EditText(context).apply"))
        assertTrue(accountPanelSource.contains("accountPasswordInput = EditText(context).apply"))
        assertTrue(accountPanelSource.contains("accountPasswordConfirmationInput = EditText(context).apply"))
        assertTrue(accountPanelSource.contains("accountOtpInput = EditText(context).apply"))
        listOf(
            "terms_of_service",
            "privacy_notice",
            "location_terms",
            "raw_original",
            "automatic_reporting",
            "training_reuse",
        ).forEach { assertTrue(accountPanelSource.contains("\"$it\"")) }
        assertTrue(onboardingPanelSource.contains("firstRunOnboardingControls.addView(accountContent, 2)"))
        assertTrue(source.contains("onboardingPanel.setAccountContent(accountAccessPresenter.rootTarget())"))
        assertTrue(accountPanelSource.contains("contentDescription = \"계정 이메일 입력\""))
        assertTrue(accountPanelSource.contains("contentDescription = \"이메일로 받은 숫자 인증번호 6자리 입력\""))
        assertTrue(functionBlock("fun rootTarget(", accountPresenterSource).contains("publishedRoot.accountAccessControls"))
    }

    @Test
    fun signupValidatesRequiredConsentAndDoesNotPersistSensitiveInputs() {
        val request = functionBlock("private fun requestEmailAccountOtp()")
        assertTrue(request.contains("if (!selections.requiredGranted)"))
        assertTrue(request.contains("EmailEnrollmentPartial("))
        assertFalse(request.contains("putString(email"))
        assertFalse(request.contains("putString(dateOfBirth"))

        // S01-13: signup uses the 12-character rule; login keeps the legacy check.
        assertTrue(request.contains("GatewayAccountInputPolicy.validNewPassword(password)"))
        assertFalse(request.contains("GatewayAccountInputPolicy.validPassword(password)"))
        assertTrue(request.contains("비밀번호는 12자 이상 128자 이하로 입력하세요."))

        val create = functionBlock("private fun createEmailAccount()")
        assertTrue(create.contains("GatewayAccountInputPolicy.validNewPassword(password)"))
        assertFalse(create.contains("GatewayAccountInputPolicy.validPassword(password)"))
        assertTrue(create.contains("비밀번호는 12자 이상 128자 이하로 입력하세요."))
        assertTrue(functionBlock("private fun loginEmailAccount(")
            .contains("GatewayAccountInputPolicy.validPassword(password)"))
        assertTrue(MainActivityRefactorSource.email.contains(
            "\"account_password_too_common\" ->\n" +
                "                    \"많이 쓰이거나 추측하기 쉬운 비밀번호는 사용할 수 없습니다. 다른 비밀번호를 입력하세요.\"",
        ))
        assertTrue(create.contains("if (!selections.requiredGranted)"))
        assertTrue(create.contains("password != confirmation"))
        assertTrue(create.contains("Regex(\"^[0-9]{6}$\")"))
        assertTrue(create.contains("storage.clear()"))
    }

    @Test
    fun loginPersistsBeforePublishingAndCanResumeBoundAdvancedProgress() {
        val login = functionBlock("private fun loginEmailAccount(")
        assertTrue(login.contains("services.loginWithPassword("))
        assertTrue(login.contains("services.installationDeviceId()"))
        assertTrue(login.contains("deviceId = installationDeviceId"))
        assertTrue(login.contains("rememberMe = true"))
        assertFalse(login.contains("val rememberMe"))
        assertFalse(login.contains("rememberMeOverride"))
        assertFalse(login.contains("accountRememberMeCheck"))
        assertTrue(login.contains("flow.prepareVerifiedLogin(actorBinding, receipt, completedNativeProgress)"))
        val prepare = onboardingOwner("prepareVerifiedEmailLogin")
        assertTrue(prepare.contains("FirstRunOnboardingPolicy.recordVerifiedEmailLogin("))
        assertTrue(prepare.contains("FirstRunOnboardingPolicy.mayReauthenticateVerifiedEmailActor("))
        assertTrue(prepare.contains("val next = if (reauthenticates) completed?.let(::effectiveSessionSnapshot) ?: before"))
        assertTrue(prepare.contains("completedNativeProgress?.takeIf { it.verifiedActorBinding == actor }"))
        assertTrue(login.contains("val nextFirstRunSnapshot = preparedLogin.snapshot"))
        val wiring = functionBlock("private fun createEmailAccountController()")
        assertTrue(wiring.contains("firstRunOnboardingController.prepareVerifiedEmailLogin(actor, receipt, completed)"))
        assertTrue(wiring.contains("firstRunOnboardingController.applyVerifiedEmailLogin(prepared)"))
        val apply = onboardingOwner("applyVerifiedEmailLogin")
        assertInOrder(apply, "if (prepared.owner !== this) return false", "if (snapshot == prepared.snapshot) return true",
            "if (snapshot !== prepared.before) return false", "snapshot = prepared.snapshot")
        assertTrue(File("src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/ActorSessionLifecycleCoordinator.kt").readText().contains("ageBand = PriorityUserAgeBand.VERIFIED_14_PLUS"))
        assertTrue(login.contains("GatewaySessionProcessCoordinator.publishVerified("))
        assertTrue(login.contains("로그인했습니다. 먼저 서비스 목적과 안전 한계를 확인하세요."))
        assertTrue(login.contains("account=authenticated next=purpose_and_safety"))
        assertTrue(login.contains("account=reauthenticated next=onboarding_resume"))
        assertTrue(login.contains("effects.applyVerifiedAccount(session.actorId, reauthenticatesExistingProgress)"))
        assertTrue(functionBlock("private fun createEmailAccountController()").contains("restorePriorityUserOnboardingFromPrefs()"))
        assertInOrder(
            login,
            "services.saveBackendDeviceIfAbsent(",
            "GatewaySessionProcessCoordinator.publishVerified(",
            "if (!flow.applyVerifiedLogin(preparedLogin))",
        )
        assertFalse(login.contains("PriorityUserAgeBand.AGE_14_TO_17"))
        assertFalse(login.contains("VERIFIED_SMS"))
        assertFalse(login.contains("LOCAL_CREDENTIAL_PHONE_SUBMISSION"))
    }

    @Test
    fun passwordSessionRestoresBeforeLegacyFeatureToggleAndNeedsServerVerification() {
        val restore = functionBlock("private fun restoreGatewaySessionFromPrefs()")
        assertInOrder(restore,
            "store.restoreBackendDevice(expectedOrigin)",
            "restoreBackendDeviceSession(backendDevice, operation, expectedFirstRun)",
            "if (!config.longLivedLoginEnabled)",
        )
        val backend = functionBlock("private fun restoreBackendDeviceSession(")
        assertInOrder(backend,
            "GatewaySessionProcessCoordinator.publishRestoredUnverified(",
            "remote.revalidateBackendAccountDeviceSession(",
            "GatewaySessionProcessCoordinator.publishVerified(",
        )
        assertTrue(backend.contains("remote.revalidateBackendAccountDeviceSession(restored.session)"))
        assertFalse(backend.contains("accountBlockReason()"))
        assertFalse(backend.contains("stageRestoredGatewayRevocation("))
        assertTrue(backend.contains("gateway_backend_device_restore_unavailable"))
        assertTrue(backend.contains("store.removeBackendDeviceIfCurrent(restored.version)"))
        val wiring = MainActivityRefactorSource.composition(source, "gatewaySessionController")
        assertTrue(wiring.contains("BuildConfig.WALKSAFE_LONG_LIVED_LOGIN_ENABLED"))
        assertTrue("Stored session verification must reach the same live gateway client",
            wiring.contains("override fun revalidateBackendAccountDeviceSession(session: GatewayFieldSession) = gatewaySessionClient.revalidateBackendAccountDeviceSession(session)"))
        val storageInit = MainActivityRefactorSource.member(source, "onCreate")
        assertTrue(storageInit.contains("gatewaySessionController.initializeStorage(object : GatewaySessionController.Store"))
        assertTrue(storageInit.contains("override fun restoreBackendDevice(expectedGatewayBaseUrl: String) = gatewaySessionStore.restoreBackendDevice(expectedGatewayBaseUrl)"))
    }

    @Test
    fun pendingPasswordRestoreHasRetryAndExplicitLogoutWithoutNewCredentials() {
        val login = functionBlock("private fun loginEmailAccount(")
        assertInOrder(login, "if (readers.backendRestorePending())", "effects.restoreGatewaySession()",
            "GatewayAccountInputPolicy.validEmail(email)")
        val wiring = functionBlock("private fun updateEmailAccountAccessUi(")
        assertTrue(wiring.contains("screen.renderAccess("))
        assertTrue(wiring.contains("backendDeviceRestorePending = gateway.backendRestorePending()"))
        assertTrue(wiring.contains("processStorageBlocked = process.storageBlocked"))
        assertTrue(wiring.contains("processDeletionRecoveryOnly = process.deletionRecoveryOnly"))
        assertTrue(wiring.contains("processRequestInFlight = process.inFlightOperationId != null"))
        val ui = functionBlock("fun updateEmailAccountAccessUi(", accountPresenterSource)
        assertTrue(ui.contains("facts.backendDeviceRestorePending && !authenticated"))
        assertTrue(ui.contains("!facts.processStorageBlocked && !facts.processDeletionRecoveryOnly"))
        assertTrue(ui.contains("저장된 로그인 다시 확인"))
        assertTrue(ui.contains("저장된 로그인 해제"))
        assertTrue(ui.contains("accountSessionLogoutButton.isEnabled = !verifying"))
        val rendered = functionBlock("private fun applyNativePreviewPresentation(")
        assertInOrder(rendered, "val accountLogoutVisible = accountVisible",
            "!accountSession.storageBlocked", "!accountSession.deletionRecoveryOnly",
            "backendDeviceRestorePending ||",
            "accountSession.session?.sessionScope == GatewaySessionScope.GENERAL")
        assertTrue(rendered.contains("nativeScreenPresenter.renderPreviewAccountAndPreparation(preview,"))
        assertTrue(rendered.contains("NativeScreenPresenter.AccountPreviewFacts(accountLogoutVisible, accountTone)"))
        val preview = functionBlock("fun renderPreviewAccountAndPreparation(", nativePresenterSource)
        assertTrue(preview.contains("show(accountSessionLogoutButton, accountFacts.logoutVisible)"))
        assertFalse(rendered.contains("accountSessionLogoutButton.text =="))
        assertFalse(preview.contains("accountSessionLogoutButton.text =="))
        val resumed = functionBlock("private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()")
        assertTrue(resumed.contains("retryPendingBackendDeviceRestore()"))
        val clear = functionBlock("private fun clearGatewaySession(")
        assertInOrder(clear, "store.removeBackendDeviceIfCurrent(version)",
            "GatewaySessionProcessCoordinator.publishPendingRevocation(operation)")
        val progress = functionBlock("private fun onFirstRunOnboardingStateChanged(")
        assertTrue(progress.contains("gateway.publishStageSnapshot("))
        val ports = MainActivityRefactorSource.composition(source, "accountAccessInteractionOwner")
        assertTrue(ports.contains("override fun renderAccess(facts: AccountAccessPresenter.AccessFacts) { accountAccessPresenter.updateEmailAccountAccessUi(facts) }"))
        assertTrue(ports.contains("override fun backendRestorePending() = gatewaySessionController.backendRestorePending()"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingController").contains("gatewaySessionStore.updateBackendDeviceFirstRunSnapshot("))
    }

    @Test
    fun featureReadinessLookupKeepsUsableLoginWhileActorAndOnboardingAreStillBinding() {
        listOf("private fun integratedConsentRefreshSessionOrNull()",
            "private fun gatewaySessionOrNull(").forEach { marker ->
            val lookup = functionBlock(marker)
            assertTrue(lookup.contains("isGatewaySessionReadyForCurrentActor(session)"))
            assertTrue(lookup.contains("clearExpiredGatewaySessionIfCurrent(session)"))
            assertFalse(lookup.contains("clearGatewaySession("))
        }
        val expiry = functionBlock("private fun clearExpiredGatewaySessionIfCurrent(")
        assertInOrder(expiry,
            "session.verificationState != GatewaySessionVerificationState.VERIFIED",
            "session.isUsableFor(session.actorId)",
            ") return",
            "GatewaySessionProcessCoordinator.snapshot()",
            "clearGatewaySession(",
            "expectedSession = session",
            "expectedProcessGeneration = process.generation",
        )
        assertFalse(expiry.contains("currentReporterUserId()"))
        assertFalse(expiry.contains("permissionSessionPolicy"))
    }

    @Test
    fun purposeAndSafetyAcknowledgementAlsoPersistsVoiceProcessingDisclosure() {
        val acknowledgement =
            functionBlock("private fun acknowledgeFirstRunPurposeAndSafety()")
        assertInOrder(
            acknowledgement,
            "FirstRunOnboardingStage.PURPOSE_AND_SAFETY",
            "if (!effects.acknowledgeVoiceDisclosure())",
            "FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(",
            "snapshot = transition.current",
        )
        val disclosureFailureGate = acknowledgement.substringAfter("if (!effects.acknowledgeVoiceDisclosure()) {").substringBefore("val transition = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(")
        assertTrue(disclosureFailureGate.contains("return"))

        val voiceDisclosure =
            functionBlock("private fun acknowledgeHandsFreeVoiceDisclosure()")
        assertInOrder(
            voiceDisclosure,
            ".putInt(",
            "PREF_HANDS_FREE_VOICE_DISCLOSURE_VERSION",
            "HANDS_FREE_VOICE_DISCLOSURE_VERSION",
            ".commit()",
        )
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingController").contains("override fun acknowledgeVoiceDisclosure() = this@MainActivity.acknowledgeHandsFreeVoiceDisclosure()"))
    }

    @Test
    fun duplicateAndBackgroundCallbacksAreFencedAndLogoutReturnsToLoginAccess() {
        assertTrue(MainActivityRefactorSource.email.contains("private val accountRequestFence = AccountRequestFence()"))
        assertTrue(MainActivityRefactorSource.email.contains("accountRequestFence.enteredBackground()"))
        assertTrue(source.contains("emailAccountController.enteredBackground()"))
        assertTrue(MainActivityRefactorSource.email.contains("accountRequestFence.completeIfCurrent("))
        val logout = functionBlock("private fun onAccountLogoutClicked()")
        assertTrue(logout.contains("onboarding.restartEmailAccount()"))
        val binding = MainActivityRefactorSource.composition(source, "accountAccessInteractionOwner")
        assertTrue(binding.contains("firstRunOnboardingController.restartEmailAccount(SystemClock::elapsedRealtimeNanos)"))
        val restart = onboardingOwner("restartEmailAccount")
        assertTrue(restart.contains("snapshot.epoch + 1L"))
        assertTrue(restart.contains("snapshot = FirstRunOnboardingPolicy.initialEmailAccount(epoch)"))
        assertTrue(logout.contains("이메일과 비밀번호를 다시 입력해 로그인하세요"))
        assertFalse(logout.contains("resetAfterConfirmedAccountDeletion"))
    }

    @Test
    fun backendMinimumAgeRejectionHasAnExplicitUserMessage() {
        val failure = functionBlock("private fun postAccountFailure(")
        assertTrue(failure.contains("account_enrollment_not_allowed"))
        assertTrue(failure.contains("만 14세 미만은 현재 WalkSafe 계정에 가입할 수 없습니다."))
    }

    @Test
    fun finishedOtpRequestExplainsExplicitFreshRequestRatherThanConnectionFailure() {
        val failure = functionBlock("private fun postAccountFailure(")
        assertInOrder(
            failure,
            "emailOtpRequestRetryPolicy.recordFailure(",
            "serverCode == \"account_enrollment_idempotency_conflict\"",
            "이전 인증 요청을 사용할 수 없습니다.",
            "새 요청으로 시작합니다.",
            "ui.showMessage(message)",
        )
        assertTrue(failure.contains("otpAttempt != null && statusCode == 409 &&"))
    }

    @Test
    fun activityRecreationRestoresIncompleteEmailJitWithoutOpeningProtectedFeatures() {
        val changed = actorSessionChange(source)
        assertTrue(changed.contains("verifiedEmailActorId"))
        assertTrue(changed.contains("firstRun.restoreSessionSnapshot(restoredFirstRun)"))
        assertTrue(changed.contains("appliedFirstRun.isComplete &&"))
        assertTrue(changed.contains("firstRun.effectiveSessionSnapshot(restoredFirstRun)"))
        assertTrue(onboardingOwner("effectiveSessionSnapshot").contains("restored.revision < current.revision"))
        assertTrue(changed.contains("permissions.expireAuthentication()"))
        assertTrue(changed.contains("runtime.refreshActorScreens()"))
        val ports = MainActivityRefactorSource.composition(source, "actorSessionLifecycleCoordinator")
        assertTrue(ports.contains("override fun expireAuthentication() { permissionSessionPolicy.authenticationExpired() }"))
        assertTrue(ports.contains("firstRunSource = { firstRunOnboardingController }"))
        val actorOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/ActorSessionLifecycleCoordinator.kt").readText()
        assertTrue(MainActivityRefactorSource.property(actorOwner, "firstRun").contains("get() = firstRunSource()"))
    }

    @Test
    fun emailAccountProgressUsesTheActualSevenStageFlow() {
        assertTrue(source.contains("const val EMAIL_FIRST_RUN_STAGE_COUNT = 7"))
        assertTrue(MainActivityRefactorSource.composition(source, "firstRunPresentationCoordinator").contains("EMAIL_FIRST_RUN_STAGE_COUNT, FIRST_RUN_STAGE_COUNT"))

        val numbering = functionBlock("private fun firstRunStageNumber(")
        assertInOrder(
            numbering,
            "FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT -> 1",
            "FirstRunOnboardingStage.ACCOUNT_CREATED -> 2",
            "FirstRunOnboardingStage.VERIFIED_LOGIN -> 3",
            "FirstRunOnboardingStage.PURPOSE_AND_SAFETY -> 4",
            "FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION -> 5",
            "FirstRunOnboardingStage.DEVICE_CHECK -> 6",
            "FirstRunOnboardingStage.FP004_TRAINING -> 7",
            "FirstRunOnboardingStage.COMPLETE -> 7",
        )

        val count = functionBlock("private fun firstRunStageCount(")
        assertTrue(count.contains("FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4"))
        assertTrue(count.contains("emailStageCount"))

        val progress = sourceBlock(
            "firstRunProgressBar = LinearLayout(context).apply",
            "firstRunOnboardingStatusText = TextView(context).apply",
            onboardingPanelSource,
        )
        assertTrue(progress.contains("repeat(props.stageCount)"))
        assertTrue(source.contains("stageCount = firstRunStageCount(firstRunOnboardingSnapshot)"))
        val registrationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val registration = MainActivityRefactorSource.member(registrationSource, "bindRegistration")
        assertInOrder(registration, "FirstRunOnboardingPresenter(context, style, panel)",
            "firstRun.publishRegistrationTargets()", "publication.publish(NativeUiPublication.Target.FIRST_RUN_PROGRESS_BAR)")
        assertTrue(MainActivityRefactorSource.composition(source, "nativeRegistrationComposition").contains(
            "NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains(
            "nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(MainActivityRefactorSource.member(firstRunPresenterSource, "renderProgress").contains("firstRunProgressSegments"))

        val update = functionBlock("private fun updateFirstRunOnboardingUi()")
        assertTrue(update.contains("val stageCount = firstRunStageCount(snapshot)"))
        assertTrue(update.contains("effects.renderStage"))
        assertTrue(update.contains("stageNumber = stageNumber"))
        assertTrue(update.contains("stageCount = stageCount"))
        val stageUi = functionBlock("fun renderStage(", firstRunPresenterSource)
        assertTrue(stageUi.contains("val stageNumber = facts.stageNumber"))
        assertTrue(stageUi.contains("val stageCount = facts.stageCount"))
        assertTrue(stageUi.contains("\$stageNumber/\${stageCount}단계"))
        assertFalse(stageUi.contains("\$stageNumber/6"))
        assertFalse(stageUi.contains("\$stageNumber/7"))
        val ports = MainActivityRefactorSource.composition(source, "firstRunPresentationCoordinator")
        assertTrue(ports.contains("firstRunOnboardingPresenter.renderStage(facts())"))
        assertTrue(MainActivityRefactorSource.member(firstRunPresenterSource, "renderProgress").contains("firstRunProgressBar"))
    }

    @Test
    fun existingAccountLoginIsPrimaryAndSignupDetailsAreProgressivelyDisclosed() {
        assertTrue(accountPresenterSource.contains("var accountSignupExpanded = false"))
        assertTrue(accountPresenterSource.contains("var accountConsentDisclosureExpanded = false"))
        assertTrue(accountPanelSource.contains("label = \"회원가입\""))
        assertTrue(accountPanelSource.contains("spokenLabel = \"새 계정 만들기, 가입 입력 펼치기\""))

        val controls = sourceBlock(
            "accountAccessControls = LinearLayout(context).apply",
            "listOf(",
            accountPanelSource,
        )
        assertInOrder(
            controls,
            "addView(style.wsFieldGroup(accountEmailInput, \"이메일\"))",
            "addView(style.wsFieldGroup(accountPasswordInput, \"비밀번호\"",
            "addView(accountLoginButton)",
            "addView(accountSignupToggleButton)",
            "addView(accountSignupControls)",
        )
        assertFalse(source.contains("accountRememberMeCheck"))
        assertFalse(accountPanelSource.contains("accountRememberMeCheck"))
        assertFalse(source.contains("이 기기에서 로그인 유지"))
        assertFalse(accountPanelSource.contains("이 기기에서 로그인 유지"))

        val update = functionBlock("fun updateEmailAccountAccessUi(", accountPresenterSource)
        val authority = functionBlock("private fun updateEmailAccountAccessUi(")
        assertTrue(update.contains("if (verifiedLogin || reauthenticationRequired)"))
        assertTrue(update.contains("val reauthenticationRequired ="))
        assertTrue(authority.contains("liveBinding?.actorId == expectedActorId"))
        assertTrue(update.contains("!reauthenticationRequired"))
        assertTrue(update.contains("accountSignupControls.visibility ="))
        assertTrue(update.contains("accountConsentCards.values.forEach"))
        assertTrue(update.contains("card.visibility = if (onConsentStep) View.VISIBLE else View.GONE"))
        assertTrue(update.contains("accountConsentDisclosureText.visibility = View.GONE"))
        assertTrue(
            update.contains(
                "accountLoginButton.visibility = if (signupVisible) View.GONE else View.VISIBLE",
            ),
        )
        assertTrue(update.contains("로그인 화면으로 돌아가기"))
        assertTrue(authority.contains("FirstRunOnboardingStage.ACCOUNT_CREATED"))
        assertTrue(authority.contains("FirstRunOnboardingStage.VERIFIED_LOGIN"))
        assertTrue(update.contains("if (facts.isComplete)"))
        assertTrue(update.contains("로그인과 첫 실행 등록을 완료했습니다"))
        assertFalse(update.contains("accountEmailInput.text?.clear()"))
        assertFalse(update.contains("accountPasswordInput.text?.clear()"))
    }

    @Test
    fun lostSessionShowsReauthenticationWithoutDiscardingAdvancedProgress() {
        val sessionChange = actorSessionChange(source)
        val update = functionBlock("fun updateEmailAccountAccessUi(", accountPresenterSource)
        val authority = functionBlock("private fun updateEmailAccountAccessUi(")
        val login = functionBlock("private fun loginEmailAccount(")
        val sessionAction = functionBlock("private fun onAccountSessionButtonClicked()")

        assertTrue(sessionChange.contains("if (verifiedActorSession == null)"))
        assertTrue(sessionChange.contains("permissions.reporterActorId = null"))
        assertFalse(sessionChange.contains("firstRun.snapshot = FirstRunOnboardingPolicy.initialEmailAccount("))
        assertFalse(sessionChange.contains("firstRun.restartEmailAccount("))
        assertFalse(sessionChange.contains("firstRun.resetAfterConfirmedAccountDeletion("))
        assertTrue(authority.contains("onboarding.currentDeviceBinding()"))
        assertTrue(update.contains("다시 로그인해 주세요."))
        assertTrue(update.contains("val credentialFieldsVisible = !onConsentStep"))
        assertTrue(update.contains("if (credentialFieldsVisible) View.VISIBLE else View.GONE"))
        assertTrue(update.contains("accountLoginButton.visibility"))
        assertTrue(update.contains("이전 로그인 상태 정리"))
        assertTrue(login.contains("flow.prepareVerifiedLogin(actorBinding, receipt, completedNativeProgress)"))
        assertTrue(onboardingOwner("prepareVerifiedEmailLogin").contains("mayReauthenticateVerifiedEmailActor"))
        assertTrue(login.contains("firstRunSnapshot = nextFirstRunSnapshot"))
        assertTrue(source.contains("onLogout = ::onAccountSessionButtonClicked"))
        assertTrue(accountPanelSource.contains("onClick = actions.onLogout"))
        assertTrue(sessionAction.contains("preservesAdvancedProgress"))
        assertTrue(sessionAction.contains("gateway.clear("))
        assertTrue(sessionAction.contains("온보딩 진도는 유지했습니다"))
        val preservedProgressBranch = sessionAction.substringAfter("val staleSession = process.session")
        assertFalse(preservedProgressBranch.contains("onAccountLogoutClicked()"))
        assertFalse(preservedProgressBranch.contains("onboarding.restartEmailAccount()"))
        assertFalse(preservedProgressBranch.contains("resetAfterConfirmedAccountDeletion("))
        assertFalse(
            sessionAction.contains(
                "firstRun.snapshot = FirstRunOnboardingPolicy.initialEmailAccount(",
            ),
        )
        val ports = MainActivityRefactorSource.composition(source, "accountAccessInteractionOwner")
        assertTrue(ports.contains("override fun currentDeviceBinding() = currentPostLoginDeviceCheckSessionBinding()"))
        assertTrue(ports.contains("gatewaySessionController.clearGatewaySession("))
        MainActivityRefactorSource.composition(source, "actorSessionLifecycleCoordinator")
    }

    @Test
    fun staleSessionCleanupCannotDiscardDeletionRecoveryOrMaskStorageFailure() {
        val update = functionBlock("fun updateEmailAccountAccessUi(", accountPresenterSource)
        val authority = functionBlock("private fun updateEmailAccountAccessUi(")
        val sessionAction = functionBlock("private fun onAccountSessionButtonClicked()")

        assertTrue(sessionAction.contains("process.deletionRecoveryOnly"))
        assertTrue(
            sessionAction.contains(
                "process.session?.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY",
            ),
        )
        assertTrue(sessionAction.contains("if (process.storageBlocked)"))
        assertInOrder(
            sessionAction,
            "process.deletionRecoveryOnly",
            "if (process.storageBlocked)",
            "val preservesAdvancedProgress",
            "val staleSession = process.session",
            "gateway.clear(",
        )
        assertTrue(sessionAction.contains("if (staleSession == null)"))
        assertTrue(sessionAction.contains("expectedSession = staleSession"))
        assertTrue(sessionAction.contains("expectedProcessGeneration = process.generation"))

        val clear = functionBlock("private fun clearGatewaySession(")
        assertTrue(clear.contains("expectedProcessGeneration: Long? = null"))
        assertTrue(clear.contains("beginGeneralSessionOperationIfCurrent("))

        assertTrue(update.contains("val deletionRecoveryBlocksReauthentication ="))
        assertTrue(update.contains("val storageBlocksReauthentication ="))
        assertTrue(update.contains("val staleGeneralSession ="))
        assertTrue(
            update.contains(
                "if (authenticated || staleGeneralSession) View.VISIBLE else View.GONE",
            ),
        )
        assertTrue(update.contains("계정 삭제 복구 전용 로그인이 진행 중입니다"))
        assertTrue(update.contains("로그인 저장소가 안전 차단 상태입니다"))
        assertTrue(MainActivityRefactorSource.composition(source, "accountAccessInteractionOwner").contains("gatewaySessionController.clearGatewaySession("))
        MainActivityRefactorSource.composition(source, "gatewaySessionController")
    }

    @Test
    fun openingSignupClearsOnlyTheDisplayedPasswordLoginFailureNotice() {
        val failure = functionBlock("private fun postAccountFailure(")
        assertInOrder(
            failure,
            "accountRequestFence.completeIfCurrent(",
            "ui.showMessage(message)",
            "ui.setPasswordLoginFailureNotice(",
            "message.takeIf { token.action == AccountRemoteAction.PASSWORD_LOGIN }",
        )
        val message = functionBlock("fun recordAccountMessage(", accountPresenterSource)
        assertInOrder(message, "passwordLoginFailureNotice = null", "accountAccessNotice = message")
        assertTrue(functionBlock("private fun showAccountMessage(").contains("accountAccessPresenter.recordAccountMessage("))

        val toggle = functionBlock("fun completeSignupToggle(", accountPresenterSource)
        assertTrue(accountPresenterSource.contains("fun nextSignupExpanded(): Boolean = !accountSignupExpanded"))
        assertInOrder(
            toggle,
            "if (opening && passwordLoginFailureNotice != null &&",
            "accountAccessNotice == passwordLoginFailureNotice",
            "accountAccessNotice = null",
            "passwordLoginFailureNotice = null",
            "accountSignupExpanded = opening",
            "actions.refreshAccessUi()",
        )
        val wiring = sourceBlock("onSignupToggle = {", "onDisclosureToggle = {")
        assertInOrder(
            wiring,
            "accountAccessPresenter.nextSignupExpanded()",
            "emailAccountController.signupClosed()",
            "accountAccessPresenter.completeSignupToggle(",
            "accountAccessPresenter.focusOpenedSignup(",
        )
        assertTrue(MainActivityRefactorSource.member(MainActivityRefactorSource.email, "signupClosed")
            .contains("emailOtpRequestRetryPolicy.cancel()"))
        assertFalse(toggle.contains("emailEnrollmentStorageBlocked ="))
        assertFalse(toggle.contains("storage.clear()"))
        assertFalse(toggle.contains("emailEnrollmentPartial ="))
        assertFalse(toggle.contains("GatewaySessionProcessCoordinator."))
        assertFalse(toggle.contains("accountRequestFence.cancel("))
        assertFalse(toggle.contains("check.isChecked ="))
    }

    @Test
    fun accountPanelBindingsAndActionsPrecedeRestoredConsentAndAvailability() {
        val composition = sourceBlock("val accountPanel = AccountAccessPanel(", "onboardingPanel.setAccountContent(accountAccessPresenter.rootTarget())")
        assertInOrder(
            composition,
            "onRequestOtp = ::requestEmailAccountOtp",
            "onCreateAccount = ::createEmailAccount",
            "onLogin = {",
            "loginEmailAccount()",
            "nativeRegistrationComposition.bindAccountInputs(accountPanel)",
            "stopAccountConsentSpeech()",
            "resetAccountConsentSpeechContextAfterBinding()",
            "nativeRegistrationComposition.publishAccountConsentAndRoot()",
        )
        val restoredConsent = composition.substringAfter(
            "nativeRegistrationComposition.publishAccountConsentAndRoot()",
        )
        assertInOrder(
            restoredConsent,
            "emailEnrollmentPartial?.selections?.let(::applyAccountConsentSelections)",
            "refreshAccountConsentSummary()",
            "refreshAccountActionAvailability()",
        )
        assertTrue(accountPanelSource.contains("onClick = actions.onRequestOtp"))
        assertTrue(accountPanelSource.contains("onClick = actions.onCreateAccount"))
        assertTrue(accountPanelSource.contains("onClick = actions.onLogin"))
        assertTrue(accountPanelSource.contains("target.error = null"))
        assertTrue(accountPanelSource.contains("actions.onInputChanged()"))
        assertFalse(source.contains("forEach(::bindAccountInputValidation)"))
        val registrationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val binding = MainActivityRefactorSource.member(registrationSource, "bindAccountInputs")
        assertInOrder(binding, "accountSource().bindPanel(panel)",
            "publication.publish(NativeUiPublication.Target.ACCOUNT_EMAIL_INPUT)",
            "publication.publish(NativeUiPublication.Target.ACCOUNT_PASSWORD_INPUT)",
            "accountSource().publishInputTargets()")
        val root = MainActivityRefactorSource.member(registrationSource, "publishAccountConsentAndRoot")
        assertInOrder(root, "accountSource().publishRootTarget()",
            "publication.publish(NativeUiPublication.Target.ACCOUNT_ACCESS_CONTROLS)")
        assertTrue(MainActivityRefactorSource.composition(source, "nativeRegistrationComposition").contains(
            "NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.member(source, "currentAccountConsentSelections").contains("accountAccessPresenter.currentAccountConsentSelections()"))
        assertTrue(MainActivityRefactorSource.member(accountPresenterSource, "currentAccountConsentSelections").contains("panel?.accountConsentChecks"))
        assertTrue(source.contains("get() = accountAccessPresenter.accountSignupExpanded"))
        assertTrue(source.contains("set(value) { accountAccessPresenter.accountSignupExpanded = value }"))
        assertTrue(source.contains("accountAccessPresenter.onInputChanged(firstRunOnboardingController.initialized)"))
        assertTrue(accountPresenterSource.contains("accountInputErrorActive = false"))
        assertTrue(accountPresenterSource.contains("if (snapshotInitialized) actions.refreshAccessUi()"))
        assertTrue(functionBlock("fun rootTarget(", accountPresenterSource).contains("publishedRoot.accountAccessControls"))
        assertTrue(functionBlock("fun publishRootTarget(", accountPresenterSource).contains("publishedRoot = checkNotNull(panel)"))
        assertTrue(functionBlock("fun publishInputTargets(", accountPresenterSource).contains("publishedInputs = checkNotNull(panel)"))
        val inputs = functionBlock("fun inputTarget(", accountPresenterSource)
        assertTrue(inputs.contains("EmailAccountController.InputField.EMAIL -> publishedInputs.accountEmailInput"))
        assertTrue(inputs.contains("EmailAccountController.InputField.PASSWORD -> publishedInputs.accountPasswordInput"))
        val emailFactory = MainActivityRefactorSource.factory(source, "createEmailAccountController")
        assertTrue(emailFactory.contains("inputs = object : EmailAccountController.Inputs"))
        assertTrue(emailFactory.contains("override fun text(field: EmailAccountController.InputField) = this@MainActivity.accountAccessPresenter.inputTarget(field).text?.toString().orEmpty()"))
    }

    private fun assertInOrder(source: String, vararg markers: String) {
        var previous = -1
        markers.forEach { marker ->
            val current = source.indexOf(marker)
            assertTrue("Missing or out of order: $marker", current > previous)
            previous = current
        }
    }

    private fun sourceBlock(
        startMarker: String,
        endMarker: String,
        ownerSource: String = source,
    ): String {
        val start = ownerSource.indexOf(startMarker)
        require(start >= 0) { "missing start marker: $startMarker" }
        val end = ownerSource.indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "missing end marker after $startMarker: $endMarker" }
        return ownerSource.substring(start, end)
    }

    private fun functionBlock(signature: String, ownerSource: String = source): String =
        MainActivityRefactorSource.function(ownerSource, signature)

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


    private fun onboardingOwner(name: String): String {
        MainActivityRefactorSource.composition(source, "firstRunOnboardingController")
        return MainActivityRefactorSource.member(java.io.File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/onboarding/FirstRunOnboardingController.kt",
        ).readText(), name)
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
