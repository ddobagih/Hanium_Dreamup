package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.DetectorNativeCleanupFailure
import kr.co.hanium.dreamup.walksafe.inference.DetectorCohortRetired
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionReleaseReceipt
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeException
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CompletableFuture

class JointRuntimePhysicalReleaseFenceTest {
    @Test fun opaqueRetiredHandleFailureStillCarriesTheFactoriesPhysicalReceipt() {
        val fence = JointRuntimeReleaseFence()
        val death = CompletableFuture<Unit>()
        fence.retain(DetectorCohortRetired(IllegalStateException("retired handle"), death))
        assertThrows(DetectorNativeCleanupFailure::class.java) { fence.ensureConfirmed() }
        death.complete(Unit)
        fence.ensureConfirmed()
    }

    @Test fun completedLoadReceiptSurvivesRequireCleanResultAndThePreparationCatch() {
        for (reason in listOf("DetectorNativeCleanupFailure", "DetectorCohortRetired")) {
        val fence = JointRuntimeReleaseFence()
        val config = JointRuntimeTestFixtures.config
        val choice = JointRuntimeTestFixtures.choice(JointRuntimeTestFixtures.candidate)
        val failure = org.junit.Assert.assertThrows(DetectorNativeCleanupFailure::class.java) {
            AndroidJointRuntimeLoader.prepare(config, choice, { true }, choice.bindingHash, 8, true,
                create = { _, _ -> kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorLoadResult(
                    null, true, false, config.primaryModelKey, false, reason,
                    actualReleasedFuture = CompletableFuture.completedFuture(Unit)) },
                sample = { error("A failed OPEN cannot run a sample") },
                close = { error("No detector was returned") }, fence = fence)
        }
        fence.ensureConfirmed()
        // A duplicate late catch of the same error must still carry its already-confirmed proof.
        fence.retain(failure)
        fence.ensureConfirmed()
        }
    }

    @Test fun aTimeoutReportCannotReleaseButLatePhysicalAcknowledgementCan() {
        val fence = JointRuntimeReleaseFence()
        val death = CompletableFuture<VisionReleaseReceipt>()
        val timeout = VisionRuntimeException("open_timeout", releaseReceipt = death)
        fence.retain(DetectorNativeCleanupFailure(timeout))
        assertThrows(DetectorNativeCleanupFailure::class.java) { fence.ensureConfirmed() }
        death.complete(VisionReleaseReceipt("same-cohort", 77))
        fence.ensureConfirmed()
    }

    @Test fun releasingOneFailureCannotClearAnUnprovenNativeOwner() {
        val fence = JointRuntimeReleaseFence()
        val death = CompletableFuture<Unit>()
        fence.retain(IllegalStateException("unproven local owner"))
        fence.retain(IllegalStateException("another owner"), physicalRelease = death)
        death.complete(Unit)
        assertThrows(DetectorNativeCleanupFailure::class.java) { fence.ensureConfirmed() }
    }

    @Test fun semanticFailureOrFailedReceiptCannotMasqueradeAsPhysicalRelease() {
        val fence = JointRuntimeReleaseFence()
        val notPhysical = CompletableFuture<Unit>()
        fence.retain(IllegalStateException("unconfirmed"), physicalRelease = notPhysical)
        notPhysical.completeExceptionally(IllegalStateException("close report failed"))
        assertThrows(DetectorNativeCleanupFailure::class.java) { fence.ensureConfirmed() }
    }

    @Test fun onlyTheCapturedFailureIsRemovedWhenAnotherFailureArrivesBeforeDeath() {
        val fence = JointRuntimeReleaseFence()
        val first = CompletableFuture<Unit>()
        val second = CompletableFuture<Unit>()
        fence.retain(IllegalStateException("first"), physicalRelease = first)
        fence.retain(IllegalStateException("second"), physicalRelease = second)
        first.complete(Unit)
        assertThrows(DetectorNativeCleanupFailure::class.java) { fence.ensureConfirmed() }
        second.complete(Unit)
        fence.ensureConfirmed()
    }
}
