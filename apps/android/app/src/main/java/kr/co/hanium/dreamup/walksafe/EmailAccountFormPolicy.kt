package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.account.SignupConsentSelections
import kr.co.hanium.dreamup.walksafe.network.GatewayAccountInputPolicy

internal data class EmailAccountFormValidity(
    val emailValid: Boolean,
    val dateOfBirthValid: Boolean,
    val passwordValid: Boolean,
    val passwordConfirmationValid: Boolean,
    val otpValid: Boolean,
    val requiredConsentsGranted: Boolean,
) {
    val canLogin: Boolean
        get() = emailValid && passwordValid

    val canContinueFromConsent: Boolean
        get() = requiredConsentsGranted

    val canRequestOtp: Boolean
        get() = emailValid && dateOfBirthValid && requiredConsentsGranted

    val canCreateAccount: Boolean
        get() =
            emailValid && passwordValid && passwordConfirmationValid && otpValid &&
                requiredConsentsGranted
}

internal object EmailAccountFormPolicy {
    private val OTP = Regex("^[0-9]{6}$")

    fun evaluate(
        email: String,
        dateOfBirth: String,
        password: String,
        passwordConfirmation: String,
        otp: String,
        selections: SignupConsentSelections,
    ): EmailAccountFormValidity {
        val passwordValid = GatewayAccountInputPolicy.validPassword(password)
        return EmailAccountFormValidity(
            emailValid = GatewayAccountInputPolicy.validEmail(email),
            dateOfBirthValid = GatewayAccountInputPolicy.validDateOfBirth(dateOfBirth),
            passwordValid = passwordValid,
            passwordConfirmationValid =
                passwordValid && password == passwordConfirmation,
            otpValid = OTP.matches(otp),
            requiredConsentsGranted = selections.requiredGranted,
        )
    }
}
