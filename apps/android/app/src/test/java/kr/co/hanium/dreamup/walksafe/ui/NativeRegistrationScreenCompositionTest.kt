package kr.co.hanium.dreamup.walksafe.ui

import android.content.Context
import android.content.ContextWrapper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kr.co.hanium.dreamup.walksafe.session.account.EmailAccountController
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/** Actual SDK-typed panel/presenter instances; allocation skips Android constructors only. */
class NativeRegistrationScreenCompositionTest {
    @Test fun constructionDoesNotReadPeersPublishOrCreatePresenters() {
        val f = Fixture(); assertEquals(0, f.accountQueries); assertFalse(f.owner.registrationInitialized())
        assertTrue(f.published().isEmpty()); uninitialized { f.owner.firstRunPresenter() }; uninitialized { f.owner.devicePresenter() }
    }
    @Test fun registrationOwnsTheSameActualPanelAndSharedStyleWithoutPublishingLaterStages() {
        val f = Fixture(); f.owner.bindRegistration(f.onboarding)
        assertTrue(f.owner.registrationInitialized()); assertSame(f.onboarding, f.owner.firstRunPresenter().registrationBinding())
        assertSame(f.onboarding, field(f.owner.devicePresenter(), "panel")); assertSame(f.style, field(f.owner.devicePresenter(), "style"))
        assertEquals(REGISTRATION, f.published()); assertEquals(0, f.accountQueries)
        uninitialized { f.owner.firstRunPresenter().educationBinding() }; uninitialized { f.owner.firstRunPresenter().readinessBinding() }; uninitialized { f.owner.firstRunPresenter().permissionBinding() }
    }
    @Test fun registrationConsentTailIsPublishedAfterTheCallerCopiesTheLiveConsentButtons() {
        val f = Fixture(); f.owner.bindRegistration(f.onboarding); assertFalse(f.publication.isPublished(NativeUiPublication.Target.FIRST_RUN_CONSENT_ALL_BUTTON))
        f.callerEvents.add("copy-live-consent-buttons"); f.owner.publishRegistrationConsentTail()
        assertEquals(REGISTRATION + REGISTRATION_TAIL, f.published()); assertEquals(listOf("copy-live-consent-buttons"), f.callerEvents)
    }
    @Test fun accountInputBindingUsesTheActualAccountPresenterAndDefersRootPublication() {
        val f = Fixture(); f.owner.bindRegistration(f.onboarding); f.owner.bindAccountInputs(f.accountPanel)
        assertEquals(3, f.accountQueries); assertSame(f.accountPanel.accountAccessStatusText, f.account.statusTarget())
        assertSame(f.accountPanel.accountEmailInput, f.account.inputTarget(EmailAccountController.InputField.EMAIL))
        assertSame(f.accountPanel.accountOtpInput, f.account.inputTarget(EmailAccountController.InputField.OTP))
        assertEquals(REGISTRATION + ACCOUNT_INPUTS, f.published()); uninitialized { f.account.rootTarget() }
    }
    @Test fun callerSpeechRetirementAndSilentRestoreRemainBetweenInputAndRootStages() {
        val f = Fixture(); f.owner.bindRegistration(f.onboarding); f.owner.bindAccountInputs(f.accountPanel)
        f.callerEvents.add("stop-speech"); f.callerEvents.add("clear-speech-view-maps"); f.callerEvents.add("reset-context"); f.callerEvents.add("publish-speech-view-maps")
        assertFalse(f.publication.isPublished(NativeUiPublication.Target.ACCOUNT_ACCESS_CONTROLS))
        f.owner.publishAccountConsentAndRoot(); assertSame(f.accountPanel.accountAccessControls, f.account.rootTarget())
        f.callerEvents.add("restore-saved-consents-silently"); f.callerEvents.add("summary"); f.callerEvents.add("availability")
        assertEquals(listOf("stop-speech", "clear-speech-view-maps", "reset-context", "publish-speech-view-maps", "restore-saved-consents-silently", "summary", "availability"), f.callerEvents)
        assertEquals(REGISTRATION + ACCOUNT_INPUTS + ACCOUNT_ROOT, f.published()); assertEquals(4, f.accountQueries)
    }
    @Test fun bindingNeverInvokesAccountInputClickRefreshOrMessageActions() {
        val f = Fixture(); f.owner.bindRegistration(f.onboarding); f.owner.bindAccountInputs(f.accountPanel); f.owner.publishAccountConsentAndRoot()
        assertEquals(0, f.accountActionCalls); assertTrue(f.callerEvents.isEmpty())
    }
    @Test fun rebindingRetainsUnavailablePolicyWithoutTouchingViewsOrPeerActions() {
        val f = Fixture()
        set(f.account, "unavailableOptionalDocuments", setOf("raw_original"))
        f.owner.bindRegistration(f.onboarding)
        f.owner.bindAccountInputs(f.accountPanel)
        val replacement = f.newAccountPanel()
        f.owner.bindAccountInputs(replacement)
        assertEquals(setOf("raw_original"), field(f.account, "unavailableOptionalDocuments"))
        assertEquals(0, f.accountActionCalls)
        assertSame(replacement.accountEmailInput, f.account.inputTarget(EmailAccountController.InputField.EMAIL))
        assertTrue(f.callerEvents.isEmpty())
    }
    @Test fun educationAndReadinessExposeTheSamePanelOnlyAtTheirOriginalStage() {
        val f = Fixture(); f.owner.bindRegistration(f.onboarding)
        uninitialized { f.owner.firstRunPresenter().educationBinding() }; uninitialized { f.owner.firstRunPresenter().readinessBinding() }
        f.owner.publishEducationAndReadiness(); assertSame(f.onboarding, f.owner.firstRunPresenter().educationBinding()); assertSame(f.onboarding, f.owner.firstRunPresenter().readinessBinding())
        assertEquals(REGISTRATION + EDUCATION_READINESS, f.published()); assertEquals(0, f.accountQueries)
        uninitialized { f.owner.firstRunPresenter().permissionBinding() }
    }
    @Test fun legacyLoginBindsTheSameSdkViewsWithoutPublishingPermissionEarly() {
        val f = Fixture(); f.owner.bindRegistration(f.onboarding)
        val legacy = FirstRunOnboardingPresenter.LegacyLoginViews(allocate(EditText::class.java), allocate(Button::class.java), allocate(Button::class.java))
        f.owner.bindLegacyLogin(legacy); assertSame(legacy, field(f.owner.firstRunPresenter(), "legacyLogin")); assertEquals(REGISTRATION, f.published())
        f.callerEvents.add("login-button-refresh"); f.callerEvents.add("priority-traversal"); f.callerEvents.add("priority-refresh")
        f.owner.publishPermissionTargets(); assertSame(f.onboarding, f.owner.firstRunPresenter().permissionBinding())
        assertEquals(REGISTRATION + PERMISSION, f.published()); assertEquals(listOf("login-button-refresh", "priority-traversal", "priority-refresh"), f.callerEvents)
    }
    @Test fun rebindingRetainsFirstRunNoticeCacheAndReplacesOnlyDevicePresenter() {
        val f = Fixture(); f.owner.bindRegistration(f.onboarding); val presenter = f.owner.firstRunPresenter(); val device = f.owner.devicePresenter()
        set(presenter, "firstRunNoticeExpandedByUser", true); set(presenter, "firstRunDisclosureExpanded", true)
        val replacement = allocate(OnboardingReadinessPanel::class.java); f.owner.bindRegistration(replacement)
        assertSame(presenter, f.owner.firstRunPresenter()); assertSame(replacement, presenter.registrationBinding()); assertNotSame(device, f.owner.devicePresenter())
        assertSame(replacement, field(f.owner.devicePresenter(), "panel")); assertEquals(true, field(presenter, "firstRunNoticeExpandedByUser")); assertEquals(true, field(presenter, "firstRunDisclosureExpanded"))
    }
    @Test fun rebindingDoesNotReplaceOldEducationReadinessPermissionTargetsUntilEachStage() {
        val f = Fixture(); f.owner.bindRegistration(f.onboarding); f.owner.publishEducationAndReadiness(); f.owner.publishPermissionTargets()
        val replacement = allocate(OnboardingReadinessPanel::class.java); f.owner.bindRegistration(replacement); val presenter = f.owner.firstRunPresenter()
        assertSame(f.onboarding, presenter.educationBinding()); assertSame(f.onboarding, presenter.readinessBinding()); assertSame(f.onboarding, presenter.permissionBinding())
        f.owner.publishEducationAndReadiness(); assertSame(replacement, presenter.educationBinding()); assertSame(replacement, presenter.readinessBinding()); assertSame(f.onboarding, presenter.permissionBinding())
        f.owner.publishPermissionTargets(); assertSame(replacement, presenter.permissionBinding())
    }
    @Test fun sharedPublicationKeepsPreviouslyPublishedSettingsAndRuntimeBits() {
        val f = Fixture(); f.publication.publish(NativeUiPublication.Target.PRIVACY_CONTROLS); f.publication.publish(NativeUiPublication.Target.RUNTIME_CONTROLS)
        f.owner.bindRegistration(f.onboarding); f.owner.publishRegistrationConsentTail(); f.owner.bindAccountInputs(f.accountPanel); f.owner.publishAccountConsentAndRoot(); f.owner.publishEducationAndReadiness(); f.owner.publishPermissionTargets()
        assertEquals(REGISTRATION + REGISTRATION_TAIL + ACCOUNT_INPUTS + ACCOUNT_ROOT + EDUCATION_READINESS + PERMISSION + setOf(NativeUiPublication.Target.PRIVACY_CONTROLS, NativeUiPublication.Target.RUNTIME_CONTROLS), f.published())
        assertFalse(f.publication.isPublished(NativeUiPublication.Target.HOME_CARD_GRID)); assertFalse(f.publication.isPublished(NativeUiPublication.Target.NATIVE_VOICE_CONTROLS))
    }
    @Test fun repeatedAccountBindingPublishesTheNewPanelAtInputsThenRootStage() {
        val f = Fixture(); f.owner.bindRegistration(f.onboarding); f.owner.bindAccountInputs(f.accountPanel); f.owner.publishAccountConsentAndRoot()
        val replacement = f.newAccountPanel(); f.owner.bindAccountInputs(replacement)
        assertSame(replacement.accountEmailInput, f.account.inputTarget(EmailAccountController.InputField.EMAIL)); assertSame(f.accountPanel.accountAccessControls, f.account.rootTarget())
        f.owner.publishAccountConsentAndRoot(); assertSame(replacement.accountAccessControls, f.account.rootTarget())
    }
    private class Fixture {
        val context: Context = allocate(ContextWrapper::class.java)
        val style = WalkSafeViewStyle(context)
        val publication = NativeUiPublication()
        val onboarding = allocate(OnboardingReadinessPanel::class.java)
        var accountActionCalls = 0; var accountQueries = 0
        val callerEvents = mutableListOf<String>()
        val account = AccountAccessPresenter(style, AccountAccessPresenter.Actions(
            isRequestBusy = { accountActionCalls++; false }, isGatewayRequestInFlight = { accountActionCalls++; false },
            refreshActionAvailability = { accountActionCalls++ }, refreshAccessUi = { accountActionCalls++ }, showAccountMessage = { accountActionCalls++ },
        ))
        val accountPanel = newAccountPanel()
        val owner = NativeRegistrationScreenComposition(context, style, publication) { accountQueries++; account }
        fun published() = NativeUiPublication.Target.entries.filter(publication::isPublished).toSet()
        fun newAccountPanel(): AccountAccessPanel = allocate(AccountAccessPanel::class.java).apply {
            set(this, "accountAccessStatusText", allocate(TextView::class.java)); set(this, "accountAccessControls", allocate(LinearLayout::class.java))
            listOf("accountEmailInput", "accountDateOfBirthInput", "accountPasswordInput", "accountPasswordConfirmationInput", "accountOtpInput").forEach { set(this, it, allocate(EditText::class.java)) }
        }
    }
    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        private fun <T> allocate(type: Class<T>): T = type.cast(unsafe.allocateInstance(type))
        private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
        private fun set(value: Any, name: String, field: Any?) { value.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(value, field) }
        private fun uninitialized(action: () -> Any?) { try { action(); fail("Target must remain unpublished") } catch (_: UninitializedPropertyAccessException) { } }
        private fun targets(vararg names: String) = names.map(NativeUiPublication.Target::valueOf).toSet()
        private val REGISTRATION = targets("PRODUCT_PURPOSE_TEXT", "FIRST_RUN_NOTICE_TOGGLE_BUTTON", "FIRST_RUN_PROGRESS_BAR", "FIRST_RUN_ONBOARDING_STATUS_TEXT", "FIRST_RUN_INTEGRATED_CONSENT_DISCLOSURE_TEXT", "FIRST_RUN_DISCLOSURE_TOGGLE_BUTTON", "FIRST_RUN_CONSENT_COUNT_TEXT")
        private val REGISTRATION_TAIL = targets("FIRST_RUN_CONSENT_ALL_BUTTON", "FIRST_RUN_INTEGRATED_CONSENT_SAVE_BUTTON", "FIRST_RUN_WAITING_CARD")
        private val ACCOUNT_INPUTS = targets("ACCOUNT_ACCESS_STATUS_TEXT", "ACCOUNT_EMAIL_INPUT", "ACCOUNT_DATE_OF_BIRTH_INPUT", "ACCOUNT_PASSWORD_INPUT", "ACCOUNT_PASSWORD_CONFIRMATION_INPUT", "ACCOUNT_OTP_INPUT")
        private val ACCOUNT_ROOT = targets("ACCOUNT_CONSENT_ALL_CHECK", "ACCOUNT_CONSENT_SUMMARY_TEXT", "ACCOUNT_CONSENT_CONTINUE_BUTTON", "ACCOUNT_REQUEST_OTP_BUTTON", "ACCOUNT_CREATE_BUTTON", "ACCOUNT_LOGIN_BUTTON", "ACCOUNT_SIGNUP_CONTROLS", "ACCOUNT_ACCESS_CONTROLS")
        private val EDUCATION_READINESS = targets("PRIORITY_USER_ONBOARDING_STATUS_TEXT", "PRIORITY_USER_EDUCATION_AGREE_BUTTON", "FIRST_RUN_PHONE_POSTURE_BUTTON", "FIRST_RUN_PHONE_POSTURE_CONTROLS", "OFFICIAL_ENVIRONMENT_STATUS_TEXT", "OFFICIAL_ENVIRONMENT_CONFIRM_BUTTON", "PHONE_MOUNTING_STATUS_TEXT", "PHONE_MOUNTING_CHEST_CONFIRM_BUTTON", "PHONE_MOUNTING_NECKLACE_CONFIRM_BUTTON", "STARTUP_CAPABILITY_TEXT", "POST_LOGIN_DEVICE_CHECK_LIVE_STATUS_TEXT", "STARTUP_METRIC_PREFLIGHT_BUTTON", "STARTUP_CAPABILITY_CONFIRM_BUTTON")
        private val PERMISSION = targets("PERMISSION_DENIAL_SUMMARY_TEXT", "PERMISSION_DENIAL_CONFIRM_BUTTON", "PERMISSION_DENIAL_SETTINGS_BUTTON", "PERMISSION_DENIAL_PANEL")
    }
}
