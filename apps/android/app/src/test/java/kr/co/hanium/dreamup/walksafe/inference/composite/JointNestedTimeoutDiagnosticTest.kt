package kr.co.hanium.dreamup.walksafe.inference.composite

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeoutException
import java.util.concurrent.CancellationException
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeException

class JointNestedTimeoutDiagnosticTest {
    @Test fun nestedTimeoutIsNotNativeFailure() {
        val error = VisionRuntimeException("vision_open_failed", VisionRuntimeException("vision_operation_failed", TimeoutException("PRIVATE_SENTINEL")))
        val event = jointDiagnosticFailure(JointDiagnosticStage.AUXILIARY_INIT, null, error)
        assertEquals(JointDiagnosticReason.TIMED_OUT, event.reason)
        assertFalse(formatJointRuntimeCandidateDiagnostic(event).contains("PRIVATE_SENTINEL"))
    }
    @Test fun nestedCancellationIsNotNativeFailure() {
        assertEquals(JointDiagnosticReason.CANCELLED, jointDiagnosticFailure(JointDiagnosticStage.AUXILIARY_INIT, null,
            VisionRuntimeException("vision_open_failed", CancellationException("PRIVATE_SENTINEL"))).reason)
    }
    @Test fun cyclicUnknownCauseIsBoundedAndPrivate() {
        val a = RuntimeException("PRIVATE_SENTINEL"); val b = RuntimeException("PRIVATE_SENTINEL", a); a.initCause(b)
        val event = jointDiagnosticFailure(JointDiagnosticStage.AUXILIARY_INIT, null, a)
        assertEquals(JointDiagnosticReason.NATIVE_FAILED, event.reason)
        assertFalse(formatJointRuntimeCandidateDiagnostic(event).contains("PRIVATE_SENTINEL"))
    }
}
