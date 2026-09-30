package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingCompletionRegressionTest {
    private val environment = PriorityUserSupportEnvironment(
        screenReaderActive = false,
        largeTextEnabled = false,
        highContrastEnabled = false,
        offlineKoreanVoiceAvailable = true,
        vibrationAvailable = true,
    )

    @Test
    fun legacyAgeInputRejectsEmailOnlyAgeWithoutConsumingTheRequest() {
        val snapshot = awaitingLegacyAge()
        val request = request(snapshot)

        val rejected = FirstRunOnboardingPolicy.recordAgeAndGuardianNeed(
            snapshot,
            request,
            FirstRunAgeBand.VERIFIED_14_PLUS,
            receipt(),
        )

        assertFalse(rejected.accepted)
        assertEquals(FirstRunOnboardingRejection.STAGE_MISMATCH, rejected.rejection)
        assertSame(snapshot, rejected.current)
        val retry = FirstRunOnboardingPolicy.recordAgeAndGuardianNeed(
            rejected.current,
            request,
            FirstRunAgeBand.ADULT_18_PLUS,
            receipt(),
        )
        assertTrue(retry.accepted)
        assertEquals(FirstRunOnboardingStage.INTEGRATED_CONSENT, retry.current.stage)
    }

    @Test
    fun staleAgeRequestKeepsItsRejectionPrecedenceOverInvalidAge() {
        val snapshot = awaitingLegacyAge()
        val rejected = FirstRunOnboardingPolicy.recordAgeAndGuardianNeed(
            snapshot,
            request(snapshot).copy(revision = snapshot.revision - 1L),
            FirstRunAgeBand.VERIFIED_14_PLUS,
            receipt(),
        )

        assertFalse(rejected.accepted)
        assertEquals(FirstRunOnboardingRejection.STALE_STATE, rejected.rejection)
        assertSame(snapshot, rejected.current)
    }

    @Test
    fun legacyRestoreStopsAtTheVerifiedPrefixForEmailOnlyAge() {
        val restored = FirstRunOnboardingPolicy.restoreVerifiedReceiptPrefix(
            epoch = 21L,
            orderedEvidence = listOf(
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt()),
                FirstRunOnboardingEvidence.AgeAndGuardianNeed(
                    FirstRunAgeBand.VERIFIED_14_PLUS,
                    receipt(),
                ),
            ),
        )

        assertEquals(FirstRunOnboardingRejection.STAGE_MISMATCH, restored.rejection)
        assertEquals(1, restored.restoredEvidenceCount)
        assertEquals(FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED, restored.snapshot.stage)
        assertEquals(
            setOf(FirstRunOnboardingStage.PURPOSE_AND_SAFETY),
            restored.snapshot.completedReceiptHashes.keys,
        )
        assertFalse(restored.snapshot.mayEnterWalk)
    }

    @Test
    fun emailRestoreRejectsEveryLegacyAgeBeforeCallingTheVerifier() {
        FirstRunAgeBand.entries.filter { it != FirstRunAgeBand.VERIFIED_14_PLUS }.forEach { age ->
            var verifierCalls = 0
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
                epoch = 22L,
                orderedEvidence = listOf(
                    FirstRunOnboardingEvidence.EmailOtpEnrollment(age, receipt()),
                ),
                verifier = FirstRunOnboardingEvidenceVerifier { _, _ ->
                    verifierCalls += 1
                    true
                },
            )

            assertEquals(age.name, FirstRunOnboardingRejection.STAGE_MISMATCH, restored.rejection)
            assertEquals(0, restored.restoredEvidenceCount)
            assertEquals(0, verifierCalls)
            assertEquals(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, restored.snapshot.stage)
            assertTrue(restored.snapshot.completedReceiptHashes.isEmpty())
            assertFalse(restored.snapshot.mayEnterWalk)
        }
    }

    @Test
    fun emailCompletionRejectsLegacyAgesButKeepsTheCurrentAttemptForValidEvidence() {
        FirstRunAgeBand.entries.filter { it != FirstRunAgeBand.VERIFIED_14_PLUS }.forEach { age ->
            val initial = FirstRunOnboardingPolicy.initialEmailAccount(23L)
            val pending = FirstRunOnboardingPolicy.beginAttempt(initial, request(initial)).current
            val token = pending.pendingAttempt!!
            var verifierCalls = 0
            val verifier = FirstRunOnboardingEvidenceVerifier { _, _ ->
                verifierCalls += 1
                true
            }
            val rejected = FirstRunOnboardingPolicy.completeAttempt(
                pending,
                token,
                FirstRunOnboardingEvidence.EmailOtpEnrollment(age, receipt()),
                verifier,
            )

            assertFalse(rejected.accepted)
            assertEquals(age.name, FirstRunOnboardingRejection.STAGE_MISMATCH, rejected.rejection)
            assertSame(pending, rejected.current)
            assertSame(token, rejected.current.pendingAttempt)
            assertEquals(0, verifierCalls)
            val accepted = FirstRunOnboardingPolicy.completeAttempt(
                rejected.current,
                token,
                FirstRunOnboardingEvidence.EmailOtpEnrollment(
                    FirstRunAgeBand.VERIFIED_14_PLUS,
                    receipt(),
                ),
                verifier,
            )
            assertTrue(accepted.accepted)
            assertEquals(1, verifierCalls)
            assertEquals(FirstRunOnboardingStage.ACCOUNT_CREATED, accepted.current.stage)
        }
    }

    @Test
    fun forgedEmailAttemptCannotReachTheNewAgeValidationBoundary() {
        val initial = FirstRunOnboardingPolicy.initialEmailAccount(24L)
        val pending = FirstRunOnboardingPolicy.beginAttempt(initial, request(initial)).current
        val token = pending.pendingAttempt!!
        val forged = FirstRunOnboardingAttemptToken(
            epoch = token.epoch,
            revision = token.revision,
            stage = token.stage,
            requestId = token.requestId,
            attemptId = token.attemptId,
        )
        val rejected = FirstRunOnboardingPolicy.completeAttempt(
            pending,
            forged,
            FirstRunOnboardingEvidence.EmailOtpEnrollment(
                FirstRunAgeBand.ADULT_18_PLUS,
                receipt(),
            ),
            FirstRunOnboardingEvidenceVerifier { _, _ -> true },
        )

        assertFalse(rejected.accepted)
        assertEquals(FirstRunOnboardingRejection.ATTEMPT_MISMATCH, rejected.rejection)
        assertSame(pending, rejected.current)
    }

    @Test
    fun allInteractivePracticesWithoutEducationConsentCannotStartOrContinueWalk() {
        val policy = practiceOnlyPolicy()

        assertTrue(policy.snapshot().interactivePracticeComplete)
        assertFalse(policy.snapshot().safetyEducationConsentComplete)
        assertIncomplete(policy)
        assertEquals(
            PriorityUserBlockReason.APP_USAGE_EDUCATION_INCOMPLETE,
            policy.evaluate(environment).walkBlockReason,
        )
    }

    @Test
    fun completedPracticesStayBlockedUntilEveryNativeEducationStepIsAccepted() {
        val policy = practiceOnlyPolicy()
        assertIncomplete(policy)
        policy.reviewPracticeNecessity(true)
        assertIncomplete(policy)
        policy.acceptEducationConsent()
        assertIncomplete(policy)
        policy.acknowledgeUsageConditions()
        assertIncomplete(policy)
        policy.acceptAppUsageEducation()
        assertIncomplete(policy)
        policy.finishAppUsageEducationPlayback(
            policy.beginAppUsageEducationPlayback()!!,
            completed = true,
        )
        assertIncomplete(policy)
        policy.acceptAppUsageEducation()

        assertTrue(policy.snapshot().nativeEducationComplete)
        assertTrue(policy.evaluate(environment, walkIsActive = true).mayStartWalk)
        assertFalse(policy.evaluate(environment, walkIsActive = true).requiresSafetyStop)
    }

    @Test
    fun restoringCompletedPracticeHistoryDoesNotSynthesizeMissingNativeEducation() {
        val policy = PriorityUserOnboardingPolicy(
            PriorityUserOnboardingSnapshot(
                ageBand = PriorityUserAgeBand.ADULT_18_PLUS,
                educationReviewed = true,
                safePracticePlaceConfirmed = true,
                completedPractices = PriorityUserPractice.entries.toSet(),
                hazardResponseConfirmed = true,
            ),
        )

        assertTrue(policy.snapshot().interactivePracticeComplete)
        assertIncomplete(policy)
        assertFalse(policy.snapshot().educationAccepted)
        assertFalse(policy.snapshot().appUsageAccepted)
    }

    private fun awaitingLegacyAge(): FirstRunOnboardingSnapshot {
        val initial = FirstRunOnboardingPolicy.initial(20L)
        return FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(
            initial,
            request(initial),
            receipt(),
        ).current
    }

    private fun request(snapshot: FirstRunOnboardingSnapshot) =
        FirstRunOnboardingAttemptRequest.forSnapshot(
            snapshot,
            requestId = "req_" + "a".repeat(64),
            attemptId = "att_" + "b".repeat(64),
        )

    private fun receipt() = FirstRunReceiptHash.fromSha256Hex("c".repeat(64))

    private fun practiceOnlyPolicy() = PriorityUserOnboardingPolicy().apply {
        selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
        reviewSafetyEducation(true)
        confirmSafePracticePlace()
        PriorityUserPractice.entries.forEach { practice ->
            val token = beginPractice(practice)!!
            PriorityUserPracticeDeliverySignal.entries.forEach { signal ->
                recordPracticeDelivery(token, signal)
            }
            if (practice == PriorityUserPractice.HAZARD_ALERT) {
                confirmHazardResponse(token)
            }
        }
    }

    private fun assertIncomplete(policy: PriorityUserOnboardingPolicy) {
        assertFalse(policy.snapshot().nativeEducationComplete)
        val decision = policy.evaluate(environment, walkIsActive = true)
        assertFalse(decision.mayStartWalk)
        assertTrue(decision.requiresSafetyStop)
    }
}
