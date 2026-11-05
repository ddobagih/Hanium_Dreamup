package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeoutException
import java.util.concurrent.CancellationException

class VisionOpenDiagnosticTest {
    @Test fun originalNestedTimeoutSurvivesWrappingAndWire() {
        val original = RuntimeException("PRIVATE_SENTINEL", TimeoutException("PRIVATE_SENTINEL"))
        val wire = nativeCallbackFailure(visionFailureKind(original).name)
        assertTrue(wire is VisionNativeCallbackTimeout)
        assertEquals(VisionFailureKind.TIMEOUT, visionFailureKind(VisionRuntimeException("vision_open_failed", wire)))
    }
    @Test fun nativeFailureIsNotParentAwaitTimeout() {
        assertTrue(nativeCallbackFailure("TIMEOUT") is VisionNativeCallbackTimeout)
        assertFalse(nativeCallbackFailure("STATE") is TimeoutException)
        assertEquals(VisionFailureKind.STATE, visionFailureKind(nativeCallbackFailure("STATE")))
        assertEquals(VisionFailureKind.OTHER, visionFailureKind(nativeCallbackFailure("PRIVATE_SENTINEL")))
        assertFalse(nativeCallbackFailure("PRIVATE_SENTINEL").toString().contains("PRIVATE_SENTINEL"))
    }
    @Test fun nestedCancellationPreservedWithoutMessage() {
        assertEquals(VisionFailureKind.CANCELLED, visionFailureKind(RuntimeException("PRIVATE_SENTINEL", InterruptedException("PRIVATE_SENTINEL"))))
        assertTrue(nativeCallbackFailure("CANCELLED") is CancellationException)
    }
    @Test fun failureClassAllowlistIsPrivate() {
        val errors = listOf(OutOfMemoryError("PRIVATE_SENTINEL"), SecurityException("PRIVATE_SENTINEL"),
            IllegalArgumentException("PRIVATE_SENTINEL"), IllegalStateException("PRIVATE_SENTINEL"),
            UnsatisfiedLinkError("PRIVATE_SENTINEL"), RuntimeException("PRIVATE_SENTINEL"))
        val expected = listOf(VisionFailureKind.OUT_OF_MEMORY, VisionFailureKind.SECURITY, VisionFailureKind.ARGUMENT,
            VisionFailureKind.STATE, VisionFailureKind.LINKAGE, VisionFailureKind.OTHER)
        errors.forEachIndexed { i, error -> assertEquals(expected[i], visionFailureKind(error)) }
    }
    @Test fun cyclesAndDepthAreBounded() {
        val a = RuntimeException("PRIVATE_SENTINEL"); val b = RuntimeException("PRIVATE_SENTINEL", a); a.initCause(b)
        assertEquals(VisionFailureKind.OTHER, visionFailureKind(a))
        var deep: Throwable = TimeoutException("PRIVATE_SENTINEL")
        repeat(16) { deep = RuntimeException("PRIVATE_SENTINEL", deep) }
        assertEquals(VisionFailureKind.OTHER, visionFailureKind(deep))
    }
    @Test fun deathIsNotProofOfWatchdog() {
        assertEquals(VisionFailureKind.CHILD_DIED, visionFailureKind(VisionRuntimeException("vision_child_died")))
        assertEquals(VisionFailureKind.OTHER, visionFailureKind(RuntimeException("vision_child_died")))
    }
    @Test fun traceRecordsBoundaryAndClampsWithoutLeaking() {
        val out = mutableListOf<VisionOpenSnapshot>()
        val trace = VisionOpenTrace(true, VisionOpenBackend.GPU, 100, 20_100, out::add)
        trace.emit(VisionOpenEvent.CHILD_ENTERED, 100)
        trace.emit(VisionOpenEvent.SERVICE_WATCHDOG, 20_100, TimeoutException("PRIVATE_SENTINEL"))
        assertEquals(0L, out[0].durationMs); assertEquals(20_000L, out[0].remainingMs)
        assertEquals(20_000L, out[1].durationMs); assertEquals(0L, out[1].remainingMs)
        assertTrue(out[1].toString().contains("operation=OPEN role=AUXILIARY requested_backend=GPU"))
        assertFalse(out.joinToString().contains("PRIVATE_SENTINEL"))
        trace.emit(VisionOpenEvent.CHILD_FINISHED, Long.MAX_VALUE)
        assertEquals(300_000L, out.last().durationMs)
    }
    @Test fun negativeClockAndOverflowAreBounded() {
        val out = mutableListOf<VisionOpenSnapshot>()
        val trace = VisionOpenTrace(false, VisionOpenBackend.CPU, Long.MIN_VALUE, Long.MAX_VALUE, out::add)
        trace.emit(VisionOpenEvent.PARENT_ENTERED, 0)
        assertEquals(300_000L, out.single().durationMs)
        assertEquals(300_000L, out.single().remainingMs)
        assertEquals(300_000L, out.single().budgetMs)
    }
    @Test fun throwingSinkCannotAffectOperation() {
        val trace = VisionOpenTrace(true, VisionOpenBackend.GPU, 0, 20_000) { throw IllegalStateException("PRIVATE_SENTINEL") }
        trace.emit(VisionOpenEvent.SOURCE_CANCEL, 1)
    }
    @Test fun sharedDeadlineCallerClampAndCpuPrimaryLimitsRemainFinite() {
        assertEquals(20_000L, VisionRuntimeProtocol.OPEN_MS)
        assertEquals(20_100L, VisionRuntimeProtocol.deadline(Long.MAX_VALUE, 100, VisionRuntimeProtocol.OPEN_MS))
        assertEquals(101L, VisionRuntimeProtocol.deadline(101, 100, VisionRuntimeProtocol.OPEN_MS))
        assertEquals(20_100L, VisionRuntimeProtocol.deadline(20_100, 200, VisionRuntimeProtocol.OPEN_MS))
        assertEquals(Long.MAX_VALUE, VisionRuntimeProtocol.deadline(Long.MAX_VALUE, Long.MAX_VALUE - 1, VisionRuntimeProtocol.OPEN_MS))
        assertThrows(IllegalArgumentException::class.java) { VisionRuntimeProtocol.deadline(100, 100, VisionRuntimeProtocol.OPEN_MS) }
        assertEquals(2_000L, VisionRuntimeProtocol.operationBudget(VisionPurpose.BENCHMARK))
    }
    @Test fun diagnosticsCannotGrantAdmissionOrPhysicalRelease() {
        val authority = VisionDispatchAuthority()
        val released = java.util.concurrent.CompletableFuture<VisionReleaseReceipt>()
        assertTrue(authority.grantOpen(1)); assertTrue(authority.retire(1))
        val trace = VisionOpenTrace(true, VisionOpenBackend.GPU, 0, 20_000) {}
        trace.emit(VisionOpenEvent.SERVICE_WATCHDOG, 20_000)
        assertFalse(authority.grantOpen(1)); assertFalse(authority.grantOpen(2))
        assertFalse(authority.provesNoNativeStart()); assertFalse(released.isDone)
    }
}
