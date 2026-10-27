package kr.co.hanium.dreamup.walksafe.session.account

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.account.AccountRemoteAction
import kr.co.hanium.dreamup.walksafe.account.AccountRequestFence
import kr.co.hanium.dreamup.walksafe.account.AccountRequestToken
import kr.co.hanium.dreamup.walksafe.account.EmailEnrollmentPartial
import kr.co.hanium.dreamup.walksafe.account.EmailEnrollmentRestoreResult
import kr.co.hanium.dreamup.walksafe.account.EmailOtpDeadline
import kr.co.hanium.dreamup.walksafe.account.SignupConsentSelections
import kr.co.hanium.dreamup.walksafe.account.SIGNUP_DOCUMENT_VERSIONS
import kr.co.hanium.dreamup.walksafe.account.SignupPolicyPreflight
import kr.co.hanium.dreamup.walksafe.network.CreatedEmailAccount
import kr.co.hanium.dreamup.walksafe.network.EmailOtpEnrollmentResponse
import kr.co.hanium.dreamup.walksafe.network.EmailOtpRequestRetryPolicy
import kr.co.hanium.dreamup.walksafe.network.GatewayAccountHttpException
import kr.co.hanium.dreamup.walksafe.network.GatewayAccountInputPolicy
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionHttpException
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionStoreResult
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionVersion
import kr.co.hanium.dreamup.walksafe.network.PendingEmailOtpRequestStore
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingFlow
import kr.co.hanium.dreamup.walksafe.session.onboarding.FirstRunOnboardingController
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueActorBinding
import kr.co.hanium.dreamup.walksafe.session.FirstRunReceiptHash

/** Owns email enrollment and its asynchronous requests; construction has no external effects. */
internal class EmailAccountController(
    private val services: Services,
    private val flow: Flow,
    private val inputs: Inputs,
    private val readers: Readers,
    private val ui: Ui,
    private val effects: VerifiedEffects,
    private val executor: Executor,
    private val postToMain: (() -> Unit) -> Unit,
    private val elapsedRealtimeMs: () -> Long,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    createRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    enum class InputField { EMAIL, DATE_OF_BIRTH, PASSWORD, PASSWORD_CONFIRMATION, OTP }

    data class EnrollmentState(val partial: EmailEnrollmentPartial?, val storageBlocked: Boolean, val unconfirmedCreation: Boolean)

    interface EnrollmentStorage : PendingEmailOtpRequestStore {
        fun restore(nowEpochMs: Long, expectedOwnerBindingSha256: String): EmailEnrollmentRestoreResult
        fun save(partial: EmailEnrollmentPartial): Boolean
        fun clear(): Boolean
        fun resetAfterConfirmedAccountDeletion(): Boolean
        fun hasUnconfirmedCreation(ownerBindingSha256: String): Boolean
        fun markCreationPending(ownerBindingSha256: String): Boolean
        fun clearUnconfirmedCreation(ownerBindingSha256: String): Boolean
    }

    interface Services {
        fun installationDeviceId(): String?
        fun signupUnavailableOptionalDocuments(gatewayBaseUrl: String): Set<String>
        fun requestEmailOtp(gatewayBaseUrl: String, email: String, dateOfBirth: String, requestId: String): EmailOtpEnrollmentResponse
        fun createAccount(gatewayBaseUrl: String, enrollmentHandle: String, otpCode: String, password: String, selections: SignupConsentSelections, documentVersions: Map<String, String>): CreatedEmailAccount
        fun loginWithPassword(gatewayBaseUrl: String, email: String, password: String, rememberMe: Boolean, deviceId: String, enableLongLivedSession: Boolean = false): GatewayFieldSession
        fun retirePasswordLogin(session: GatewayFieldSession, operationId: String): GatewaySessionStoreResult
        fun logout(session: GatewayFieldSession)
        fun saveBackendDeviceIfAbsent(session: GatewayFieldSession, firstRunSnapshot: FirstRunOnboardingSnapshot, expectedGatewayBaseUrl: String): GatewaySessionStoreResult
        fun removeBackendDeviceIfCurrent(expectedVersion: GatewaySessionVersion): GatewaySessionStoreResult
    }

    interface Flow {
        val snapshot: FirstRunOnboardingSnapshot
        fun restartEmailAccount()
        fun recordOtpEnrollment(receipt: FirstRunReceiptHash): Boolean
        fun recordAccountCreated(receipt: FirstRunReceiptHash, clearEnrollment: () -> Boolean): Boolean
        fun prepareVerifiedLogin(actor: FirstRunOpaqueActorBinding, receipt: FirstRunReceiptHash,
            completed: FirstRunOnboardingSnapshot?): FirstRunOnboardingController.PreparedEmailLogin?
        fun applyVerifiedLogin(prepared: FirstRunOnboardingController.PreparedEmailLogin): Boolean
        fun completedNativeProgress(actorId: String): FirstRunOnboardingSnapshot?
    }

    interface Inputs {
        fun text(field: InputField): String
        fun consentSelections(): SignupConsentSelections
        fun signupExpanded(): Boolean
        fun displayedSignupDocumentVersions(): Map<String, String>?
    }

    data class Readers(
        val gatewayOrigin: () -> String?,
        val networkAvailable: () -> Boolean,
        val transitionAllowed: () -> Boolean,
        val backendRestorePending: () -> Boolean,
        val gatewaySession: () -> GatewayFieldSession?,
        val developmentQuickStartEnabled: () -> Boolean,
    )

    interface Ui {
        fun showMessage(message: String)
        fun showInputError(field: InputField, message: String)
        fun showMissingRequiredConsentError()
        fun clearAccessNotice()
        fun setPasswordLoginFailureNotice(message: String?)
        fun refresh()
        fun revealSignup()
        fun applyConsentSelections(selections: SignupConsentSelections)
        fun showUnavailableOptionalSignupDocuments(keys: Set<String>)
        fun speakInteraction(message: String)
        fun focusOtp()
        fun clearInputs(fields: List<InputField>)
        fun onFirstRunStateChanged(message: String)
        fun updateStatus(message: String)
    }

    /** Effects on other account/session owners stay explicit and run inline at the original point. */
    interface VerifiedEffects {
        fun restoreGatewaySession()
        fun refreshConfirmedConsent()
        fun noteDevelopmentQuickStartActor(actorId: String)
        fun onBackendRestoreVerified()
        fun applyVerifiedAccount(actorId: String, reauthenticatesExistingProgress: Boolean)
    }

    private lateinit var storage: EnrollmentStorage
    private var emailEnrollmentPartial: EmailEnrollmentPartial? = null
    private var emailEnrollmentOwnerBindingSha256: String? = null
    private var emailEnrollmentStorageBlocked = false
    private var unconfirmedCreation = false
    private val accountRequestFence = AccountRequestFence()
    private val emailOtpRequestRetryPolicy = EmailOtpRequestRetryPolicy(
        elapsedRealtimeMs = elapsedRealtimeMs,
        createRequestId = createRequestId,
        pendingStore = {
            if (::storage.isInitialized && !emailEnrollmentStorageBlocked) storage else null
        },
        wallClockMs = wallClockMs,
    )
    // S01-9: wall-clock changes do not move the deadline received in this process.
    private var emailOtpDeadline: EmailOtpDeadline? = null

    fun initializeStorage(storage: EnrollmentStorage) {
        check(!this::storage.isInitialized)
        this.storage = storage
    }

    fun enrollmentState(): EnrollmentState = EnrollmentState(
        emailEnrollmentPartial, emailEnrollmentStorageBlocked,
        unconfirmedCreation || (::storage.isInitialized &&
            emailEnrollmentOwnerBindingSha256?.let(storage::hasUnconfirmedCreation) == true),
    )
    fun isRequestInFlight(): Boolean = accountRequestFence.isInFlight()
    fun enteredForeground() = accountRequestFence.enteredForeground()
    fun enteredBackground() {
        accountRequestFence.enteredBackground()
        emailOtpRequestRetryPolicy.suspendForLifecycle()
    }
    fun activityDestroyed() = accountRequestFence.enteredBackground()
    fun signupClosed() = emailOtpRequestRetryPolicy.cancel()

    fun resetAfterConfirmedAccountDeletion() {
        emailEnrollmentPartial = null
        unconfirmedCreation = false
        emailEnrollmentStorageBlocked =
            !::storage.isInitialized || !storage.resetAfterConfirmedAccountDeletion()
        emailEnrollmentOwnerBindingSha256 = services.installationDeviceId()
            ?.let { sha256Hex(it.toByteArray(Charsets.UTF_8)) }
        if (emailEnrollmentOwnerBindingSha256 == null) emailEnrollmentStorageBlocked = true
    }

    fun restoreEmailEnrollmentPartialAtStartup() {
        val installBinding = services.installationDeviceId()
        if (installBinding == null) {
            emailEnrollmentStorageBlocked = true
            return
        }
        val ownerSha256 = sha256Hex(installBinding.toByteArray(Charsets.UTF_8))
        emailEnrollmentOwnerBindingSha256 = ownerSha256
        unconfirmedCreation = storage.hasUnconfirmedCreation(ownerSha256)
        if (unconfirmedCreation) ui.showMessage(UNCONFIRMED_CREATION_MESSAGE)
        when (
            val restored = storage.restore(
                nowEpochMs = wallClockMs(),
                expectedOwnerBindingSha256 = ownerSha256,
            )
        ) {
            EmailEnrollmentRestoreResult.Absent,
            EmailEnrollmentRestoreResult.ClearedInvalid,
            -> Unit
            EmailEnrollmentRestoreResult.Blocked ->
                emailEnrollmentStorageBlocked = true
            is EmailEnrollmentRestoreResult.Restored -> {
                if (flow.recordOtpEnrollment(emailOtpEnrollmentReceipt(restored.partial))) {
                    emailEnrollmentPartial = restored.partial
                    ui.revealSignup()
                } else {
                    emailEnrollmentStorageBlocked = !storage.clear()
                }
            }
        }
    }

    // S01-9: set when this process received the OTP; wall-clock changes do not move it.

    private fun emailOtpPartialStillValid(
        partial: EmailEnrollmentPartial,
        ownerBinding: String,
    ): Boolean {
        val deadline = emailOtpDeadline?.takeIf { it.expiresAtEpochMs == partial.expiresAtEpochMs }
            ?: return partial.isValidAt(wallClockMs(), ownerBinding)
        return partial.ownerBindingSha256 == ownerBinding &&
            deadline.isOpenAt(elapsedRealtimeMs())
    }

    private fun emailOtpEnrollmentReceipt(
        partial: EmailEnrollmentPartial,
    ): FirstRunReceiptHash = FirstRunReceiptHash.fromSha256Hex(
        sha256Hex(
            (
                "email-otp-enrollment-v4|${partial.enrollmentHandle}|" +
                    "${partial.expiresAtEpochMs}"
            ).toByteArray(Charsets.UTF_8),
        ),
    )

    private fun accountStateBinding(): String =
        "${flow.snapshot.epoch}:${flow.snapshot.revision}:" +
            flow.snapshot.stage.name +
            emailEnrollmentPartial?.let { ":${it.enrollmentHandle}" }.orEmpty()

    private fun currentEmailOtpRetryPayload():
        kr.co.hanium.dreamup.walksafe.network.EmailOtpRequestRetryPolicy.Payload? {
        if (!inputs.signupExpanded() ||
            flow.snapshot.flow != FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 ||
            flow.snapshot.stage != FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT) {
            return null
        }
        val origin = readers.gatewayOrigin() ?: return null
        val owner = emailEnrollmentOwnerBindingSha256 ?: return null
        return kr.co.hanium.dreamup.walksafe.network.EmailOtpRequestRetryPolicy.Payload(
            gatewayBaseUrl = origin.trimEnd('/'),
            ownerBindingSha256 = owner,
            signupAttempt = flow.snapshot.epoch.toString(),
            email = inputs.text(InputField.EMAIL),
            dateOfBirth = inputs.text(InputField.DATE_OF_BIRTH),
        )
    }

    fun requestEmailAccountOtp() {
        if (!readers.transitionAllowed()) return
        if (
            flow.snapshot.flow != FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 ||
            flow.snapshot.stage != FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT ||
            emailEnrollmentStorageBlocked ||
            !readers.networkAvailable()
        ) {
            ui.showMessage("인터넷 연결과 가입 임시 저장소를 확인하세요.")
            return
        }
        val displayedVersions = displayedSignupVersionsOrNull() ?: run {
            ui.showMessage("표시한 가입 약관을 확인할 수 없습니다. 약관을 다시 열어 확인하세요.")
            return
        }
        val email = inputs.text(InputField.EMAIL)
        val dateOfBirth = inputs.text(InputField.DATE_OF_BIRTH)
        val password = inputs.text(InputField.PASSWORD)
        val confirmation = inputs.text(InputField.PASSWORD_CONFIRMATION)
        val selections = inputs.consentSelections()
        if (!GatewayAccountInputPolicy.validEmail(email)) {
            ui.showInputError(InputField.EMAIL, "이메일 형식을 확인하세요.")
            return
        }
        if (!GatewayAccountInputPolicy.validDateOfBirth(dateOfBirth)) {
            ui.showInputError(
                InputField.DATE_OF_BIRTH,
                "생년월일을 20020101 또는 2002-01-01 형식으로 정확히 입력하세요.",
            )
            return
        }
        if (!GatewayAccountInputPolicy.validNewPassword(password)) {
            ui.showInputError(InputField.PASSWORD, "비밀번호는 12자 이상 128자 이하로 입력하세요.")
            return
        }
        if (password != confirmation) {
            ui.showInputError(InputField.PASSWORD_CONFIRMATION, "비밀번호 확인이 일치하지 않습니다.")
            return
        }
        if (!selections.requiredGranted) {
            ui.showMissingRequiredConsentError()
            return
        }
        val ownerBinding = emailEnrollmentOwnerBindingSha256
        val gatewayOrigin = readers.gatewayOrigin()
        if (ownerBinding == null || gatewayOrigin == null) {
            ui.showMessage("가입 보안 설정을 확인할 수 없습니다.")
            return
        }
        val otpPayload = currentEmailOtpRetryPayload() ?: return
        val retryWaitMs = emailOtpRequestRetryPolicy.remainingWaitMs(otpPayload)
        if (retryWaitMs > 0L) {
            ui.showMessage(
                "인증번호를 다시 요청하려면 " + ((retryWaitMs + 999L) / 1_000L) +
                    "초 기다린 뒤 요청 버튼을 누르세요. 자동으로 재전송하지 않습니다.",
            )
            ui.refresh()
            return
        }
        ui.clearAccessNotice()
        val token = accountRequestFence.begin(
            AccountRemoteAction.REQUEST_EMAIL_OTP,
            accountStateBinding(),
        ) ?: return
        val otpAttempt = emailOtpRequestRetryPolicy.begin(otpPayload)
        if (otpAttempt == null) {
            accountRequestFence.cancel(token)
            ui.showMessage(if (emailOtpRequestRetryPolicy.storageFailure) {
                "인증 요청 복구 정보를 안전하게 저장하지 못해 발송하지 않았습니다. 저장 상태를 확인하세요."
            } else "이메일 인증 요청을 처리 중입니다. 결과를 확인한 뒤 다시 요청하세요.")
            ui.refresh()
            return
        }
        ui.refresh()
        try {
            executor.execute {
                try {
                    val unavailableOptional = services.signupUnavailableOptionalDocuments(gatewayOrigin)
                    val restricted = SignupPolicyPreflight.restrict(selections, unavailableOptional)
                    if (restricted != selections) {
                        postUnavailableOptionalDocuments(token, unavailableOptional, restricted, otpAttempt)
                        return@execute
                    }
                    val response = services.requestEmailOtp(
                        gatewayBaseUrl = gatewayOrigin,
                        email = email,
                        dateOfBirth = dateOfBirth,
                        requestId = otpAttempt.requestId,
                    )
                    postToMain {
                        if (!accountRequestFence.completeIfCurrent(token, accountStateBinding())) {
                            return@postToMain
                        }
                        if (inputs.displayedSignupDocumentVersions() != displayedVersions ||
                            inputs.consentSelections() != selections) {
                            emailOtpRequestRetryPolicy.suspendForLifecycle()
                            ui.showMessage("가입 동의 상태가 변경되어 인증 결과를 적용하지 않았습니다. 다시 확인하세요.")
                            ui.refresh()
                            return@postToMain
                        }
                        val partial = runCatching {
                            EmailEnrollmentPartial(
                                enrollmentHandle = response.enrollmentHandle,
                                expiresAtEpochMs = response.expiresAtEpochMs,
                                resendAvailableAtEpochMs = response.resendAvailableAtEpochMs,
                                ownerBindingSha256 = ownerBinding,
                                documentVersions = displayedVersions,
                                selections = selections,
                            )
                        }.getOrNull()
                        if (!emailOtpRequestRetryPolicy.recordSuccess(
                                otpAttempt, currentEmailOtpRetryPayload(),
                                persistResult = { partial != null && storage.save(partial) },
                            )) {
                            emailEnrollmentStorageBlocked = emailOtpRequestRetryPolicy.storageFailure
                            ui.showMessage("인증 상태를 안전하게 저장하지 못해 가입을 중단했습니다.")
                            ui.refresh()
                            return@postToMain
                        }
                        if (partial == null) return@postToMain
                        ui.showUnavailableOptionalSignupDocuments(unavailableOptional)
                        if (!flow.recordOtpEnrollment(emailOtpEnrollmentReceipt(partial))) {
                            storage.clear()
                            ui.showMessage("가입 단계가 변경되어 인증번호를 적용하지 않았습니다.")
                            ui.refresh()
                            return@postToMain
                        }
                        emailEnrollmentPartial = partial
                        emailOtpDeadline = kr.co.hanium.dreamup.walksafe.account.EmailOtpDeadline.received(
                            expiresAtEpochMs = partial.expiresAtEpochMs,
                            receivedAtEpochMs = wallClockMs(),
                            receivedAtElapsedMs = elapsedRealtimeMs(),
                        )
                        ui.revealSignup()
                        ui.applyConsentSelections(selections)
                        ui.refresh()
                        ui.speakInteraction("이메일 인증번호를 보냈습니다. 인증번호를 입력하세요.")
                        ui.focusOtp()
                    }
                } catch (error: GatewayAccountHttpException) {
                    postAccountFailure(
                        token, error.serverCode, error.statusCode, error.retryAfterMs, otpAttempt,
                    )
                } catch (_: Exception) {
                    postAccountFailure(token, null, 0, otpAttempt = otpAttempt)
                }
            }
        } catch (_: RejectedExecutionException) {
            accountRequestFence.cancel(token)
            emailOtpRequestRetryPolicy.recordFailure(
                otpAttempt, currentEmailOtpRetryPayload(), 0, null, null,
            )
            ui.showMessage("계정 요청을 시작할 수 없습니다. 잠시 후 다시 시도하세요.")
            ui.refresh()
        }
    }

    fun createEmailAccount() {
        if (!readers.transitionAllowed()) return
        val partial = emailEnrollmentPartial
        val ownerBinding = emailEnrollmentOwnerBindingSha256
        if (
            flow.snapshot.flow != FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 ||
            flow.snapshot.stage != FirstRunOnboardingStage.ACCOUNT_CREATED ||
            partial == null ||
            ownerBinding == null ||
            !emailOtpPartialStillValid(partial, ownerBinding)
        ) {
            storage.clear()
            emailEnrollmentPartial = null
            flow.restartEmailAccount()
            ui.showMessage("인증번호가 만료되었거나 안전하게 복원되지 않아 다시 발급해야 합니다.")
            ui.refresh()
            return
        }
        val email = inputs.text(InputField.EMAIL)
        val otp = inputs.text(InputField.OTP)
        val password = inputs.text(InputField.PASSWORD)
        val confirmation = inputs.text(InputField.PASSWORD_CONFIRMATION)
        val selections = inputs.consentSelections()
        if (!GatewayAccountInputPolicy.validEmail(email)) {
            ui.showInputError(
                InputField.EMAIL,
                "계정 생성 뒤 로그인에 사용할 이메일을 다시 입력하세요.",
            )
            return
        }
        if (!Regex("^[0-9]{6}$").matches(otp)) {
            ui.showInputError(InputField.OTP, "이메일 인증번호 6자리를 확인하세요.")
            return
        }
        if (!GatewayAccountInputPolicy.validNewPassword(password)) {
            ui.showInputError(
                InputField.PASSWORD,
                "비밀번호는 12자 이상 128자 이하로 입력하세요.",
            )
            return
        }
        if (password != confirmation) {
            ui.showInputError(
                InputField.PASSWORD_CONFIRMATION,
                "비밀번호 확인이 일치하지 않습니다.",
            )
            return
        }
        if (!selections.requiredGranted) {
            ui.showMissingRequiredConsentError()
            return
        }
        // A restored durable partial already contains the versions displayed before the restart.
        // Freshly visible documents may disagree, but absence in this process does not erase that evidence.
        val currentlyDisplayed = inputs.displayedSignupDocumentVersions()
        if (currentlyDisplayed != null && currentlyDisplayed != partial.documentVersions) {
            ui.showMessage("가입 약관 표시 상태가 바뀌었습니다. 약관을 다시 확인하고 인증번호를 요청하세요.")
            return
        }
        val updatedPartial = partial.copy(selections = selections)
        if (!storage.save(updatedPartial)) {
            emailEnrollmentStorageBlocked = true
            ui.showMessage("가입 동의 상태를 안전하게 저장하지 못했습니다.")
            ui.refresh()
            return
        }
        emailEnrollmentPartial = updatedPartial
        val gatewayOrigin = readers.gatewayOrigin()
        if (gatewayOrigin == null || !readers.networkAvailable()) {
            ui.showMessage("인터넷 연결과 게이트웨이 주소를 확인하세요.")
            return
        }
        ui.clearAccessNotice()
        val token = accountRequestFence.begin(
            AccountRemoteAction.CREATE_ACCOUNT,
            accountStateBinding(),
        ) ?: return
        ui.refresh()
        try {
            executor.execute {
                try {
                    val unavailableOptional = services.signupUnavailableOptionalDocuments(gatewayOrigin)
                    val restricted = SignupPolicyPreflight.restrict(selections, unavailableOptional)
                    if (restricted != selections) {
                        postUnavailableOptionalDocuments(token, unavailableOptional, restricted)
                        return@execute
                    }
                    if (!storage.markCreationPending(ownerBinding)) {
                        postToMain {
                            if (!accountRequestFence.completeIfCurrent(token, accountStateBinding())) return@postToMain
                            emailEnrollmentStorageBlocked = true
                            ui.showMessage("가입 요청 복구 상태를 안전하게 저장하지 못해 요청하지 않았습니다.")
                            ui.refresh()
                        }
                        return@execute
                    }
                    val created = services.createAccount(
                        gatewayBaseUrl = gatewayOrigin,
                        enrollmentHandle = updatedPartial.enrollmentHandle,
                        otpCode = otp,
                        password = password,
                        selections = selections,
                        documentVersions = updatedPartial.documentVersions,
                    )
                    postToMain {
                        if (!accountRequestFence.completeIfCurrent(token, accountStateBinding())) {
                            return@postToMain
                        }
                        if (!storage.clearUnconfirmedCreation(ownerBinding)) {
                            emailEnrollmentStorageBlocked = true
                            ui.showMessage("계정 생성 결과는 확인됐지만 복구 상태를 정리하지 못했습니다. 로그인으로 확인하세요.")
                            ui.refresh()
                            return@postToMain
                        }
                        unconfirmedCreation = false
                        if (!flow.recordAccountCreated(
                                FirstRunReceiptHash.fromSha256Hex(created.signupReceiptSha256), storage::clear,
                            )) {
                            emailEnrollmentStorageBlocked = true
                            ui.showMessage("계정은 생성됐지만 로컬 가입 상태를 정리하지 못했습니다. 앱을 다시 시작하지 말고 로그인하세요.")
                            ui.refresh()
                            return@postToMain
                        }
                        emailEnrollmentPartial = null
                        ui.clearInputs(listOf(InputField.OTP))
                        ui.clearInputs(listOf(InputField.PASSWORD_CONFIRMATION))
                        ui.refresh()
                        loginEmailAccount(
                            emailOverride = email,
                            passwordOverride = password,
                            expectedCreatedActorId = created.actorId,
                        )
                    }
                } catch (error: GatewayAccountHttpException) {
                    postAccountFailure(token, error.serverCode, error.statusCode, error.retryAfterMs)
                } catch (_: Exception) {
                    postAccountFailure(token, null, 0)
                }
            }
        } catch (_: RejectedExecutionException) {
            accountRequestFence.cancel(token)
            ui.showMessage("계정 생성을 시작할 수 없습니다. 잠시 후 다시 시도하세요.")
            ui.refresh()
        }
    }

    fun loginEmailAccount(
        emailOverride: String? = null,
        passwordOverride: String? = null,
        expectedCreatedActorId: String? = null,
    ) {
        if (readers.backendRestorePending()) {
            effects.restoreGatewaySession()
            ui.refresh()
            return
        }
        if (!readers.transitionAllowed()) return
        val email = emailOverride ?: inputs.text(InputField.EMAIL)
        val password = passwordOverride ?: inputs.text(InputField.PASSWORD)
        if (!GatewayAccountInputPolicy.validEmail(email)) {
            ui.showInputError(InputField.EMAIL, "이메일 형식을 확인하세요.")
            return
        }
        if (!GatewayAccountInputPolicy.validPassword(password)) {
            ui.showInputError(
                InputField.PASSWORD,
                "비밀번호는 10자 이상 128자 이하로 입력하세요.",
            )
            return
        }
        if (!readers.networkAvailable() || readers.gatewaySession() != null) {
            ui.showMessage("인터넷 연결 또는 현재 로그인 상태를 확인하세요.")
            return
        }
        if (flow.snapshot.stage == FirstRunOnboardingStage.ACCOUNT_CREATED) {
            if (!storage.clear()) {
                emailEnrollmentStorageBlocked = true
                ui.showMessage("가입 임시 상태를 정리하지 못해 로그인하지 않았습니다.")
                return
            }
            emailEnrollmentPartial = null
            flow.restartEmailAccount()
        }
        val gatewayOrigin = readers.gatewayOrigin()
        if (gatewayOrigin == null) {
            ui.showMessage("게이트웨이 주소를 확인할 수 없습니다.")
            return
        }
        val installationDeviceId = services.installationDeviceId()
        if (installationDeviceId == null) {
            ui.showMessage("이 기기의 로그인 결속 정보를 준비하지 못했습니다.")
            return
        }
        ui.clearAccessNotice()
        val token = accountRequestFence.begin(
            AccountRemoteAction.PASSWORD_LOGIN,
            accountStateBinding(),
        ) ?: return
        val operation = GatewaySessionProcessCoordinator.beginOperation()
        if (operation == null) {
            accountRequestFence.cancel(token)
            ui.showMessage("다른 로그인 확인이 진행 중입니다. 잠시 후 다시 시도하세요.")
            return
        }
        fun applyLogin(session: GatewayFieldSession) {
        val current = accountRequestFence.isCurrent(
            token,
            accountStateBinding(),
        ) && GatewaySessionProcessCoordinator.snapshot().inFlightOperationId ==
            operation.operationId
        if (!current || expectedCreatedActorId?.let { it != session.actorId } == true) {
            runCatching { services.logout(session) }
            GatewaySessionProcessCoordinator.clear(operation)
            ui.refresh()
            return
        }
        val actorBinding =
            FirstRunOpaqueActorBinding.fromProvider(session.actorId)
        val completedNativeProgress = flow.completedNativeProgress(session.actorId)
        if (!session.isLongLived && completedNativeProgress != null &&
            kr.co.hanium.dreamup.walksafe.BuildConfig.WALKSAFE_LONG_LIVED_LOGIN_ENABLED) {
            try {
                executor.execute {
                    try {
                        fun current() = accountRequestFence.isCurrent(token, token.stateBinding) &&
                            GatewaySessionProcessCoordinator.snapshot().inFlightOperationId == operation.operationId
                        if (!current()) {
                            runCatching { services.logout(session) }
                            GatewaySessionProcessCoordinator.clear(operation)
                            return@execute
                        }
                        services.logout(session)
                        if (!current()) { GatewaySessionProcessCoordinator.clear(operation); return@execute }
                        val longSession = services.loginWithPassword(gatewayOrigin, email, password, true,
                            installationDeviceId, enableLongLivedSession = true)
                        if (!longSession.isLongLived || longSession.actorId != session.actorId ||
                            longSession.backendAccountGeneration != session.backendAccountGeneration) {
                            runCatching { services.logout(longSession) }
                            throw GatewaySessionHttpException(0, "gateway_actor_binding_failed")
                        }
                        postToMain { applyLogin(longSession) }
                    } catch (error: GatewaySessionHttpException) {
                        GatewaySessionProcessCoordinator.clear(operation)
                        postAccountFailure(token, error.serverCode, error.statusCode)
                    } catch (_: Exception) {
                        GatewaySessionProcessCoordinator.clear(operation)
                        postAccountFailure(token, null, 0)
                    }
                }
            } catch (_: RejectedExecutionException) {
                GatewaySessionProcessCoordinator.clear(operation)
                accountRequestFence.cancel(token)
                ui.showMessage("장기 로그인을 시작하지 못했습니다. 다시 로그인하세요.")
                ui.refresh()
            }
            return
        }
        if (!accountRequestFence.completeIfCurrent(token, accountStateBinding())) {
            runCatching { services.logout(session) }
            GatewaySessionProcessCoordinator.clear(operation)
            return
        }
        val receipt = FirstRunReceiptHash.fromSha256Hex(
            sha256Hex(
                (
                    "email-password-session-v4|${session.actorId}|" +
                        "${flow.snapshot.epoch}"
                ).toByteArray(Charsets.UTF_8),
            ),
        )
        val preparedLogin = flow.prepareVerifiedLogin(actorBinding, receipt, completedNativeProgress)
        if (preparedLogin == null) {
            runCatching { services.logout(session) }
            GatewaySessionProcessCoordinator.clear(operation)
            ui.showMessage("로그인 단계가 변경되어 결과를 적용하지 않았습니다.")
            ui.refresh()
            return
        }
        val nextFirstRunSnapshot = preparedLogin.snapshot
        val reauthenticatesExistingProgress = preparedLogin.reauthenticatesExistingProgress
        if (readers.developmentQuickStartEnabled()) {
            effects.noteDevelopmentQuickStartActor(session.actorId)
        }
        val stored = services.saveBackendDeviceIfAbsent(
            session, nextFirstRunSnapshot, gatewayOrigin,
        )
        if (stored != GatewaySessionStoreResult.COMMITTED &&
            stored != GatewaySessionStoreResult.ALREADY_COMMITTED
        ) {
            session.invalidate()
            if (stored == GatewaySessionStoreResult.BLOCKED ||
                stored == GatewaySessionStoreResult.STORAGE_FAILURE
            ) {
                GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            } else {
                GatewaySessionProcessCoordinator.clear(operation)
            }
            ui.showMessage("로그인을 안전하게 저장하지 못했습니다. 다시 확인하세요.")
            ui.refresh()
            return
        }
        val published = GatewaySessionProcessCoordinator.publishVerified(
            operation = operation,
            session = session,
            firstRunSnapshot = nextFirstRunSnapshot,
        )
        if (!published) {
            services.retirePasswordLogin(session, operation.operationId)
            session.invalidate()
            ui.showMessage("로그인 세션을 안전하게 연결하지 못했습니다.")
            ui.refresh()
            return
        }
        effects.onBackendRestoreVerified()
        if (!flow.applyVerifiedLogin(preparedLogin)) {
            ui.refresh()
            return
        }
        effects.applyVerifiedAccount(session.actorId, reauthenticatesExistingProgress)
        if (emailEnrollmentOwnerBindingSha256?.let(storage::clearUnconfirmedCreation) == true) unconfirmedCreation = false
        storage.clear()
        emailEnrollmentPartial = null
        ui.clearInputs(listOf(InputField.EMAIL))
        ui.clearInputs(listOf(InputField.PASSWORD))
        ui.clearInputs(listOf(InputField.PASSWORD_CONFIRMATION))
        ui.clearInputs(listOf(InputField.OTP))
        ui.onFirstRunStateChanged(
            if (reauthenticatesExistingProgress) {
                "다시 로그인했습니다. 기기 점검 상태를 확인한 뒤 중단된 단계를 이어갑니다."
            } else {
                "로그인했습니다. 먼저 서비스 목적과 안전 한계를 확인하세요."
            },
        )
        effects.refreshConfirmedConsent()
        ui.updateStatus(
            if (reauthenticatesExistingProgress) {
                "account=reauthenticated next=onboarding_resume"
            } else {
                "account=authenticated next=purpose_and_safety"
            },
        )
        }
        ui.refresh()
        try {
            executor.execute {
                try {
                    val session = services.loginWithPassword(
                        gatewayBaseUrl = gatewayOrigin,
                        email = email,
                        password = password,
                        rememberMe = true,
                        deviceId = installationDeviceId,
                    )
                    postToMain { applyLogin(session) }
                } catch (error: GatewaySessionHttpException) {
                    GatewaySessionProcessCoordinator.clear(operation)
                    postAccountFailure(token, error.serverCode, error.statusCode)
                } catch (_: Exception) {
                    GatewaySessionProcessCoordinator.clear(operation)
                    postAccountFailure(token, null, 0)
                }
            }
        } catch (_: RejectedExecutionException) {
            GatewaySessionProcessCoordinator.clear(operation)
            accountRequestFence.cancel(token)
            ui.showMessage("로그인을 시작할 수 없습니다. 잠시 후 다시 시도하세요.")
            ui.refresh()
        }
    }

    private fun displayedSignupVersionsOrNull(): Map<String, String>? {
        val displayed = inputs.displayedSignupDocumentVersions() ?: return null
        if (displayed != SIGNUP_DOCUMENT_VERSIONS) return null
        return java.util.Collections.unmodifiableMap(LinkedHashMap(displayed))
    }

    private fun postUnavailableOptionalDocuments(
        token: AccountRequestToken,
        unavailable: Set<String>,
        restricted: SignupConsentSelections,
        otpAttempt: EmailOtpRequestRetryPolicy.Attempt? = null,
    ) {
        postToMain {
            if (!accountRequestFence.completeIfCurrent(token, accountStateBinding())) return@postToMain
            if (otpAttempt != null) emailOtpRequestRetryPolicy.recordFailure(
                otpAttempt, currentEmailOtpRetryPayload(), 409, "signup_optional_documents_changed", null,
            )
            ui.applyConsentSelections(restricted)
            ui.showUnavailableOptionalSignupDocuments(unavailable)
            ui.showMessage("바뀐 선택 동의 항목은 이번 가입에서 동의하지 않은 상태로 처리합니다. 가입 정보를 확인한 뒤 다시 요청하세요.")
            ui.refresh()
        }
    }

    private fun postAccountFailure(
        token: kr.co.hanium.dreamup.walksafe.account.AccountRequestToken,
        serverCode: String?,
        statusCode: Int,
        retryAfterMs: Long? = null,
        otpAttempt: kr.co.hanium.dreamup.walksafe.network.EmailOtpRequestRetryPolicy.Attempt? = null,
    ) {
        postToMain {
            if (!accountRequestFence.completeIfCurrent(token, accountStateBinding())) return@postToMain
            if (otpAttempt != null && !emailOtpRequestRetryPolicy.recordFailure(
                    otpAttempt, currentEmailOtpRetryPayload(), statusCode, serverCode, retryAfterMs,
                )) {
                ui.refresh()
                return@postToMain
            }
            val message = if (token.action == AccountRemoteAction.PASSWORD_LOGIN && statusCode == 429) {
                val retrySeconds = retryAfterMs
                    ?.coerceIn(1_000L, 300_000L)
                    ?.let { (it + 999L) / 1_000L }
                if (retrySeconds != null) {
                    "로그인 시도가 많습니다. ${retrySeconds}초 후 다시 시도해 주세요."
                } else {
                    "로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요."
                }
            } else if (otpAttempt != null && statusCode == 409 &&
                serverCode == "account_enrollment_in_progress") {
                val waitMs = emailOtpRequestRetryPolicy.remainingWaitMs(otpAttempt.payload)
                "이전 이메일 인증 요청을 처리 중입니다. " + ((waitMs + 999L) / 1_000L) +
                    "초 뒤 같은 가입 정보로 요청 버튼을 다시 누르세요. 자동으로 재전송하지 않습니다."
            } else if (otpAttempt != null && statusCode == 409 &&
                serverCode == "account_enrollment_idempotency_conflict") {
                "이전 인증 요청을 사용할 수 없습니다. 가입 정보를 확인한 뒤 인증번호 요청 버튼을 다시 누르세요. 새 요청으로 시작합니다."
            } else if (token.action == AccountRemoteAction.CREATE_ACCOUNT &&
                (statusCode == 0 || statusCode == 408 || statusCode >= 500)) {
                UNCONFIRMED_CREATION_MESSAGE
            } else when (serverCode) {
                "account_enrollment_not_allowed" ->
                    "만 14세 미만은 현재 WalkSafe 계정에 가입할 수 없습니다."
                "account_enrollment_rate_limited" ->
                    "인증번호를 다시 보낼 수 있을 때까지 잠시 기다리세요."
                "account_enrollment_verification_failed" ->
                    "인증번호가 틀렸거나 만료되었습니다. 확인 후 다시 시도하세요."
                "account_enrollment_conflict" ->
                    "이미 계정이 만들어졌을 수 있습니다. 이메일과 비밀번호로 로그인하세요."
                "account_password_too_common" ->
                    "많이 쓰이거나 추측하기 쉬운 비밀번호는 사용할 수 없습니다. 다른 비밀번호를 입력하세요."
                // S01-3: required signup documents changed; this build cannot show them.
                kr.co.hanium.dreamup.walksafe.account.SignupConsentPolicyNotice.OUTDATED_CODE ->
                    kr.co.hanium.dreamup.walksafe.account.SignupConsentPolicyNotice.OUTDATED_MESSAGE
                "invalid_account_credentials" ->
                    "이메일 또는 비밀번호를 확인하세요."
                else -> if (statusCode == 401) {
                    "이메일, 비밀번호 또는 인증번호를 확인하세요."
                } else {
                    "계정 서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요."
                }
            }
            ui.showMessage(message)
            ui.setPasswordLoginFailureNotice(
                message.takeIf { token.action == AccountRemoteAction.PASSWORD_LOGIN },
            )
            ui.refresh()
        }
    }

    private companion object {
        const val UNCONFIRMED_CREATION_MESSAGE =
            "가입 결과를 확인하지 못했습니다. 계정이 이미 만들어졌을 수 있습니다. " +
                "같은 가입 정보로 다시 시도하거나 이메일과 비밀번호로 로그인하세요."
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
