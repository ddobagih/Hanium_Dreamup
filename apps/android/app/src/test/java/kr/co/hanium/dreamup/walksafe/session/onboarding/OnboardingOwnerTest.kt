package kr.co.hanium.dreamup.walksafe.session.onboarding

import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.readiness.allowsRequirement
import kr.co.hanium.dreamup.walksafe.voice.SpeechOutputControllerBehaviorTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnboardingOwnerTest {
    @Test fun constructorsAndInitializedQueriesDoNotReadPeersOrStorage() {
        val f = Fixture()
        assertFalse(f.owner.initialized)
        assertFalse(f.profiles.policyInitialized)
        assertTrue(f.events.isEmpty())
        assertNull(f.profiles.actorId)
        assertNull(f.profiles.nativeCompletedRecord)
    }

    @Test fun requestAndReceiptKeepOriginalEpochRevisionStageIdentity() {
        val f = Fixture()
        val snapshot = FirstRunOnboardingPolicy.initial(3)
        f.owner.restoreSessionSnapshot(snapshot)
        val request = f.owner.firstRunLocalRequest("purpose_and_safety")
        assertEquals("req_" + hash("FP010|request|purpose_and_safety|3|0|PURPOSE_AND_SAFETY".toByteArray()), request.requestId)
        assertEquals("att_" + hash("FP010|attempt|purpose_and_safety|3|0|PURPOSE_AND_SAFETY".toByteArray()), request.attemptId)
        assertEquals(hash("FP010|purpose_and_safety|3|0|PURPOSE_AND_SAFETY".toByteArray()),
            f.owner.firstRunLocalReceipt("purpose_and_safety").value)
        assertSame(snapshot, f.owner.snapshot)
    }

    @Test fun failedDisclosureAckCannotAdvancePurposeOrCreateAReceipt() {
        val f = Fixture()
        f.owner.restoreSessionSnapshot(FirstRunOnboardingPolicy.initial())
        val before = f.owner.snapshot
        f.disclosure = false
        f.owner.acknowledgeFirstRunPurposeAndSafety()
        assertSame(before, f.owner.snapshot)
        assertEquals(listOf("disclosure", "status:안전 고지 저장 실패"), f.events)
    }

    @Test fun under14SelectionUsesExistingTerminalPolicyAndDoesNotBindAnActor() {
        val f = Fixture()
        f.owner.restoreSessionSnapshot(FirstRunOnboardingPolicy.initial())
        f.owner.acknowledgeFirstRunPurposeAndSafety()
        f.owner.selectFirstRunAgeBand(FirstRunAgeBand.UNDER_14)
        assertEquals(FirstRunOnboardingStage.BLOCKED_UNDER_14, f.owner.snapshot.stage)
        assertNull(f.owner.snapshot.verifiedActorBinding)
        assertEquals(setOf(FirstRunOnboardingStage.PURPOSE_AND_SAFETY, FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED),
            f.owner.snapshot.completedReceiptHashes.keys)
    }

    @Test fun backgroundTrainingCurrentnessShortCircuitsBeforeUninitializedPolicyOrSnapshot() {
        val f = Fixture()
        f.foreground = false
        assertFalse(f.owner.isTrainingDeliveryCurrent(1, 1, ACTOR, PriorityUserOnboardingPolicy()))
        assertEquals(listOf("foreground"), f.events)
    }

    @Test fun trainingCurrentnessRequiresGenerationActorPolicyIdentityAndLiveDeviceGate() {
        val f = Fixture().training()
        val policy = f.profiles.policy
        assertTrue(f.owner.isTrainingDeliveryCurrent(5, 5, ACTOR, policy))
        assertFalse(f.owner.isTrainingDeliveryCurrent(4, 5, ACTOR, policy))
        assertFalse(f.owner.isTrainingDeliveryCurrent(5, 5, ACTOR, PriorityUserOnboardingPolicy(policy.snapshot())))
        f.gate = false
        assertFalse(f.owner.isTrainingDeliveryCurrent(5, 5, ACTOR, policy))
    }

    @Test fun failedInvalidMarkerResetsPolicyIdentityAndBlocksCompletionRestoreInMemory() {
        val f = Fixture().training()
        val policy = f.profiles.policy
        f.storage.markerAccepted = false
        assertFalse(f.profiles.beginPriorityUserProfileMutationOrFailClosed())
        assertNotSame(policy, f.profiles.policy)
        assertTrue(f.profiles.blockedForActorHash(hash(ACTOR.toByteArray())))
        assertNull(f.profiles.restoreCompletedNativeOnboarding(ACTOR))
    }

    @Test fun saveRequiresDurableInvalidMarkerBeforeProfileSerialization() {
        val f = Fixture().training()
        assertFalse(f.profiles.persistPriorityUserOnboarding())
        assertFalse(f.events.any { it == "write" })
        assertTrue(f.profiles.beginPriorityUserProfileMutationOrFailClosed())
        assertTrue(f.profiles.persistPriorityUserOnboarding())
        assertTrue(f.events.indexOf("marker") < f.events.indexOf("edit"))
        assertTrue(f.events.indexOf("edit") < f.events.indexOf("write"))
        assertFalse(f.storage.booleans[f.profiles.priorityUserProfileInvalidKey(ACTOR)] == true)
    }

    @Test fun storageAckFailureResetsPolicyAndCannotBeRestoredByAChangedMarkerAlone() {
        val f = Fixture().training()
        val policy = f.profiles.policy
        f.profiles.beginPriorityUserProfileMutationOrFailClosed()
        f.storage.writeAccepted = false
        assertFalse(f.profiles.persistPriorityUserOnboardingOrFailClosed())
        assertNotSame(policy, f.profiles.policy)
        f.storage.booleans.clear()
        assertNull(f.profiles.restoreCompletedNativeOnboarding(ACTOR))
    }

    @Test fun malformedPracticeOrderIsRemovedAndMemoryBlockedInsteadOfRestored() {
        val f = Fixture().training()
        f.profiles.beginPriorityUserProfileMutationOrFailClosed()
        f.profiles.persistPriorityUserOnboarding()
        val key = f.profiles.priorityUserProfileKey(ACTOR)
        val malformed = JSONObject(f.storage.strings.getValue(key))
            .put("completed_practices", JSONArray(listOf("RESUME")))
        f.storage.strings[key] = malformed.toString()
        f.profiles.restorePriorityUserOnboardingFromPrefs()
        assertFalse(f.storage.strings.containsKey(key))
        assertTrue(f.profiles.blockedForActorHash(hash(ACTOR.toByteArray())))
        assertEquals(PriorityUserOnboardingSnapshot(), f.profiles.policy.snapshot())
    }

    @Test fun existingProfileAndNativeRecordRoundTripWithoutChangingEducationData() {
        val f = Fixture().training()
        val before = f.profiles.policy.snapshot()
        val completed = completeEmail(f.owner.snapshot)
        assertTrue(f.profiles.persistNativeCompletedOnboarding(completed))
        val saved = f.profiles.nativeCompletedRecord!!
        f.profiles.restorePriorityUserOnboardingFromPrefs()
        assertEquals(before, f.profiles.policy.snapshot())
        assertEquals(saved.toString(), f.profiles.nativeCompletedRecord!!.toString())
        assertEquals(completed, f.profiles.restoreCompletedNativeOnboarding(ACTOR))
    }

    @Test fun changedInstallationRequiresDeviceChecksAndEducationWithoutDeletingThePreviousPhonesRecord() {
        val f = Fixture().training()
        val completed = completeEmail(f.owner.snapshot)
        assertTrue(f.profiles.persistNativeCompletedOnboarding(completed))
        val key = f.profiles.priorityUserProfileKey(ACTOR)
        val saved = f.storage.strings.getValue(key)
        f.installation = "replacement-installation"
        f.gate = false
        assertNull(f.profiles.restoreCompletedNativeOnboarding(ACTOR))
        f.owner.restartEmailAccount { 1L }
        val login = requireNotNull(f.owner.prepareVerifiedEmailLogin(
            FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(), null))
        assertTrue(f.owner.applyVerifiedEmailLogin(login))
        f.profiles.restorePriorityUserOnboardingFromPrefs()
        assertEquals(PriorityUserOnboardingSnapshot(), f.profiles.policy.snapshot())
        assertNull(f.profiles.nativeCompletedRecord)

        f.owner.bindFirstRunVerifiedActorForTraining()

        assertEquals(FirstRunOnboardingStage.PURPOSE_AND_SAFETY, f.owner.snapshot.stage)
        assertFalse(f.owner.snapshot.isComplete)
        assertFalse(FirstRunOnboardingStage.FP004_TRAINING in f.owner.snapshot.completedReceiptHashes)
        assertFalse(f.profiles.policy.snapshot().nativeEducationComplete)
        assertTrue(f.profiles.policy.snapshot().completedPractices.isEmpty())
        assertEquals(saved, f.storage.strings.getValue(key))
        assertFalse(f.profiles.blockedForActorHash(hash(ACTOR.toByteArray())))
        assertFalse(f.storage.booleans[f.profiles.priorityUserProfileInvalidKey(ACTOR)] == true)
        f.installation = "installation"
        f.profiles.restorePriorityUserOnboardingFromPrefs()
        assertEquals(nativeEducation(), f.profiles.policy.snapshot())
        assertEquals(completed, f.profiles.restoreCompletedNativeOnboarding(ACTOR))
        assertEquals(saved, f.storage.strings.getValue(key))
    }

    @Test fun partialPracticeIsInstallationBoundWithoutRemovingThePreviousPhonesProgress() {
        val f = Fixture().training()
        val partial = nativeEducation().copy(completedPractices = PriorityUserPractice.entries.take(1).toSet(),
            hazardResponseConfirmed = false, appUsageAccepted = false)
        f.profiles.policy = PriorityUserOnboardingPolicy(partial)
        assertTrue(f.profiles.beginPriorityUserProfileMutationOrFailClosed())
        assertTrue(f.profiles.persistPriorityUserOnboarding())
        val key = f.profiles.priorityUserProfileKey(ACTOR)
        val saved = f.storage.strings.getValue(key)
        assertEquals("installation", JSONObject(saved).getString("installation_id"))
        f.installation = "replacement-installation"

        f.profiles.restorePriorityUserOnboardingFromPrefs()

        assertEquals(PriorityUserOnboardingSnapshot(), f.profiles.policy.snapshot())
        assertNull(f.profiles.nativeCompletedRecord)
        assertEquals(saved, f.storage.strings.getValue(key))
        assertFalse(f.profiles.blockedForActorHash(hash(ACTOR.toByteArray())))
        f.installation = "installation"
        f.profiles.restorePriorityUserOnboardingFromPrefs()
        assertEquals(partial, f.profiles.policy.snapshot())
        assertEquals(saved, f.storage.strings.getValue(key))
    }

    @Test fun legacyPartialProfileWithoutAnyInstallationIdentityRetainsItsExistingProgress() {
        val f = Fixture().training()
        val partial = nativeEducation().copy(completedPractices = PriorityUserPractice.entries.take(1).toSet(),
            hazardResponseConfirmed = false, appUsageAccepted = false)
        f.profiles.policy = PriorityUserOnboardingPolicy(partial)
        assertTrue(f.profiles.beginPriorityUserProfileMutationOrFailClosed())
        assertTrue(f.profiles.persistPriorityUserOnboarding())
        val key = f.profiles.priorityUserProfileKey(ACTOR)
        val legacy = JSONObject(f.storage.strings.getValue(key))
        legacy.remove("installation_id")
        f.storage.strings[key] = legacy.toString()
        val saved = f.storage.strings.getValue(key)

        f.profiles.restorePriorityUserOnboardingFromPrefs()

        assertEquals(partial, f.profiles.policy.snapshot())
        assertEquals(saved, f.storage.strings.getValue(key))
        assertNull(f.profiles.nativeCompletedRecord)
    }

    @Test fun legacyNativeRecordStillBindsEducationWhenTheTopLevelInstallationIdIsMissing() {
        val f = Fixture().training()
        assertTrue(f.profiles.persistNativeCompletedOnboarding(completeEmail(f.owner.snapshot)))
        val key = f.profiles.priorityUserProfileKey(ACTOR)
        val legacy = JSONObject(f.storage.strings.getValue(key))
        legacy.remove("installation_id")
        f.storage.strings[key] = legacy.toString()
        val saved = f.storage.strings.getValue(key)
        f.installation = "replacement-installation"

        f.profiles.restorePriorityUserOnboardingFromPrefs()

        assertEquals(PriorityUserOnboardingSnapshot(), f.profiles.policy.snapshot())
        assertNull(f.profiles.restoreCompletedNativeOnboarding(ACTOR))
        assertEquals(saved, f.storage.strings.getValue(key))
        f.installation = "installation"
        f.profiles.restorePriorityUserOnboardingFromPrefs()
        assertEquals(nativeEducation(), f.profiles.policy.snapshot())
        assertNotNull(f.profiles.restoreCompletedNativeOnboarding(ACTOR))
    }

    @Test fun missingInstallationIdCannotOverwriteOrRemoveAnExistingProfile() {
        val f = Fixture().training()
        assertTrue(f.profiles.beginPriorityUserProfileMutationOrFailClosed())
        assertTrue(f.profiles.persistPriorityUserOnboarding())
        val key = f.profiles.priorityUserProfileKey(ACTOR)
        val saved = f.storage.strings.getValue(key)
        f.installation = null
        assertTrue(f.profiles.beginPriorityUserProfileMutationOrFailClosed())

        assertFalse(f.profiles.persistPriorityUserOnboardingOrFailClosed())

        assertEquals(saved, f.storage.strings.getValue(key))
        assertTrue(f.storage.booleans[f.profiles.priorityUserProfileInvalidKey(ACTOR)] == true)
        assertFalse(f.events.contains("remove"))
    }

    @Test fun changedProbePolicyRequiresHardwareChecksWithoutChangingSavedEducation() {
        val f = Fixture().training()
        assertTrue(f.profiles.persistNativeCompletedOnboarding(completeEmail(f.owner.snapshot)))
        val key = f.profiles.priorityUserProfileKey(ACTOR)
        val stored = JSONObject(f.storage.strings.getValue(key))
        stored.getJSONObject("native_completed_first_run").put("probe_policy_version", "old-probe-policy")
        f.storage.strings[key] = stored.toString()
        val saved = f.storage.strings.getValue(key)

        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION,
            f.profiles.restoreCompletedNativeOnboarding(ACTOR)?.stage)
        f.profiles.restorePriorityUserOnboardingFromPrefs()
        assertTrue(f.profiles.policy.snapshot().nativeEducationComplete)
        assertEquals(saved, f.storage.strings.getValue(key))
    }

    @Test fun missingNativeRecordKeepsRealCompletedEducationWithoutInventingFlowReceipts() {
        val f = Fixture().training()
        assertTrue(f.profiles.beginPriorityUserProfileMutationOrFailClosed())
        assertTrue(f.profiles.persistPriorityUserOnboarding())
        val saved = f.storage.strings.toMap()

        assertNull(f.profiles.restoreCompletedNativeOnboarding(ACTOR))
        f.profiles.restorePriorityUserOnboardingFromPrefs()
        assertEquals(nativeEducation(), f.profiles.policy.snapshot())
        assertNull(f.profiles.nativeCompletedRecord)
        assertEquals(saved, f.storage.strings)
    }

    @Test fun missingActualPracticeOrUsageFlagsCannotRestoreACompletedFlow() {
        for (missing in listOf("hazard_response_confirmed", "app_usage_accepted", "usage_conditions_acknowledged")) {
            val f = Fixture().training()
            assertTrue(f.profiles.persistNativeCompletedOnboarding(completeEmail(f.owner.snapshot)))
            val key = f.profiles.priorityUserProfileKey(ACTOR)
            val profile = JSONObject(f.storage.strings.getValue(key)).put(missing, false)
            f.storage.strings[key] = profile.toString()
            val saved = f.storage.strings.getValue(key)

            assertNull(f.profiles.restoreCompletedNativeOnboarding(ACTOR))
            assertEquals(saved, f.storage.strings.getValue(key))
        }
    }

    @Test fun emailReauthenticationRetainsTheSameActorsMoreSpecificAgeAndPartialPractice() {
        for (coldStart in listOf(false, true)) {
            val f = Fixture().training()
            val partial = nativeEducation().copy(ageBand = PriorityUserAgeBand.ADULT_18_PLUS,
                completedPractices = PriorityUserPractice.entries.take(1).toSet(),
                hazardResponseConfirmed = false, appUsageAccepted = false)
            f.profiles.policy = PriorityUserOnboardingPolicy(partial)
            assertTrue(f.profiles.beginPriorityUserProfileMutationOrFailClosed())
            assertTrue(f.profiles.persistPriorityUserOnboarding())
            val saved = f.storage.strings.toMap()
            if (coldStart) f.owner.restartEmailAccount { 1L }
            val before = f.owner.snapshot
            val prepared = requireNotNull(f.owner.prepareVerifiedEmailLogin(
                FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(), null))
            if (coldStart) assertEquals(FirstRunOnboardingStage.PURPOSE_AND_SAFETY, prepared.snapshot.stage)
            else assertSame(before, prepared.snapshot)
            assertTrue(f.owner.applyVerifiedEmailLogin(prepared))

            f.owner.bindFirstRunVerifiedActorForTraining()

            assertEquals(partial, f.profiles.policy.snapshot())
            assertFalse(f.profiles.policy.snapshot().nativeEducationComplete)
            assertEquals(saved, f.storage.strings)
        }
    }

    @Test fun anotherActorCannotBorrowStoredEducationOrOverwriteThePreviousActorsData() {
        val f = Fixture().training()
        assertTrue(f.profiles.persistNativeCompletedOnboarding(completeEmail(f.owner.snapshot)))
        val saved = f.storage.strings.toMap()
        val other = "actor_abcdef0123456789abcdef0123456788"
        assertNull(f.profiles.restoreCompletedNativeOnboarding(other))
        val prepared = requireNotNull(f.owner.prepareVerifiedEmailLogin(
            FirstRunOpaqueActorBinding.fromProvider(other), receipt(), null))
        assertTrue(f.owner.applyVerifiedEmailLogin(prepared))

        f.owner.bindFirstRunVerifiedActorForTraining()

        assertEquals(other, f.profiles.actorId)
        assertFalse(f.profiles.policy.snapshot().nativeEducationComplete)
        assertTrue(f.profiles.policy.snapshot().completedPractices.isEmpty())
        assertEquals(saved, f.storage.strings)
        assertNotNull(f.profiles.restoreCompletedNativeOnboarding(ACTOR))
    }

    @Test fun completionIsBlockedWithoutTheEducationOwnerEvidence() {
        val f = Fixture().training()
        f.educationComplete = false
        val before = f.owner.snapshot
        assertFalse(f.owner.completeFirstRunFp004TrainingIfReady(ACTOR))
        assertSame(before, f.owner.snapshot)
        assertFalse(f.events.contains("marker"))
        assertFalse(f.events.contains("publish-complete"))
    }

    @Test fun nativeCompletionSaveFailureDoesNotPublishOrApplyCompleteSnapshot() {
        val f = Fixture().training()
        val before = f.owner.snapshot
        f.storage.writeAccepted = false
        assertFalse(f.owner.completeFirstRunFp004TrainingIfReady(ACTOR))
        assertSame(before, f.owner.snapshot)
        assertFalse(f.events.contains("publish-complete"))
        assertFalse(f.events.contains("refresh-consent"))
    }

    @Test fun changedSessionPublishAckCannotApplyLocallySavedCompleteSnapshot() {
        val f = Fixture().training()
        val before = f.owner.snapshot
        f.publishAccepted = false
        assertFalse(f.owner.completeFirstRunFp004TrainingIfReady(ACTOR))
        assertSame(before, f.owner.snapshot)
        assertNotNull(f.profiles.nativeCompletedRecord)
        assertTrue(f.events.indexOf("write") < f.events.indexOf("publish-complete"))
        assertFalse(f.events.contains("refresh-consent"))
    }

    @Test fun successfulCompletionCommitsNativeRecordThenPublishesThenAppliesSnapshot() {
        val f = Fixture().training()
        f.beforePublish = { assertEquals(FirstRunOnboardingStage.FP004_TRAINING, f.owner.snapshot.stage) }
        assertTrue(f.owner.completeFirstRunFp004TrainingIfReady(ACTOR))
        assertEquals(FirstRunOnboardingStage.COMPLETE, f.owner.snapshot.stage)
        assertTrue(f.events.indexOf("write") < f.events.indexOf("publish-complete"))
        assertEquals(FirstRunOnboardingStage.COMPLETE, f.profiles.restoreCompletedNativeOnboarding(ACTOR)?.stage)
    }

    @Test fun firstEmailEducationCompletionRefreshesConsentAfterApplyingCompleteState() {
        val f = Fixture().training()

        assertTrue(f.owner.completeFirstRunFp004TrainingIfReady(ACTOR))

        assertEquals(1, f.events.count { it == "refresh-consent" })
        assertTrue(f.events.indexOf("publish-complete") < f.events.indexOf("refresh-consent"))
        assertTrue(f.events.indexOf("onboarding-ui") < f.events.indexOf("refresh-consent"))
        assertTrue(f.owner.snapshot.isComplete)
        assertFalse(f.owner.completeFirstRunFp004TrainingIfReady(ACTOR))
        assertEquals(1, f.events.count { it == "refresh-consent" })
    }

    @Test fun consentRefreshStartFailureKeepsEducationCompletionCommitted() {
        val f = Fixture().training()
        f.consentRefreshThrows = true

        assertTrue(f.owner.completeFirstRunFp004TrainingIfReady(ACTOR))

        assertEquals(1, f.events.count { it == "refresh-consent" })
        assertTrue(f.owner.snapshot.isComplete)
        assertEquals(FirstRunOnboardingStage.COMPLETE, f.profiles.restoreCompletedNativeOnboarding(ACTOR)?.stage)
    }

    @Test fun resetRetiresEducationPeerAndEvidenceBeforeRejectingAnUnboundActor() {
        val f = Fixture().training()
        f.profiles.actorId = "other"
        f.owner.resetPriorityUserTraining()
        assertEquals(listOf("cancel-education", "invalidate-env:priority_user_training_reset",
            "invalidate-mount:priority_user_training_reset", "reporter", "speak"), f.events.take(5))
    }

    @Test fun lateDiskRestoreCannotUndoPermissionObservation() {
        val f = Fixture()
        f.owner.restoreSessionSnapshot(emailPermission())
        val expected = f.owner.snapshot
        assertTrue(f.owner.recordEmailPermissionObservation())
        val current = f.owner.snapshot
        assertFalse(f.owner.restoreSessionSnapshot(expected, expected))
        assertSame(current, f.owner.snapshot)
        assertEquals(FirstRunOnboardingStage.DEVICE_CHECK, current.stage)
    }

    @Test fun currentMatchingSessionCanResumeWithoutReplacingNewerPermissionProgress() {
        val f = Fixture()
        val permission = emailPermission()
        f.owner.restoreSessionSnapshot(permission)
        assertTrue(f.owner.recordEmailPermissionObservation())
        val current = f.owner.snapshot
        assertTrue(f.owner.restoreSessionSnapshot(permission, current))
        assertSame(current, f.owner.snapshot)
        assertTrue(f.events.isEmpty())
    }

    @Test fun lateDiskRestoreCannotUndoConfirmedConsent() {
        val f = Fixture()
        f.owner.restoreSessionSnapshot(FirstRunOnboardingPolicy.initial())
        f.owner.acknowledgeFirstRunPurposeAndSafety()
        f.owner.selectFirstRunAgeBand(FirstRunAgeBand.ADULT_18_PLUS)
        val expected = f.owner.snapshot
        assertTrue(f.owner.completeIntegratedConsent(FirstRunReceiptHash.fromSha256Hex("c".repeat(64))) { true })
        val current = f.owner.snapshot
        assertFalse(f.owner.restoreSessionSnapshot(expected, expected))
        assertSame(current, f.owner.snapshot)
    }

    @Test fun changedConsentWhileVerifyingDoesNotPublishOrAdvance() {
        val f = Fixture()
        f.owner.restoreSessionSnapshot(FirstRunOnboardingPolicy.initial())
        f.owner.acknowledgeFirstRunPurposeAndSafety()
        f.owner.selectFirstRunAgeBand(FirstRunAgeBand.ADULT_18_PLUS)
        val before = f.owner.snapshot
        f.events.clear()
        var reads = 0
        assertFalse(f.owner.completeIntegratedConsent(FirstRunReceiptHash.fromSha256Hex("d".repeat(64))) { ++reads == 1 })
        assertSame(before, f.owner.snapshot)
        assertTrue(f.events.isEmpty())
    }

    @Test fun accountCreationCleanupFailureAndLateLoginCannotOverwriteTheCurrentAttempt() {
        val f = Fixture()
        f.owner.initializeEmailAccount(8)
        assertTrue(f.owner.recordEmailOtpEnrollment(FirstRunReceiptHash.fromSha256Hex("a".repeat(64))))
        val before = f.owner.snapshot
        assertFalse(f.owner.recordEmailAccountCreated(FirstRunReceiptHash.fromSha256Hex("b".repeat(64))) { false })
        assertSame(before, f.owner.snapshot)
        f.owner.restartEmailAccount { 1L }
        val prepared = requireNotNull(f.owner.prepareVerifiedEmailLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR),
            FirstRunReceiptHash.fromSha256Hex("c".repeat(64)), null))
        f.owner.restartEmailAccount { 1L }
        val reset = f.owner.snapshot
        assertFalse(f.owner.applyVerifiedEmailLogin(prepared))
        assertSame(reset, f.owner.snapshot)
    }

    @Test fun anotherVerifiedActorStartsFreshWithoutTransferringAnyReceiptOrTraining() {
        val purpose = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(FirstRunOnboardingPolicy.initialEmailAccount(),
            FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt()).current
        val permission = emailPermission()
        val device = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(permission, permission.epoch, permission.revision).current
        for (before in listOf(purpose, permission, device, emailTraining(), completeEmail(emailTraining()), restoredEmailPrefix())) {
            val f = Fixture()
            f.owner.restoreSessionSnapshot(before)
            val actor = FirstRunOpaqueActorBinding.fromProvider("actor_abcdef0123456789abcdef0123456788")
            val verifiedReceipt = FirstRunReceiptHash.fromSha256Hex("f".repeat(64))
            val prepared = requireNotNull(f.owner.prepareVerifiedEmailLogin(actor, verifiedReceipt, null))
            assertSame(before, f.owner.snapshot)
            assertFalse(prepared.reauthenticatesExistingProgress)
            assertEquals(before.epoch + 1L, prepared.snapshot.epoch)
            assertEquals(1L, prepared.snapshot.revision)
            assertEquals(FirstRunOnboardingStage.PURPOSE_AND_SAFETY, prepared.snapshot.stage)
            assertEquals(actor, prepared.snapshot.verifiedActorBinding)
            assertEquals(mapOf(FirstRunOnboardingStage.VERIFIED_LOGIN to verifiedReceipt), prepared.snapshot.completedReceiptHashes)
            assertNull(prepared.snapshot.pendingAttempt)
            assertNull(prepared.snapshot.localCredentialPhoneSubmissionHandle)
            assertFalse(prepared.snapshot.mayEnterWalk)
            assertTrue(f.events.isEmpty())
            assertTrue(f.owner.applyVerifiedEmailLogin(prepared))
            assertSame(prepared.snapshot, f.owner.snapshot)
        }
    }

    @Test fun sameVerifiedActorRetainsPartialCompleteAndRestoredTrainingProgress() {
        val purpose = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(FirstRunOnboardingPolicy.initialEmailAccount(),
            FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt()).current
        val permission = emailPermission()
        val device = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(permission, permission.epoch, permission.revision).current
        for (before in listOf(purpose, permission, device, emailTraining(), completeEmail(emailTraining()), restoredEmailPrefix())) {
            val f = Fixture()
            f.owner.restoreSessionSnapshot(before)
            val prepared = requireNotNull(f.owner.prepareVerifiedEmailLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR),
                FirstRunReceiptHash.fromSha256Hex("f".repeat(64)), null))
            assertTrue(prepared.reauthenticatesExistingProgress)
            assertSame(before, prepared.snapshot)
            assertTrue(f.owner.applyVerifiedEmailLogin(prepared))
            assertSame(before, f.owner.snapshot)
        }
        val f = Fixture()
        val before = emailPermission()
        val completed = completeEmail(emailTraining())
        f.owner.restoreSessionSnapshot(before)
        val prepared = requireNotNull(f.owner.prepareVerifiedEmailLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(), completed))
        assertSame(before, f.owner.snapshot)
        assertTrue(prepared.reauthenticatesExistingProgress)
        assertSame(completed, prepared.snapshot)
    }

    @Test fun cachedDeviceRecheckCannotRollBackPermissionProgressDuringReauthentication() {
        val f = Fixture().training()
        assertTrue(f.profiles.persistNativeCompletedOnboarding(completeEmail(f.owner.snapshot)))
        val key = f.profiles.priorityUserProfileKey(ACTOR)
        val stored = JSONObject(f.storage.strings.getValue(key))
        stored.getJSONObject("native_completed_first_run").put("probe_policy_version", "old-probe-policy")
        f.storage.strings[key] = stored.toString()
        val cached = requireNotNull(f.profiles.restoreCompletedNativeOnboarding(ACTOR))
        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, cached.stage)
        assertTrue(f.owner.restoreSessionSnapshot(cached))
        assertTrue(f.owner.recordEmailPermissionObservation())
        val current = f.owner.snapshot
        assertEquals(FirstRunOnboardingStage.DEVICE_CHECK, current.stage)
        val restoredAgain = requireNotNull(f.profiles.restoreCompletedNativeOnboarding(ACTOR))
        assertTrue(restoredAgain.revision < current.revision)

        val prepared = requireNotNull(f.owner.prepareVerifiedEmailLogin(
            FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(), restoredAgain))

        assertSame(current, prepared.snapshot)
        assertTrue(prepared.reauthenticatesExistingProgress)
        assertSame(current, f.owner.snapshot)
        assertTrue(f.owner.applyVerifiedEmailLogin(prepared))
        assertSame(current, f.owner.snapshot)
        assertEquals(FirstRunOnboardingStage.DEVICE_CHECK, f.owner.snapshot.stage)
    }

    @Test fun reauthenticationPrefersANewerLocalEpochOverAHigherCachedRevision() {
        val f = Fixture()
        val cached = completeEmail(emailTraining())
        val newerPermission = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(cached.epoch + 1L,
            listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt()),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt())),
            FirstRunOnboardingEvidenceVerifier { _, _ -> true }).snapshot
        val current = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
            newerPermission, newerPermission.epoch, newerPermission.revision).current
        assertTrue(current.epoch > cached.epoch)
        assertTrue(current.revision < cached.revision)
        f.owner.restoreSessionSnapshot(current)

        val prepared = requireNotNull(f.owner.prepareVerifiedEmailLogin(
            FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(), cached))

        assertSame(current, prepared.snapshot)
        assertTrue(f.owner.applyVerifiedEmailLogin(prepared))
        assertSame(current, f.owner.snapshot)
    }

    @Test fun equallyVersionedCachedConflictKeepsLocalPermissionProgress() {
        val f = Fixture()
        val permission = restoredEmailPrefix()
        val current = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
            permission, permission.epoch, permission.revision).current
        val cached = permission.copy(revision = current.revision)
        f.owner.restoreSessionSnapshot(current)

        val prepared = requireNotNull(f.owner.prepareVerifiedEmailLogin(
            FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(), cached))

        assertSame(current, prepared.snapshot)
        assertTrue(f.owner.applyVerifiedEmailLogin(prepared))
        assertSame(current, f.owner.snapshot)
    }

    @Test fun anotherActorsStoredCompletionDoesNotCompeteWithTheCurrentActorsNewerVersion() {
        val f = Fixture()
        val current = emailPermission().copy(epoch = 50L, revision = 50L)
        f.owner.restoreSessionSnapshot(current)
        val other = FirstRunOpaqueActorBinding.fromProvider("actor_abcdef0123456789abcdef0123456788")
        val cached = completeEmail(emailTraining()).copy(verifiedActorBinding = other)

        val prepared = requireNotNull(f.owner.prepareVerifiedEmailLogin(other, receipt(), cached))

        assertSame(cached, prepared.snapshot)
        assertTrue(prepared.reauthenticatesExistingProgress)
        assertTrue(f.owner.applyVerifiedEmailLogin(prepared))
        assertEquals(other, f.owner.snapshot.verifiedActorBinding)
        assertSame(cached, f.owner.snapshot)
    }

    @Test fun anotherActorCannotRestartPendingOtherFlowOrInvalidLoginStage() {
        val training = emailTraining()
        val pending = FirstRunOnboardingPolicy.beginAttempt(training,
            FirstRunOnboardingAttemptRequest.forSnapshot(training, "req_" + "b".repeat(64), "att_" + "c".repeat(64))).current
        val created = FirstRunOnboardingPolicy.recordEmailOtpEnrollment(FirstRunOnboardingPolicy.initialEmailAccount(), receipt()).current
        for (before in listOf(pending, FirstRunOnboardingPolicy.initial(), created)) {
            val f = Fixture()
            f.owner.restoreSessionSnapshot(before)
            assertNull(f.owner.prepareVerifiedEmailLogin(FirstRunOpaqueActorBinding.fromProvider("actor_abcdef0123456789abcdef0123456788"), receipt(), null))
            assertSame(before, f.owner.snapshot)
            assertTrue(f.events.isEmpty())
        }
    }

    @Test fun anotherActorCannotRotateAnExhaustedEpochOrRevision() {
        val old = restoredEmailPrefix()
        for (before in listOf(old.copy(epoch = Long.MAX_VALUE), old.copy(revision = Long.MAX_VALUE))) {
            val f = Fixture()
            f.owner.restoreSessionSnapshot(before)
            assertNull(f.owner.prepareVerifiedEmailLogin(FirstRunOpaqueActorBinding.fromProvider("actor_abcdef0123456789abcdef0123456788"), receipt(), null))
            assertSame(before, f.owner.snapshot)
            assertTrue(f.events.isEmpty())
        }
    }

    @Test fun preparedActorRotationRequiresItsOriginalOwnerAndSnapshot() {
        val f = Fixture()
        val before = restoredEmailPrefix()
        f.owner.restoreSessionSnapshot(before)
        val prepared = requireNotNull(f.owner.prepareVerifiedEmailLogin(
            FirstRunOpaqueActorBinding.fromProvider("actor_abcdef0123456789abcdef0123456788"), receipt(), null))
        val other = Fixture()
        other.owner.restoreSessionSnapshot(before)
        assertFalse(other.owner.applyVerifiedEmailLogin(prepared))
        assertSame(before, other.owner.snapshot)
        f.owner.restartEmailAccount { 1L }
        val current = f.owner.snapshot
        assertFalse(f.owner.applyVerifiedEmailLogin(prepared))
        assertSame(current, f.owner.snapshot)
    }

    @Test fun preparedActorRotationAcceptsTheSamePublishedSnapshotWithoutCopyingOldProgress() {
        val f = Fixture()
        f.owner.restoreSessionSnapshot(restoredEmailPrefix())
        val prepared = requireNotNull(f.owner.prepareVerifiedEmailLogin(
            FirstRunOpaqueActorBinding.fromProvider("actor_abcdef0123456789abcdef0123456788"), receipt(), null))
        assertTrue(f.owner.restoreSessionSnapshot(prepared.snapshot))
        assertTrue(f.owner.applyVerifiedEmailLogin(prepared))
        assertSame(prepared.snapshot, f.owner.snapshot)
        assertEquals(setOf(FirstRunOnboardingStage.VERIFIED_LOGIN), f.owner.snapshot.completedReceiptHashes.keys)
    }

    @Test fun devicePassPublicationFailureAndReentrantResetDoNotEmitStageEffects() {
        val f = Fixture()
        val permission = emailPermission()
        val device = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(permission, permission.epoch, permission.revision).current
        f.owner.restoreSessionSnapshot(device)
        assertEquals(FirstRunOnboardingStage.DEVICE_CHECK, f.owner.snapshot.stage)
        assertEquals(kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckPassContinuation.SESSION_CHANGED,
            f.owner.completeEmailDeviceCheck("done") { _, _ -> false })
        assertSame(device, f.owner.snapshot)
        assertEquals(kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckPassContinuation.SESSION_CHANGED,
            f.owner.completeEmailDeviceCheck("done") { _, _ -> f.owner.restartEmailAccount { 1L }; true })
        assertEquals(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, f.owner.snapshot.stage)
        assertTrue(f.events.isEmpty())
    }

    @Test fun completedStoredLoginRetainsItsPurposeButOrdinaryChangesDoNotAcquireIt() {
        val f = Fixture().training()
        f.owner.restoreSessionSnapshot(completeEmail(emailTraining()))
        assertTrue(f.profiles.beginPriorityUserProfileMutationOrFailClosed())
        assertTrue(f.profiles.persistPriorityUserOnboarding())
        f.screenReader = false
        f.owner.onFirstRunOnboardingStateChanged("stored login",
            announcementPurpose = FirstRunOnboardingController.AnnouncementPurpose.RESTORED_LOGIN)
        assertEquals("stored login" to FirstRunOnboardingController.AnnouncementPurpose.RESTORED_LOGIN,
            f.announcements.single())
        f.announcements.clear()
        f.owner.onFirstRunOnboardingStateChanged("ordinary transition")
        assertEquals("ordinary transition" to FirstRunOnboardingController.AnnouncementPurpose.STATE_CHANGE,
            f.announcements.single())
        f.announcements.clear()
        f.screenReader = true
        f.owner.onFirstRunOnboardingStateChanged("stored login",
            announcementPurpose = FirstRunOnboardingController.AnnouncementPurpose.RESTORED_LOGIN)
        assertTrue(f.announcements.isEmpty())
    }

    @Test fun storedDevicePassAdvancingToEducationDropsTheLoginAnnouncementPurpose() {
        val f = Fixture().training()
        f.owner.restartEmailAccount { 1L }
        f.owner.restoreSessionSnapshot(emailPermission())
        f.screenReader = false
        f.educationComplete = false
        f.owner.onFirstRunOnboardingStateChanged("stored login",
            announcementPurpose = FirstRunOnboardingController.AnnouncementPurpose.RESTORED_LOGIN)
        assertEquals(FirstRunOnboardingStage.FP004_TRAINING, f.owner.snapshot.stage)
        assertEquals(FirstRunOnboardingController.AnnouncementPurpose.STATE_CHANGE, f.announcements.single().second)
        assertNotEquals("stored login", f.announcements.single().first)
    }

    @Test fun restoredHomeLoginWaitsForNativeReadyAndDoneWhileStartupTtsRemainsPending() {
        val f = RestoredLoginFixture()
        val pending = f.startup
        f.announce()
        assertEquals(FirstRunOnboardingStage.COMPLETE, f.onboarding.owner.snapshot.stage)
        assertTrue(f.output.runtime.spoken.isEmpty())
        assertTrue(f.output.terminals.isEmpty())
        f.output.runtime.ready()
        assertEquals(1, f.output.runtime.spoken.size)
        assertTrue(f.output.terminals.isEmpty())
        val id = f.output.runtime.spoken.single().id
        f.output.runtime.start(id)
        assertTrue(f.output.terminals.isEmpty())
        f.output.runtime.done(id)
        assertEquals(listOf(true), f.output.terminals)
        assertSame(pending, f.startup)
        assertEquals(listOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS), f.startup.pendingRequirements)
        assertFalse(f.startup.mayConfirmAndStart)
        assertFalse(f.output.controller.speakInteraction("일반 보행 안내"))
        var navigationCompleted = 0
        var navigationFailed = 0
        assertFalse(f.output.controller.dispatchNavigationSpeech("직진하세요.",
            { navigationCompleted++ }, { navigationFailed++ }))
        assertEquals(0, navigationCompleted)
        assertEquals(1, navigationFailed)
        assertEquals(1, f.output.runtime.spoken.size)
    }

    @Test fun storedReceiptPrefixAndCachedEducationRetainRestorationPurposeAfterCompletingTheOwnerChain() {
        val restored = restoredEmailPrefix()
        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, restored.stage)
        assertTrue(FirstRunOnboardingStage.FP004_TRAINING in restored.completedReceiptHashes)
        val f = RestoredLoginFixture(restored)
        f.announce()
        assertEquals(FirstRunOnboardingStage.COMPLETE, f.onboarding.owner.snapshot.stage)
        assertEquals(0, f.onboarding.events.count { it == "publish-complete" })
        assertEquals("저장된 로그인으로 이어갑니다.", f.onboarding.announcements.single().first)
        assertEquals(FirstRunOnboardingController.AnnouncementPurpose.RESTORED_LOGIN,
            f.onboarding.announcements.single().second)
        f.output.runtime.ready()
        assertEquals(1, f.output.runtime.spoken.size)
        val id = f.output.runtime.spoken.single().id
        f.output.runtime.start(id)
        f.output.runtime.done(id)
        assertEquals(listOf(true), f.output.terminals)
        assertFalse(f.startup.mayConfirmAndStart)
    }

    @Test fun cachedNativeEducationWithoutAStoredReceiptRetainsRestorationPurposeThroughCompletion() {
        val f = RestoredLoginFixture(restoredEmailPrefix(includeTraining = false))
        f.announce()
        assertEquals(FirstRunOnboardingStage.COMPLETE, f.onboarding.owner.snapshot.stage)
        assertEquals(1, f.onboarding.events.count { it == "publish-complete" })
        assertEquals("안전 교육과 사전 연습을 완료했습니다. 홈으로 이동합니다.", f.onboarding.announcements.single().first)
        assertEquals(FirstRunOnboardingController.AnnouncementPurpose.RESTORED_LOGIN,
            f.onboarding.announcements.single().second)
        f.output.runtime.ready()
        val id = f.output.runtime.spoken.single().id
        f.output.runtime.start(id)
        f.output.runtime.done(id)
        assertEquals(listOf(true), f.output.terminals)
        assertFalse(f.startup.mayConfirmAndStart)
    }

    @Test fun ordinaryCachedEducationCompletionDoesNotAcquireRestorationPurpose() {
        val f = RestoredLoginFixture(restoredEmailPrefix(includeTraining = false))
        f.announce(FirstRunOnboardingController.AnnouncementPurpose.STATE_CHANGE)
        assertEquals(FirstRunOnboardingStage.COMPLETE, f.onboarding.owner.snapshot.stage)
        assertEquals(FirstRunOnboardingController.AnnouncementPurpose.STATE_CHANGE,
            f.onboarding.announcements.single().second)
        f.output.runtime.ready()
        assertTrue(f.output.runtime.spoken.isEmpty())
    }

    @Test fun pendingEducationAfterStoredDevicePassStillUsesAnEducationAnnouncement() {
        val f = RestoredLoginFixture(restoredEmailPrefix(includeTraining = false))
        f.onboarding.profiles.policy = PriorityUserOnboardingPolicy()
        assertTrue(f.onboarding.profiles.beginPriorityUserProfileMutationOrFailClosed())
        assertTrue(f.onboarding.profiles.persistPriorityUserOnboarding())
        f.announce()
        assertEquals(FirstRunOnboardingStage.FP004_TRAINING, f.onboarding.owner.snapshot.stage)
        assertEquals(FirstRunOnboardingController.AnnouncementPurpose.STATE_CHANGE,
            f.onboarding.announcements.single().second)
        assertFalse(f.onboarding.events.contains("publish-complete"))
        f.output.runtime.ready()
        assertTrue(f.output.runtime.spoken.isEmpty())
    }

    @Test fun historicalReceiptWithMissingLocalEducationDoesNotLeakRestorationPurpose() {
        val f = RestoredLoginFixture(restoredEmailPrefix())
        f.onboarding.profiles.policy = PriorityUserOnboardingPolicy()
        assertTrue(f.onboarding.profiles.beginPriorityUserProfileMutationOrFailClosed())
        assertTrue(f.onboarding.profiles.persistPriorityUserOnboarding())
        f.announce()
        assertEquals(FirstRunOnboardingStage.FP004_TRAINING, f.onboarding.owner.snapshot.stage)
        assertTrue(f.onboarding.announcements.isNotEmpty())
        assertTrue(f.onboarding.announcements.all {
            it.second == FirstRunOnboardingController.AnnouncementPurpose.STATE_CHANGE
        })
        assertFalse(f.onboarding.events.contains("publish-complete"))
    }

    @Test fun restoredCompletionWithoutPublicationCannotSpeakEvenWhenGenericSpeechIsAllowed() {
        val f = RestoredLoginFixture(restoredEmailPrefix())
        f.onboarding.publishStageAccepted = false
        f.output.nativeSpeechAllowed = { true }
        f.announce()
        assertEquals(FirstRunOnboardingStage.COMPLETE, f.onboarding.owner.snapshot.stage)
        assertNotEquals(f.onboarding.owner.snapshot, f.onboarding.publishedSnapshot)
        assertTrue(f.output.runtimes.isEmpty())
        assertTrue(f.output.terminals.isEmpty())
    }

    @Test fun ordinaryOnboardingAnnouncementStillUsesTheBlockedGeneralLaneOnHome() {
        val f = RestoredLoginFixture()
        f.announce(FirstRunOnboardingController.AnnouncementPurpose.STATE_CHANGE)
        f.output.runtime.ready()
        assertTrue(f.output.runtime.spoken.isEmpty())
        assertEquals(listOf(false), f.output.terminals)
        assertFalse(f.startup.mayConfirmAndStart)
    }

    @Test fun restoredNoticeCannotAcquireStatusSpeechOutsideCurrentHomeOrDuringTalkBack() {
        for (condition in listOf("page", "home-unavailable", "screen-reader")) {
            val f = RestoredLoginFixture()
            f.output.nativeSpeechAllowed = { true } // A closed navigation gate must not hide a scope leak.
            f.change(condition)
            f.announce()
            f.output.runtimes.forEach { it.ready() }
            assertTrue(condition, f.output.runtimes.all { it.spoken.isEmpty() })
            assertFalse(condition, f.output.terminals.contains(true))
        }
    }

    @Test fun restoredNoticeLosingItsOwnerBeforeReadinessCannotDispatchLater() {
        for (condition in listOf("page", "home-unavailable", "screen-reader", "actor", "gateway", "epoch", "background", "recognition", "device-gate", "gateway-snapshot")) {
            val f = RestoredLoginFixture()
            f.announce()
            f.change(condition)
            f.output.runtime.ready()
            f.output.clock.advanceBy(100L)
            assertTrue(condition, f.output.runtime.spoken.isEmpty())
            assertFalse(condition, f.output.terminals.contains(true))
            assertFalse(condition, f.output.controller.hasPendingCommandResponse())
        }
    }

    @Test fun restoredNoticeLosingItsOwnerAtNativeStartCannotCompleteFromLateDone() {
        for (condition in listOf("page", "home-unavailable", "screen-reader", "actor", "gateway", "epoch", "background", "recognition", "device-gate", "gateway-snapshot")) {
            val f = RestoredLoginFixture()
            f.announce()
            f.output.runtime.ready()
            val id = f.output.runtime.spoken.single().id
            f.change(condition)
            val stopsBeforeStart = f.output.runtime.stops
            f.output.runtime.start(id)
            assertFalse(condition, f.output.controller.hasPendingCommandResponse())
            assertTrue(condition, f.output.runtime.stops > stopsBeforeStart)
            f.output.runtime.done(id)
            assertFalse(condition, f.output.terminals.contains(true))
            assertFalse(condition, f.output.controller.hasPendingCommandResponse())
        }
    }

    @Test fun cancellingRestoredNoticeRetiresItsNativeCallbacksWithoutACompletion() {
        val f = RestoredLoginFixture()
        f.announce()
        f.output.runtime.ready()
        val id = f.output.runtime.spoken.single().id
        f.output.controller.cancelCommandSpeechResponse()
        f.output.runtime.start(id)
        f.output.runtime.done(id)
        assertFalse(f.output.terminals.contains(true))
        assertFalse(f.output.controller.hasPendingCommandResponse())
    }

    /** Real onboarding -> output -> actuator; this adapter mirrors Main's platform effect port.
     * MainActivityRestoredLoginSpeechStaticTest separately verifies that actual composition block.
     * The OS readiness callback and Main's current HOME/permission facts remain synthetic. */
    private class RestoredLoginFixture(initial: FirstRunOnboardingSnapshot = completeEmail(emailTraining())) {
        val onboarding = Fixture()
        val output = SpeechOutputControllerBehaviorTest.Fixture()
        val startup = WalkSafeStartupCapabilityResolver.resolve(WalkSafeStartupCapabilityInput(
            true, true, true, true, true, true, null, true, true, "test-profile"))
        init {
            onboarding.owner.restoreSessionSnapshot(initial)
            onboarding.publishedSnapshot = initial
            onboarding.profiles.actorId = ACTOR
            onboarding.profiles.policy = PriorityUserOnboardingPolicy(nativeEducation())
            onboarding.educationSource = { onboarding.profiles.policy.snapshot().nativeEducationComplete }
            check(onboarding.profiles.beginPriorityUserProfileMutationOrFailClosed())
            check(onboarding.profiles.persistPriorityUserOnboarding())
            onboarding.screenReader = false
            output.context = output.context.copy(page = SpeechOutputControllerBehaviorTest.Page.HOME,
                visibleResponse = false, voiceInteractionPage = false)
            output.nativeSpeechAllowed = { startup.allowsRequirement(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS) }
            onboarding.onAnnouncement = { message, purpose ->
                val restoredLoginCurrent = {
                    output.context.page == SpeechOutputControllerBehaviorTest.Page.HOME &&
                        output.homeAvailable && output.lifetime.foreground && !output.screenReader &&
                        onboarding.owner.snapshot.mayEnterWalk && onboarding.gate &&
                        onboarding.profiles.policy.snapshot().nativeEducationComplete &&
                        onboarding.publishedSnapshot == onboarding.owner.snapshot
                }
                val restoredLogin = purpose == FirstRunOnboardingController.AnnouncementPurpose.RESTORED_LOGIN
                val restoredLoginAllowed = restoredLogin && restoredLoginCurrent()
                if (restoredLogin) {
                    if (restoredLoginAllowed) {
                        output.controller.speakCommandResponse(message, statusExplanation = true,
                            additionalCurrent = restoredLoginCurrent)
                    }
                } else {
                    output.controller.speakInteraction(message)
                }
            }
        }
        fun announce(purpose: FirstRunOnboardingController.AnnouncementPurpose = FirstRunOnboardingController.AnnouncementPurpose.RESTORED_LOGIN) {
            onboarding.owner.onFirstRunOnboardingStateChanged("저장된 로그인으로 이어갑니다.", announcementPurpose = purpose)
        }
        fun change(condition: String) {
            when (condition) {
                "page" -> output.context = output.context.copy(page = SpeechOutputControllerBehaviorTest.Page.VOICE)
                "home-unavailable" -> output.homeAvailable = false // Includes active walk / permission-recovery in Main.
                "screen-reader" -> { output.screenReader = true; onboarding.screenReader = true }
                "actor" -> output.context = output.context.copy(actorId = "other")
                "gateway" -> output.context = output.context.copy(gatewayGeneration = 12L)
                "epoch" -> output.context = output.context.copy(walkEpoch = WalkRuntimeEpoch("other", 1L))
                "background" -> output.lifetime = output.lifetime.copy(foreground = false)
                "recognition" -> output.context = output.context.copy(recognitionActive = true)
                "device-gate" -> onboarding.gate = false
                "gateway-snapshot" -> onboarding.publishedSnapshot = null
                else -> error("Unknown condition")
            }
        }
    }

    private class Fixture : PriorityUserProfileController.Actors, FirstRunOnboardingController.Gateway,
        FirstRunOnboardingController.Education, FirstRunOnboardingController.Effects {
        val events = mutableListOf<String>()
        val storage = Storage(events)
        var actor: String? = ACTOR
        var gate = true
        var installation: String? = "installation"
        var disclosure = true
        var foreground = true
        var screenReader = true
        val announcements = mutableListOf<Pair<String, FirstRunOnboardingController.AnnouncementPurpose>>()
        var onAnnouncement: (String, FirstRunOnboardingController.AnnouncementPurpose) -> Unit = { _, _ -> }
        var educationComplete = true
        var consentRefreshThrows = false
        var educationSource: (() -> Boolean)? = null
        var publishAccepted = true
        var publishStageAccepted = true
        var publishedSnapshot: FirstRunOnboardingSnapshot? = null
        var beforePublish: () -> Unit = {}
        val profiles = PriorityUserProfileController(storage, this, ::hash,
            PriorityUserProfileController.Config("profile_", "invalid_", 2, "probe-test"))
        val owner = FirstRunOnboardingController(profiles, this, this, this, ::hash)
        fun training(): Fixture {
            owner.restoreSessionSnapshot(emailTraining())
            profiles.actorId = ACTOR
            profiles.policy = PriorityUserOnboardingPolicy(nativeEducation())
            return this
        }
        override fun reporterActorId(): String? { events += "reporter"; return actor }
        override fun deviceGatePassed(): Boolean { events += "gate"; return gate }
        override fun installationId(): String? { events += "install-id"; return installation }
        override fun gatewayOrigin(): String { events += "origin"; return "https://example.test" }
        override fun storeInitialized() = false
        override fun fieldActorId() = actor
        override fun clearSession() { events += "clear-gateway" }
        override fun publishStageSnapshot(actorId: String, snapshot: () -> FirstRunOnboardingSnapshot) { events += "publish-stage"; if (publishStageAccepted) publishedSnapshot = snapshot() }
        override fun publishCompletedSnapshot(actorId: String, snapshot: FirstRunOnboardingSnapshot): Boolean {
            events += "publish-complete"; beforePublish()
            if (publishAccepted) publishedSnapshot = snapshot
            return publishAccepted
        }
        override fun cancelPendingFeedback() { events += "cancel-education" }
        override fun evidenceComplete() = educationSource?.invoke() ?: educationComplete
        override fun onboardingComplete() = owner.initialized && owner.snapshot.isComplete
        override fun quickStartEnabled() = false
        override fun quickStartActorOrNull(): String? = null
        override fun setReporterActor(actorId: String) { actor = actorId }
        override fun invalidateSpeechActorIfChanged(actorId: String) {}
        override fun authenticateActor(actorId: String) {}
        override fun reapplyActorBoundConsent() {}
        override fun rawWalkWasActive() = false
        override fun invalidateMetric(reason: String) { events += "invalidate-metric" }
        override fun safetyStop(reason: String) { events += "safety-stop" }
        override fun walkRuntimeActive() = false
        override fun invalidateEnvironment(reason: String) { events += "invalidate-env:$reason" }
        override fun invalidateMounting(reason: String) { events += "invalidate-mount:$reason" }
        override fun authenticatedFor(actorId: String) = true
        override fun invalidateSpeechStorage() {}
        override fun rememberActor(actorId: String) {}
        override fun acknowledgeVoiceDisclosure(): Boolean { events += "disclosure"; return disclosure }
        override fun startFirstRunDeviceCheckProbes() { events += "probes" }
        override fun updateOnboardingUi() { events += "onboarding-ui" }
        override fun updatePriorityUi() { events += "priority-ui" }
        override fun refreshStartupIfInitialized() { events += "startup-ui" }
        override fun refreshConfirmedConsentAfterCompletion(actorId: String) {
            assertEquals(ACTOR, actorId)
            assertTrue(owner.snapshot.isComplete)
            events += "refresh-consent"
            if (consentRefreshThrows) throw IllegalStateException("executor closed")
        }
        override fun focusStage(stage: FirstRunOnboardingStage) { events += "focus" }
        override fun screenReaderActive() = screenReader
        override fun foreground(): Boolean { events += "foreground"; return foreground }
        override fun showStatus(status: String, detail: String) { events += "status:$status" }
        override fun navigationDiagnostic(message: String) {}
        override fun speakInteraction(message: String, purpose: FirstRunOnboardingController.AnnouncementPurpose) { events += "speak"; announcements += message to purpose; onAnnouncement(message, purpose) }
    }

    private class Storage(val events: MutableList<String>) : PriorityUserProfileController.Storage {
        val booleans = mutableMapOf<String, Boolean>()
        val strings = mutableMapOf<String, String>()
        var markerAccepted = true
        var writeAccepted = true
        override fun initialized() = true
        override fun blocked(): Boolean { events += "storage-blocked"; return false }
        override fun contains(key: String) = key in strings
        override fun boolean(key: String, defaultValue: Boolean) = booleans[key] ?: defaultValue
        override fun string(key: String, defaultValue: String?) = strings[key] ?: defaultValue
        override fun remove(key: String): Boolean { events += "remove"; strings.remove(key); return true }
        override fun markInvalid(key: String): Boolean {
            events += "marker"; if (markerAccepted) booleans[key] = true; return markerAccepted
        }
        override fun writeAndClearMarker(key: () -> String, json: () -> String, invalidKey: String): Boolean {
            events += "edit"
            val field = key(); val raw = json()
            events += "write"
            if (writeAccepted) { strings[field] = raw; booleans.remove(invalidKey) }
            return writeAccepted
        }
    }

    private companion object {
        const val ACTOR = "actor_abcdef0123456789abcdef0123456789"
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun receipt() = FirstRunReceiptHash.fromSha256Hex("a".repeat(64))
        // Same ordered receipt prefix used by AndroidGatewaySessionStore.decodeBackendDevice.
        // JIT/device observations are deliberately fresh; cached FP004 history is retained.
        fun restoredEmailPrefix(includeTraining: Boolean = true): FirstRunOnboardingSnapshot {
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, listOf(
                FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt()),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt()),
            ) + if (includeTraining) listOf(FirstRunOnboardingEvidence.Fp004Training(receipt())) else emptyList(),
                FirstRunOnboardingEvidenceVerifier { _, _ -> true })
            check(restored.fullyRestored)
            return restored.snapshot
        }
        fun emailPermission(): FirstRunOnboardingSnapshot {
            val loggedIn = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(FirstRunOnboardingPolicy.initialEmailAccount(),
                FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt()).current
            return FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(loggedIn,
                FirstRunOnboardingAttemptRequest.forSnapshot(loggedIn, "req_" + "b".repeat(64), "att_" + "c".repeat(64)), receipt()).current
        }
        fun emailTraining(): FirstRunOnboardingSnapshot {
            val permission = emailPermission()
            val device = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(permission, permission.epoch, permission.revision).current
            return FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(device, device.epoch, device.revision).current
        }
        fun completeEmail(snapshot: FirstRunOnboardingSnapshot): FirstRunOnboardingSnapshot {
            val started = FirstRunOnboardingPolicy.beginAttempt(snapshot,
                FirstRunOnboardingAttemptRequest.forSnapshot(snapshot, "req_" + "d".repeat(64), "att_" + "e".repeat(64)))
            return FirstRunOnboardingPolicy.completeAttempt(started.current, started.current.pendingAttempt!!,
                FirstRunOnboardingEvidence.Fp004Training(receipt()), FirstRunOnboardingEvidenceVerifier { _, _ -> true }).current
        }
        fun nativeEducation() = PriorityUserOnboardingSnapshot(ageBand = PriorityUserAgeBand.VERIFIED_14_PLUS,
            educationReviewed = true, safePracticePlaceConfirmed = true, completedPractices = PriorityUserPractice.entries.toSet(),
            phonePostureAcknowledged = true, practiceNecessityReviewed = true, educationAccepted = true,
            usageConditionsAcknowledged = true, appUsageReviewed = true, appUsageAccepted = true, hazardResponseConfirmed = true)
    }
}
