package kr.co.hanium.dreamup.walksafe.account

import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingFlow
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueActorBinding
import kr.co.hanium.dreamup.walksafe.session.FirstRunReceiptHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailAccountEnrollmentTest {
    @Test
    fun signupDocumentVersionsMatchTheBackendCanonicalDefaults() {
        assertEquals(
            linkedMapOf(
                "terms_of_service" to "walksafe.terms-of-service.v1",
                "privacy_notice" to "walksafe.privacy-notice.v1",
                "location_terms" to "walksafe.location-terms.v1",
                "raw_original" to "FP-013-RAW-1.1.0",
                "automatic_reporting" to "FP-013-AUTO-1.1.0",
                "training_reuse" to "FP-013-TRAINING-1.1.0",
            ),
            SIGNUP_DOCUMENT_VERSIONS,
        )
    }

    @Test
    fun optionalSignupSelectionsDefaultOffAndRequiredSelectionsAreExplicit() {
        val defaults = SignupConsentSelections()
        assertFalse(defaults.requiredGranted)
        assertFalse(defaults.rawOriginal)
        assertFalse(defaults.automaticReporting)
        assertFalse(defaults.trainingReuse)

        val requiredOnly = requiredSelections()
        assertTrue(requiredOnly.requiredGranted)
        assertFalse(requiredOnly.rawOriginal)
        assertFalse(requiredOnly.automaticReporting)
        assertFalse(requiredOnly.trainingReuse)
    }

    @Test
    fun partialCodecStoresOnlyOpaqueHandleExpiryOwnerAndConsentMetadata() {
        val partial = partial()
        val encoded = EmailEnrollmentPartialCodec.encode(partial)

        assertFalse(encoded.contains("person@example.com"))
        assertFalse(encoded.contains("2000-01-01"))
        assertFalse(encoded.contains("012345"))
        assertFalse(encoded.contains("correct horse"))
        assertEquals(partial, EmailEnrollmentPartialCodec.decode(encoded))
    }

    @Test
    fun partialResumeRejectsExpiryOwnerMismatchAndHandleTamper() {
        val partial = partial()
        assertTrue(partial.isValidAt(1_500L, OWNER))
        assertFalse(partial.isValidAt(2_000L, OWNER))
        assertFalse(partial.isValidAt(1_500L, "b".repeat(64)))

        val tampered = EmailEnrollmentPartialCodec.encode(partial)
            .replace(HANDLE, HANDLE.dropLast(1))
        assertNull(EmailEnrollmentPartialCodec.decode(tampered))

        val stringExpiry = EmailEnrollmentPartialCodec.encode(partial)
            .replace("\"expires_at_epoch_ms\":2000", "\"expires_at_epoch_ms\":\"2000\"")
        assertNull(EmailEnrollmentPartialCodec.decode(stringExpiry))
    }

    @Test
    fun requestFenceRejectsDuplicateStaleAndBackgroundCallbacks() {
        val fence = AccountRequestFence()
        fence.enteredForeground()
        val first = fence.begin(AccountRemoteAction.REQUEST_EMAIL_OTP, "state-1")!!
        assertNull(fence.begin(AccountRemoteAction.REQUEST_EMAIL_OTP, "state-1"))
        assertFalse(fence.completeIfCurrent(first, "state-2"))
        assertTrue(fence.completeIfCurrent(first, "state-1"))

        val second = fence.begin(AccountRemoteAction.PASSWORD_LOGIN, "state-2")!!
        fence.enteredBackground()
        assertFalse(fence.completeIfCurrent(second, "state-2"))
        fence.enteredForeground()
        assertNull(fence.begin(AccountRemoteAction.CREATE_ACCOUNT, ""))
    }

    @Test
    fun signupAndReturningLoginBothProceedToJitWithoutLegacyPhoneStages() {
        val initial = FirstRunOnboardingPolicy.initialEmailAccount(10L)
        assertEquals(FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4, initial.flow)
        assertEquals(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, initial.stage)

        val otp = FirstRunOnboardingPolicy.recordEmailOtpEnrollment(initial, receipt('a'))
        assertTrue(otp.accepted)
        assertEquals(FirstRunOnboardingStage.ACCOUNT_CREATED, otp.current.stage)
        val created = FirstRunOnboardingPolicy.recordEmailAccountCreated(otp.current, receipt('b'))
        assertTrue(created.accepted)
        assertEquals(FirstRunOnboardingStage.VERIFIED_LOGIN, created.current.stage)
        val loggedIn = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(
            created.current,
            FirstRunOpaqueActorBinding.fromProvider(ACTOR_ID),
            receipt('c'),
        )
        assertTrue(loggedIn.accepted)
        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, loggedIn.current.stage)
        assertFalse(loggedIn.current.mayEnterWalk)
        assertFalse(
            loggedIn.current.completedReceiptHashes.containsKey(
                FirstRunOnboardingStage.VERIFIED_SMS,
            ),
        )

        val returning = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(
            FirstRunOnboardingPolicy.initialEmailAccount(20L),
            FirstRunOpaqueActorBinding.fromProvider(ACTOR_ID),
            receipt('d'),
        )
        assertTrue(returning.accepted)
        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, returning.current.stage)
        assertEquals(
            setOf(FirstRunOnboardingStage.VERIFIED_LOGIN),
            returning.current.completedReceiptHashes.keys,
        )
        assertFalse(returning.current.mayEnterWalk)
    }

    @Test
    fun legacyReducerRemainsSeparateAndIsNeverDecodedAsEmailV4() {
        val legacy = FirstRunOnboardingPolicy.initial(30L)
        assertEquals(FirstRunOnboardingFlow.LEGACY_PHONE_V3, legacy.flow)
        assertEquals(FirstRunOnboardingStage.PURPOSE_AND_SAFETY, legacy.stage)
        assertFalse(
            FirstRunOnboardingPolicy.recordEmailOtpEnrollment(legacy, receipt('e')).accepted,
        )
    }

    private fun partial() = EmailEnrollmentPartial(
        enrollmentHandle = HANDLE,
        expiresAtEpochMs = 2_000L,
        resendAvailableAtEpochMs = 1_100L,
        ownerBindingSha256 = OWNER,
        selections = requiredSelections(),
    )

    private fun requiredSelections() = SignupConsentSelections(
        termsOfService = true,
        privacyNotice = true,
        locationTerms = true,
    )

    private fun receipt(character: Char): FirstRunReceiptHash =
        FirstRunReceiptHash.fromSha256Hex(character.toString().repeat(64))

    private companion object {
        val HANDLE = "A".repeat(43)
        val OWNER = "a".repeat(64)
        const val ACTOR_ID = "123e4567-e89b-42d3-a456-426614174000"
    }
}
