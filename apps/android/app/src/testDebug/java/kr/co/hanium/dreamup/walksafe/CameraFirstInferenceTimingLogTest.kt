package kr.co.hanium.dreamup.walksafe

import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorLoadPhase
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorPhaseOutcome
import kr.co.hanium.dreamup.walksafe.inference.DetectorLoadTimingRecorder
import kr.co.hanium.dreamup.walksafe.inference.DetectorTensorContract
import kr.co.hanium.dreamup.walksafe.inference.DetectorTensorContractViolation
import kr.co.hanium.dreamup.walksafe.inference.LetterboxTransform
import kr.co.hanium.dreamup.walksafe.inference.PreprocessedImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFirstInferenceTimingLogTest {
    @Test fun rejectedInputKeepsTheProductionLogGatePendingForTheLaterActualAttempt() {
        val recorder = DetectorLoadTimingRecorder()
        val log = CameraFirstInferenceTimingLog()
        val invalid = PreprocessedImage(ByteBuffer.allocateDirect(16),
            LetterboxTransform(1920, 1080, 768, 0.4f, 0f, 168f, 768, 432))
        assertThrows(DetectorTensorContractViolation::class.java) {
            try { DetectorTensorContract.requireModelInput(invalid, 768) }
            finally { assertNull(log.take(recorder.snapshot())) }
        }
        assertTrue(log.pending)
        recorder.measure(AndroidDetectorLoadPhase.FIRST_INFERENCE, "unified_walksafe", "cpu") { Unit }
        assertNotNull(log.take(recorder.snapshot()))
        assertFalse(log.pending)
        assertNull(log.take(recorder.snapshot()))
    }

    @Test fun failedActualNativeAttemptIsLoggedOnceWithoutWaitingForSuccessfulOutput() {
        val recorder = DetectorLoadTimingRecorder()
        val log = CameraFirstInferenceTimingLog()
        assertThrows(IllegalStateException::class.java) {
            recorder.measure(AndroidDetectorLoadPhase.FIRST_INFERENCE, "unified_walksafe", "gpu") {
                throw IllegalStateException("invoke")
            }
        }
        val emitted = log.take(recorder.snapshot())
        assertNotNull(emitted)
        assertTrue(emitted!!.phases.single().outcome == AndroidDetectorPhaseOutcome.FAILED)
        assertFalse(log.pending)
        assertNull(log.take(recorder.snapshot()))
    }
}
