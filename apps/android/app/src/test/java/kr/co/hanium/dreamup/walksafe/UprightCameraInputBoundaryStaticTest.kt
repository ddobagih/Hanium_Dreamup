package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-wiring checks only; these do not prove on-device execution or resource release. */
class UprightCameraInputBoundaryStaticTest {
    private val mainSource: String by lazy {
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    }

    @Test
    fun invalidCurrentBasisProducesNoCurrentEvidenceOrObservation() {
        val evidence = compact(section(
            "val currentEvidence = if (grayFrame != null && trackingResult != null) {",
            "val currentObservation = if (trackingResult != null && currentEvidence != null) {",
        ))
        assertInOrder(
            evidence,
            "val currentImageQuarterTurns = UprightCameraImage.quarterTurns(currentViewBasis)",
            "if (currentImageQuarterTurns == null) null else DetectionFrameEvidence(",
            "imageQuarterTurns = currentImageQuarterTurns,",
        )
        assertEquals(1, occurrences(evidence, "DetectionFrameEvidence("))
        assertTrue(evidence.endsWith("} else null"))

        val observation = compact(section(
            "val currentObservation = if (trackingResult != null && currentEvidence != null) {",
            "val overlayDetections =",
        ))
        assertInOrder(
            observation,
            "currentEvidence != null",
            "CurrentTrackedFrameEvidence.fromTrackingResult(",
            "currentEvidence",
        )
        assertTrue(observation.endsWith("} else null"))
    }

    @Test
    fun invalidPrimaryBasisReturnsBeforeEvidenceAndWorkerWhileKeepingImageAndPacingCleanup() {
        val source = compact(section(
            "private fun scheduleDetectionIfDue(",
            "private fun encodeDebugFrameJpeg(",
        ))
        assertInOrder(
            source,
            "var acquiredImage: android.media.Image? = null",
            "var submittedToWorker = false",
            "try {",
            "acquiredImage = cameraImage",
            "val imageQuarterTurns = UprightCameraImage.quarterTurns(orientationBasis) ?: return",
            "val frameEvidence = DetectionFrameEvidence(",
            "imageQuarterTurns = imageQuarterTurns,",
            "detectorExecutor.execute {",
            "submittedToWorker = true",
        )
        assertEquals(1, occurrences(source, "val frameEvidence = DetectionFrameEvidence("))
        assertEquals(1, occurrences(source, "detectorExecutor.execute {"))
        assertTrue(source.contains(
            "finally { if (!submittedToWorker) { try { acquiredImage?.close() } " +
                "finally { finishDetectionWork(pacingTicket, detectorInvoked = false) } } }",
        ))
    }

    @Test
    fun invalidAuxiliaryBasisReturnsBeforeEvidenceAndWorkerWhileKeepingImageAndInflightCleanup() {
        val source = compact(section(
            "private fun scheduleUnknownDetectionIfDue(",
            "private fun submitUnknownObjectFrame(",
        ))
        assertInOrder(
            source,
            "var image: android.media.Image? = null",
            "var queued = false",
            "try {",
            "image = owned",
            "val imageQuarterTurns = UprightCameraImage.quarterTurns(basis) ?: return",
            "val evidence = UnknownCaptureEvidence(",
            "imageQuarterTurns = imageQuarterTurns,",
            "detectorExecutor.execute {",
            "finally { try { owned.close() } finally { unknownFrameCopyInFlight.set(false) } }",
            "queued = true",
        )
        assertEquals(1, occurrences(source, "val evidence = UnknownCaptureEvidence("))
        assertEquals(1, occurrences(source, "detectorExecutor.execute {"))
        assertTrue(source.contains(
            "finally { if (!queued) { try { image?.close() } " +
                "finally { unknownFrameCopyInFlight.set(false) } } }",
        ))
    }

    @Test
    fun invalidDebugBasisReturnsBeforeEvidenceAndWorkerWhileKeepingImageAndBusyCleanup() {
        val debugSource = File(
            "src/debug/java/kr/co/hanium/dreamup/walksafe/CameraTestActivity.kt",
        ).readText()
        val start = debugSource.indexOf("override fun onDrawFrame(gl: GL10?) {")
        assertTrue("Missing debug frame callback", start >= 0)
        val end = debugSource.indexOf("private fun showError(", start)
        assertTrue("Missing debug frame callback end", end > start)
        val source = compact(debugSource.substring(start, end))

        assertInOrder(
            source,
            "if (!busy.compareAndSet(false, true)) return",
            "var transferred = false",
            "var image: android.media.Image? = null",
            "try {",
            "image = source.acquireCameraImageOrNull(frame) ?: return",
            "val ownedImage = image",
            "val turns = UprightCameraImage.quarterTurns(view) ?: return",
            "val geometryId =",
            "worker.execute {",
            "FrozenUnknownDepthCapture.freeze(",
            "imageQuarterTurns = turns)",
            "model.detectOriented(ownedImage, frameId / 1_000_000L, turns)",
            "finally { ownedImage.close() busy.set(false) }",
            "transferred = true",
            "finally { if (!transferred) { image?.close(); busy.set(false) } }",
        )
        assertEquals(1, occurrences(source, "UprightCameraImage.quarterTurns(view)"))
        assertEquals(1, occurrences(source, "val geometryId ="))
        assertEquals(1, occurrences(source, "worker.execute {"))
        assertEquals(1, occurrences(source, "FrozenUnknownDepthCapture.freeze("))
        assertEquals(1, occurrences(source, "model.detectOriented("))
    }

    private fun section(start: String, end: String): String {
        val startIndex = mainSource.indexOf(start)
        assertTrue("Missing Main source anchor: $start", startIndex >= 0)
        val endIndex = mainSource.indexOf(end, startIndex + start.length)
        assertTrue("Missing Main source end: $end", endIndex > startIndex)
        return mainSource.substring(startIndex, endIndex)
    }

    private fun compact(source: String): String = source.replace(Regex("\\s+"), " ").trim()

    private fun occurrences(source: String, token: String): Int =
        Regex(Regex.escape(token)).findAll(source).count()

    private fun assertInOrder(source: String, vararg tokens: String) {
        var offset = 0
        tokens.forEach { token ->
            val found = source.indexOf(token, offset)
            assertTrue("Missing or out-of-order Main contract: $token", found >= 0)
            offset = found + token.length
        }
    }
}
