package kr.co.hanium.dreamup.walksafe.session.account

import java.util.ArrayDeque
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.account.EmailEnrollmentPartial
import kr.co.hanium.dreamup.walksafe.account.EmailEnrollmentRestoreResult
import kr.co.hanium.dreamup.walksafe.account.SignupConsentSelections
import kr.co.hanium.dreamup.walksafe.account.SIGNUP_DOCUMENT_VERSIONS
import kr.co.hanium.dreamup.walksafe.network.CreatedEmailAccount
import kr.co.hanium.dreamup.walksafe.network.EmailOtpEnrollmentResponse
import kr.co.hanium.dreamup.walksafe.network.GatewayAccountHttpException
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionStoreResult
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionVersion
import kr.co.hanium.dreamup.walksafe.network.PendingEmailOtpRequest
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.session.FirstRunReceiptHash
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueActorBinding
import kr.co.hanium.dreamup.walksafe.session.onboarding.FirstRunOnboardingController
import kr.co.hanium.dreamup.walksafe.session.onboarding.onboardingStateForTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmailAccountControllerTest {
    @Before
    @After
    fun clearSessionProcess() {
        val snapshot = GatewaySessionProcessCoordinator.snapshot()
        GatewaySessionProcessCoordinator.clear(snapshot.generation)
    }

    @Test
    fun constructionDoesNotReadInputsStorageServicesOrEmitEffects() {
        val f = Fixture()
        assertTrue(f.events.isEmpty())
        assertNull(f.controller.enrollmentState().partial)
        assertFalse(f.controller.enrollmentState().storageBlocked)
        assertFalse(f.controller.isRequestInFlight())
    }

    @Test
    fun invalidPasswordAndMissingRequiredConsentNeverDispatchRequests() {
        val f = Fixture().ready()
        f.values[EmailAccountController.InputField.PASSWORD] = "short"
        f.controller.requestEmailAccountOtp()
        assertEquals(EmailAccountController.InputField.PASSWORD, f.lastError)
        assertTrue(f.worker.isEmpty())
        f.values[EmailAccountController.InputField.PASSWORD] = PASSWORD
        f.selections = SignupConsentSelections()
        f.controller.requestEmailAccountOtp()
        assertTrue(f.events.contains("required-consent-error"))
        assertTrue(f.worker.isEmpty())
    }

    @Test
    fun duplicateTapKeepsOneWorkerAndOneWireRequest() {
        val f = Fixture().ready()
        f.controller.requestEmailAccountOtp()
        f.controller.requestEmailAccountOtp()
        assertEquals(1, f.worker.size)
        f.runWorker()
        assertEquals(1, f.otpIds.size)
    }

    @Test
    fun lateOtpCallbackAfterBackgroundCannotApplyOrConsumeTheNewAttempt() {
        val f = Fixture().ready()
        f.controller.requestEmailAccountOtp()
        f.runWorker()
        f.controller.enteredBackground()
        f.controller.enteredForeground()
        f.controller.requestEmailAccountOtp()
        f.runMain()
        assertNull(f.controller.enrollmentState().partial)
        assertTrue(f.controller.isRequestInFlight())
        f.runWorker()
        f.runMain()
        assertNotNull(f.controller.enrollmentState().partial)
        assertFalse(f.controller.isRequestInFlight())
        assertEquals(f.otpIds[0], f.otpIds[1])
    }

    @Test
    fun changedEmailAtCallbackRejectsTheResponseUsingFreshInputs() {
        val f = Fixture().ready()
        f.controller.requestEmailAccountOtp()
        f.runWorker()
        f.values[EmailAccountController.InputField.EMAIL] = "changed@example.test"
        f.runMain()
        assertNull(f.controller.enrollmentState().partial)
        assertFalse(f.events.contains("save-partial"))
        assertEquals(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, f.snapshot.stage)
    }

    @Test
    fun providerFailureRetriesWithTheSameNonceAndPreservesTheAgeDisclosure() {
        val f = Fixture().ready()
        f.otpFailure = GatewayAccountHttpException(503, "provider_unavailable")
        f.controller.requestEmailAccountOtp()
        f.runWorker()
        f.runMain()
        f.otpFailure = null
        f.controller.requestEmailAccountOtp()
        f.runWorker()
        f.runMain()
        assertEquals(f.otpIds[0], f.otpIds[1])
        assertNotNull(f.controller.enrollmentState().partial)
        val blocked = Fixture().ready()
        blocked.otpFailure = GatewayAccountHttpException(403, "age_blocked", "account_enrollment_not_allowed")
        blocked.controller.requestEmailAccountOtp()
        blocked.runWorker()
        blocked.runMain()
        assertEquals("만 14세 미만은 현재 WalkSafe 계정에 가입할 수 없습니다.", blocked.messages.last())
        assertNull(blocked.controller.enrollmentState().partial)
    }

    @Test
    fun enrollmentStoreFailureDoesNotAdvanceOrFocusOtp() {
        val f = Fixture().ready()
        f.savePartialAllowed = false
        f.controller.requestEmailAccountOtp()
        f.runWorker()
        f.runMain()
        assertTrue(f.controller.enrollmentState().storageBlocked)
        assertEquals(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, f.snapshot.stage)
        assertFalse(f.events.contains("focus-otp"))
    }

    @Test
    fun createUsesCapturedCredentialsAndStoresBeforePublishThenClearsInputs() {
        val f = Fixture().ready().enroll()
        val owner = Any()
        GatewaySessionProcessCoordinator.attach(owner) { snapshot ->
            if (snapshot.session != null) f.events.add("published")
        }
        try {
            f.controller.createEmailAccount()
            f.runWorker()
            f.values[EmailAccountController.InputField.EMAIL] = "changed@example.test"
            f.values[EmailAccountController.InputField.PASSWORD] = "changed-password-value"
            f.runMain()
            f.runWorker()
            f.runMain()
            assertEquals(EMAIL, f.loginEmail)
            assertEquals(PASSWORD, f.loginPassword)
            assertTrue(f.loginRememberMe)
            assertEquals(ACTOR, GatewaySessionProcessCoordinator.snapshot().session?.actorId)
            assertInOrder(f.events, "account-created", "clear-OTP", "clear-PASSWORD_CONFIRMATION", "password-login", "store-login", "published", "verified-binding", "clear-EMAIL", "clear-PASSWORD", "consent-refresh")
        } finally {
            GatewaySessionProcessCoordinator.detach(owner)
        }
    }

    @Test
    fun createdActorMismatchLogsOutWithoutStoreOrPublish() {
        val f = Fixture().ready().enroll()
        f.loginActor = "123e4567-e89b-42d3-a456-426614174001"
        f.controller.createEmailAccount()
        f.runWorker()
        f.runMain()
        f.runWorker()
        f.runMain()
        assertTrue(f.events.contains("remote-logout"))
        assertFalse(f.events.contains("store-login"))
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertFalse(f.events.contains("verified-binding"))
    }

    @Test
    fun loginStorageFailureKeepsTheProcessFailClosedWithoutVerifiedEffects() {
        val f = Fixture().ready()
        f.loginStoreResult = GatewaySessionStoreResult.STORAGE_FAILURE
        f.controller.loginEmailAccount()
        f.runWorker()
        f.runMain()
        assertTrue(GatewaySessionProcessCoordinator.snapshot().storageBlocked)
        assertNull(GatewaySessionProcessCoordinator.snapshot().session)
        assertFalse(f.events.contains("verified-binding"))
        assertEquals(EMAIL, f.values[EmailAccountController.InputField.EMAIL])
    }

    @Test
    fun verifiedDifferentActorStoresFreshProgressBeforePublishingAndApplyingIt() {
        val f = Fixture().ready()
        val old = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(7L, listOf(
            kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidence.VerifiedLogin(
                FirstRunOpaqueActorBinding.fromProvider(ACTOR), FirstRunReceiptHash.fromSha256Hex("a".repeat(64))),
            kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidence.PurposeAndSafety(
                FirstRunReceiptHash.fromSha256Hex("b".repeat(64))),
            kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidence.Fp004Training(
                FirstRunReceiptHash.fromSha256Hex("c".repeat(64))),
        ), kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidenceVerifier { _, _ -> true }).snapshot
        assertTrue(f.restoreProgress(old))
        f.loginActor = "123e4567-e89b-42d3-a456-426614174001"
        val observer = Any()
        GatewaySessionProcessCoordinator.attach(observer) { process ->
            if (process.session != null) {
                assertEquals(f.savedFirstRun, process.restoredFirstRunSnapshot)
                assertEquals(old, f.snapshot)
                f.events.add("published")
            }
        }
        try {
            f.controller.loginEmailAccount()
            f.runWorker()
            assertEquals(old, f.snapshot)
            assertNull(f.savedFirstRun)
            f.runMain()
            assertEquals(f.loginActor, GatewaySessionProcessCoordinator.snapshot().session?.actorId)
            assertEquals(FirstRunOnboardingStage.PURPOSE_AND_SAFETY, f.snapshot.stage)
            assertEquals(old.epoch + 1L, f.snapshot.epoch)
            assertEquals(setOf(FirstRunOnboardingStage.VERIFIED_LOGIN), f.snapshot.completedReceiptHashes.keys)
            assertEquals(f.savedFirstRun, f.snapshot)
            assertEquals(false, f.appliedReauthentication)
            assertFalse(f.snapshot.mayEnterWalk)
            assertInOrder(f.events, "password-login", "store-login", "published", "restore-verified", "verified-binding", "consent-refresh")
            assertTrue(f.events.contains("account=authenticated next=purpose_and_safety"))
        } finally { GatewaySessionProcessCoordinator.detach(observer) }
    }

    @Test
    fun delayedDifferentActorLoginCannotReplaceProgressAfterBackgroundOrLocalRestart() {
        for (background in listOf(true, false)) {
            val f = Fixture().ready()
            val before = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(FirstRunOnboardingPolicy.initialEmailAccount(7L),
                FirstRunOpaqueActorBinding.fromProvider(ACTOR), FirstRunReceiptHash.fromSha256Hex("a".repeat(64))).current
            assertTrue(f.restoreProgress(before))
            f.loginActor = "123e4567-e89b-42d3-a456-426614174001"
            f.controller.loginEmailAccount()
            f.runWorker()
            if (background) { f.controller.enteredBackground(); f.controller.enteredForeground() }
            else f.restartEmailAccount()
            val current = f.snapshot
            f.runMain()
            assertEquals(current, f.snapshot)
            assertNull(f.savedFirstRun)
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertTrue(f.events.contains("remote-logout"))
            assertFalse(f.events.contains("verified-binding"))
        }
    }

    @Test
    fun rollingBackWallClockCannotExtendTheInProcessMonotonicExpiry() {
        val f = Fixture().ready().enroll()
        f.wallMs -= 1_000_000L
        f.elapsedMs += 300_001L
        f.controller.createEmailAccount()
        assertEquals(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, f.snapshot.stage)
        assertNull(f.controller.enrollmentState().partial)
        assertTrue(f.worker.isEmpty())
    }

    @Test
    fun missingInstallBindingBlocksRestoreAndStorageCannotBeRebound() {
        val f = Fixture()
        f.installationId = null
        f.controller.restoreEmailEnrollmentPartialAtStartup()
        assertTrue(f.controller.enrollmentState().storageBlocked)
        assertFalse(f.events.contains("restore-enrollment"))
        var rejected = false
        try { f.controller.initializeStorage(f.storage) } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
    }

    @Test
    fun recoveryReadOrSaveFailureDoesNotDispatchOtp() {
        for (readFailure in listOf(false, true)) {
            val f = Fixture().ready()
            f.savePendingAllowed = false
            f.pendingReadFailure = readFailure
            f.controller.requestEmailAccountOtp()
            assertTrue(f.worker.isEmpty())
            assertTrue(f.otpIds.isEmpty())
            assertTrue(f.messages.last().contains("발송하지 않았습니다"))
        }
    }

    @Test
    fun failedEnrollmentSaveKeepsTheReplayIdForTheRestartedProcess() {
        val f = Fixture().ready()
        f.savePartialAllowed = false
        f.controller.requestEmailAccountOtp()
        f.runWorker(); f.runMain()
        val pending = requireNotNull(f.pending)
        assertTrue(f.controller.enrollmentState().storageBlocked)
        val restarted = Fixture().apply { this.pending = pending }.ready()
        restarted.controller.requestEmailAccountOtp()
        restarted.runWorker(); restarted.runMain()
        assertEquals(pending.requestId, restarted.otpIds.single())
        assertInOrder(restarted.events, "dispatch-otp", "save-partial", "clear-pending", "focus-otp")
    }

    @Test
    fun policyFailureOrMissingDisplayedDocumentsBlocksEnrollment() {
        val missing = Fixture().ready().apply { displayedVersions = null }
        missing.controller.requestEmailAccountOtp()
        assertTrue(missing.worker.isEmpty())
        val failed = Fixture().ready().apply { policyFailure = GatewayAccountHttpException(503, "policy unavailable") }
        failed.controller.requestEmailAccountOtp()
        failed.runWorker(); failed.runMain()
        assertTrue(failed.otpIds.isEmpty())
        assertNull(failed.controller.enrollmentState().partial)
    }

    @Test
    fun changedOptionalGrantIsShownAndRequiresExplicitRetryBeforeOtp() {
        val f = Fixture().ready().apply {
            selections = selections.copy(rawOriginal = true)
            unavailableOptional = setOf("raw_original")
        }
        f.controller.requestEmailAccountOtp(); f.runWorker(); f.runMain()
        assertFalse(f.selections.rawOriginal)
        assertTrue(f.otpIds.isEmpty())
        assertTrue(f.events.contains("unavailable-optional:raw_original"))
        f.controller.requestEmailAccountOtp(); f.runWorker(); f.runMain()
        assertNotNull(f.controller.enrollmentState().partial)
        assertFalse(requireNotNull(f.partial).selections.rawOriginal)
    }

    @Test
    fun creationAdvisoryIsDurableBeforeDispatchAndNeverAdvancesAFailedRequest() {
        val f = Fixture().ready().enroll().apply { createFailure = java.net.SocketTimeoutException("synthetic") }
        val before = f.snapshot
        f.controller.createEmailAccount()
        assertNull(f.creationOwner)
        f.runWorker()
        assertNotNull(f.creationOwner)
        assertTrue(f.controller.enrollmentState().unconfirmedCreation)
        f.runMain()
        assertEquals(before, f.snapshot)
        assertInOrder(f.events, "mark-creation-pending", "account-created")
        val restarted = Fixture().apply { creationOwner = f.creationOwner }.ready()
        assertTrue(restarted.controller.enrollmentState().unconfirmedCreation)
        assertEquals(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, restarted.snapshot.stage)
        assertTrue(restarted.messages.any { it.contains("이미 만들어졌을 수") })
    }

    @Test
    fun failedPolicyPreflightNeverCreatesAnUnconfirmedCreationAdvisory() {
        val f = Fixture().ready().enroll().apply { policyFailure = GatewayAccountHttpException(503, "policy unavailable") }
        f.controller.createEmailAccount(); f.runWorker(); f.runMain()
        assertNull(f.creationOwner)
        assertFalse(f.events.contains("account-created"))
        assertFalse(f.controller.enrollmentState().unconfirmedCreation)
    }

    @Test
    fun creationAdvisorySaveFailureBlocksRemoteCreationAndConfirmationClearsIt() {
        val denied = Fixture().ready().enroll().apply { markCreationAllowed = false }
        denied.controller.createEmailAccount()
        denied.runWorker(); denied.runMain()
        assertTrue(denied.worker.isEmpty())
        assertFalse(denied.events.contains("account-created"))
        val confirmed = Fixture().ready().enroll()
        confirmed.controller.createEmailAccount(); confirmed.runWorker(); confirmed.runMain()
        assertNull(confirmed.creationOwner)
        assertFalse(confirmed.controller.enrollmentState().unconfirmedCreation)
        assertEquals(SIGNUP_DOCUMENT_VERSIONS, confirmed.sentVersions)
        assertInOrder(confirmed.events, "mark-creation-pending", "account-created", "clear-creation-pending")
    }

    private class Fixture : EmailAccountController.Flow, EmailAccountController.Inputs,
        EmailAccountController.Services, EmailAccountController.Ui, EmailAccountController.VerifiedEffects {
        val events = mutableListOf<String>()
        val worker = ArrayDeque<Runnable>()
        val main = ArrayDeque<() -> Unit>()
        val messages = mutableListOf<String>()
        val otpIds = mutableListOf<String>()
        val values = mutableMapOf(
            EmailAccountController.InputField.EMAIL to EMAIL,
            EmailAccountController.InputField.DATE_OF_BIRTH to "20020101",
            EmailAccountController.InputField.PASSWORD to PASSWORD,
            EmailAccountController.InputField.PASSWORD_CONFIRMATION to PASSWORD,
            EmailAccountController.InputField.OTP to "123456",
        )
        var wallMs = 1_000_000L
        var elapsedMs = 10_000L
        var selections = SignupConsentSelections(termsOfService = true, privacyNotice = true, locationTerms = true)
        var expanded = true
        var savePartialAllowed = true
        var savePendingAllowed = true
        var pendingReadFailure = false
        var markCreationAllowed = true
        var creationOwner: String? = null
        var createFailure: Exception? = null
        var policyFailure: Exception? = null
        var unavailableOptional = emptySet<String>()
        var displayedVersions: Map<String, String>? = SIGNUP_DOCUMENT_VERSIONS
        var sentVersions: Map<String, String>? = null
        var partial: EmailEnrollmentPartial? = null
        var pending: PendingEmailOtpRequest? = null
        var otpFailure: Exception? = null
        var lastError: EmailAccountController.InputField? = null
        var installationId: String? = "00000000-0000-4000-8000-000000000001"
        var loginActor = ACTOR
        var loginEmail: String? = null
        var loginPassword: String? = null
        var loginRememberMe = false
        var loginStoreResult = GatewaySessionStoreResult.COMMITTED
        var savedFirstRun: FirstRunOnboardingSnapshot? = null
        var appliedReauthentication: Boolean? = null
        private val onboarding = onboardingStateForTest(FirstRunOnboardingPolicy.initialEmailAccount(epoch = 1L))
        override val snapshot get() = onboarding.snapshot
        fun restoreProgress(snapshot: FirstRunOnboardingSnapshot) = onboarding.restoreSessionSnapshot(snapshot)
        override fun restartEmailAccount() = onboarding.restartEmailAccount { 1L }
        override fun recordOtpEnrollment(receipt: FirstRunReceiptHash) = onboarding.recordEmailOtpEnrollment(receipt)
        override fun recordAccountCreated(receipt: FirstRunReceiptHash, clearEnrollment: () -> Boolean) =
            onboarding.recordEmailAccountCreated(receipt, clearEnrollment)
        override fun prepareVerifiedLogin(actor: FirstRunOpaqueActorBinding, receipt: FirstRunReceiptHash, completed: FirstRunOnboardingSnapshot?) =
            onboarding.prepareVerifiedEmailLogin(actor, receipt, completed)
        override fun applyVerifiedLogin(prepared: FirstRunOnboardingController.PreparedEmailLogin) = onboarding.applyVerifiedEmailLogin(prepared)
        val storage = object : EmailAccountController.EnrollmentStorage {
            override fun restore(nowEpochMs: Long, expectedOwnerBindingSha256: String): EmailEnrollmentRestoreResult {
                events.add("restore-enrollment")
                return partial?.let { EmailEnrollmentRestoreResult.Restored(it) } ?: EmailEnrollmentRestoreResult.Absent
            }
            override fun save(partial: EmailEnrollmentPartial): Boolean {
                events.add("save-partial")
                if (savePartialAllowed) this@Fixture.partial = partial
                return savePartialAllowed
            }
            override fun clear(): Boolean { partial = null; return true }
            override fun resetAfterConfirmedAccountDeletion(): Boolean { partial = null; pending = null; creationOwner = null; return true }
            override fun hasUnconfirmedCreation(ownerBindingSha256: String) = creationOwner == ownerBindingSha256
            override fun markCreationPending(ownerBindingSha256: String): Boolean {
                events.add("mark-creation-pending")
                if (markCreationAllowed) creationOwner = ownerBindingSha256
                return markCreationAllowed
            }
            override fun clearUnconfirmedCreation(ownerBindingSha256: String): Boolean {
                if (creationOwner != null && creationOwner != ownerBindingSha256) return false
                creationOwner = null; events.add("clear-creation-pending"); return true
            }
            override fun loadPendingOtpRequest(): PendingEmailOtpRequest? {
                if (pendingReadFailure) error("synthetic pending read failure")
                return pending
            }
            override fun savePendingOtpRequest(request: PendingEmailOtpRequest): Boolean {
                events.add("save-pending")
                if (savePendingAllowed) pending = request
                return savePendingAllowed
            }
            override fun clearPendingOtpRequest(): Boolean { events.add("clear-pending"); pending = null; return true }
        }
        val controller = EmailAccountController(
            services = this, flow = this, inputs = this, ui = this, effects = this,
            readers = EmailAccountController.Readers(
                gatewayOrigin = { ORIGIN }, networkAvailable = { true }, transitionAllowed = { true },
                backendRestorePending = { false }, gatewaySession = { GatewaySessionProcessCoordinator.snapshot().session },
                developmentQuickStartEnabled = { false },
            ),
            executor = Executor { worker.add(it) }, postToMain = { main.add(it) },
            elapsedRealtimeMs = { elapsedMs }, wallClockMs = { wallMs },
            createRequestId = { "00000000-0000-4000-8000-000000000002" },
        ).also { it.initializeStorage(storage) }

        fun ready(): Fixture {
            controller.restoreEmailEnrollmentPartialAtStartup()
            controller.enteredForeground()
            events.clear()
            return this
        }
        fun enroll(): Fixture {
            controller.requestEmailAccountOtp()
            runWorker()
            runMain()
            events.clear()
            return this
        }
        fun runWorker() = worker.removeFirst().run()
        fun runMain() = main.removeFirst().invoke()
        override fun completedNativeProgress(actorId: String): FirstRunOnboardingSnapshot? = null
        override fun text(field: EmailAccountController.InputField) = values.getValue(field)
        override fun consentSelections() = selections
        override fun signupExpanded() = expanded
        override fun displayedSignupDocumentVersions() = displayedVersions
        override fun installationDeviceId(): String? { events.add("installation-id"); return installationId }
        override fun signupUnavailableOptionalDocuments(gatewayBaseUrl: String): Set<String> {
            events.add("signup-policy")
            policyFailure?.let { throw it }
            return unavailableOptional
        }
        override fun requestEmailOtp(gatewayBaseUrl: String, email: String, dateOfBirth: String, requestId: String): EmailOtpEnrollmentResponse {
            events.add("dispatch-otp")
            otpIds.add(requestId)
            otpFailure?.let { throw it }
            return EmailOtpEnrollmentResponse("A".repeat(43), wallMs + 300_000L, wallMs + 60_000L)
        }
        override fun createAccount(gatewayBaseUrl: String, enrollmentHandle: String, otpCode: String, password: String, selections: SignupConsentSelections, documentVersions: Map<String, String>): CreatedEmailAccount {
            events.add("account-created")
            sentVersions = documentVersions
            createFailure?.let { throw it }
            return CreatedEmailAccount(ACTOR, 1L, "b".repeat(64))
        }
        override fun loginWithPassword(gatewayBaseUrl: String, email: String, password: String, rememberMe: Boolean, deviceId: String): GatewayFieldSession {
            events.add("password-login")
            loginEmail = email; loginPassword = password; loginRememberMe = rememberMe
            return GatewayFieldSession.verified(ORIGIN, loginActor, "walksafe_field_session=v2.test.account.session", Long.MAX_VALUE)
        }
        override fun logout(session: GatewayFieldSession) { events.add("remote-logout") }
        override fun saveBackendDeviceIfAbsent(session: GatewayFieldSession, firstRunSnapshot: FirstRunOnboardingSnapshot, expectedGatewayBaseUrl: String): GatewaySessionStoreResult {
            events.add("store-login")
            savedFirstRun = firstRunSnapshot
            return loginStoreResult
        }
        override fun removeBackendDeviceIfCurrent(expectedVersion: GatewaySessionVersion) = GatewaySessionStoreResult.COMMITTED
        override fun showMessage(message: String) { messages.add(message) }
        override fun showInputError(field: EmailAccountController.InputField, message: String) { lastError = field; messages.add(message) }
        override fun showMissingRequiredConsentError() { events.add("required-consent-error") }
        override fun clearAccessNotice() = Unit
        override fun setPasswordLoginFailureNotice(message: String?) = Unit
        override fun refresh() { events.add("refresh") }
        override fun revealSignup() { expanded = true }
        override fun applyConsentSelections(selections: SignupConsentSelections) { this.selections = selections }
        override fun showUnavailableOptionalSignupDocuments(keys: Set<String>) {
            if (keys.isNotEmpty()) events.add("unavailable-optional:" + keys.sorted().joinToString(","))
        }
        override fun speakInteraction(message: String) { messages.add(message) }
        override fun focusOtp() { events.add("focus-otp") }
        override fun clearInputs(fields: List<EmailAccountController.InputField>) { fields.forEach { values[it] = ""; events.add("clear-$it") } }
        override fun onFirstRunStateChanged(message: String) { messages.add(message) }
        override fun updateStatus(message: String) { events.add(message) }
        override fun restoreGatewaySession() { events.add("restore-session") }
        override fun refreshConfirmedConsent() { events.add("consent-refresh") }
        override fun noteDevelopmentQuickStartActor(actorId: String) { events.add("quick-start") }
        override fun onBackendRestoreVerified() { events.add("restore-verified") }
        override fun applyVerifiedAccount(actorId: String, reauthenticatesExistingProgress: Boolean) {
            appliedReauthentication = reauthenticatesExistingProgress
            events.add("verified-binding")
        }
    }

    private fun assertInOrder(events: List<String>, vararg wanted: String) {
        var previous = -1
        wanted.forEach { event ->
            val at = (previous + 1 until events.size).firstOrNull { events[it] == event } ?: -1
            assertTrue("Missing/out of order $event in $events", at > previous)
            previous = at
        }
    }

    private companion object {
        const val ORIGIN = "https://gateway.example.test"
        const val EMAIL = "person@example.test"
        const val PASSWORD = "a-very-long-safe-password"
        const val ACTOR = "123e4567-e89b-42d3-a456-426614174000"
    }
}
