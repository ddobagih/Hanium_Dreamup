package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityRawCollectionRuntimeStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun captureIsLowFrequencyMetadataOnlyAndLeavesTheFrameLockBeforeStorage() {
        val capture = MainActivityRefactorSource.function(source, "fun recordRawCollectionDetectionMetadata(")
        val worker = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryArDetectorWorker.kt").readText()
        val publishBoundary = worker.substringAfter("val published = publisher.publish(").substringBefore("if (work.captureRequested) mainDispatcher")
        val invocation = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorInvocationWorker.kt").readText()
        assertTrue(source.contains("File(noBackupRoot.canonicalFile, \"raw_collections\")"))
        assertTrue(source.contains("Thread(runnable, \"walksafe-raw-collection\")"))
        assertTrue(capture.contains("RAW_COLLECTION_CAPTURE_INTERVAL_MS"))
        assertTrue(capture.contains("observedAtEpochMs - elapsedWindowMs"))
        assertFalse(capture.contains("epochWindowMs"))
        assertTrue(capture.contains("rawRuntimeWindow.snapshot("))
        assertTrue(capture.contains("samples.detection"))
        assertTrue(capture.contains("samples.performance"))
        assertTrue(capture.contains("rawDetectionDueButInFlightDropCount.getAndSet(0)"))
        assertTrue(capture.contains("busyDroppedSincePrevious = dueButInFlightDropCount"))
        // 16-17 #4: the frame duration is the completion interval, not the inference average.
        assertFalse(capture.contains("averageFrameDurationMs = averageProcessingFrameMs"))
        assertTrue(capture.contains("thermalThrottled ="))
        assertTrue(capture.contains("captureMetadataBatch("))
        listOf(
            "RawChunkType.VIDEO",
            "RawChunkType.AUDIO",
            "RawChunkType.EXACT_LOCATION",
            "RawChunkType.SENSOR",
            "RawChunkType.ROUTE",
            "RawChunkType.REPORT",
            "reportImageJpeg",
            "captureJpeg",
            "latestTrustedLocation",
            "routeNavigator",
            "earthOrientationTracker",
        ).forEach { forbiddenReference ->
            assertFalse(capture.contains(forbiddenReference))
        }
        assertFalse(capture.contains("frameStateLock"))
        assertTrue(invocation.contains("detectorOwner.tryStartWork(nowMs)"))
        assertTrue(source.contains("primaryDetectorOwner, primaryDetectorInvocationWorker"))
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorExecutionOwner.kt").readText()
        assertTrue(MainActivityRefactorSource.member(owner, "tryStartWork").contains("onDueButInFlightDrop()"))
        assertTrue(source.contains("{ rawCollectionTransferController.recordDueInferenceDrop() }"))
        val rawOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/RawCollectionTransferController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(rawOwner, "recordDueInferenceDrop").contains("rawDetectionDueButInFlightDropCount.incrementAndGet()"))
        assertTrue(publishBoundary.contains("recording.completed("))
        assertTrue(publishBoundary.indexOf("recording.completed(") < publishBoundary.indexOf("synchronized(frameStateLock)"))
        assertTrue(source.contains("override fun completed(event: PrimaryCompletedInference) { recordPrimaryCompletedInference(event) }"))
        assertTrue(MainActivityRefactorSource.member(source, "recordPrimaryCompletedInference").contains("recordRawCollectionDetectionMetadata("))
    }

    @Test
    fun uploadStartsOnlyAfterTheWalkOnWifiWhileChargingAndPausedStartsNone() {
        // Owner decision 3(b): the RAW upload never starts in ACTIVE or PAUSED.
        val postWalkUpload = MainActivityRefactorSource.function(source, "fun scheduleRawCollectionPostWalkUpload(")
        val window = MainActivityRefactorSource.function(source, "fun schedulePostWalkTransfers(")
        val transition = MainActivityRefactorSource.function(source, "fun handleRawCollectionTransition(")
        assertTrue(postWalkUpload.contains("!initial.walk.state.allowsPostWalkTransfer"))
        assertTrue(postWalkUpload.contains("!runtime.isForeground()"))
        assertTrue(postWalkUpload.contains("ActiveNetworkTransport.WIFI"))
        assertTrue(postWalkUpload.contains("IntegratedConsentNetworkTransport.WIFI"))
        assertTrue(postWalkUpload.contains("!initial.charging"))
        assertTrue(postWalkUpload.contains("initial.gatewayWalkLease != null"))
        assertTrue(postWalkUpload.contains("sealActiveSegment(initial)"))
        assertTrue(postWalkUpload.contains("startNextUpload"))
        assertTrue(window.contains("if (!walk.state.allowsPostWalkTransfer) return"))
        assertTrue(transition.contains("WalkSessionState.ENDED"))
        assertTrue(transition.contains("WalkSessionState.SAFE_STOP"))
        assertTrue(transition.contains("cancelActiveUpload()"))
        assertTrue(transition.contains("onWalkEnded(context)"))
        assertTrue(transition.contains("discardEndedPartials(context)"))
        assertFalse(transition.contains("WalkSessionEvent.RecheckRequested"))
        assertFalse(transition.contains("startNextUpload"))
        assertFalse(source.contains("scheduleRawCollectionPausedUpload"))
        // Every trigger opens the post-walk window through the same gate.
        assertTrue(MainActivityRefactorSource.function(source, "fun onReportQueueDrainNetworkChanged(")
            .contains("schedulePostWalkTransfers()"))
        assertTrue(MainActivityRefactorSource.function(source, "private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection(")
            .contains("schedulePostWalkTransfers()"))
        assertTrue(source.contains("onPostWalkChargingMaybeChanged()\n"))
        assertTrue(MainActivityRefactorSource.function(source, "fun currentRawCollectionRuntimeContextOrNull(")
            .contains("charging = walk.state.allowsPostWalkTransfer && runtime.isCharging()"))
        assertTrue(MainActivityRefactorSource.composition(source, "rawCollectionTransferController").contains("override fun isCharging() = chargingStateProbe.isCharging()"))
    }
}
