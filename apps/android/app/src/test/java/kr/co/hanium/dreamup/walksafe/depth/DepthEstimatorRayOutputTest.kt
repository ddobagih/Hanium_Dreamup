package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The existing metric output, reached through the actual runtime pipeline, not new distance math. */
class DepthEstimatorRayOutputTest {
    @Test fun runtimeRawAndFullKeepComputedRayWhileTheirRiskChoiceAndTrackerSeriesStaySeparate() {
        for (full in listOf(false,true)) {
            val (output,tracker) = runtime(full=full)
            val zRisk = requireNotNull(output.depthP20M)
            assertEquals(if(full) DepthSource.ARCORE_FULL_DEPTH else DepthSource.ARCORE_RAW_DEPTH,output.source)
            assertEquals(RiskDistanceKind.HORIZONTAL_RANGE,output.riskDistanceKind)
            val horizontal = (zRisk*sqrt(cos(TILT)*cos(TILT)+LATERAL*LATERAL)).toFloat()
            assertEquals(horizontal,requireNotNull(output.horizontalDistanceM),EPS)
            assertEquals(horizontal,requireNotNull(output.riskDistanceM),EPS)
            tracker.activeTracks().single().distanceHistory.forEach { assertEquals(zRisk,it.distanceM,EPS) }
            assertNotNull("same-capture computed centre ray was discarded by the runtime metric output",output.rayDistanceM)
            assertEquals(zRisk*RAY_FACTOR,requireNotNull(output.rayDistanceM),EPS)
            assertTrue(output.rayDistanceM!! > output.riskDistanceM!!)
            assertTrue(output.depthMedianM!! > zRisk)
        }
    }

    @Test fun outputRayUsesTheClassRiskStatisticRatherThanRelabellingTheMedianDepth() {
        for (className in listOf("person","linear_tactile_paving")) {
            val (output,_) = runtime(className=className)
            val zRisk = if(className=="person") minOf(output.depthMedianM!!,output.depthP20M!!+.20f) else output.depthP20M!!
            assertEquals(zRisk*RAY_FACTOR,requireNotNull(output.rayDistanceM),EPS)
            assertEquals(output.depthMedianM!!,requireNotNull(output.zDistanceM),0f)
            assertEquals(CollisionAssessment.UNKNOWN,output.collisionAssessment)
        }
    }

    @Test fun missingForeignUnreliableOrReprojectedPoseDoesNotCreateARay() {
        for (full in listOf(false,true)) for (pose in listOf<(Long)->CameraPoseEvidence?>(
            { null },{ at -> pose(at-1) },{ at -> pose(at).copy(imageProjection=null) })) {
            val (output,_) = runtime(full=full,poseAt=pose)
            assertTrue(output.source.metric); assertNull(output.rayDistanceM)
        }
        val (unreliable,_) = runtime(motion=MotionContext(trackingQuality=.5f))
        assertTrue(unreliable.source.metric); assertNull(unreliable.rayDistanceM)
        val (reprojected,_) = runtime(reprojected=true)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH,reprojected.source)
        assertTrue(reprojected.depthReprojected==true); assertNull(reprojected.rayDistanceM)
    }

    @Test fun explicitInputRayRetainsItsExistingPriorityOverTheComputedFallback() {
        val output = direct(inputRay=1.2345f)
        assertEquals(1.2345f,requireNotNull(output.rayDistanceM),0f)
        assertEquals(RiskDistanceKind.HORIZONTAL_RANGE,output.riskDistanceKind)
        assertNotEquals(output.rayDistanceM!!,output.riskDistanceM!!,EPS)
    }

    @Test fun supportedMaskWithPoseInterpretationDisabledStillDoesNotInventACentreRay() {
        val output = direct(maskStats=true)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH,output.source)
        assertEquals(RiskDistanceKind.CAMERA_Z,output.riskDistanceKind)
        assertNull(output.rayDistanceM)
    }

    private fun runtime(full: Boolean=false,className: String="car",poseAt: (Long)->CameraPoseEvidence?={pose(it)},
        motion: MotionContext=MotionContext(),reprojected: Boolean=false): Pair<TrackedObjectDepth,ObjectTracker> {
        val tracker = ObjectTracker(); val pipeline = ObjectDepthRuntimePipeline(tracker=tracker)
        var latest: TrackedObjectDepth? = null
        for(i in 0 until 4) {
            val at = 1_000L+i*500L; val frameNs=at*1_000_000; val cpuNs=frameNs-100_000_000
            val depthNs=cpuNs-if(reprojected) 10_000_000 else 0
            val snapshot=DepthFrameSnapshot(frameNs,if(full) null else depth(),if(full) null else confidence(),
                if(full) depth() else null,rawDepthTimestampNs=if(full) null else depthNs,
                rawConfidenceTimestampNs=if(full) null else depthNs,fullDepthTimestampNs=if(full) cpuNs else null,
                cameraImageTimestampNs=cpuNs,cameraPoseEvidence=poseAt(at))
            latest=pipeline.process(snapshot,frameNs,at,listOf(DetectionCandidate(className,.9f,BBOX)),
                detectionSequenceId=frameNs,mapper=MAPPER,mapperFrameId=frameNs,motionContext=motion,
                capturedAtElapsedRealtimeMs=50_000L+i*500L).single()
        }
        return requireNotNull(latest) to tracker
    }

    private fun direct(inputRay: Float?=null,maskStats: Boolean=false): TrackedObjectDepth {
        val tracker=ObjectTracker(); val estimator=ObjectDepthEstimator(tracker=tracker)
        val geometry=MaskPolygonExtractor().extract(DetectionCandidate("car",.9f,BBOX))
        var latest: TrackedObjectDepth?=null
        for(i in 0 until 4) {
            val at=1_000L+i*500L; val track=tracker.update(listOf(geometry),at).single()
            val input=ObjectDepthInput(i.toLong(),at,geometry,track,MAPPER,rawDepth=depth(),rawConfidence=confidence(),
                motionContext=MotionContext(cameraPoseEvidence=pose(at)),rayDistanceM=inputRay,
                rawDepthMatchesCameraFrame=true,rawDepthTimestampNs=at*1_000_000,
                requireIndependentDepthObservation=maskStats)
            latest=if(maskStats) estimator.estimateSupportedMask(input,DepthStats(100,1f,3.62f,3.60f,3.61f,3.63f,
                .02f,.01f,1f,0f,spatialCoverage=1f)) else estimator.estimate(input)
        }
        return requireNotNull(latest)
    }

    private fun depth()=DepthImage16(40,40,IntArray(1600){3600+((it*7)%41)})
    private fun confidence()=ConfidenceImage8(40,40,ByteArray(1600){255.toByte()})
    private fun pose(at: Long): CameraPoseEvidence {
        val s=sin(TILT).toFloat(); val c=cos(TILT).toFloat()
        return CameraPoseEvidence(1,at,0f,1.3f,0f,0f,-s,-c,
            CameraImageProjection(10,10,7f,7f,5f,5f,1f,0f,0f,0f,c,-s),Vec3(0f,1f,0f))
    }

    private companion object {
        const val EPS=1e-4f
        val BBOX=RectNorm(.6f,.3f,.2f,.4f)
        val TILT=Math.toRadians(20.0)
        val LATERAL=(BBOX.center.x*10f-5f)/7f
        val RAY_FACTOR=sqrt(1f+LATERAL*LATERAL)
        val MAPPER=LetterboxCoordinateMapper(ModelInputTransform(10,10,10,10,1f,0f,0f),ImageSize(40,40))
    }
}
