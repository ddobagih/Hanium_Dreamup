package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.account.SignupConsentSelections
import kr.co.hanium.dreamup.walksafe.network.GatewayAccountInputPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailAccountFormPolicyTest {
    private val requiredConsents = SignupConsentSelections(
        termsOfService = true,
        privacyNotice = true,
        locationTerms = true,
    )

    @Test
    fun emptyFormCannotRunAnyAccountAction() {
        val validity = EmailAccountFormPolicy.evaluate(
            email = "",
            dateOfBirth = "",
            password = "",
            passwordConfirmation = "",
            otp = "",
            selections = SignupConsentSelections(),
        )

        assertFalse(validity.canLogin)
        assertFalse(validity.canContinueFromConsent)
        assertFalse(validity.canRequestOtp)
        assertFalse(validity.canCreateAccount)
    }

    @Test
    fun requiredThreeConsentsEnableContinueWithoutOptionalConsents() {
        val validity = validForm(requiredConsents)

        assertTrue(validity.requiredConsentsGranted)
        assertTrue(validity.canContinueFromConsent)
        assertTrue(validity.canRequestOtp)
        assertTrue(validity.canCreateAccount)
    }

    @Test
    fun optionalConsentsNeverGateSignupActions() {
        val withoutOptional = validForm(requiredConsents)
        val withOptional = validForm(
            requiredConsents.copy(
                rawOriginal = true,
                automaticReporting = true,
                trainingReuse = true,
            ),
        )

        assertTrue(withoutOptional.canRequestOtp)
        assertTrue(withoutOptional.canCreateAccount)
        assertTrue(withOptional.canRequestOtp)
        assertTrue(withOptional.canCreateAccount)
    }

    @Test
    fun eachFieldGatesOnlyTheActionsThatNeedIt() {
        val invalidEmail = validForm(requiredConsents, email = "invalid")
        assertFalse(invalidEmail.canLogin)
        assertFalse(invalidEmail.canRequestOtp)
        assertFalse(invalidEmail.canCreateAccount)

        val invalidDob = validForm(requiredConsents, dateOfBirth = "2024-02-30")
        assertTrue(invalidDob.canLogin)
        assertFalse(invalidDob.canRequestOtp)
        assertTrue(invalidDob.canCreateAccount)

        val mismatchedPassword = validForm(
            requiredConsents,
            passwordConfirmation = "different-password",
        )
        assertTrue(mismatchedPassword.canLogin)
        assertTrue(mismatchedPassword.canRequestOtp)
        assertFalse(mismatchedPassword.canCreateAccount)

        val invalidOtp = validForm(requiredConsents, otp = "12345")
        assertTrue(invalidOtp.canLogin)
        assertTrue(invalidOtp.canRequestOtp)
        assertFalse(invalidOtp.canCreateAccount)
    }

    @Test
    fun s0113NewPasswordNeedsTwelveCodePointsWhileLoginKeepsLegacyRange() {
        // Same vectors as backend/tests/test_accounts.py and the Gateway relay test.
        listOf(
            "x".repeat(12),
            "우리집강아지산책좋아해요",
            "🐶🐱🐭🐹🐰🦊🐻🐼🐨🐯🦁🐮",
            "e\u0301".repeat(6),
        ).forEach { password ->
            assertTrue(GatewayAccountInputPolicy.validNewPassword(password))
            assertTrue(validForm(requiredConsents, password = password).canCreateAccount)
        }
        listOf(
            "x".repeat(11),
            "가".repeat(11),
            "🐶".repeat(11),
            "x".repeat(129),
            "twelve\u0000chars",
        ).forEach { password ->
            assertFalse(GatewayAccountInputPolicy.validNewPassword(password))
            assertFalse(validForm(requiredConsents, password = password).canCreateAccount)
        }
        val legacy = validForm(requiredConsents, password = "tenletters")
        assertTrue(legacy.canLogin)
        assertFalse(legacy.canCreateAccount)
    }

    @Test
    fun reservedDomainHyphensMatchGatewayBeforeOtpAndLogin() {
        listOf("a@ab--cd.example", "a@AB--CD.example").forEach { email ->
            assertFalse(GatewayAccountInputPolicy.validEmail(email))
            val validity = validForm(requiredConsents, email = email)
            assertFalse(validity.emailValid)
            assertFalse(validity.canLogin)
            assertFalse(validity.canRequestOtp)
            assertFalse(validity.canCreateAccount)
        }
        listOf("a@ab-cd.example", "a@xn--bcher-kva.example").forEach { email ->
            assertTrue(GatewayAccountInputPolicy.validEmail(email))
            assertTrue(validForm(requiredConsents, email = email).canRequestOtp)
        }
    }

    private fun validForm(
        selections: SignupConsentSelections,
        email: String = "walker@example.com",
        dateOfBirth: String = "2000-02-29",
        password: String = "correct-horse-battery",
        passwordConfirmation: String = password,
        otp: String = "123456",
    ): EmailAccountFormValidity = EmailAccountFormPolicy.evaluate(
        email = email,
        dateOfBirth = dateOfBirth,
        password = password,
        passwordConfirmation = passwordConfirmation,
        otp = otp,
        selections = selections,
    )
}
