package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import org.junit.Assert.*
import org.junit.Test

class VisionRuntimeProtocolTest {
    @Test fun busyBeforeLeaseCarriesOnlyAvailabilityAndCannotPoisonOwnReleaseLedger() {
        val previous = java.util.concurrent.CompletableFuture<VisionReleaseReceipt>()
        val failure: Throwable = VisionCohortBusyException(previous)
        assertFalse(failure is VisionRuntimeException)
        assertSame(previous, (failure as VisionCohortBusyException).availabilityReceipt)
        assertFalse(previous.isDone)
    }
    @Test fun cancelBeforeQueuedOpenRevokesItsNativePermit() {
        val authority = VisionDispatchAuthority()
        assertTrue(authority.retire(1))
        assertFalse(authority.grantOpen(1))
        assertTrue(authority.provesNoNativeStart())
    }
    @Test fun grantedOpenRequiresDeathEvenIfTransportHasNotReturned() {
        val authority = VisionDispatchAuthority()
        assertTrue(authority.grantOpen(1))
        assertTrue(authority.retire(1))
        assertFalse(authority.provesNoNativeStart())
        assertFalse(authority.grantOpen(1))
    }
    @Test fun transferRevokesOldExitAndOldQueuedOpenWithoutDroppingNativeOwnership() {
        val authority = VisionDispatchAuthority()
        assertTrue(authority.grantOpen(1))
        assertEquals(2L, authority.transfer(1))
        assertFalse(authority.retire(1))
        assertFalse(authority.grantOpen(1))
        assertTrue(authority.accepts(2))
        assertTrue(authority.retire(2))
        assertFalse(authority.provesNoNativeStart())
    }
    @Test fun retiredCohortCannotTransferAndCannotRetireANewCohort() {
        val previous = VisionDispatchAuthority()
        previous.grantOpen(1); previous.retire(1)
        assertThrows(IllegalStateException::class.java) { previous.transfer(1) }
        val next = VisionDispatchAuthority()
        assertTrue(next.grantOpen(1))
        previous.retire(1)
        assertTrue(next.accepts(1))
        next.retire(1)
        assertFalse(next.provesNoNativeStart())
    }
    @Test fun exactFastSamSingleFrameFitsButFourFrameRawParcelDoesNot() {
        val output = intArrayOf(37 * 12096 * 4, 192 * 192 * 32 * 4)
        assertEquals(6_508_800, output.sum())
        VisionSharedMemory.validateSizes(768 * 768 * 4, output)
        assertThrows(IllegalArgumentException::class.java) {
            VisionSharedMemory.validateSizes(768 * 768 * 4, intArrayOf(output.sum() * 4))
        }
    }
    @Test fun malformedOrOverflowedTensorSizesAreRejectedBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) { VisionRuntimeProtocol.tensorBytes(intArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { VisionRuntimeProtocol.tensorBytes(intArrayOf(1, -1, 3)) }
        assertThrows(ArithmeticException::class.java) { VisionRuntimeProtocol.tensorBytes(intArrayOf(100000, 100000)) }
        assertThrows(IllegalArgumentException::class.java) { VisionSharedMemory.validateSizes(0, intArrayOf(4)) }
        assertThrows(ArithmeticException::class.java) { VisionSharedMemory.validateSizes(4, intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE)) }
    }
    @Test fun deadlinesUseCallerBoundAndPurposeLimitWithoutOverflow() {
        assertEquals(1005L, VisionRuntimeProtocol.deadline(1005, 1000, 20_000))
        assertEquals(3000L, VisionRuntimeProtocol.deadline(10000, 1000, VisionRuntimeProtocol.operationBudget(VisionPurpose.LIVE)))
        assertEquals(Long.MAX_VALUE, VisionRuntimeProtocol.deadline(Long.MAX_VALUE, Long.MAX_VALUE - 1, 20_000))
        assertThrows(IllegalArgumentException::class.java) { VisionRuntimeProtocol.deadline(1000, 1000, 20_000) }
        assertEquals(15_000L, VisionRuntimeProtocol.operationBudget(VisionPurpose.WARMUP))
        assertEquals(10_000L, VisionRuntimeProtocol.operationBudget(VisionPurpose.REFERENCE))
    }
    @Test fun optionsSeparateRequestedFallbackFromRemoteNativeAllocation() {
        val options = VisionRuntimeOptions("gpu", 2, false, false, true)
        assertEquals(options, VisionRuntimeCodec.decodeOptions(VisionRuntimeCodec.encodeOptions(options)))
        assertThrows(IllegalArgumentException::class.java) {
            VisionRuntimeCodec.decodeOptions(VisionRuntimeCodec.encodeOptions(options.copy(fallbackToCpu = true)))
        }
        assertThrows(IllegalArgumentException::class.java) { VisionRuntimeCodec.encodeOptions(options.copy(numThreads = 5)) }
    }
    @Test fun runtimeIdentityAndFallbackAreNotAcceptedAsJustOpaqueJson() {
        val actual = """{"requestedDelegate":"gpu","activeDelegate":"gpu","numThreads":2,"fallbackUsed":false,"modelOwnerPid":42,"modelOwnerThreadId":7,"loadMs":1.0}"""
        assertEquals(actual, VisionRuntimeCodec.validateRuntimeJson(actual))
        assertThrows(IllegalArgumentException::class.java) {
            VisionRuntimeCodec.validateRuntimeJson(actual.replace("\"fallbackUsed\":false", "\"fallbackUsed\":true"))
        }
        assertThrows(IllegalArgumentException::class.java) { VisionRuntimeCodec.objectJson(" ".repeat(4097)) }
    }
}
