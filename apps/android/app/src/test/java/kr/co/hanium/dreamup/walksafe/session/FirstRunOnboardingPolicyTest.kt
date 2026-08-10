package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunOnboardingPolicyTest {
    private val acceptingVerifier = FirstRunOnboardingEvidenceVerifier { _, _ -> true }

    @Test
    fun adultCompletesTheOrderedFlowWithoutGuardianApproval() {
        var snapshot = readyForRemoteFlow(FirstRunAgeBand.ADULT_18_PLUS)
        val visited = mutableListOf(snapshot.stage)

        while (!snapshot.isComplete) {
            snapshot = completeRemote(snapshot)
            visited += snapshot.stage
            if (snapshot.stage == FirstRunOnboardingStage.FP004_TRAINING) {
                assertTrue(snapshot.hasVerifiedActorBinding)
                assertNull(snapshot.reporterActorBinding)
                assertThrows(IllegalArgumentException::class.java) {
                    snapshot.copy(stage = FirstRunOnboardingStage.COMPLETE)
                }
            }
        }

        assertEquals(
            listOf(
                FirstRunOnboardingStage.INTEGRATED_CONSENT,
                FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION,
                FirstRunOnboardingStage.VERIFIED_SMS,
                FirstRunOnboardingStage.ACCOUNT_ACTIVATION,
                FirstRunOnboardingStage.VERIFIED_LOGIN,
                FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION,
                FirstRunOnboardingStage.DEVICE_CHECK,
                FirstRunOnboardingStage.FP004_TRAINING,
                FirstRunOnboardingStage.COMPLETE,
            ),
            visited,
        )
        assertFalse(snapshot.guardianApprovalRequired)
        assertFalse(
            FirstRunOnboardingStage.GUARDIAN_APPROVAL in snapshot.completedReceiptHashes,
        )
        assertEquals(
            submissionHandle(),
            snapshot.localCredentialPhoneSubmissionHandle,
        )
        assertEquals(actorBinding(), snapshot.reporterActorBinding)
        assertTrue(snapshot.mayEnterWalk)
    }

    @Test
    fun minorCannotSkipGuardianApproval() {
        var snapshot = readyForRemoteFlow(FirstRunAgeBand.AGE_14_TO_17)
        snapshot = completeRemote(snapshot)
        snapshot = completeRemote(snapshot)
        snapshot = completeRemote(snapshot)

        assertEquals(FirstRunOnboardingStage.GUARDIAN_APPROVAL, snapshot.stage)
        assertTrue(snapshot.guardianApprovalRequired)

        val started = FirstRunOnboardingPolicy.beginAttempt(snapshot, request(snapshot))
        val token = started.current.pendingAttempt!!
        val mismatched = FirstRunOnboardingPolicy.completeAttempt(
            started.current,
            token,
            FirstRunOnboardingEvidence.AccountActivation(receipt(20)),
            acceptingVerifier,
        )

        assertFalse(mismatched.accepted)
        assertEquals(FirstRunOnboardingRejection.STAGE_MISMATCH, mismatched.rejection)
        assertSame(started.current, mismatched.current)

        val approved = FirstRunOnboardingPolicy.completeAttempt(
            mismatched.current,
            token,
            FirstRunOnboardingEvidence.GuardianApproval(receipt(21)),
            acceptingVerifier,
        )

        assertTrue(approved.accepted)
        assertEquals(FirstRunOnboardingStage.ACCOUNT_ACTIVATION, approved.current.stage)

        var trainingReady = approved.current
        while (trainingReady.stage != FirstRunOnboardingStage.FP004_TRAINING) {
            trainingReady = completeRemote(trainingReady)
        }
        assertTrue(trainingReady.hasVerifiedActorBinding)
        assertNull(trainingReady.reporterActorBinding)
    }

    @Test
    fun under14ResultIsTerminalAndNeverEntersTheWalk() {
        var snapshot = completePurpose(FirstRunOnboardingPolicy.initial())
        val ageRequest = request(snapshot)
        val blocked = FirstRunOnboardingPolicy.recordAgeAndGuardianNeed(
            snapshot,
            ageRequest,
            FirstRunAgeBand.UNDER_14,
            receipt(2),
        )
        snapshot = blocked.current

        assertTrue(blocked.accepted)
        assertEquals(FirstRunOnboardingStage.BLOCKED_UNDER_14, snapshot.stage)
        assertTrue(snapshot.isTerminal)
        assertFalse(snapshot.isComplete)
        assertFalse(snapshot.mayEnterWalk)

        val after = FirstRunOnboardingPolicy.beginAttempt(snapshot, request(snapshot))
        assertFalse(after.accepted)
        assertEquals(FirstRunOnboardingRejection.TERMINAL_STATE, after.rejection)
        assertSame(snapshot, after.current)
    }

    @Test
    fun productionVerifierRejectsRemoteEvidenceAndVerifierErrorsFailClosed() {
        val snapshot = readyForRemoteFlow(FirstRunAgeBand.ADULT_18_PLUS)
        val started = FirstRunOnboardingPolicy.beginAttempt(snapshot, request(snapshot))
        val token = started.current.pendingAttempt!!
        val evidence = FirstRunOnboardingEvidence.IntegratedConsent(receipt(3))

        val unavailable = FirstRunOnboardingPolicy.completeAttempt(
            started.current,
            token,
            evidence,
        )
        val throwing = FirstRunOnboardingPolicy.completeAttempt(
            started.current,
            token,
            evidence,
            FirstRunOnboardingEvidenceVerifier { _, _ -> error("provider failed") },
        )

        assertFalse(FirstRunOnboardingPolicy.productionEvidenceAvailable)
        assertFalse(unavailable.accepted)
        assertFalse(throwing.accepted)
        assertEquals(
            FirstRunOnboardingRejection.REMOTE_EVIDENCE_REJECTED,
            unavailable.rejection,
        )
        assertEquals(unavailable.rejection, throwing.rejection)
        assertSame(started.current, unavailable.current)
        assertSame(started.current, throwing.current)
    }

    @Test
    fun staleLateDuplicateAndForgedAttemptsCannotAdvance() {
        val initial = FirstRunOnboardingPolicy.initial(epoch = 7L)
        val purposeRequest = request(initial)
        val purpose = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(
            initial,
            purposeRequest,
            receipt(1),
        )
        val duplicatePurpose = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(
            purpose.current,
            purposeRequest,
            receipt(1),
        )

        assertTrue(purpose.accepted)
        assertFalse(duplicatePurpose.accepted)
        assertEquals(FirstRunOnboardingRejection.STALE_STATE, duplicatePurpose.rejection)
        assertSame(purpose.current, duplicatePurpose.current)

        var snapshot = completeAge(
            purpose.current,
            FirstRunAgeBand.ADULT_18_PLUS,
        )
        val beginRequest = request(snapshot)
        val started = FirstRunOnboardingPolicy.beginAttempt(snapshot, beginRequest)
        val token = started.current.pendingAttempt!!
        val duplicateBegin = FirstRunOnboardingPolicy.beginAttempt(
            started.current,
            beginRequest,
        )
        val forged = FirstRunOnboardingAttemptToken(
            epoch = token.epoch,
            revision = token.revision,
            stage = token.stage,
            requestId = token.requestId,
            attemptId = token.attemptId,
        )
        val forgedCompletion = FirstRunOnboardingPolicy.completeAttempt(
            started.current,
            forged,
            evidenceFor(started.current.stage),
            acceptingVerifier,
        )

        assertFalse(duplicateBegin.accepted)
        assertFalse(forgedCompletion.accepted)
        assertEquals(
            FirstRunOnboardingRejection.ATTEMPT_MISMATCH,
            forgedCompletion.rejection,
        )

        val completed = FirstRunOnboardingPolicy.completeAttempt(
            started.current,
            token,
            evidenceFor(started.current.stage),
            acceptingVerifier,
        )
        val duplicateCompletion = FirstRunOnboardingPolicy.completeAttempt(
            completed.current,
            token,
            evidenceFor(token.stage),
            acceptingVerifier,
        )

        assertTrue(completed.accepted)
        assertFalse(duplicateCompletion.accepted)
        assertEquals(
            FirstRunOnboardingRejection.NO_PENDING_ATTEMPT,
            duplicateCompletion.rejection,
        )

        snapshot = completed.current
        val secondStart = FirstRunOnboardingPolicy.beginAttempt(snapshot, request(snapshot))
        val secondToken = secondStart.current.pendingAttempt!!
        val cancelled = FirstRunOnboardingPolicy.cancelAttempt(
            secondStart.current,
            secondToken,
        )
        val late = FirstRunOnboardingPolicy.completeAttempt(
            cancelled.current,
            secondToken,
            evidenceFor(secondToken.stage),
            acceptingVerifier,
        )

        assertTrue(cancelled.accepted)
        assertEquals(secondStart.current.epoch + 1L, cancelled.current.epoch)
        assertEquals(secondStart.current.revision + 1L, cancelled.current.revision)
        assertFalse(late.accepted)
        assertEquals(FirstRunOnboardingRejection.NO_PENDING_ATTEMPT, late.rejection)
        assertEquals(snapshot.stage, late.current.stage)
    }

    @Test
    fun publicEvidenceTypesRejectRawLookingValues() {
        assertThrows(IllegalArgumentException::class.java) {
            FirstRunReceiptHash.fromSha256Hex("123456")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FirstRunOpaqueSubmissionHandle.fromProvider("01012345678")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FirstRunOpaqueSubmissionHandle.fromProvider("123456")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FirstRunOpaqueActorBinding.fromProvider("01012345678")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FirstRunOpaqueSubmissionHandle.fromProvider(
                "onb_01012345678901234567890123456789",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FirstRunOpaqueActorBinding.fromProvider(
                "actor_01012345678901234567890123456789",
            )
        }

        val snapshot = readyForRemoteFlow(FirstRunAgeBand.ADULT_18_PLUS)
        val invalidRequest = FirstRunOnboardingPolicy.beginAttempt(
            snapshot,
            request(snapshot).copy(requestId = "req_walksafe_integrated_consent"),
        )

        assertFalse(invalidRequest.accepted)
        assertEquals(
            FirstRunOnboardingRejection.INVALID_IDENTITY,
            invalidRequest.rejection,
        )
        assertNull(snapshot.localCredentialPhoneSubmissionHandle)
        assertEquals(FirstRunAgeBand.ADULT_18_PLUS, snapshot.ageBand)
        assertTrue(
            snapshot.completedReceiptHashes.values.all {
                it.value.matches(Regex("^[0-9a-f]{64}$"))
            },
        )
    }

    @Test
    fun publishedReceiptMapsRemainImmutableAcrossAdvanceAndTrainingRestart() {
        var snapshot = readyForRemoteFlow(FirstRunAgeBand.ADULT_18_PLUS)
        while (!snapshot.isComplete) {
            snapshot = completeRemote(snapshot)
        }
        val restarted = FirstRunOnboardingPolicy.restartFp004Training(snapshot).current

        listOf(snapshot, restarted).forEach { published ->
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                val mutable = published.completedReceiptHashes as
                    MutableMap<FirstRunOnboardingStage, FirstRunReceiptHash>
                mutable[FirstRunOnboardingStage.PURPOSE_AND_SAFETY] = receipt(99)
            }
        }
    }

    @Test
    fun processRecreationRestoresOnlyAnOrderedVerifiedReceiptPrefix() {
        val evidence = adultEvidencePrefix()

        val restored = FirstRunOnboardingPolicy.restoreVerifiedReceiptPrefix(
            epoch = 42L,
            orderedEvidence = evidence,
            verifier = acceptingVerifier,
        )

        assertTrue(restored.fullyRestored)
        assertEquals(evidence.size, restored.restoredEvidenceCount)
        assertEquals(42L, restored.snapshot.epoch)
        assertEquals(evidence.size.toLong(), restored.snapshot.revision)
        assertEquals(FirstRunOnboardingStage.COMPLETE, restored.snapshot.stage)
        assertNull(restored.snapshot.pendingAttempt)
        assertTrue(restored.snapshot.mayEnterWalk)
        assertEquals(submissionHandle(), restored.snapshot.localCredentialPhoneSubmissionHandle)
        assertEquals(actorBinding(), restored.snapshot.reporterActorBinding)
    }

    @Test
    fun productionAndInvalidGuardianPrefixesStopAtTheLastVerifiedReceipt() {
        val production = FirstRunOnboardingPolicy.restoreVerifiedReceiptPrefix(
            epoch = 9L,
            orderedEvidence = adultEvidencePrefix(),
        )

        assertFalse(production.fullyRestored)
        assertEquals(2, production.restoredEvidenceCount)
        assertEquals(
            FirstRunOnboardingRejection.REMOTE_EVIDENCE_REJECTED,
            production.rejection,
        )
        assertEquals(FirstRunOnboardingStage.INTEGRATED_CONSENT, production.snapshot.stage)
        assertNull(production.snapshot.pendingAttempt)

        val adultWithGuardian = adultEvidencePrefix().toMutableList().apply {
            add(
                5,
                FirstRunOnboardingEvidence.GuardianApproval(receipt(6)),
            )
        }
        val invalidAdult = FirstRunOnboardingPolicy.restoreVerifiedReceiptPrefix(
            epoch = 10L,
            orderedEvidence = adultWithGuardian,
            verifier = acceptingVerifier,
        )
        val minorWithoutGuardian = FirstRunOnboardingPolicy.restoreVerifiedReceiptPrefix(
            epoch = 11L,
            orderedEvidence = listOf(
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(1)),
                FirstRunOnboardingEvidence.AgeAndGuardianNeed(
                    FirstRunAgeBand.AGE_14_TO_17,
                    receipt(2),
                ),
                FirstRunOnboardingEvidence.IntegratedConsent(receipt(3)),
                FirstRunOnboardingEvidence.LocalCredentialPhoneSubmission(
                    submissionHandle(),
                    receipt(4),
                ),
                FirstRunOnboardingEvidence.VerifiedSms(receipt(5)),
                FirstRunOnboardingEvidence.AccountActivation(receipt(7)),
            ),
            verifier = acceptingVerifier,
        )

        assertEquals(5, invalidAdult.restoredEvidenceCount)
        assertEquals(FirstRunOnboardingStage.ACCOUNT_ACTIVATION, invalidAdult.snapshot.stage)
        assertEquals(FirstRunOnboardingRejection.STAGE_MISMATCH, invalidAdult.rejection)
        assertFalse(
            FirstRunOnboardingStage.GUARDIAN_APPROVAL in
                invalidAdult.snapshot.completedReceiptHashes,
        )
        assertNull(invalidAdult.snapshot.pendingAttempt)

        assertEquals(5, minorWithoutGuardian.restoredEvidenceCount)
        assertEquals(
            FirstRunOnboardingStage.GUARDIAN_APPROVAL,
            minorWithoutGuardian.snapshot.stage,
        )
        assertEquals(FirstRunOnboardingRejection.STAGE_MISMATCH, minorWithoutGuardian.rejection)
        assertNull(minorWithoutGuardian.snapshot.pendingAttempt)
    }

    @Test
    fun completedOnboardingReturnsToFp004WhenTrainingIsReset() {
        var trainingReady = readyForRemoteFlow(FirstRunAgeBand.ADULT_18_PLUS)
        while (trainingReady.stage != FirstRunOnboardingStage.FP004_TRAINING) {
            trainingReady = completeRemote(trainingReady)
        }
        val oldRequest = request(trainingReady)
        val oldStart = FirstRunOnboardingPolicy.beginAttempt(trainingReady, oldRequest)
        val oldToken = oldStart.current.pendingAttempt!!
        val completed = FirstRunOnboardingPolicy.completeAttempt(
            oldStart.current,
            oldToken,
            evidenceFor(FirstRunOnboardingStage.FP004_TRAINING),
            acceptingVerifier,
        ).current

        val premature = FirstRunOnboardingPolicy.restartFp004Training(trainingReady)
        val restarted = FirstRunOnboardingPolicy.restartFp004Training(completed)

        assertFalse(premature.accepted)
        assertEquals(
            FirstRunOnboardingRejection.TRAINING_RESTART_NOT_ALLOWED,
            premature.rejection,
        )
        assertSame(trainingReady, premature.current)

        assertTrue(restarted.accepted)
        assertEquals(completed.epoch + 1L, restarted.current.epoch)
        assertEquals(completed.revision + 1L, restarted.current.revision)
        assertEquals(FirstRunOnboardingStage.FP004_TRAINING, restarted.current.stage)
        assertEquals(
            trainingReady.completedReceiptHashes,
            restarted.current.completedReceiptHashes,
        )
        assertEquals(completed.verifiedActorBinding, restarted.current.verifiedActorBinding)
        assertTrue(restarted.current.hasVerifiedActorBinding)
        assertNull(restarted.current.reporterActorBinding)
        assertNull(restarted.current.pendingAttempt)

        val staleRequest = FirstRunOnboardingPolicy.beginAttempt(
            restarted.current,
            oldRequest,
        )
        val lateCompletion = FirstRunOnboardingPolicy.completeAttempt(
            restarted.current,
            oldToken,
            evidenceFor(FirstRunOnboardingStage.FP004_TRAINING),
            acceptingVerifier,
        )

        assertFalse(staleRequest.accepted)
        assertEquals(FirstRunOnboardingRejection.STALE_STATE, staleRequest.rejection)
        assertFalse(lateCompletion.accepted)
        assertEquals(
            FirstRunOnboardingRejection.NO_PENDING_ATTEMPT,
            lateCompletion.rejection,
        )

        val freshStart = FirstRunOnboardingPolicy.beginAttempt(
            restarted.current,
            request(restarted.current),
        )
        val retrained = FirstRunOnboardingPolicy.completeAttempt(
            freshStart.current,
            freshStart.current.pendingAttempt!!,
            evidenceFor(FirstRunOnboardingStage.FP004_TRAINING),
            acceptingVerifier,
        )

        assertTrue(retrained.current.isComplete)
        assertEquals(actorBinding(), retrained.current.reporterActorBinding)
    }

    private fun readyForRemoteFlow(ageBand: FirstRunAgeBand): FirstRunOnboardingSnapshot =
        completeAge(
            completePurpose(FirstRunOnboardingPolicy.initial()),
            ageBand,
        )

    private fun completePurpose(
        snapshot: FirstRunOnboardingSnapshot,
    ): FirstRunOnboardingSnapshot {
        val transition = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(
            snapshot,
            request(snapshot),
            receipt(1),
        )
        assertTrue(transition.accepted)
        return transition.current
    }

    private fun completeAge(
        snapshot: FirstRunOnboardingSnapshot,
        ageBand: FirstRunAgeBand,
    ): FirstRunOnboardingSnapshot {
        val transition = FirstRunOnboardingPolicy.recordAgeAndGuardianNeed(
            snapshot,
            request(snapshot),
            ageBand,
            receipt(2),
        )
        assertTrue(transition.accepted)
        return transition.current
    }

    private fun completeRemote(
        snapshot: FirstRunOnboardingSnapshot,
    ): FirstRunOnboardingSnapshot {
        val started = FirstRunOnboardingPolicy.beginAttempt(snapshot, request(snapshot))
        assertTrue(started.accepted)
        val completed = FirstRunOnboardingPolicy.completeAttempt(
            started.current,
            started.current.pendingAttempt!!,
            evidenceFor(snapshot.stage),
            acceptingVerifier,
        )
        assertTrue(completed.accepted)
        return completed.current
    }

    private fun evidenceFor(
        stage: FirstRunOnboardingStage,
    ): FirstRunOnboardingEvidence = when (stage) {
        FirstRunOnboardingStage.INTEGRATED_CONSENT ->
            FirstRunOnboardingEvidence.IntegratedConsent(receipt(3))
        FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION ->
            FirstRunOnboardingEvidence.LocalCredentialPhoneSubmission(
                submissionHandle(),
                receipt(4),
            )
        FirstRunOnboardingStage.VERIFIED_SMS ->
            FirstRunOnboardingEvidence.VerifiedSms(receipt(5))
        FirstRunOnboardingStage.GUARDIAN_APPROVAL ->
            FirstRunOnboardingEvidence.GuardianApproval(receipt(6))
        FirstRunOnboardingStage.ACCOUNT_ACTIVATION ->
            FirstRunOnboardingEvidence.AccountActivation(receipt(7))
        FirstRunOnboardingStage.VERIFIED_LOGIN ->
            FirstRunOnboardingEvidence.VerifiedLogin(actorBinding(), receipt(8))
        FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION ->
            FirstRunOnboardingEvidence.JitPermissionObservation(receipt(9))
        FirstRunOnboardingStage.DEVICE_CHECK ->
            FirstRunOnboardingEvidence.DeviceCheck(receipt(10))
        FirstRunOnboardingStage.FP004_TRAINING ->
            FirstRunOnboardingEvidence.Fp004Training(receipt(11))
        else -> error("No remote evidence for $stage")
    }

    private fun adultEvidencePrefix(): List<FirstRunOnboardingEvidence> = listOf(
        FirstRunOnboardingEvidence.PurposeAndSafety(receipt(1)),
        FirstRunOnboardingEvidence.AgeAndGuardianNeed(
            FirstRunAgeBand.ADULT_18_PLUS,
            receipt(2),
        ),
        FirstRunOnboardingEvidence.IntegratedConsent(receipt(3)),
        FirstRunOnboardingEvidence.LocalCredentialPhoneSubmission(
            submissionHandle(),
            receipt(4),
        ),
        FirstRunOnboardingEvidence.VerifiedSms(receipt(5)),
        FirstRunOnboardingEvidence.AccountActivation(receipt(7)),
        FirstRunOnboardingEvidence.VerifiedLogin(actorBinding(), receipt(8)),
        FirstRunOnboardingEvidence.JitPermissionObservation(receipt(9)),
        FirstRunOnboardingEvidence.DeviceCheck(receipt(10)),
        FirstRunOnboardingEvidence.Fp004Training(receipt(11)),
    )

    private fun request(
        snapshot: FirstRunOnboardingSnapshot,
    ): FirstRunOnboardingAttemptRequest {
        val suffix =
            "${snapshot.epoch.toString(16)}" +
                "${snapshot.revision.toString(16)}" +
                snapshot.stage.ordinal.toString(16)
        return FirstRunOnboardingAttemptRequest.forSnapshot(
            snapshot = snapshot,
            requestId = "req_${suffix.padStart(64, 'a')}",
            attemptId = "att_${suffix.padStart(64, 'b')}",
        )
    }

    private fun receipt(number: Int): FirstRunReceiptHash =
        FirstRunReceiptHash.fromSha256Hex(number.toString(16).padStart(64, '0'))

    private fun submissionHandle(): FirstRunOpaqueSubmissionHandle =
        FirstRunOpaqueSubmissionHandle.fromProvider(
            "onb_a1b2c3d4e5f60718293a4b5c6d7e8f90",
        )

    private fun actorBinding(): FirstRunOpaqueActorBinding =
        FirstRunOpaqueActorBinding.fromProvider(
            "actor_b1c2d3e4f5a60718293b4c5d6e7f809a",
        )
}
