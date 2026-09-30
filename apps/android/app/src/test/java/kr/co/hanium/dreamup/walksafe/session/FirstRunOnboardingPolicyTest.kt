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
    fun verifiedEmailLoginRequiresPurposeAndSafetyBeforeLocalPostLoginChecks() {
        val loggedIn = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(
            FirstRunOnboardingPolicy.initialEmailAccount(2L),
            actorBinding(),
            receipt(40),
        ).current
        val skippedSafety = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
            loggedIn,
            loggedIn.epoch,
            loggedIn.revision,
        )
        val safety = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(
            loggedIn,
            request(loggedIn),
            receipt(42),
        )
        val receiptKeys = safety.current.completedReceiptHashes.keys

        val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
            safety.current,
            safety.current.epoch,
            safety.current.revision,
        )
        val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(
            jit.current,
            jit.current.epoch,
            jit.current.revision,
        )

        assertEquals(FirstRunOnboardingStage.PURPOSE_AND_SAFETY, loggedIn.stage)
        assertFalse(skippedSafety.accepted)
        assertSame(loggedIn, skippedSafety.current)
        assertTrue(safety.accepted)
        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, safety.current.stage)
        assertTrue(
            FirstRunOnboardingStage.PURPOSE_AND_SAFETY in
                safety.current.completedReceiptHashes,
        )
        assertTrue(jit.accepted)
        assertEquals(FirstRunOnboardingStage.DEVICE_CHECK, jit.current.stage)
        assertEquals(receiptKeys, jit.current.completedReceiptHashes.keys)
        assertTrue(device.accepted)
        assertEquals(FirstRunOnboardingStage.FP004_TRAINING, device.current.stage)
        assertEquals(receiptKeys, device.current.completedReceiptHashes.keys)
    }

    @Test
    fun advancedEmailProgressCanOnlyBeReauthenticatedByItsBoundActor() {
        val awaitingSafety = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(
            FirstRunOnboardingPolicy.initialEmailAccount(3L),
            actorBinding(),
            receipt(41),
        ).current
        val loggedIn = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(
            awaitingSafety,
            request(awaitingSafety),
            receipt(42),
        ).current
        val deviceCheck = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
            loggedIn,
            loggedIn.epoch,
            loggedIn.revision,
        ).current
        val otherActor = FirstRunOpaqueActorBinding.fromProvider(
            "actor_0123456789abcdef0123456789abcdef",
        )

        assertTrue(
            FirstRunOnboardingPolicy.mayReauthenticateVerifiedEmailActor(
                awaitingSafety,
                actorBinding(),
            ),
        )
        assertTrue(
            FirstRunOnboardingPolicy.mayReauthenticateVerifiedEmailActor(
                deviceCheck,
                actorBinding(),
            ),
        )
        assertFalse(
            FirstRunOnboardingPolicy.mayReauthenticateVerifiedEmailActor(
                deviceCheck,
                otherActor,
            ),
        )
        assertFalse(
            FirstRunOnboardingPolicy.mayReauthenticateVerifiedEmailActor(
                FirstRunOnboardingPolicy.initialEmailAccount(4L),
                actorBinding(),
            ),
        )
    }

    @Test
    fun newlyCreatedEmailAccountUsesTheSamePurposeAndSafetyGate() {
        val enrolled = FirstRunOnboardingPolicy.recordEmailOtpEnrollment(
            FirstRunOnboardingPolicy.initialEmailAccount(4L),
            receipt(43),
        ).current
        val created = FirstRunOnboardingPolicy.recordEmailAccountCreated(
            enrolled,
            receipt(44),
        ).current
        val loggedIn = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(
            created,
            actorBinding(),
            receipt(45),
        ).current
        val safety = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(
            loggedIn,
            request(loggedIn),
            receipt(46),
        )

        assertEquals(FirstRunOnboardingStage.PURPOSE_AND_SAFETY, loggedIn.stage)
        assertTrue(safety.accepted)
        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, safety.current.stage)
        assertEquals(
            setOf(
                FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT,
                FirstRunOnboardingStage.ACCOUNT_CREATED,
                FirstRunOnboardingStage.VERIFIED_LOGIN,
                FirstRunOnboardingStage.PURPOSE_AND_SAFETY,
            ),
            safety.current.completedReceiptHashes.keys,
        )
    }

    @Test
    fun emailReceiptRestorationCannotSynthesizeMissingSafetyAcknowledgement() {
        val missingSafety = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
            epoch = 5L,
            orderedEvidence = listOf(
                FirstRunOnboardingEvidence.VerifiedLogin(actorBinding(), receipt(47)),
                FirstRunOnboardingEvidence.Fp004Training(receipt(48)),
            ),
            verifier = acceptingVerifier,
        )
        val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
            epoch = 5L,
            orderedEvidence = listOf(
                FirstRunOnboardingEvidence.VerifiedLogin(actorBinding(), receipt(47)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(49)),
                FirstRunOnboardingEvidence.Fp004Training(receipt(48)),
            ),
            verifier = acceptingVerifier,
        )

        assertEquals(1, missingSafety.restoredEvidenceCount)
        assertEquals(FirstRunOnboardingStage.PURPOSE_AND_SAFETY, missingSafety.snapshot.stage)
        assertEquals(FirstRunOnboardingRejection.STAGE_MISMATCH, missingSafety.rejection)
        assertTrue(restored.fullyRestored)
        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, restored.snapshot.stage)
        assertFalse(restored.snapshot.isComplete)
        assertEquals(
            mapOf(
                FirstRunOnboardingStage.VERIFIED_LOGIN to receipt(47),
                FirstRunOnboardingStage.PURPOSE_AND_SAFETY to receipt(49),
                FirstRunOnboardingStage.FP004_TRAINING to receipt(48),
            ),
            restored.snapshot.completedReceiptHashes,
        )
        assertTrue(
            FirstRunOnboardingStage.PURPOSE_AND_SAFETY in
                restored.snapshot.completedReceiptHashes,
        )
        val permission = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
            restored.snapshot,
            restored.snapshot.epoch,
            restored.snapshot.revision,
        )
        assertTrue(permission.accepted)
        assertEquals(FirstRunOnboardingStage.DEVICE_CHECK, permission.current.stage)
        assertFalse(permission.current.isComplete)
        val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(
            permission.current,
            permission.current.epoch,
            permission.current.revision,
        )
        assertTrue(device.accepted)
        assertTrue(device.current.isComplete)
        assertEquals(restored.snapshot.completedReceiptHashes, device.current.completedReceiptHashes)
    }

    @Test
    fun rejectedEmailTrainingRestoreKeepsThePreSkipSnapshot() {
        val beforeTraining = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
            epoch = 5L,
            orderedEvidence = emailTrainingEvidence().dropLast(1),
            verifier = acceptingVerifier,
        )
        var trainingVerifierCalls = 0
        val rejected = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
            epoch = 5L,
            orderedEvidence = emailTrainingEvidence(),
            verifier = FirstRunOnboardingEvidenceVerifier { _, evidence ->
                if (evidence is FirstRunOnboardingEvidence.Fp004Training) {
                    trainingVerifierCalls += 1
                    false
                } else {
                    true
                }
            },
        )

        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, beforeTraining.snapshot.stage)
        assertFalse(rejected.fullyRestored)
        assertEquals(2, rejected.restoredEvidenceCount)
        assertEquals(FirstRunOnboardingRejection.REMOTE_EVIDENCE_REJECTED, rejected.rejection)
        assertEquals(beforeTraining.snapshot, rejected.snapshot)
        assertEquals(1, trainingVerifierCalls)
    }

    @Test
    fun throwingEmailTrainingVerifierKeepsThePreSkipSnapshot() {
        val beforeTraining = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
            epoch = 5L,
            orderedEvidence = emailTrainingEvidence().dropLast(1),
            verifier = acceptingVerifier,
        )
        var trainingVerifierCalls = 0
        val rejected = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
            epoch = 5L,
            orderedEvidence = emailTrainingEvidence(),
            verifier = FirstRunOnboardingEvidenceVerifier { _, evidence ->
                if (evidence is FirstRunOnboardingEvidence.Fp004Training) {
                    trainingVerifierCalls += 1
                    throw IllegalStateException("training verification unavailable")
                }
                true
            },
        )

        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, beforeTraining.snapshot.stage)
        assertFalse(rejected.fullyRestored)
        assertEquals(2, rejected.restoredEvidenceCount)
        assertEquals(FirstRunOnboardingRejection.REMOTE_EVIDENCE_REJECTED, rejected.rejection)
        assertEquals(beforeTraining.snapshot, rejected.snapshot)
        assertEquals(1, trainingVerifierCalls)
    }

    @Test
    fun verifiedEmailTrainingRestoreStillCompletes() {
        var trainingVerifierCalls = 0
        val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
            epoch = 5L,
            orderedEvidence = emailTrainingEvidence(),
            verifier = FirstRunOnboardingEvidenceVerifier { _, evidence ->
                if (evidence is FirstRunOnboardingEvidence.Fp004Training) {
                    trainingVerifierCalls += 1
                }
                true
            },
        )

        assertTrue(restored.fullyRestored)
        assertEquals(3, restored.restoredEvidenceCount)
        assertNull(restored.rejection)
        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, restored.snapshot.stage)
        assertFalse(restored.snapshot.mayEnterWalk)
        assertEquals(
            receipt(48),
            restored.snapshot.completedReceiptHashes[FirstRunOnboardingStage.FP004_TRAINING],
        )
        assertEquals(1, trainingVerifierCalls)
        val permission = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
            restored.snapshot,
            restored.snapshot.epoch,
            restored.snapshot.revision,
        )
        assertTrue(permission.accepted)
        assertEquals(FirstRunOnboardingStage.DEVICE_CHECK, permission.current.stage)
        assertFalse(permission.current.mayEnterWalk)
        val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(
            permission.current,
            permission.current.epoch,
            permission.current.revision,
        )
        assertTrue(device.accepted)
        assertEquals(FirstRunOnboardingStage.COMPLETE, device.current.stage)
        assertTrue(device.current.mayEnterWalk)
        assertEquals(
            receipt(48),
            device.current.completedReceiptHashes[FirstRunOnboardingStage.FP004_TRAINING],
        )
    }

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

    @Test
    fun legacyAgeStageRejectsVerifiedEmailAgeWithoutConsumingTheValidRequest() {
        val cases = listOf(
            Triple(
                FirstRunAgeBand.UNDER_14,
                FirstRunOnboardingStage.BLOCKED_UNDER_14,
                false,
            ),
            Triple(
                FirstRunAgeBand.AGE_14_TO_17,
                FirstRunOnboardingStage.INTEGRATED_CONSENT,
                true,
            ),
            Triple(
                FirstRunAgeBand.ADULT_18_PLUS,
                FirstRunOnboardingStage.INTEGRATED_CONSENT,
                false,
            ),
        )
        for ((validAge, expectedStage, expectedGuardian) in cases) {
            val original = completePurpose(FirstRunOnboardingPolicy.initial(epoch = 61L))
            val ageRequest = request(original)
            assertEquals(FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED, original.stage)
            assertEquals(1L, original.revision)
            assertNull(original.ageBand)
            assertEquals(
                mapOf(FirstRunOnboardingStage.PURPOSE_AND_SAFETY to receipt(1)),
                original.completedReceiptHashes,
            )

            val rejected = FirstRunOnboardingPolicy.recordAgeAndGuardianNeed(
                original,
                ageRequest,
                FirstRunAgeBand.VERIFIED_14_PLUS,
                receipt(60),
            )

            assertFlowAgeMismatchPreservesSnapshot(original, rejected)
            val accepted = FirstRunOnboardingPolicy.recordAgeAndGuardianNeed(
                original,
                ageRequest,
                validAge,
                receipt(61),
            )
            assertTrue(accepted.accepted)
            assertNull(accepted.rejection)
            assertSame(original, accepted.previous)
            assertEquals(FirstRunOnboardingFlow.LEGACY_PHONE_V3, accepted.current.flow)
            assertEquals(61L, accepted.current.epoch)
            assertEquals(2L, accepted.current.revision)
            assertEquals(expectedStage, accepted.current.stage)
            assertEquals(validAge, accepted.current.ageBand)
            assertEquals(expectedGuardian, accepted.current.guardianApprovalRequired)
            assertEquals(
                mapOf(
                    FirstRunOnboardingStage.PURPOSE_AND_SAFETY to receipt(1),
                    FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED to receipt(61),
                ),
                accepted.current.completedReceiptHashes,
            )
            assertNull(accepted.current.pendingAttempt)
            assertNull(accepted.current.localCredentialPhoneSubmissionHandle)
            assertNull(accepted.current.verifiedActorBinding)
            if (validAge == FirstRunAgeBand.UNDER_14) {
                assertTrue(accepted.current.isTerminal)
            } else {
                assertFalse(accepted.current.isTerminal)
            }
            assertFalse(accepted.current.mayEnterWalk)
        }
    }

    @Test
    fun emailEnrollmentAttemptRejectsLegacyAgeBeforeVerifierAndKeepsItsToken() {
        for (invalidAge in listOf(
            FirstRunAgeBand.UNDER_14,
            FirstRunAgeBand.AGE_14_TO_17,
            FirstRunAgeBand.ADULT_18_PLUS,
        )) {
            val initial = FirstRunOnboardingPolicy.initialEmailAccount(epoch = 62L)
            val started = FirstRunOnboardingPolicy.beginAttempt(initial, request(initial))
            assertTrue(started.accepted)
            assertNull(started.rejection)
            val original = started.current
            val token = original.pendingAttempt!!
            assertEquals(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, original.stage)
            assertEquals(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, token.stage)
            assertEquals(1L, original.revision)
            assertNull(original.ageBand)
            assertTrue(original.completedReceiptHashes.isEmpty())
            var verifierCalls = 0
            var observedToken: FirstRunOnboardingAttemptToken? = null
            var observedEvidence: FirstRunOnboardingEvidence? = null
            val verifier = FirstRunOnboardingEvidenceVerifier { verifiedToken, evidence ->
                verifierCalls += 1
                observedToken = verifiedToken
                observedEvidence = evidence
                true
            }

            val rejected = FirstRunOnboardingPolicy.completeAttempt(
                original,
                token,
                FirstRunOnboardingEvidence.EmailOtpEnrollment(invalidAge, receipt(62)),
                verifier,
            )

            assertFlowAgeMismatchPreservesSnapshot(original, rejected)
            assertSame(token, rejected.current.pendingAttempt)
            assertEquals(0, verifierCalls)
            assertNull(observedToken)
            assertNull(observedEvidence)
            val validEvidence = FirstRunOnboardingEvidence.EmailOtpEnrollment(
                FirstRunAgeBand.VERIFIED_14_PLUS,
                receipt(63),
            )
            val accepted = FirstRunOnboardingPolicy.completeAttempt(
                original,
                token,
                validEvidence,
                verifier,
            )
            assertTrue(accepted.accepted)
            assertNull(accepted.rejection)
            assertSame(original, accepted.previous)
            assertEquals(1, verifierCalls)
            assertSame(token, observedToken)
            assertSame(validEvidence, observedEvidence)
            assertEquals(FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4, accepted.current.flow)
            assertEquals(FirstRunOnboardingStage.ACCOUNT_CREATED, accepted.current.stage)
            assertEquals(FirstRunAgeBand.VERIFIED_14_PLUS, accepted.current.ageBand)
            assertEquals(62L, accepted.current.epoch)
            assertEquals(2L, accepted.current.revision)
            assertEquals(
                mapOf(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT to receipt(63)),
                accepted.current.completedReceiptHashes,
            )
            assertNull(accepted.current.pendingAttempt)
            assertNull(accepted.current.localCredentialPhoneSubmissionHandle)
            assertNull(accepted.current.verifiedActorBinding)
            assertFalse(accepted.current.guardianApprovalRequired)
        }

        val emailInitial = FirstRunOnboardingPolicy.initialEmailAccount(epoch = 63L)
        val wrongLocal = FirstRunOnboardingPolicy.recordAgeAndGuardianNeed(
            emailInitial,
            request(emailInitial),
            FirstRunAgeBand.ADULT_18_PLUS,
            receipt(64),
        )
        assertFlowAgeMismatchPreservesSnapshot(emailInitial, wrongLocal)
        val recorded = FirstRunOnboardingPolicy.recordEmailOtpEnrollment(emailInitial, receipt(65))
        assertTrue(recorded.accepted)
        assertNull(recorded.rejection)
        assertEquals(FirstRunOnboardingStage.ACCOUNT_CREATED, recorded.current.stage)
        assertEquals(FirstRunAgeBand.VERIFIED_14_PLUS, recorded.current.ageBand)
        assertEquals(1L, recorded.current.revision)
        assertEquals(
            mapOf(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT to receipt(65)),
            recorded.current.completedReceiptHashes,
        )
        assertNull(recorded.current.pendingAttempt)
    }

    @Test
    fun receiptRestorationRejectsCrossFlowAgeBeforeAdvancingOrVerifyingLaterEvidence() {
        val purpose = FirstRunOnboardingEvidence.PurposeAndSafety(receipt(1))
        val remote = FirstRunOnboardingEvidence.IntegratedConsent(receipt(67))
        val legacyVerifiedEvidence = mutableListOf<FirstRunOnboardingEvidence>()
        val legacyVerifier = FirstRunOnboardingEvidenceVerifier { _, evidence ->
            legacyVerifiedEvidence += evidence
            true
        }
        val rejectedLegacy = FirstRunOnboardingPolicy.restoreVerifiedReceiptPrefix(
            epoch = 64L,
            orderedEvidence = listOf(
                purpose,
                FirstRunOnboardingEvidence.AgeAndGuardianNeed(
                    FirstRunAgeBand.VERIFIED_14_PLUS,
                    receipt(66),
                ),
                remote,
            ),
            verifier = legacyVerifier,
        )

        assertFalse(rejectedLegacy.fullyRestored)
        assertEquals(1, rejectedLegacy.restoredEvidenceCount)
        assertEquals(FirstRunOnboardingRejection.STAGE_MISMATCH, rejectedLegacy.rejection)
        assertTrue(legacyVerifiedEvidence.isEmpty())
        assertEquals(FirstRunOnboardingFlow.LEGACY_PHONE_V3, rejectedLegacy.snapshot.flow)
        assertEquals(FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED, rejectedLegacy.snapshot.stage)
        assertEquals(64L, rejectedLegacy.snapshot.epoch)
        assertEquals(1L, rejectedLegacy.snapshot.revision)
        assertNull(rejectedLegacy.snapshot.ageBand)
        assertEquals(
            mapOf(FirstRunOnboardingStage.PURPOSE_AND_SAFETY to receipt(1)),
            rejectedLegacy.snapshot.completedReceiptHashes,
        )
        assertNull(rejectedLegacy.snapshot.pendingAttempt)
        assertNull(rejectedLegacy.snapshot.localCredentialPhoneSubmissionHandle)
        assertNull(rejectedLegacy.snapshot.verifiedActorBinding)

        val restoredLegacy = FirstRunOnboardingPolicy.restoreVerifiedReceiptPrefix(
            epoch = 64L,
            orderedEvidence = listOf(
                purpose,
                FirstRunOnboardingEvidence.AgeAndGuardianNeed(
                    FirstRunAgeBand.ADULT_18_PLUS,
                    receipt(66),
                ),
                remote,
            ),
            verifier = legacyVerifier,
        )
        assertTrue(restoredLegacy.fullyRestored)
        assertNull(restoredLegacy.rejection)
        assertEquals(3, restoredLegacy.restoredEvidenceCount)
        assertEquals(listOf(remote), legacyVerifiedEvidence)
        assertEquals(
            FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION,
            restoredLegacy.snapshot.stage,
        )
        assertEquals(3L, restoredLegacy.snapshot.revision)
        assertEquals(FirstRunAgeBand.ADULT_18_PLUS, restoredLegacy.snapshot.ageBand)
        assertFalse(restoredLegacy.snapshot.guardianApprovalRequired)
        assertEquals(
            mapOf(
                FirstRunOnboardingStage.PURPOSE_AND_SAFETY to receipt(1),
                FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED to receipt(66),
                FirstRunOnboardingStage.INTEGRATED_CONSENT to receipt(67),
            ),
            restoredLegacy.snapshot.completedReceiptHashes,
        )
        assertNull(restoredLegacy.snapshot.pendingAttempt)
        assertNull(restoredLegacy.snapshot.localCredentialPhoneSubmissionHandle)
        assertNull(restoredLegacy.snapshot.verifiedActorBinding)

        for (invalidAge in listOf(
            FirstRunAgeBand.UNDER_14,
            FirstRunAgeBand.AGE_14_TO_17,
            FirstRunAgeBand.ADULT_18_PLUS,
        )) {
            var verifierCalls = 0
            var observedEvidence: FirstRunOnboardingEvidence? = null
            val verifier = FirstRunOnboardingEvidenceVerifier { _, evidence ->
                verifierCalls += 1
                observedEvidence = evidence
                true
            }
            val rejectedEmail = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
                epoch = 65L,
                orderedEvidence = listOf(
                    FirstRunOnboardingEvidence.EmailOtpEnrollment(invalidAge, receipt(68)),
                    FirstRunOnboardingEvidence.AccountCreated(receipt(69)),
                ),
                verifier = verifier,
            )

            assertFalse(rejectedEmail.fullyRestored)
            assertEquals(0, rejectedEmail.restoredEvidenceCount)
            assertEquals(FirstRunOnboardingRejection.STAGE_MISMATCH, rejectedEmail.rejection)
            assertEquals(0, verifierCalls)
            assertNull(observedEvidence)
            assertEquals(FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4, rejectedEmail.snapshot.flow)
            assertEquals(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT, rejectedEmail.snapshot.stage)
            assertEquals(65L, rejectedEmail.snapshot.epoch)
            assertEquals(0L, rejectedEmail.snapshot.revision)
            assertNull(rejectedEmail.snapshot.ageBand)
            assertTrue(rejectedEmail.snapshot.completedReceiptHashes.isEmpty())
            assertNull(rejectedEmail.snapshot.pendingAttempt)
            assertNull(rejectedEmail.snapshot.localCredentialPhoneSubmissionHandle)
            assertNull(rejectedEmail.snapshot.verifiedActorBinding)

            val validEvidence = FirstRunOnboardingEvidence.EmailOtpEnrollment(
                FirstRunAgeBand.VERIFIED_14_PLUS,
                receipt(70),
            )
            val restoredEmail = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
                epoch = 65L,
                orderedEvidence = listOf(validEvidence),
                verifier = verifier,
            )
            assertTrue(restoredEmail.fullyRestored)
            assertNull(restoredEmail.rejection)
            assertEquals(1, restoredEmail.restoredEvidenceCount)
            assertEquals(1, verifierCalls)
            assertSame(validEvidence, observedEvidence)
            assertEquals(FirstRunOnboardingStage.ACCOUNT_CREATED, restoredEmail.snapshot.stage)
            assertEquals(FirstRunAgeBand.VERIFIED_14_PLUS, restoredEmail.snapshot.ageBand)
            assertEquals(1L, restoredEmail.snapshot.revision)
            assertFalse(restoredEmail.snapshot.guardianApprovalRequired)
            assertEquals(
                mapOf(FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT to receipt(70)),
                restoredEmail.snapshot.completedReceiptHashes,
            )
            assertNull(restoredEmail.snapshot.pendingAttempt)
            assertNull(restoredEmail.snapshot.localCredentialPhoneSubmissionHandle)
            assertNull(restoredEmail.snapshot.verifiedActorBinding)
        }
    }

    private fun assertFlowAgeMismatchPreservesSnapshot(
        original: FirstRunOnboardingSnapshot,
        rejected: FirstRunOnboardingTransition,
    ) {
        assertFalse(rejected.accepted)
        assertEquals(FirstRunOnboardingRejection.STAGE_MISMATCH, rejected.rejection)
        assertSame(original, rejected.previous)
        assertSame(original, rejected.current)
        assertEquals(original.policyVersion, rejected.current.policyVersion)
        assertEquals(original.flow, rejected.current.flow)
        assertEquals(original.stage, rejected.current.stage)
        assertEquals(original.epoch, rejected.current.epoch)
        assertEquals(original.revision, rejected.current.revision)
        assertEquals(original.ageBand, rejected.current.ageBand)
        assertSame(original.completedReceiptHashes, rejected.current.completedReceiptHashes)
        assertSame(original.pendingAttempt, rejected.current.pendingAttempt)
        assertEquals(
            original.localCredentialPhoneSubmissionHandle,
            rejected.current.localCredentialPhoneSubmissionHandle,
        )
        assertEquals(original.verifiedActorBinding, rejected.current.verifiedActorBinding)
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

    private fun emailTrainingEvidence(): List<FirstRunOnboardingEvidence> = listOf(
        FirstRunOnboardingEvidence.VerifiedLogin(actorBinding(), receipt(47)),
        FirstRunOnboardingEvidence.PurposeAndSafety(receipt(49)),
        FirstRunOnboardingEvidence.Fp004Training(receipt(48)),
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
