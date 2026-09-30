package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunOnboardingRestoreFreshChecksRegressionTest {
    private val acceptingVerifier = FirstRunOnboardingEvidenceVerifier { _, _ -> true }

    @Test
    fun restoredTrainingWaitsForFreshPermissionAndDeviceChecks() {
        val loginReceipt = receipt(81)
        val safetyReceipt = receipt(82)
        val trainingReceipt = receipt(83)
        val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
            epoch = 101L,
            orderedEvidence = listOf(
                FirstRunOnboardingEvidence.VerifiedLogin(actorBinding(), loginReceipt),
                FirstRunOnboardingEvidence.PurposeAndSafety(safetyReceipt),
                FirstRunOnboardingEvidence.Fp004Training(trainingReceipt),
            ),
            verifier = acceptingVerifier,
        )
        val awaitingChecks = restored.snapshot
        val expectedReceipts = mapOf(
            FirstRunOnboardingStage.VERIFIED_LOGIN to loginReceipt,
            FirstRunOnboardingStage.PURPOSE_AND_SAFETY to safetyReceipt,
            FirstRunOnboardingStage.FP004_TRAINING to trainingReceipt,
        )

        assertTrue(restored.fullyRestored)
        assertEquals(3, restored.restoredEvidenceCount)
        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, awaitingChecks.stage)
        assertFalse(awaitingChecks.isComplete)
        assertFalse(awaitingChecks.mayEnterWalk)
        assertEquals(expectedReceipts, awaitingChecks.completedReceiptHashes)

        val prematureDevice = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(
            awaitingChecks,
            awaitingChecks.epoch,
            awaitingChecks.revision,
        )
        assertFalse(prematureDevice.accepted)
        assertEquals(FirstRunOnboardingRejection.STAGE_MISMATCH, prematureDevice.rejection)
        assertSame(awaitingChecks, prematureDevice.current)

        val permission = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(
            awaitingChecks,
            awaitingChecks.epoch,
            awaitingChecks.revision,
        )
        assertTrue(permission.accepted)
        assertEquals(FirstRunOnboardingStage.DEVICE_CHECK, permission.current.stage)
        assertFalse(permission.current.mayEnterWalk)
        assertEquals(expectedReceipts, permission.current.completedReceiptHashes)

        val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(
            permission.current,
            permission.current.epoch,
            permission.current.revision,
        )
        assertTrue(device.accepted)
        assertEquals(FirstRunOnboardingStage.COMPLETE, device.current.stage)
        assertTrue(device.current.mayEnterWalk)
        assertEquals(expectedReceipts, device.current.completedReceiptHashes)
    }

    @Test
    fun historicalChecksAreVerifiedButCannotReplaceFreshChecks() {
        val enrollmentReceipt = receipt(91)
        val accountReceipt = receipt(92)
        val loginReceipt = receipt(93)
        val safetyReceipt = receipt(94)
        val trainingReceipt = receipt(97)
        val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(
            epoch = 102L,
            orderedEvidence = listOf(
                FirstRunOnboardingEvidence.EmailOtpEnrollment(
                    FirstRunAgeBand.VERIFIED_14_PLUS,
                    enrollmentReceipt,
                ),
                FirstRunOnboardingEvidence.AccountCreated(accountReceipt),
                FirstRunOnboardingEvidence.VerifiedLogin(actorBinding(), loginReceipt),
                FirstRunOnboardingEvidence.PurposeAndSafety(safetyReceipt),
                FirstRunOnboardingEvidence.JitPermissionObservation(receipt(95)),
                FirstRunOnboardingEvidence.DeviceCheck(receipt(96)),
                FirstRunOnboardingEvidence.Fp004Training(trainingReceipt),
            ),
            verifier = acceptingVerifier,
        )
        val expectedReceipts = mapOf(
            FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT to enrollmentReceipt,
            FirstRunOnboardingStage.ACCOUNT_CREATED to accountReceipt,
            FirstRunOnboardingStage.VERIFIED_LOGIN to loginReceipt,
            FirstRunOnboardingStage.PURPOSE_AND_SAFETY to safetyReceipt,
            FirstRunOnboardingStage.FP004_TRAINING to trainingReceipt,
        )

        assertTrue(restored.fullyRestored)
        assertEquals(7, restored.restoredEvidenceCount)
        assertEquals(FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION, restored.snapshot.stage)
        assertFalse(restored.snapshot.mayEnterWalk)
        assertEquals(expectedReceipts, restored.snapshot.completedReceiptHashes)

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
        assertEquals(expectedReceipts, device.current.completedReceiptHashes)
    }

    private fun receipt(number: Int): FirstRunReceiptHash =
        FirstRunReceiptHash.fromSha256Hex(number.toString(16).padStart(64, '0'))

    private fun actorBinding(): FirstRunOpaqueActorBinding =
        FirstRunOpaqueActorBinding.fromProvider(
            "actor_b1c2d3e4f5a60718293b4c5d6e7f809a",
        )
}
