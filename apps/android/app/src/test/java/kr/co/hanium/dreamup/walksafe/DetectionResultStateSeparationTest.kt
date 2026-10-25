package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionStatus
import kr.co.hanium.dreamup.walksafe.inference.DetectorAssetIntegrityFailure
import kr.co.hanium.dreamup.walksafe.inference.DetectorLoadFailureCode
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.navigation.AndroidDetectionSnapshotFrameIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S10-1 (originals/10.txt:30-38): a normal empty scene, a model load failure and no received camera
 * frame reach the walk as three different states, and "nothing detected" is never "no result".
 */
class DetectionResultStateSeparationTest {
    @Test
    fun emptySceneLoadFailureAndNoFrameAreThreeDifferentStates() {
        // 1. Normal empty scene: a completed inference with no boxes, fresh by its capture age.
        val emptyScene = AndroidDetectionResult.empty()
        assertEquals(AndroidDetectionStatus.SUCCESS, emptyScene.status)
        val published = DetectionSnapshot(
            detections = emptyScene.detections,
            identity = AndroidDetectionSnapshotFrameIdentity(captureFrameId = 7L, frameTimestampMs = 1_000L),
            capturedAtMs = 1_000L,
            startedAtMs = 1_000L,
            completedAtMs = 1_040L,
            imageWidth = 640,
            imageHeight = 480,
            detectDurationMs = 40L,
            detectorTiming = emptyScene.timing,
            partial = false,
            frameEvidence = null,
        )
        assertNull(published.staleReason(1_100L, 1_050L, MAX_AGE_MS, MAX_DELTA_MS))
        assertTrue(published.freshDetections(1_100L, 1_050L, MAX_AGE_MS, MAX_DELTA_MS).isEmpty())

        // 2. Model load failure: the load result carries its cause and the stand-in detector never completes.
        val load = TfliteAndroidFrameDetector.loadModel<Any> {
            throw DetectorAssetIntegrityFailure(DetectorLoadFailureCode.HASH_MISMATCH, "unified model asset hash_mismatch")
        }
        assertNull(load.value)
        assertEquals(DetectorLoadFailureCode.HASH_MISMATCH, load.failureCode)
        assertEquals(
            "detector_assets_unavailable:HASH_MISMATCH",
            TfliteAndroidFrameDetector.unavailableReason(legacyLoaded = false, failureCode = load.failureCode),
        )
        assertEquals(AndroidDetectionStatus.UNAVAILABLE, AndroidDetectionResult.unavailable().status)

        // 3. No camera frame (or only unavailable/failed calls): no completed result exists.
        val noFrame = DetectionSnapshot.empty()
        assertEquals(
            DetectionSnapshot.DETECTION_NOT_COMPLETED_STALE_REASON,
            noFrame.staleReason(1_100L, 1_050L, MAX_AGE_MS, MAX_DELTA_MS),
        )
        assertTrue(noFrame.debugStatusText(1_100L, 1_050L, MAX_AGE_MS, MAX_DELTA_MS).contains("아직 완료된 TFLite 결과 없음"))
    }

    @Test
    fun anOldCompletedResultIsStaleForADifferentReasonThanNoResult() {
        val old = DetectionSnapshot.empty().copy(
            identity = AndroidDetectionSnapshotFrameIdentity(captureFrameId = 1L, frameTimestampMs = 1_000L),
            capturedAtMs = 1_000L,
            startedAtMs = 1_000L,
            completedAtMs = 1_050L,
        )
        val reason = old.staleReason(3_000L, 1_000L, MAX_AGE_MS, MAX_DELTA_MS)
        assertEquals("source_age>${MAX_AGE_MS}ms", reason)
    }

    private companion object {
        const val MAX_AGE_MS = 800L
        const val MAX_DELTA_MS = 800L
    }
}
