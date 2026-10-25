package kr.co.hanium.dreamup.walksafe.session.account

import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckBinding
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.AccountAccessPresenter
import org.junit.Assert.*
import org.junit.Test

class AccountAccessInteractionOwnerTest {
    @Test fun constructionReadsNoProvidersAndPublishesNothing() { val f = Fixture(); assertTrue(f.events.isEmpty()) }
    @Test fun deletionRecoveryRetainsIdentityAndStopsBeforePurgeOrClear() {
        val f = Fixture(); f.deletionRecovery = true; f.owner.onAccountLogoutClicked()
        assertEquals(listOf("cancel-prewalk", "deletion-query", "deletion-fence", "navigation:login=blocked account_deletion_recovery", "speak"), f.events)
        assertEquals(ACTOR, f.reporter); assertFalse(f.events.contains("purge"))
    }
    @Test fun failedPositionPurgeLeavesIdentityAndProgressUntouched() {
        val f = Fixture(); val before = f.snapshot; f.purgeSucceeds = false; f.owner.onAccountLogoutClicked()
        assertSame(before, f.snapshot); assertEquals(ACTOR, f.reporter)
        assertFalse(f.events.contains("clear-session")); assertFalse(f.events.contains("invalidate-official"))
    }
    @Test fun refusedSessionClearDoesNotResetIdentityPermissionsInputsOrProgress() {
        val f = Fixture(); val before = f.snapshot; f.clearResult = null; f.owner.onAccountLogoutClicked()
        assertSame(before, f.snapshot); assertEquals(ACTOR, f.reporter)
        assertFalse(f.events.contains("clear-actor")); assertFalse(f.events.contains("explicit-logout")); assertFalse(f.events.contains("clear-inputs"))
        assertInOrder(f.events, "purge", "walk-query", "invalidate-official", "invalidate-phone", "cancel-training", "field-session-query", "clear-session", "message")
    }
    @Test fun acceptedLogoutPreservesOriginalRetirementResetAndPublicationOrder() {
        val f = Fixture(); f.walkActive = true; f.owner.onAccountLogoutClicked()
        assertNull(f.reporter); assertEquals(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, f.snapshot.stage)
        assertEquals(8L, f.snapshot.epoch); assertTrue(f.clearRemote); assertSame(f.session, f.clearExpectedSession); assertNull(f.clearExpectedGeneration)
        assertInOrder(f.events, "clear-session", "clear-actor", "profile-reset:true", "speech-storage", "explicit-logout", "forget-stored-actor", "clear-legacy-input", "safety-stop", "set-snapshot", "notice", "reset-signup", "clear-inputs", "login-ui", "backend-ui", "privacy-ui", "priority-ui", "first-run-ui", "navigation:login=logged_out independent_preferences_retained", "speak", "startup-ui")
    }
    @Test fun currentSessionShortCircuitsRestoreQueryAndNoSessionUsesCurrentRestorePending() {
        val f = Fixture(); f.owner.onAccountLogoutClicked(); assertFalse(f.events.contains("restore-query"))
        val g = Fixture(); g.session = null; g.restorePending = true; g.owner.onAccountLogoutClicked()
        assertTrue(g.clearRemote); assertInOrder(g.events, "field-session-query", "restore-query", "clear-session")
    }
    @Test fun maximumEpochQueriesFallbackClockOnlyAfterAcceptedClearAndSafetyStop() {
        val f = Fixture(); f.snapshot = FirstRunOnboardingPolicy.initialEmailAccount(Long.MAX_VALUE); f.events.clear(); f.nanos = 0L
        f.owner.onAccountLogoutClicked(); assertEquals(1L, f.snapshot.epoch)
        assertInOrder(f.events, "clear-session", "clear-legacy-input", "nanos", "set-snapshot")
    }
    @Test fun recoveryScopeAndStorageBlockCannotStartSessionButtonLogout() {
        val f = Fixture(); f.recoveryOnly = true; f.owner.onAccountSessionButtonClicked(); assertEquals(listOf("process-query", "message"), f.events)
        val g = Fixture(); g.storageBlocked = true; g.owner.onAccountSessionButtonClicked(); assertEquals(listOf("process-query", "message"), g.events)
    }
    @Test fun deletionRecoverySessionScopeBlocksEvenWithoutRecoveryFlag() {
        val f = Fixture(); f.session = session(GatewaySessionScope.ACCOUNT_DELETION_RECOVERY); f.owner.onAccountSessionButtonClicked()
        assertEquals(listOf("process-query", "message"), f.events); assertEquals(ACTOR, f.reporter)
    }
    @Test fun restorePendingAndCurrentDeviceBindingUseFullLogoutInsteadOfPreservingProgress() {
        val f = Fixture(); f.restorePending = true; f.owner.onAccountSessionButtonClicked(); assertTrue(f.events.contains("cancel-prewalk")); assertTrue(f.events.contains("explicit-logout"))
        val g = Fixture(); g.binding = PostLoginDeviceCheckBinding(ACTOR, 12L, 1L); g.owner.onAccountSessionButtonClicked(); assertTrue(g.events.contains("profile-reset:true"))
    }
    @Test fun noLiveSessionRetainsAdvancedSnapshotAndOnlyRefreshesFirstRun() {
        val f = Fixture(); f.session = null; val before = f.snapshot; f.owner.onAccountSessionButtonClicked()
        assertSame(before, f.snapshot); assertEquals(ACTOR, f.reporter); assertTrue(f.notice!!.contains("세션이 만료"))
        assertEquals(listOf("process-query", "restore-query", "device-binding-query", "notice", "first-run-ui"), f.events)
    }
    @Test fun staleProcessGenerationAndSameSessionAreForwardedWithoutResetOnRefusal() {
        val f = Fixture(); val before = f.snapshot; f.processGeneration = 41L; f.clearResult = null
        f.onDeviceRead = { f.processGeneration = 42L }; f.owner.onAccountSessionButtonClicked()
        assertEquals(41L, f.clearExpectedGeneration); assertSame(f.session, f.clearExpectedSession); assertSame(before, f.snapshot); assertEquals(ACTOR, f.reporter)
        assertFalse(f.events.contains("clear-actor"))
    }
    @Test fun acceptedReauthenticationRetainsAdvancedSnapshotAndCompletedRecord() {
        val f = Fixture(); val before = f.snapshot; f.owner.onAccountSessionButtonClicked()
        assertSame(before, f.snapshot); assertNull(f.reporter); assertEquals(12L, f.clearExpectedGeneration)
        assertTrue(f.events.contains("profile-reset:false")); assertTrue(f.events.contains("auth-expired")); assertFalse(f.events.contains("forget-stored-actor")); assertFalse(f.events.contains("clear-inputs")); assertFalse(f.events.contains("explicit-logout"))
        assertInOrder(f.events, "clear-session", "clear-actor", "profile-reset:false", "speech-storage", "auth-expired", "notice", "first-run-ui", "startup-ui")
    }
    @Test fun unpublishedAccountControlsDoNotReadDomainOrSpeechState() {
        val f = Fixture(); f.published = false; f.owner.updateEmailAccountAccessUi(f.snapshot)
        assertEquals(listOf("publication-query"), f.events); assertNull(f.facts)
    }
    @Test fun uiReadsLiveDeviceBindingBeforeProcessAndEnrollmentAtOriginalLatePoints() {
        val f = Fixture(); f.binding = PostLoginDeviceCheckBinding(ACTOR, 12L, 1L); f.requestInFlight = "pending"; f.owner.updateEmailAccountAccessUi(f.snapshot)
        assertTrue(f.facts!!.authenticated); assertFalse(f.facts!!.reauthenticationRequired); assertTrue(f.facts!!.processRequestInFlight)
        assertEquals(listOf("publication-query", "speech-context", "speech-stage", "render-entry", "device-binding-query", "process-query", "restore-query", "enrollment-block-query", "partial-query", "render-access"), f.events)
    }
    @Test fun unmatchedActorRequiresReauthenticationAndClassifiesRecoveryStorageOrStaleSession() {
        val f = Fixture(); f.binding = PostLoginDeviceCheckBinding(OTHER_ACTOR, 12L, 1L); f.owner.updateEmailAccountAccessUi(f.snapshot)
        assertTrue(f.facts!!.reauthenticationRequired); assertTrue(f.facts!!.staleGeneralSession); assertFalse(f.facts!!.authenticated)
        f.recoveryOnly = true; f.owner.updateEmailAccountAccessUi(f.snapshot); assertTrue(f.facts!!.deletionRecoveryBlocksReauthentication); assertFalse(f.facts!!.staleGeneralSession)
        f.recoveryOnly = false; f.storageBlocked = true; f.owner.updateEmailAccountAccessUi(f.snapshot); assertTrue(f.facts!!.storageBlocksReauthentication); assertFalse(f.facts!!.staleGeneralSession)
    }
    @Test fun enrollmentFactsRemainIndependentQueriesAndReflectMutationsAtEachPoint() {
        val f = Fixture(); f.enrollmentBlocked = true; f.onEnrollmentRead = { f.partial = true }; f.owner.updateEmailAccountAccessUi(f.snapshot)
        assertTrue(f.facts!!.emailEnrollmentStorageBlocked); assertTrue(f.facts!!.hasEnrollmentPartial)
        assertEquals(ACTOR, f.reporter); assertFalse(f.events.contains("clear-session"))
    }
    private class Fixture : AccountAccessInteractionOwner.Gateway, AccountAccessInteractionOwner.Onboarding,
        AccountAccessInteractionOwner.Identity, AccountAccessInteractionOwner.Admission, AccountAccessInteractionOwner.Runtime,
        AccountAccessInteractionOwner.Screen, AccountAccessInteractionOwner.Enrollment, AccountAccessInteractionOwner.Ui {
        val events = mutableListOf<String>()
        var session: GatewayFieldSession? = session()
        var reporter: String? = ACTOR
        override var snapshot = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(FirstRunOnboardingPolicy.initialEmailAccount(7L),
            FirstRunOpaqueActorBinding.fromProvider(ACTOR), FirstRunReceiptHash.fromSha256Hex("a".repeat(64))).current
            set(value) { field = value; events.add("set-snapshot") }
        var deletionRecovery = false; var purgeSucceeds = true; var clearResult: Long? = 13L
        var walkActive = false; var restorePending = false; var recoveryOnly = false; var storageBlocked = false
        var processGeneration = 12L; var binding: PostLoginDeviceCheckBinding? = null; var requestInFlight: String? = null
        var published = true; var enrollmentBlocked = false; var partial = false; var nanos = 999L
        var onDeviceRead: () -> Unit = {}; var onEnrollmentRead: () -> Unit = {}
        var clearRemote = false; var clearExpectedSession: GatewayFieldSession? = null; var clearExpectedGeneration: Long? = null
        var facts: AccountAccessPresenter.AccessFacts? = null
        override var notice: String? = null
            set(value) { field = value; events.add("notice") }
        val owner = AccountAccessInteractionOwner(this, this, this, this, this, this, this, this,
            { events.add("speech-context") }, { events.add("speech-stage") })
        override fun snapshot(): GatewaySessionProcessSnapshot { events.add("process-query"); return GatewaySessionProcessSnapshot(session, null, processGeneration, storageBlocked, requestInFlight, recoveryOnly) }
        override fun backendRestorePending(): Boolean { events.add("restore-query"); return restorePending }
        override fun currentFieldSession(): GatewayFieldSession? { events.add("field-session-query"); return session }
        override fun clear(logoutRemote: Boolean, expectedSession: GatewayFieldSession?, expectedProcessGeneration: Long?): Long? { events.add("clear-session"); clearRemote = logoutRemote; clearExpectedSession = expectedSession; clearExpectedGeneration = expectedProcessGeneration; return clearResult }
        override fun restartEmailAccount() {
            snapshot = FirstRunOnboardingPolicy.initialEmailAccount(if (snapshot.epoch < Long.MAX_VALUE) snapshot.epoch + 1L else {
                events.add("nanos"); nanos.coerceAtLeast(1L)
            })
        }
        override fun currentDeviceBinding(): PostLoginDeviceCheckBinding? { events.add("device-binding-query"); onDeviceRead(); return binding }
        override fun resetPriorityProfile(clearCompletedRecord: Boolean) { events.add("profile-reset:$clearCompletedRecord") }
        override fun clearReporterActor() { events.add("clear-actor"); reporter = null }
        override fun invalidateSpeechStorageBinding() { events.add("speech-storage") }
        override fun explicitLogout() { events.add("explicit-logout") }
        override fun authenticationExpired() { events.add("auth-expired") }
        override fun forgetStoredReporterActor() { events.add("forget-stored-actor") }
        override fun cancelPrewalkPreparation() { events.add("cancel-prewalk") }
        override fun deletionRecoveryRequired(): Boolean { events.add("deletion-query"); return deletionRecovery }
        override fun fenceAccountDeletionRuntime() { events.add("deletion-fence") }
        override fun purgeAndRotatePositionScope(): Boolean { events.add("purge"); return purgeSucceeds }
        override fun walkActive(): Boolean { events.add("walk-query"); return walkActive }
        override fun invalidateOfficialEnvironment(reason: String) { assertEquals("priority_user_account_logged_out", reason); events.add("invalidate-official") }
        override fun invalidatePhoneMounting(reason: String) { assertEquals("priority_user_account_logged_out", reason); events.add("invalidate-phone") }
        override fun cancelPriorityTraining() { events.add("cancel-training") }
        override fun enterWalkSafetyStop(reason: String) { assertEquals("priority_user_account_logged_out", reason); events.add("safety-stop") }
        override fun controlsPublished(): Boolean { events.add("publication-query"); return published }
        override fun resetSignupPresentation() { events.add("reset-signup") }
        override fun clearLegacyLoginInputIfInitialized() { events.add("clear-legacy-input") }
        override fun clearPublishedAccountInputs() { events.add("clear-inputs") }
        override fun renderAccountEntry() { events.add("render-entry") }
        override fun renderAccess(facts: AccountAccessPresenter.AccessFacts) { this.facts = facts; events.add("render-access") }
        override fun storageBlocked(): Boolean { events.add("enrollment-block-query"); onEnrollmentRead(); return enrollmentBlocked }
        override fun hasPartial(): Boolean { events.add("partial-query"); return partial }
        override fun showAccountMessage(message: String) { events.add("message") }
        override fun navigationStatus(message: String) { events.add("navigation:$message") }
        override fun speakInteraction(message: String) { events.add("speak") }
        override fun refreshLoginButton() { events.add("login-ui") }
        override fun refreshBackendAuthButton() { events.add("backend-ui") }
        override fun refreshReportPrivacy() { events.add("privacy-ui") }
        override fun refreshPriorityOnboarding() { events.add("priority-ui") }
        override fun refreshFirstRun() { events.add("first-run-ui") }
        override fun refreshStartupIfInitialized() { events.add("startup-ui") }
    }
    companion object {
        private const val ACTOR = "actor_abcdef0123456789abcdef0123456789"
        private const val OTHER_ACTOR = "actor_abcdef0123456789abcdef0123456788"
        private fun session(scope: GatewaySessionScope = GatewaySessionScope.GENERAL) = GatewayFieldSession.legacyVerified("https://gateway.example.test", ACTOR, "device-test", "walksafe_field_session=account-interaction-test", Long.MAX_VALUE, scope)
        private fun assertInOrder(events: List<String>, vararg values: String) { var start = 0; values.forEach { value -> val i = events.indexOfFirstFrom(start, value); assertTrue("Missing ordered $value: $events", i >= start); start = i + 1 } }
        private fun List<String>.indexOfFirstFrom(start: Int, value: String): Int = indices.firstOrNull { it >= start && this[it] == value } ?: -1
    }
}
