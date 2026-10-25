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
        assertTrue(MainActivityRefactorSource.member(mainSource, "onDrawFrame").contains("framePipelineController.draw()"))
        assertTrue(MainActivityRefactorSource.property(mainSource, "framePipelineController").contains("FramePipelineController(frameStateLock, frameRenderController"))
        val synthesis = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePipelineController.kt").readText(), "synthesize")
        val evidence = compact(section(synthesis,
            "val evidence = if (q.gray != null && tracking != null) {", "val observation ="))
        assertInOrder(evidence, "val turns = UprightCameraImage.quarterTurns(basis)",
            "if (turns == null) null else DetectionFrameEvidence(", "imageQuarterTurns = turns)")
        assertEquals(1, occurrences(evidence, "DetectionFrameEvidence("))
        assertTrue(evidence.endsWith("} else null"))
        val observation = compact(section(synthesis, "val observation =", "val overlays ="))
        assertInOrder(observation, "tracking != null && evidence != null",
            "CurrentTrackedFrameEvidence.fromTrackingResult(", "tracking, evidence,")
        assertEquals(1, occurrences(observation, "CurrentTrackedFrameEvidence.fromTrackingResult("))
        assertTrue(observation.endsWith("else null"))
    }

    @Test
    fun invalidPrimaryBasisReturnsBeforeEvidenceAndWorkerWhileKeepingImageAndPacingCleanup() {
        assertTrue(MainActivityRefactorSource.member(mainSource, "copyRuntimeRecognitionInput").contains("frameRecognition.copyFrameRecognitionInput("))
        assertTrue(MainActivityRefactorSource.property(mainSource, "frameRecognition").contains("by frameRecognitionHolder"))
        assertTrue(MainActivityRefactorSource.property(mainSource, "frameRecognitionHolder").contains("ArFrameRecognitionCapture(frameStateLock,"))
        assertTrue(MainActivityRefactorSource.member(mainSource, "scheduleDetectionIfDue").contains("primaryArSubmissionBridge.schedule("))
        val acquisition = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRecognitionCapture.kt").readText()
        val copy = compact(MainActivityRefactorSource.member(acquisition, "copyFrameRecognitionInput"))
        assertInOrder(copy, "val quarterTurns = UprightCameraImage.quarterTurns(basis) ?: run {",
            "return null", "return FrameRecognitionInput(ownedFrame, cameraTimestampNs, gray, qualityObservation, quarterTurns,")
        assertEquals(1, occurrences(copy, "UprightCameraImage.quarterTurns(basis)"))
        // Native images end synchronously at the copy callback; neither async worker owns an Image.
        val capture = compact(section(acquisition, "internal fun <T> acquireFrameRecognitionCapture(", "fun copyFrameRecognitionInput("))
        assertTrue(capture.contains("provider.acquireUntrackedRecognitionCameraImageOrNull(frame)?.use(copyInput)"))
        assertTrue(capture.contains("packet?.image?.use(copyInput)"))
        val submission = compact(MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/AndroidPrimaryArSubmissionBridge.kt").readText(), "schedule"))
        assertInOrder(submission, "val input = recognitionInput ?: return", "scheduling().admit(",
            "var submittedToWorker = false", "try {", "val imageQuarterTurns = input.imageQuarterTurns",
            "val frameEvidence = DetectionFrameEvidence(", "imageQuarterTurns = imageQuarterTurns,",
            "submittedToWorker = true", "targetWorker.submit(queuedWork)")
        assertEquals(1, occurrences(submission, "val frameEvidence = DetectionFrameEvidence("))
        assertTrue(submission.contains("finally { if (!submittedToWorker) {"))
        assertTrue(submission.contains("authority.finishUnsubmitted(pacingTicket)"))
        val workerSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryArDetectorWorker.kt").readText()
        val submit = compact(MainActivityRefactorSource.member(workerSource, "submit"))
        assertInOrder(submit, "var submitted = false", "detectorOwner.nativeLane.execute { run(work) }", "submitted = true",
            "finally { if (!submitted) cancelUnsubmitted(work.ticket, work.visualSourceLease) }")
        val cancel = compact(MainActivityRefactorSource.member(workerSource, "cancelUnsubmitted"))
        assertInOrder(cancel, "releaseSource(sourceLease)", "completion.finish(ticket, false, null, false)")
        assertTrue(MainActivityRefactorSource.property(mainSource, "primaryArSubmissionBridge").contains("finishDetectionWork(ticket, detectorInvoked = false)"))
    }

    @Test
    fun invalidAuxiliaryBasisReturnsBeforeEvidenceAndWorkerWhileKeepingImageAndInflightCleanup() {
        assertTrue(MainActivityRefactorSource.member(mainSource, "scheduleUnknownDetectionIfDue").contains("unknownFrameCapture.scheduleUnknownDetectionIfDue("))
        assertTrue(MainActivityRefactorSource.property(mainSource, "unknownFrameCapture").contains("UnknownFrameCaptureBridge(runtimeMetricStateLock"))
        val acquisition = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRecognitionCapture.kt").readText()
        val copy = compact(MainActivityRefactorSource.member(acquisition, "copyFrameRecognitionInput"))
        assertInOrder(copy, "val quarterTurns = UprightCameraImage.quarterTurns(basis) ?: run {", "return null",
            "UnknownMaterialPointLumaCapture.copyFromLuma(", "imageQuarterTurns = quarterTurns,")
        val capture = compact(section(acquisition, "internal fun <T> acquireFrameRecognitionCapture(", "fun copyFrameRecognitionInput("))
        assertTrue(capture.contains("packet?.image?.use(copyInput)"))
        val bridge = compact(MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/capture/UnknownFrameCaptureBridge.kt").readText(), "scheduleUnknownDetectionIfDue"))
        assertInOrder(bridge, "val input = recognitionInput ?: return", "val luma = input.unknownLuma ?: return",
            "unknown().scheduleUnknownDetectionIfDue(", "UnknownCaptureEvidence(", "input.imageQuarterTurns, processingLease,")
        assertEquals(1, occurrences(bridge, "UnknownCaptureEvidence("))
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/unknown/UnknownCaptureCoordinator.kt").readText()
        val schedule = compact(MainActivityRefactorSource.member(owner, "scheduleUnknownDetectionIfDue"))
        assertInOrder(schedule, "val input = recognitionInput ?: return", "var queued = false", "try {",
            "unknownFrameCopyInFlight.compareAndSet(false, true)", "evidenceSupplier()", "context.frameQueue.enqueue(",
            "schedule = { detectorExecutor.execute(it) }", "discard = { releaseUnknownFrameCopyClaim(it.submission?.claim) }")
        assertEquals(1, occurrences(schedule, "detectorExecutor.execute(it)"))
        assertTrue(schedule.contains("finally { if (!queued) releaseUnknownFrameCopyClaim(claim) }"))
        assertTrue(compact(MainActivityRefactorSource.member(owner, "processQueuedUnknownLumaFrame"))
            .contains("finally { releaseUnknownFrameCopyClaim(frame.submission?.claim) }"))
        val release = compact(MainActivityRefactorSource.member(owner, "releaseUnknownFrameCopyClaim"))
        assertInOrder(release, "unknownFrameCopyOwner === claim", "unknownFrameCopyOwner = null", "unknownFrameCopyInFlight.set(false)")
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
            "background.draw(frame, texture)",
            "if (!busy.compareAndSet(false, true)) return",
            "val pacingTicket = primaryInferencePacing.tryStart(SystemClock.elapsedRealtime()) ?: run { busy.set(false) return }",
            "var transferred = false",
            "var image: android.media.Image? = null",
            "try {",
            "val capture = captureCameraTestFrame(source, frame, profileFullDepth = true) ?: return",
            "image = capture.image",
            "val ownedImage = image",
            "val turns = UprightCameraImage.quarterTurns(view) ?: return",
            "val geometryId =",
            "worker.execute {",
            "var detectorInvoked = false",
            "FrozenUnknownDepthCapture.freeze(",
            "imageQuarterTurns = turns)",
            "detectorInvoked = true",
            "model.detectOriented(ownedImage, frameId / 1_000_000L, turns)",
            "inferenceDurationMs = result.timing.modelInferenceMs",
            "try { ownedImage.close() } finally {",
            "if (detectorInvoked && foreground && epoch == generation && model === detector)",
            "primaryInferencePacing.complete(pacingTicket, SystemClock.elapsedRealtime(), inferenceDurationMs)",
            "else primaryInferencePacing.cancel(pacingTicket)",
            "finally { busy.set(false)",
            "transferred = true",
            "try { releaseUntransferredCameraTestFrame(source, image, busy, transferred) } finally {",
            "if (!transferred) { try { primaryInferencePacing.cancel(pacingTicket) } finally { busy.set(false) } }",
        )
        assertEquals(1, occurrences(source, "UprightCameraImage.quarterTurns(view)"))
        assertEquals(1, occurrences(source, "val geometryId ="))
        assertEquals(1, occurrences(source, "worker.execute {"))
        assertEquals(1, occurrences(source, "FrozenUnknownDepthCapture.freeze("))
        assertEquals(1, occurrences(source, "model.detectOriented("))
        val acquire = compact(ReportStaticSourceInspector.blockAfter(debugSource, "internal fun captureCameraTestFrame("))
        assertInOrder(acquire, "source.acquireRecognitionCameraImageOrNull(frame) ?: return null",
            "source.acquireUntrackedRecognitionCameraImageOrNull(frame) ?: return null", "requireNotNull(recognition).image")
        assertTrue(acquire.contains("catch (error: Throwable) { source.invalidateRecognitionCameraImageTimestamps() image.close() throw error }"))
        val release = compact(ReportStaticSourceInspector.blockAfter(debugSource, "internal fun releaseUntransferredCameraTestFrame("))
        assertInOrder(release, "if (!transferred) {", "source.invalidateRecognitionCameraImageTimestamps()", "image?.close()", "busy.set(false)")
    }

    private fun section(source: String, start: String, end: String): String {
        assertEquals("Expected one exact owner anchor", 1, occurrences(source, start))
        val startIndex = source.indexOf(start)
        assertTrue("Missing Main source anchor: $start", startIndex >= 0)
        val endIndex = source.indexOf(end, startIndex + start.length)
        assertTrue("Missing Main source end: $end", endIndex > startIndex)
        return source.substring(startIndex, endIndex)
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
