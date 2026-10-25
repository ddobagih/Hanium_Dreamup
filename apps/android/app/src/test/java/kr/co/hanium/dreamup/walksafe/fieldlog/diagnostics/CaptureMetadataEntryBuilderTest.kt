package kr.co.hanium.dreamup.walksafe.fieldlog.diagnostics

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.runtime.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameOverlayDiagnostics
import kr.co.hanium.dreamup.walksafe.navigation.AndroidDetectionSnapshotFrameIdentity
import org.junit.Assert.*
import org.junit.Test

class CaptureMetadataEntryBuilderTest {
    @Test fun constructionIsInertAndRecordKeepsOriginalDetectorClocksAndOverlayFields() {
        val f=Fixture();assertTrue(f.reads.isEmpty())
        val detection=DetectionCandidate("tactile",0.8f,RectNorm(0.1f,0.2f,0.3f,0.4f))
        val entry=f.build(listOf(detection))
        assertEquals(1_000L,entry.frameTimestampMs);assertEquals(1_000L,entry.detectorFrameTimestampMs)
        assertEquals(300L,entry.detectorSourceAgeMs);assertEquals(270L,entry.detectorCompletedAgeMs)
        assertEquals(0L,entry.detectorFrameDeltaMs);assertEquals("tactile",entry.topDetectionClassName)
        assertEquals(0.1f,entry.topDetectionBbox!!.x,0f)
        assertEquals("tracked",entry.overlaySelection);assertEquals(7,entry.overlayDetectionCount)
        assertEquals(4,entry.overlayHeldTactileBoxCount);assertEquals("original_stale_reason",entry.overlayStaleReason)
        assertEquals(listOf("loaded","fallback","reason","width","height","rotation"),f.reads)
    }
    @Test fun staleTelemetryCannotInventMetricDepthOrRawImageAndDimensionsRemainQualified() {
        val f=Fixture();val entry=f.build(emptyList(),now=1_800L)
        assertNotNull(entry.staleReason);assertNull(entry.topDetectionClassName)
        assertNull(entry.bestDepthMedianM);assertNull(entry.bestDepthBbox)
        assertNull(entry.depthWidth);assertNull(entry.depthHeight);assertNull(entry.previewWidth)
        assertEquals(400,entry.previewHeight);assertEquals(90,entry.displayRotation)
        assertFalse(entry.detectionsUsedForDepth)
        assertEquals("arcore_image_to_view",entry.overlayTransformPath)
        assertEquals("unavailable",entry.depthTransformPath)
        assertEquals("original_fallback",entry.fallbackReason)
    }
    @Test fun emptyDepthInputDoesNotEraseTopDetectionButKeepsItSuppressed() {
        val f=Fixture();val detections=listOf(DetectionCandidate("low",0.2f,RectNorm(0f,0f,1f,1f)),
            DetectionCandidate("high",0.9f,RectNorm(0f,0f,1f,1f)))
        val entry=f.build(detections)
        assertEquals("high",entry.topDetectionClassName);assertEquals(2,entry.detectionCount)
        assertFalse(entry.detectionsUsedForDepth);assertFalse(entry.detectorPartial)
    }
    private class Fixture:CaptureMetadataReadings {
        val reads=mutableListOf<String>()
        val builder=CaptureMetadataEntryBuilder(this,800L,800L)
        override val detectorLoadedModelKey get()="model".also {reads+="loaded"}
        override val detectorModelFallbackUsed get()=false.also {reads+="fallback"}
        override val detectorLoadReason get()="loaded".also {reads+="reason"}
        override val surfaceWidth get()=0.also {reads+="width"}
        override val surfaceHeight get()=400.also {reads+="height"}
        override fun displayRotation()=90.also {reads+="rotation"}
        fun build(detections:List<DetectionCandidate>,now:Long=1_200L)=builder.buildCaptureLogEntry(
            1_000L,now,DetectionSnapshot(detections,AndroidDetectionSnapshotFrameIdentity(4L,1_000L),
                900L,905L,930L,640,480,25L,null,partial=false,frameEvidence=null),
            FrameOverlayDiagnostics("tracked",7,5,6,5,4,3,true,true,70L,80L,"original_stale_reason"),
            emptyList(),DepthFrameSnapshot(1_000_000_000L,null,null,null),null,"unavailable","original_fallback")
    }
}
