package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.account.SignupConsentSelections
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
