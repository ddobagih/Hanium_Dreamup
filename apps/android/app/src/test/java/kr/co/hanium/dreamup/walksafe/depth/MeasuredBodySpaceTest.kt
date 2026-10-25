package android.media

import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometry/owner tests on the actual producer; constants below are explicit fixture inputs. */
class MeasuredBodySpaceTest {
    @Test fun actualCellBackprojectionAndMeasuredExpectedGeometryHavePointOnlyIntervals() {
        val f = Fixture()
        val capture = f.capture()
        val result = requireNotNull(f.producer.pointClearance(capture,f.key,Point2(.625f,.625f),RAW,ERROR,NOW))
        assertEquals(2,result.depthX); assertEquals(2,result.depthY)
        assertPoint(.25,-.25,-2.0,result.cameraPoint)
        assertPoint(.25,.75,-2.0,result.bodyPoint)
        assertEquals(1.6,result.bodyPointClearance.nominalM,EPS)
        assertEquals(1.0,result.expectedGroundRegionPointClearance.nominalM,EPS)
        assertEquals(.75,result.heightAboveExpectedGroundM,EPS)
        val common = .04 + sqrt(1.0+.125*.125+.125*.125)*.001 + .01
        assertEquals(1.6-common-.02,result.bodyPointClearance.lowerM,EPS)
        assertEquals(1.6+common+.02,result.bodyPointClearance.upperM,EPS)
        assertEquals(common+.03,result.expectedGroundHeightErrorM,EPS)
        assertEquals(1-common-.03,result.expectedGroundRegionPointClearance.lowerM,EPS)
        assertEquals(255,result.depthPixelConfidence)
        assertEquals(15,result.semanticPixel?.label); assertEquals(115,result.semanticPixel?.confidence)
        assertEquals(LABEL,result.semanticPixel?.labelTimestampNs)
        assertEquals(SEMANTIC_CONFIDENCE,result.semanticPixel?.confidenceTimestampNs)
        assertEquals(CPU,result.depthTimestampNs)
        assertEquals(8,capture.occupiedCornersInAnchor.size)
        assertEquals(4,capture.expectedGroundCornersInAnchor.size)
        assertPoint(-.4,-1.0,-.4,capture.occupiedCornersInAnchor.first())
        assertPoint(-.4,-1.0,0.0,capture.expectedGroundCornersInAnchor.first())
    }

    @Test fun equalZWithDifferentMeasuredHeightTiltAndLateralTranslationChangesGeometry() {
        fun result(input: MeasuredBodySpaceInput): MeasuredBodyPointClearance {
            val f = Fixture(input)
            return requireNotNull(f.producer.pointClearance(f.capture(),f.key,Point2(.625f,.625f),RAW,ERROR,NOW))
        }
        val normal = result(input())
        val high = result(input().copy(cameraToBodyTranslationM=doubleArrayOf(0.0,3.0,0.0)))
        val lateral = result(input().copy(cameraToBodyTranslationM=doubleArrayOf(2.0,1.0,0.0)))
        val tilted = result(input().copy(cameraToBodyQuaternion=doubleArrayOf(sin(Math.PI/4),0.0,0.0,cos(Math.PI/4))))
        listOf(normal,high,lateral,tilted).forEach { assertEquals(-2.0,it.cameraPoint.z,EPS) }
        assertPoint(.25,2.75,-2.0,high.bodyPoint)
        assertPoint(2.25,.75,-2.0,lateral.bodyPoint)
        assertPoint(.25,3.0,-.25,tilted.bodyPoint)
        assertTrue(high.bodyPointClearance.nominalM > normal.bodyPointClearance.nominalM)
        assertTrue(lateral.expectedGroundRegionPointClearance.nominalM > normal.expectedGroundRegionPointClearance.nominalM)
        assertEquals(0.0,tilted.expectedGroundRegionPointClearance.nominalM,EPS)
        assertNotEquals(normal.bodyPointClearance.nominalM,tilted.bodyPointClearance.nominalM,EPS)
    }

    @Test fun rotationUncertaintyAddsDeclaredEuclideanDisplacementWithoutAxisDefault() {
        val f = Fixture(input().copy(rotationErrorRad=.2,occupiedBoundsErrorM=.12,groundModelErrorM=.23))
        val result = requireNotNull(f.producer.pointClearance(f.capture(),f.key,Point2(.625f,.625f),RAW,ERROR,NOW))
        val rayLength = sqrt(1.0+.125*.125+.125*.125)
        val expected = .04 + rayLength*.001 + .01 + 2*(rayLength*2)*sin(.2/2) + .12
        assertEquals(expected,result.bodyPointClearance.upperM-result.bodyPointClearance.nominalM,EPS)
    }

    @Test fun nonSquareRotatedMapperUsesSelectedCellCentreAndSnapshotIntrinsics() {
        val f = Fixture(width=8,height=4,depthWidth=4,depthHeight=2)
        f.mapper = mapper(listOf(Point2(1f,0f),Point2(1f,1f),Point2(0f,0f),Point2(0f,1f)),Point2(.5f,.5f))
        val values = IntArray(8) { (it+1)*1000 }
        f.snapshot = f.snapshot.copy(rawDepth=DepthImage16(4,2,values))
        val result = requireNotNull(f.producer.pointClearance(f.capture(),f.key,Point2(.25f,.5f),RAW,ERROR,NOW))
        assertEquals(2,result.depthX); assertEquals(0,result.depthY)
        assertPoint(-.75,.375,-3.0,result.cameraPoint)
        assertPoint(-.75,1.375,-3.0,result.bodyPoint)
    }

    @Test fun cropUsesInverseCalibrationAndRejectsUnobservedCellCentre() {
        val f = Fixture()
        f.mapper = mapper(listOf(Point2(-.5f,0f),Point2(1.5f,0f),Point2(-.5f,1f),Point2(1.5f,1f)),Point2(.5f,.5f))
        val capture = f.capture()
        assertNull(f.producer.pointClearance(capture,f.key,Point2(.1f,.5f),RAW,ERROR,NOW))
        val result = requireNotNull(f.producer.pointClearance(capture,f.key,Point2(.375f,.625f),RAW,ERROR,NOW))
        assertPoint(-.125,-.25,-2.0,result.cameraPoint)
        f.mapper = mapper(listOf(Point2(0f,0f),Point2(.1f,0f),Point2(0f,1f),Point2(.1f,1f)),Point2(.05f,.5f))
        assertNull(f.producer.pointClearance(f.capture(),f.key,Point2(.9f,.5f),RAW,ERROR,NOW))
        assertNull(FrozenImageToDepthTransform.create(FRAME,List(4) { Point2(.5f,.5f) },Point2(.5f,.5f)))
    }

    @Test fun missingInvalidMeasurementsAndUnspecifiedErrorsCannotRegister() {
        val p = MeasuredBodySpace(); p.bindOwner(OWNER)
        assertNull(p.register(null))
        val base = input()
        val invalid = listOf(base.copy(provenance=null),base.copy(provenance=base.provenance?.copy(method="")),
            base.copy(provenance=base.provenance?.copy(clock=null)),base.copy(provenance=base.provenance?.copy(axes=null)),
            base.copy(provenance=base.provenance?.copy(measuredAtNs=null)),base.copy(provenance=base.provenance?.copy(errorBasis="")),
            base.copy(provenance=base.provenance?.copy(bodyOriginDescription="")),
            base.copy(cameraToBodyQuaternion=null),base.copy(cameraToBodyQuaternion=DoubleArray(4)),
            base.copy(cameraToBodyQuaternion=doubleArrayOf(Double.NaN,0.0,0.0,1.0)),
            base.copy(cameraToBodyTranslationM=doubleArrayOf(0.0,Double.POSITIVE_INFINITY,0.0)),
            base.copy(occupiedBodyMinM=doubleArrayOf(.4,0.0,-.4)),base.copy(expectedFrontGroundM=doubleArrayOf(-.4,.4,-1.0,1.0,0.0)),
            base.copy(translationErrorM=null),base.copy(rotationErrorRad=null),base.copy(occupiedBoundsErrorM=null),
            base.copy(groundModelErrorM=null),base.copy(rotationErrorRad=4.0),base.copy(groundModelErrorM=Double.NaN),
            base.copy(translationErrorM=-1.0))
        invalid.forEach { assertNull(p.register(it)) }
        assertNotNull(p.register(base.copy(translationErrorM=0.0,rotationErrorRad=0.0,occupiedBoundsErrorM=0.0,groundModelErrorM=0.0)))
        p.bindOwner(null); assertNull(p.register(base))
    }

    @Test fun measurementClocksAreNamedMetadataNeverComparedWithCaptureElapsed() {
        val wall = Fixture(input().copy(provenance=input().provenance?.copy(measuredAtNs=Long.MAX_VALUE)))
        val capture = wall.capture()
        assertEquals(Long.MAX_VALUE,capture.registration.provenance.measuredAtNs)
        assertNotNull(wall.producer.pointClearance(capture,wall.key,Point2(.625f,.625f),RAW,ERROR,NOW))
        val elapsed = Fixture(input().copy(provenance=input().provenance?.copy(
            clock=BodyMeasurementClock.ELAPSED_REALTIME_NANOSECONDS,measuredAtNs=START-1)))
        assertNotNull(elapsed.capture())
        val future = Fixture(input().copy(provenance=input().provenance?.copy(
            clock=BodyMeasurementClock.ELAPSED_REALTIME_NANOSECONDS,measuredAtNs=START+1)))
        assertNull(future.producer.capture(future.model,future.key,future.snapshot,future.mapper,NOW))
    }

    @Test fun foreignOwnerEpochRemountReplacementAndRetirementRejectOldHandles() {
        val f = Fixture(); val capture = f.capture()
        val other = Fixture()
        assertNull(other.producer.capture(f.model,other.key,other.snapshot,other.mapper,NOW))
        for (key in listOf(f.key.copy(owner=OWNER.copy(walkEpoch=2)),f.key.copy(owner=OWNER.copy(mountIdentity="other")),
                f.key.copy(owner=OWNER.copy(mountGeneration=2)))) {
            assertNull(f.producer.pointClearance(capture,key,Point2(.625f,.625f),RAW,ERROR,NOW))
        }
        f.producer.bindOwner(OWNER.copy(mountGeneration=2))
        assertNull(f.producer.capture(f.model,f.key,f.snapshot,f.mapper,NOW))
        assertNull(f.producer.pointClearance(capture,f.key,Point2(.625f,.625f),RAW,ERROR,NOW))
        val fresh = requireNotNull(f.producer.register(input()))
        val newKey = f.key.copy(owner=OWNER.copy(mountGeneration=2),leaseIdentity="new")
        assertNotNull(f.producer.capture(fresh,newKey,f.snapshot,f.mapper,NOW))
        f.producer.retire()
        assertNull(f.producer.capture(fresh,newKey,f.snapshot,f.mapper,NOW))
    }

    @Test fun referenceReplacementRetiresTheRegistrationAndRequiresNewMeasuredRegistration() {
        val f = Fixture(); val old = f.capture()
        val key = f.key.copy(poseReferenceId=2)
        val snapshot = f.snapshot.copy(cameraPoseEvidence=f.snapshot.cameraPoseEvidence?.copy(referenceId=2),semanticFrame=null)
        assertNull(f.producer.capture(f.model,key,snapshot,f.mapper,NOW))
        assertNull(f.producer.capture(f.model,f.key,f.snapshot,f.mapper,NOW))
        assertNull(f.producer.pointClearance(old,f.key,Point2(.625f,.625f),RAW,ERROR,NOW))
        val newModel = requireNotNull(f.producer.register(input()))
        val current = requireNotNull(f.producer.capture(newModel,key,snapshot,f.mapper,NOW))
        assertNotNull(f.producer.pointClearance(current,key,Point2(.625f,.625f),RAW,ERROR,NOW))
    }

    @Test fun originalCaptureClockDeadlineAndLeaseCannotBeRenewedAtConsumption() {
        val f = Fixture(); val captured = f.capture()
        val changed = listOf(f.key.copy(frameTimestampNs=FRAME+1),f.key.copy(cameraImageTimestampNs=CPU+1),
            f.key.copy(poseReferenceId=2),f.key.copy(leaseIdentity="other"),f.key.copy(capturedAtElapsedNs=START+1),
            f.key.copy(validUntilElapsedNs=END+1),f.key.copy(geometryVersion=2))
        changed.forEach { assertNull(f.producer.pointClearance(captured,it,Point2(.625f,.625f),RAW,ERROR,NOW)) }
        assertNull(f.producer.pointClearance(captured,f.key,Point2(.625f,.625f),RAW,ERROR,START-1))
        assertNull(f.producer.pointClearance(captured,f.key,Point2(.625f,.625f),RAW,ERROR,END+1))
        assertNotNull(f.producer.pointClearance(captured,f.key,Point2(.625f,.625f),RAW,ERROR,END))
        val next = f.key.copy(leaseIdentity="replacement")
        assertNotNull(f.producer.capture(f.model,next,f.snapshot,f.mapper,NOW))
        assertNull(f.producer.pointClearance(captured,f.key,Point2(.625f,.625f),RAW,ERROR,NOW))
        assertNull(f.producer.capture(f.model,f.key,f.snapshot.copy(cameraImageTimestampNs=CPU+1),f.mapper,NOW))
        assertNull(f.producer.capture(f.model,f.key,f.snapshot.copy(cameraPoseEvidence=null),f.mapper,NOW))
        assertNull(f.producer.capture(f.model,f.key,f.snapshot,mapper(frameId=FRAME+1),NOW))
    }

    @Test fun missingMetricErrorDepthHolesOutsideImageAndForeignSemanticAreUnknown() {
        val f = Fixture()
        f.snapshot.rawDepth?.millimeters?.set(10,0)
        val capture = f.capture()
        assertNull(f.producer.pointClearance(capture,f.key,Point2(.625f,.625f),RAW,ERROR,NOW))
        assertNull(f.producer.pointClearance(capture,f.key,Point2(-.1f,.5f),RAW,ERROR,NOW))
        for (error in listOf(null,ERROR.copy(metricPositionErrorM=null),ERROR.copy(axialQuantizationErrorBoundM=null),
            ERROR.copy(basis=""),ERROR.copy(metricPositionErrorM=Double.NaN),ERROR.copy(axialQuantizationErrorBoundM=-1.0))) {
            assertNull(f.producer.pointClearance(capture,f.key,Point2(.125f,.125f),RAW,error,NOW))
        }
        assertNotNull(f.producer.pointClearance(capture,f.key,Point2(.125f,.125f),FULL,ERROR,NOW))
        val semantic = requireNotNull(f.snapshot.semanticFrame)
        f.snapshot = f.snapshot.copy(semanticFrame=SemanticFrameSnapshot(FRAME,CPU,2,semantic.labels,semantic.confidence))
        assertNull(f.producer.capture(f.model,f.key,f.snapshot,f.mapper,NOW))
    }

    @Test fun registrationAndCaptureOwnArraysAndCollectionsAgainstConsumerMutation() {
        val measured = input(); val f = Fixture(measured); val capture = f.capture()
        measured.cameraToBodyQuaternion?.fill(0.0); measured.cameraToBodyTranslationM?.fill(100.0)
        measured.occupiedBodyMinM?.fill(100.0); measured.occupiedBodyMaxM?.fill(-100.0); measured.expectedFrontGroundM?.fill(100.0)
        f.snapshot.rawDepth?.millimeters?.fill(0); f.snapshot.rawConfidence?.values?.fill(0); f.snapshot.fullDepth?.millimeters?.fill(0)
        val result = requireNotNull(f.producer.pointClearance(capture,f.key,Point2(.625f,.625f),RAW,ERROR,NOW))
        assertPoint(.25,.75,-2.0,result.bodyPoint); assertEquals(255,result.depthPixelConfidence)
        assertNotNull(f.producer.pointClearance(capture,f.key,Point2(.625f,.625f),FULL,ERROR,NOW))
        try { (capture.occupiedCornersInAnchor as MutableList).clear(); fail("stored corners must be immutable") }
        catch (_: UnsupportedOperationException) { }
        assertEquals(8,capture.occupiedCornersInAnchor.size)
    }

    @Test fun depthSourceQualificationKeepsOriginalReprojectedRawClockAndRejectsMissingPairsOrStaleFull() {
        val f = Fixture()
        val original = f.snapshot
        f.snapshot = original.copy(rawConfidenceTimestampNs=CPU-10_000_000)
        assertNull(f.producer.pointClearance(f.capture(),f.key,Point2(.625f,.625f),RAW,ERROR,NOW))
        f.snapshot = original.copy(rawConfidence=null)
        assertNull(f.producer.pointClearance(f.capture(),f.key,Point2(.625f,.625f),RAW,ERROR,NOW))
        f.snapshot = original.copy(fullDepthTimestampNs=CPU-10_000_000)
        assertNull(f.producer.pointClearance(f.capture(),f.key,Point2(.625f,.625f),FULL,ERROR,NOW))
        f.snapshot = original.copy(rawDepthTimestampNs=CPU-10_000_000,rawConfidenceTimestampNs=CPU-10_000_000)
        val raw = requireNotNull(f.producer.pointClearance(f.capture(),f.key,Point2(.625f,.625f),RAW,ERROR,NOW))
        assertEquals(CPU-10_000_000,raw.depthTimestampNs)
        assertPoint(.25,-.25,-2.0,raw.cameraPoint)
        assertNotNull(f.producer.pointClearance(f.capture(),f.key,Point2(.625f,.625f),FULL,ERROR,NOW))
    }

    private class Fixture(measured: MeasuredBodySpaceInput=input(),width: Int=4,height: Int=4,
        depthWidth: Int=4,depthHeight: Int=4) {
        val producer = MeasuredBodySpace().also { it.bindOwner(OWNER) }
        val model = requireNotNull(producer.register(measured))
        val key = MeasuredBodyCaptureKey(OWNER,"original",FRAME,CPU,1,START,END,1)
        var mapper = mapper()
        var snapshot = DepthFrameSnapshot(FRAME,DepthImage16(depthWidth,depthHeight,IntArray(depthWidth*depthHeight) { 2000 }),
            ConfidenceImage8(depthWidth,depthHeight,ByteArray(depthWidth*depthHeight) { -1 }),
            DepthImage16(depthWidth,depthHeight,IntArray(depthWidth*depthHeight) { 2000 }),
            rawDepthTimestampNs=CPU,rawConfidenceTimestampNs=CPU,fullDepthTimestampNs=CPU,cameraImageTimestampNs=CPU,
            cameraPoseEvidence=CameraPoseEvidence(1,FRAME/1_000_000,0f,0f,0f,0f,0f,-1f,
                CameraImageProjection(width,height,width.toFloat(),height.toFloat(),width/2f,height/2f,1f,0f,0f,0f,1f,0f)),
            semanticFrame=SemanticFrameSnapshot(FRAME,CPU,1,SemanticImage8Snapshot.copyOf(SemanticImage(LABEL,10)),
                SemanticImage8Snapshot.copyOf(SemanticImage(SEMANTIC_CONFIDENCE,110))))
        fun capture() = requireNotNull(producer.capture(model,key,snapshot,mapper,NOW))
    }

    private class SemanticImage(private val time: Long,first: Int): Image() {
        val bytes = ByteArray(8) { (first+it).toByte() }
        override fun getWidth() = 2
        override fun getHeight() = 4
        override fun getFormat() = 35
        override fun getTimestamp() = time
        override fun close() = Unit
        override fun getPlanes(): Array<Plane> = arrayOf(object: Plane() {
            override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
            override fun getRowStride() = 2
            override fun getPixelStride() = 1
        })
    }

    private companion object {
        const val FRAME=1_000_000_000L; const val CPU=900_000_000L
        const val LABEL=700_000_000L; const val SEMANTIC_CONFIDENCE=710_000_000L
        const val START=5_000_000_000L; const val END=5_200_000_000L; const val NOW=5_100_000_000L
        const val EPS=1e-7
        val OWNER=BodySpaceOwner(1,"measured-mount",1)
        val RAW=DepthSource.ARCORE_RAW_DEPTH; val FULL=DepthSource.ARCORE_FULL_DEPTH
        val ERROR=BodyDepthObservationError(.04,.001,"declared fixture depth/K/frozen mapping error and absolute axial quantization bound")
        fun input() = MeasuredBodySpaceInput(BodyMeasurementProvenance("fixture ruler/angular measurement",
            BodyMeasurementClock.UNIX_EPOCH_NANOSECONDS,1_000_000L,BodySpaceAxes.RIGHT_UP_BACK,
            "fixture registered body reference mark; transform, bounds and ground use this same origin",
            "fixture Euclidean shape/ground bounds and mount rotation/translation over usable lifetime"),
            doubleArrayOf(0.0,0.0,0.0,1.0),doubleArrayOf(0.0,1.0,0.0),doubleArrayOf(-.4,0.0,-.4),
            doubleArrayOf(.4,1.7,.2),doubleArrayOf(-.4,.4,0.0,1.0,0.0),.01,0.0,.02,.03)
        fun mapper(corners: List<Point2> = listOf(Point2(0f,0f),Point2(1f,0f),Point2(0f,1f),Point2(1f,1f)),
            center: Point2=Point2(.5f,.5f),frameId: Long=FRAME) = requireNotNull(FrozenImageToDepthTransform.create(frameId,corners,center))
        fun assertPoint(x: Double,y: Double,z: Double,actual: BodySpacePoint) {
            assertEquals(x,actual.x,EPS); assertEquals(y,actual.y,EPS); assertEquals(z,actual.z,EPS)
        }
    }
}
