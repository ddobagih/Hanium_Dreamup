package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityRawCollectionRuntimeStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun captureIsLowFrequencyMetadataOnlyAndLeavesTheFrameLockBeforeStorage() {
        val capture = source.substringAfter(
            "private fun recordRawCollectionDetectionMetadata(",
        ).substringBefore("private fun revalidateRawCollectionRuntime()")
        val publishBoundary = source.substringAfter(
            "val published = this@MainActivity.publishDetectionSnapshot(",
        ).substringBefore("if (shouldCaptureFrame)")

        assertTrue(source.contains("File(noBackupRoot, \"raw_collections\")"))
        assertTrue(source.contains("Thread(runnable, \"walksafe-raw-collection\")"))
        assertTrue(capture.contains("RAW_COLLECTION_CAPTURE_INTERVAL_MS"))
        assertTrue(capture.contains("RawDetectionMetadataSample("))
        assertFalse(capture.contains("frameStateLock"))
        assertTrue(publishBoundary.contains("recordRawCollectionDetectionMetadata("))
        assertTrue(
            publishBoundary.indexOf("recordRawCollectionDetectionMetadata(") <
                publishBoundary.indexOf("synchronized(frameStateLock)"),
        )
    }

    @Test
    fun uploadAndDeletionRemainBoundToExplicitWalkLifecycleStates() {
        val pausedUpload = source.substringAfter(
            "private fun scheduleRawCollectionPausedUpload()",
        ).substringBefore("private fun handleRawCollectionTransition(")
        val transition = source.substringAfter(
            "private fun handleRawCollectionTransition(",
        ).substringBefore("private fun handleWalkSessionForegroundReturn()")

        assertTrue(pausedUpload.contains("WalkSessionState.PAUSED"))
        assertTrue(pausedUpload.contains("WalkSessionRecoveryStage.RECHECK_REQUIRED"))
        assertTrue(pausedUpload.contains("ActiveNetworkTransport.WIFI"))
        assertTrue(pausedUpload.contains("IntegratedConsentNetworkTransport.WIFI"))
        assertTrue(pausedUpload.contains("initial.gatewayWalkLease == null"))
        assertTrue(pausedUpload.contains("sealActiveSegment(initial)"))
        assertTrue(pausedUpload.contains("startNextUpload"))
        assertTrue(transition.contains("WalkSessionState.ENDED"))
        assertTrue(transition.contains("cancelActiveUpload()"))
        assertTrue(transition.contains("discardEndedPartials(context)"))
        assertTrue(transition.contains("event == WalkSessionEvent.RecheckRequested"))
        assertFalse(transition.substringBefore("WalkSessionState.PAUSED").contains("startNextUpload"))
    }
}
