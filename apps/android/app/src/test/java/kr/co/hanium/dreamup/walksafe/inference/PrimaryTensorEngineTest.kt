package kr.co.hanium.dreamup.walksafe.inference

import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test

class PrimaryTensorEngineTest {
    @Test fun nativeAuthorityRejectsTheParentAndUnsupportedApiBeforeAnyModelCanBeCreated() {
        PrimaryTensorProcessPolicy.requireLocal(31, "test.app:vision_runtime", "test.app")
        PrimaryTensorProcessPolicy.requireLocal(31, "test.app:fixture_runtime", "test.app")
        listOf(30 to "test.app:vision_runtime", 31 to "test.app", 31 to "other.app:vision_runtime").forEach { (api, process) ->
            assertTrue(runCatching { PrimaryTensorProcessPolicy.requireLocal(api, process, "test.app") }
                .exceptionOrNull() is IllegalStateException)
        }
    }

    @Test fun nativeBuffersRejectWrongOrPartialInputAndReadOnlyOutputBeforeInvocation() {
        val shape = intArrayOf(1, 2, 6)
        val input = ByteBuffer.allocateDirect(2 * 2 * 3 * 4)
        val output = ByteBuffer.allocateDirect(2 * 6 * 4)
        PrimaryTensorContract.requireBuffers(input, output, 2, shape, 2, 2)
        val badInputs = listOf(ByteBuffer.allocate(input.capacity()), ByteBuffer.allocateDirect(input.capacity() - 4),
            input.duplicate().apply { position(4) })
        badInputs.forEach { bad ->
            val failure = runCatching { PrimaryTensorContract.requireBuffers(bad, output, 2, shape, 2, 2) }.exceptionOrNull()
            assertTrue(failure is DetectorTensorContractViolation)
            assertEquals(DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH, (failure as DetectorTensorContractViolation).code)
        }
        assertTrue(runCatching { PrimaryTensorContract.requireBuffers(input, output.asReadOnlyBuffer(), 2, shape, 2, 2) }
            .exceptionOrNull() is DetectorTensorContractViolation)
        assertTrue(runCatching { PrimaryTensorContract.requireBuffers(input, output, 2, shape, 3, 2) }
            .exceptionOrNull() is DetectorTensorContractViolation)
    }

    @Test fun hostFallbackProvenanceCannotInventActualBackendCacheOrNativeOwner() {
        val actual = AndroidDetectorRuntime("cpu", "cpu", 2, modelOwnerPid = 234,
            modelOwnerThreadId = 55L, modelInstanceId = "observed-model", cohortId = "observed-cohort", nativeLoadMs = 12.0)
        val host = AndroidDetectorRuntime("gpu", "cpu", 2, true, "gpu_invoke_failure",
            gpuSerializationCacheStatus = "must-not-be-copied", modelOwnerPid = 999)
        val merged = PrimaryTensorContract.observedRuntime(actual, host)
        assertEquals("gpu", merged.requestedDelegate)
        assertEquals("cpu", merged.activeDelegate)
        assertTrue(merged.fallbackUsed)
        assertEquals("gpu_invoke_failure", merged.fallbackReason)
        assertEquals(234, merged.modelOwnerPid)
        assertEquals(55L, merged.modelOwnerThreadId)
        assertEquals("observed-model", merged.modelInstanceId)
        assertEquals("observed-cohort", merged.cohortId)
        assertNull(merged.gpuSerializationCacheStatus)
        listOf(actual.copy(activeDelegate = "gpu"), actual.copy(numThreads = 3), actual.copy(fallbackUsed = true)).forEach {
            assertTrue(runCatching { PrimaryTensorContract.observedRuntime(it, host) }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test fun failedOpenReceiptExistsBeforeAnEngineAndWaitsForActualDeathAfterSeal() {
        val owner = PrimaryTensorRelease()
        val receipt = owner.actualReleasedFuture.toCompletableFuture()
        val admission = owner.beforeOpen()
        val death = CompletableFuture<Unit>()
        owner.follow(admission, death)
        owner.seal()
        assertFalse(receipt.isDone)
        death.complete(Unit)
        assertEquals(Unit, receipt.getNow(null))
        assertSame(receipt, owner.actualReleasedFuture.toCompletableFuture())
    }

    @Test fun anEarlierGpuDeathCannotReleaseTheCpuReplacementOrAFactoryStillOpenForAdmission() {
        val owner = PrimaryTensorRelease()
        val gpu = CompletableFuture<Unit>()
        owner.follow(owner.beforeOpen(), gpu)
        gpu.complete(Unit)
        assertFalse(owner.actualReleasedFuture.toCompletableFuture().isDone)
        val cpu = CompletableFuture<Unit>()
        owner.follow(owner.beforeOpen(), cpu)
        owner.seal()
        assertFalse(owner.actualReleasedFuture.toCompletableFuture().isDone)
        cpu.complete(Unit)
        assertTrue(owner.actualReleasedFuture.toCompletableFuture().isDone)
        assertTrue(runCatching { owner.beforeOpen() }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun closeFailureIsNotPhysicalReleaseAndLateDeathCanStillCompleteTheSameReceipt() {
        val owner = PrimaryTensorRelease()
        val slot = owner.beforeOpen()
        val close = CompletableFuture<Unit>()
        val death = CompletableFuture<Unit>()
        owner.follow(slot, close)
        owner.follow(slot, death)
        close.completeExceptionally(IllegalStateException("close timeout"))
        owner.seal()
        assertFalse(owner.actualReleasedFuture.toCompletableFuture().isDone)
        death.complete(Unit)
        assertEquals(Unit, owner.actualReleasedFuture.toCompletableFuture().getNow(null))
    }
}
