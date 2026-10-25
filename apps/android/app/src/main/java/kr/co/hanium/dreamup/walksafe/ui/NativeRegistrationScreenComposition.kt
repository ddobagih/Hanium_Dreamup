package kr.co.hanium.dreamup.walksafe.ui

import android.content.Context

/** Owns the actual registration/device presenters and publishes their existing panels in stages.
 * Account authority, consent restoration, speech retirement and feature admission stay outside.
 * Construction performs no View creation, peer lookup or callback.
 */
internal class NativeRegistrationScreenComposition(
    private val context: Context,
    private val style: WalkSafeViewStyle,
    private val publication: NativeUiPublication,
    private val accountSource: () -> AccountAccessPresenter,
) {
    private lateinit var firstRun: FirstRunOnboardingPresenter
    private lateinit var deviceCheck: DeviceCheckPresenter

    fun registrationInitialized(): Boolean = ::firstRun.isInitialized
    fun firstRunPresenter(): FirstRunOnboardingPresenter = firstRun
    fun devicePresenter(): DeviceCheckPresenter = deviceCheck

    fun bindRegistration(panel: OnboardingReadinessPanel) {
        if (!::firstRun.isInitialized) {
            firstRun = FirstRunOnboardingPresenter(context, style, panel)
        } else {
            firstRun.bindOnboardingPanel(panel)
        }
        deviceCheck = DeviceCheckPresenter(panel, style)
        firstRun.publishRegistrationTargets()
        publication.publish(NativeUiPublication.Target.PRODUCT_PURPOSE_TEXT)
        publication.publish(NativeUiPublication.Target.FIRST_RUN_NOTICE_TOGGLE_BUTTON)
        publication.publish(NativeUiPublication.Target.FIRST_RUN_PROGRESS_BAR)
        publication.publish(NativeUiPublication.Target.FIRST_RUN_ONBOARDING_STATUS_TEXT)
        publication.publish(NativeUiPublication.Target.FIRST_RUN_INTEGRATED_CONSENT_DISCLOSURE_TEXT)
        publication.publish(NativeUiPublication.Target.FIRST_RUN_DISCLOSURE_TOGGLE_BUTTON)
        publication.publish(NativeUiPublication.Target.FIRST_RUN_CONSENT_COUNT_TEXT)
    }

    /** The live integrated-consent button map is populated by the caller before this stage. */
    fun publishRegistrationConsentTail() {
        publication.publish(NativeUiPublication.Target.FIRST_RUN_CONSENT_ALL_BUTTON)
        publication.publish(NativeUiPublication.Target.FIRST_RUN_INTEGRATED_CONSENT_SAVE_BUTTON)
        publication.publish(NativeUiPublication.Target.FIRST_RUN_WAITING_CARD)
    }

    fun bindAccountInputs(panel: AccountAccessPanel) {
        accountSource().bindPanel(panel)
        firstRun.bindAccountPanel(panel)
        accountSource().publishStatusTarget()
        publication.publish(NativeUiPublication.Target.ACCOUNT_ACCESS_STATUS_TEXT)
        publication.publish(NativeUiPublication.Target.ACCOUNT_EMAIL_INPUT)
        publication.publish(NativeUiPublication.Target.ACCOUNT_DATE_OF_BIRTH_INPUT)
        publication.publish(NativeUiPublication.Target.ACCOUNT_PASSWORD_INPUT)
        publication.publish(NativeUiPublication.Target.ACCOUNT_PASSWORD_CONFIRMATION_INPUT)
        accountSource().publishInputTargets()
        publication.publish(NativeUiPublication.Target.ACCOUNT_OTP_INPUT)
    }

    /** Caller stops playback and resets its speech binding/context before this stage. */
    fun publishAccountConsentAndRoot() {
        publication.publish(NativeUiPublication.Target.ACCOUNT_CONSENT_ALL_CHECK)
        publication.publish(NativeUiPublication.Target.ACCOUNT_CONSENT_SUMMARY_TEXT)
        publication.publish(NativeUiPublication.Target.ACCOUNT_CONSENT_CONTINUE_BUTTON)
        publication.publish(NativeUiPublication.Target.ACCOUNT_REQUEST_OTP_BUTTON)
        publication.publish(NativeUiPublication.Target.ACCOUNT_CREATE_BUTTON)
        publication.publish(NativeUiPublication.Target.ACCOUNT_LOGIN_BUTTON)
        publication.publish(NativeUiPublication.Target.ACCOUNT_SIGNUP_CONTROLS)
        accountSource().publishRootTarget()
        publication.publish(NativeUiPublication.Target.ACCOUNT_ACCESS_CONTROLS)
    }

    /** Caller restores selections and attaches the same account root to its original slot first. */
    fun publishEducationAndReadiness() {
        firstRun.publishEducationTargets()
        publication.publish(NativeUiPublication.Target.PRIORITY_USER_ONBOARDING_STATUS_TEXT)
        publication.publish(NativeUiPublication.Target.PRIORITY_USER_EDUCATION_AGREE_BUTTON)
        publication.publish(NativeUiPublication.Target.FIRST_RUN_PHONE_POSTURE_BUTTON)
        publication.publish(NativeUiPublication.Target.FIRST_RUN_PHONE_POSTURE_CONTROLS)
        firstRun.publishReadinessTargets()
        publication.publish(NativeUiPublication.Target.OFFICIAL_ENVIRONMENT_STATUS_TEXT)
        publication.publish(NativeUiPublication.Target.OFFICIAL_ENVIRONMENT_CONFIRM_BUTTON)
        publication.publish(NativeUiPublication.Target.PHONE_MOUNTING_STATUS_TEXT)
        publication.publish(NativeUiPublication.Target.PHONE_MOUNTING_CHEST_CONFIRM_BUTTON)
        publication.publish(NativeUiPublication.Target.PHONE_MOUNTING_NECKLACE_CONFIRM_BUTTON)
        publication.publish(NativeUiPublication.Target.STARTUP_CAPABILITY_TEXT)
        publication.publish(NativeUiPublication.Target.POST_LOGIN_DEVICE_CHECK_LIVE_STATUS_TEXT)
        publication.publish(NativeUiPublication.Target.STARTUP_METRIC_PREFLIGHT_BUTTON)
        publication.publish(NativeUiPublication.Target.STARTUP_CAPABILITY_CONFIRM_BUTTON)
    }

    fun bindLegacyLogin(views: FirstRunOnboardingPresenter.LegacyLoginViews) {
        firstRun.bindLegacyLoginViews(views)
    }

    fun publishPermissionTargets() {
        firstRun.publishPermissionTargets()
        publication.publish(NativeUiPublication.Target.PERMISSION_DENIAL_SUMMARY_TEXT)
        publication.publish(NativeUiPublication.Target.PERMISSION_DENIAL_CONFIRM_BUTTON)
        publication.publish(NativeUiPublication.Target.PERMISSION_DENIAL_SETTINGS_BUTTON)
        publication.publish(NativeUiPublication.Target.PERMISSION_DENIAL_PANEL)
    }
}
