package android.media

import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.util.BitSet
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform
import kr.co.hanium.dreamup.walksafe.navigation.TactileProjectionContext
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/** Actual mask sampler/projector -> actual accepted body producer. Values are explicit fixtures. */
class TactileMeasuredBodySurfaceTest {
    @Test fun publicEntryJoinsActualRawCellsWithoutConvertingServiceEpochToWalkEpoch() {
        val f = Fixture()
        assertNotEquals(f.token.sessionEpoch,f.bodyKey.owner.walkEpoch)
        val result = f.project()
        val surface = requireNotNull(result.measuredSurface)
        assertSame(result.projectionResult.surface,surface.originalSurface)
        assertTrue(surface.cells.size > 100)
        assertEquals(surface.originalSurface.samples,surface.cells.map { it.sample })
        surface.cells.forEach { cell ->
            val point = requireNotNull(cell.clearance)
            assertEquals(cell.sample.depthX,point.depthX); assertEquals(cell.sample.depthY,point.depthY)
            assertEquals(cell.sample.depthCellIndex,point.depthY*64+point.depthX)
            assertEquals(cell.sample.axialDepthM,(-point.cameraPoint.z).toFloat(),0f)
            assertEquals(DepthSource.ARCORE_RAW_DEPTH,point.source); assertEquals(CPU,point.depthTimestampNs)
            assertEquals(245,point.depthPixelConfidence)
            assertEquals(.9607843f,cell.sample.pixelConfidence!!,0f)
            assertEquals(f.bodyKey,point.captureKey)
            assertEquals(LABEL,point.semanticPixel?.labelTimestampNs)
            assertEquals(SEMANTIC_CONFIDENCE,point.semanticPixel?.confidenceTimestampNs)
        }
        assertEquals("opaque original geometry / q0",surface.originalSurface.candidate.capture.token.geometryId)
    }

    @Test fun fullFallbackKeepsActualFullCellTimeAndNoRawConfidence() {
        val f = Fixture(full=true)
        val result = f.project()
        assertEquals(DepthSource.ARCORE_FULL_DEPTH,result.projectionResult.surface?.source)
        requireNotNull(result.measuredSurface).cells.forEach {
            assertNotNull(it.clearance); assertEquals(DepthSource.ARCORE_FULL_DEPTH,it.clearance?.source)
            assertEquals(CPU,it.clearance?.depthTimestampNs); assertNull(it.clearance?.depthPixelConfidence)
            assertNull(it.sample.pixelConfidence)
        }
    }

    @Test fun acceptedReprojectedRawKeepsItsOriginalImageClockWithoutFreshOnlyGate() {
        val f = Fixture(rawTime=CPU-10_000_000)
        val result = f.project()
        assertFalse(requireNotNull(result.projectionResult.surface).depthMatchesCameraImage)
        assertTrue(requireNotNull(result.measuredSurface).cells.all { it.clearance?.depthTimestampNs == CPU-10_000_000 })
    }

    @Test fun sameMetadataWithDifferentActualDepthOrConfidenceLeavesOnlyMismatchedCellsUnknown() {
        val f = Fixture()
        val altered = f.snapshot.rawDepth!!.millimeters.copyOf().also { it[30*64+30] += 10 }
        val confidence = f.snapshot.rawConfidence!!.values.copyOf().also { it[31*64+31] = 230.toByte() }
        val frozen = f.freeze(f.snapshot.copy(rawDepth=DepthImage16(64,64,altered),rawConfidence=ConfidenceImage8(64,64,confidence)))
        val result = requireNotNull(f.project(frozen=frozen).measuredSurface)
        assertNull(result.cells.single { it.sample.depthCellIndex==30*64+30 }.clearance)
        assertNull(result.cells.single { it.sample.depthCellIndex==31*64+31 }.clearance)
        assertTrue(result.cells.filter { it.sample.depthCellIndex !in setOf(30*64+30,31*64+31) }.all { it.clearance!=null })
    }

    @Test fun manipulatedCellIdentityAxialZConfidenceAndOutsideSensorPointAreNeverRepaired() {
        val f = Fixture(); val actual = requireNotNull(f.project().projectionResult.surface)
        val cell = actual.samples.first()
        val invalid = listOf(cell.copy(depthCellIndex=cell.depthCellIndex+1),cell.copy(depthX=cell.depthX+1),
            cell.copy(depthY=cell.depthY+1),cell.copy(axialDepthM=cell.axialDepthM+.001f),
            cell.copy(pixelConfidence=.8f),cell.copy(imageX=-.5))
        invalid.forEach { changed ->
            val surface = withSamples(actual,listOf(changed))
            val joined = TactileMeasuredBodySurface.join(surface,f.frozen,f.binding,f.bodyKey,NOW)
            assertSame(changed,joined.cells.single().sample); assertNull(joined.cells.single().clearance)
        }
    }

    @Test fun holesAndSeparateMaskComponentsArePreservedWithoutFillingUnobservedSpace() {
        val f = Fixture(foreground={x,y -> (x in 20..29 && y in 20..35) || (x in 36..45 && y in 38..53)},
            zeroCells=setOf(26*64+24,27*64+24))
        val result = requireNotNull(f.project().measuredSurface)
        assertEquals(2,result.cells.map { it.sample.componentId }.distinct().size)
        assertTrue(result.cells.none { it.sample.depthCellIndex in setOf(26*64+24,27*64+24) })
        assertTrue(result.cells.all { f.candidate.sensorMask.contains(it.sample.imageX.toInt(),it.sample.imageY.toInt()) })
        assertTrue(result.cells.none { it.sample.imageX.toInt() in 30..35 })
        assertTrue(result.cells.all { it.clearance!=null })
        assertTrue(result.cells.size < 26*34)
    }

    @Test fun absentOrInvalidObservationErrorKeepsOriginalSurfaceWithUnknownCellJoins() {
        val f = Fixture()
        for (error in listOf(null,ERROR.copy(metricPositionErrorM=null),ERROR.copy(axialQuantizationErrorBoundM=null),
            ERROR.copy(metricPositionErrorM=Double.NaN),ERROR.copy(axialQuantizationErrorBoundM=-1.0),ERROR.copy(basis=""))) {
            val binding = f.binding(error=error)
            assertNull(binding)
            val result = f.project(binding=binding)
            assertNotNull(result.projectionResult.surface); assertNull(result.projectionResult.rejection)
            assertTrue(requireNotNull(result.measuredSurface).cells.all { it.clearance==null })
        }
    }

    @Test fun originalGeometryClockDeadlineAndForeignSemanticCaptureMustMatchTheBoundAcquisition() {
        val f = Fixture()
        assertNull(f.binding(version=2))
        assertNull(f.binding(key=f.bodyKey.copy(cameraImageTimestampNs=CPU+1)))
        assertNull(f.binding(capture=f.semantic.copy(originalDeadlineElapsedNs=END+1)))
        val differentToken = FastSamFrameToken(f.token.source,f.token.sessionEpoch+1,FRAME,FRAME,CPU,START,
            f.token.geometryId,64,64)
        val foreign = f.semantic.copy(token=differentToken)
        // No domain conversion: a binding may carry that original service epoch, but not another capture's surface.
        val binding = requireNotNull(f.binding(capture=foreign))
        assertTrue(requireNotNull(f.project(binding=binding).measuredSurface).cells.all { it.clearance==null })
        val identicalCopy = requireNotNull(f.binding(capture=f.semantic.copy()))
        assertTrue(requireNotNull(f.project(binding=identicalCopy).measuredSurface).cells.all { it.clearance==null })
        val other = Fixture()
        assertNull(TactileMeasuredBodyBinding.freeze(f.semantic,1,other.body,f.bodyCapture,f.bodyKey,ERROR))
    }

    @Test fun currentOwnerReferenceClockGeometryLeaseRetirementAreActuallyConsumedByBodyProducer() {
        val f = Fixture()
        val changed = listOf(f.bodyKey.copy(owner=OWNER.copy(walkEpoch=4)),f.bodyKey.copy(owner=OWNER.copy(mountGeneration=2)),
            f.bodyKey.copy(poseReferenceId=8),f.bodyKey.copy(frameTimestampNs=FRAME+1),
            f.bodyKey.copy(cameraImageTimestampNs=CPU+1),f.bodyKey.copy(capturedAtElapsedNs=START+1),
            f.bodyKey.copy(validUntilElapsedNs=END+1),f.bodyKey.copy(geometryVersion=2),f.bodyKey.copy(leaseIdentity="renewed"))
        changed.forEach { key -> assertTrue(requireNotNull(f.project(key=key).measuredSurface).cells.all { it.clearance==null }) }
        f.body.retire()
        // Binding metadata still matches, so this calls the actual pointClearance retirement guard.
        assertTrue(requireNotNull(f.project().measuredSurface).cells.all { it.clearance==null })
    }

    @Test fun foreignProjectionShapeCalibrationPoseAndOriginalContextCannotAcquireClearance() {
        val f = Fixture(); val actual = requireNotNull(f.project().projectionResult.surface)
        val foreignH = f.mapper.imagePixelsToDepthUvMatrix(64,64)!!.also { it[2]=.05 }
        val frozen = requireNotNull(FrozenUnknownDepthCapture.freeze(f.token,f.snapshot,foreignH,FRAME))
        assertTrue(requireNotNull(f.project(frozen=frozen).measuredSurface).cells.all { it.clearance==null })
        val wrongPose = f.snapshot.copy(cameraPoseEvidence=f.pose.copy(referenceId=8))
        assertTrue(requireNotNull(f.project(frozen=f.freeze(wrongPose)).measuredSurface).cells.all { it.clearance==null })
        val resized = f.snapshot.copy(rawDepth=DepthImage16(32,32,IntArray(1024){2000}),rawConfidence=ConfidenceImage8(32,32,ByteArray(1024){245.toByte()}))
        assertTrue(requireNotNull(f.project(frozen=f.freeze(resized)).measuredSurface).cells.all { it.clearance==null })
        assertTrue(TactileMeasuredBodySurface.join(withSamples(actual,actual.samples,time=CPU-1),f.frozen,f.binding,f.bodyKey,NOW).cells.all { it.clearance==null })
        val result = f.project(context=f.context.copy(nowElapsedRealtimeMs=START/1_000_000+1))
        assertEquals(TactileSurfaceRejection.FOREIGN_CAPTURE,result.projectionResult.rejection); assertNull(result.measuredSurface)
    }

    @Test fun sameKeysAndIntrinsicsWithDifferentOriginalPositionOrForwardCannotJoin() {
        val f = Fixture()
        for (pose in listOf(f.pose.copy(positionX=.1f),f.pose.copy(forwardZ=1f))) {
            val projected = f.project(frozen=f.freeze(f.snapshot.copy(cameraPoseEvidence=pose)))
            assertNotNull(projected.projectionResult.surface)
            assertTrue(requireNotNull(projected.measuredSurface).cells.all { it.clearance==null })
        }
    }

    @Test fun sameMetadataWithForeignSemanticPayloadCannotJoinAndBothAbsentIsAllowed() {
        val f = Fixture()
        val foreign = SemanticFrameSnapshot(FRAME,CPU,7,SemanticImage8Snapshot.copyOf(SemanticImage(LABEL,20)),
            SemanticImage8Snapshot.copyOf(SemanticImage(SEMANTIC_CONFIDENCE,120)))
        val projected = f.project(frozen=f.freeze(f.snapshot.copy(semanticFrame=foreign)))
        assertNotNull(projected.projectionResult.surface)
        assertTrue(requireNotNull(projected.measuredSurface).cells.all { it.clearance==null })
        val absent = Fixture(includeSemantic=false)
        assertTrue(requireNotNull(absent.project().measuredSurface).cells.all { it.clearance!=null })
    }

    @Test fun originalMapperFrameIdIsCheckedWithoutRetaggingItFromCameraTimestamp() {
        val f = Fixture()
        val changed = FastSamFrameToken(f.token.source,f.token.sessionEpoch,FRAME+1,FRAME,CPU,START,
            f.token.geometryId,64,64)
        assertNull(f.binding(capture=f.semantic.copy(token=changed)))
        val frozen = requireNotNull(FrozenUnknownDepthCapture.freeze(changed,f.snapshot,
            f.mapper.imagePixelsToDepthUvMatrix(64,64),FRAME+1))
        assertEquals(TactileSurfaceRejection.FOREIGN_CAPTURE,f.project(frozen=frozen).projectionResult.rejection)
    }

    @Test fun originalPoseNestedPlaneGeometryIsOwnedAndDoesNotEscapeAsMutableLists() {
        val polygon = mutableListOf(Vec3(-1f,0f,-1f),Vec3(1f,0f,-1f),Vec3(1f,0f,1f),Vec3(-1f,0f,1f))
        val planes = mutableListOf(CapturedHorizontalPlane(7,FRAME/1_000_000,polygon))
        val f = Fixture(planes=planes)
        polygon.clear(); planes.clear()
        assertEquals(1,f.bodyCapture.pose.horizontalPlaneCandidates.size)
        val copiedPolygon = f.bodyCapture.pose.horizontalPlaneCandidates.single().polygonInAnchor
        assertEquals(4,copiedPolygon.size)
        try { (copiedPolygon as MutableList).clear(); fail("nested pose polygon must be immutable") }
        catch (_: UnsupportedOperationException) { }
        try { (f.bodyCapture.pose.horizontalPlaneCandidates as MutableList).clear(); fail("pose planes must be immutable") }
        catch (_: UnsupportedOperationException) { }
        assertTrue(requireNotNull(f.project().measuredSurface).cells.all { it.clearance!=null })
    }

    @Test fun ownedOriginalInputsAndOutputListsCannotRewriteAlreadyCapturedCellEvidence() {
        val f = Fixture(); val first = requireNotNull(f.project().measuredSurface)
        f.measured.cameraToBodyTranslationM?.fill(100.0)
        f.snapshot.rawDepth!!.millimeters.fill(0); f.snapshot.rawConfidence!!.values.fill(0)
        val later = requireNotNull(f.project().measuredSurface)
        assertEquals(first.cells.map { it.sample },later.cells.map { it.sample })
        assertEquals(first.cells.map { it.clearance?.bodyPoint },later.cells.map { it.clearance?.bodyPoint })
        assertTrue(later.cells.all { it.clearance!=null })
        try { (later.cells as MutableList).clear(); fail("owned result list must be immutable") }
        catch (_: UnsupportedOperationException) { }
        assertEquals(first.cells.size,later.cells.size)
    }

    @Test fun originalProjectAndItsExpiryRejectionStayAvailableOnTheNewEntry() {
        val f = Fixture(); val legacy = TactileMaskSurfaceProjector().project(f.candidate,f.frozen,NOW,f.context)
        val result = f.project(binding=null)
        assertEquals(legacy.surface?.samples,result.projectionResult.surface?.samples)
        assertEquals(legacy.surface?.source,result.projectionResult.surface?.source)
        assertEquals(legacy.surface?.captureContext,result.projectionResult.surface?.captureContext)
        assertNull(result.projectionResult.rejection)
        assertTrue(requireNotNull(result.measuredSurface).cells.all { it.clearance==null })
        assertEquals(TactileSurfaceRejection.EXPIRED_CAPTURE,f.project(now=END+1).projectionResult.rejection)
        assertNull(f.project(now=START-1).measuredSurface)
    }

    private class Fixture(full: Boolean=false,rawTime: Long=CPU,includeSemantic: Boolean=true,
        foreground: (Int,Int)->Boolean={x,y -> x in 26..37 && y in 24..47},zeroCells: Set<Int> = emptySet(),
        planes: List<CapturedHorizontalPlane> = emptyList()) {
        val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA,41,FRAME,FRAME,CPU,START,
            "opaque original geometry / q0",64,64)
        // A native SDK Frame is never created/queried: this receipt needs an identity-only unit fixture.
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").also { it.isAccessible=true }.get(null) as Unsafe
        val semantic = TactileSemanticCapture(CameraImageTimestampEvidence(Any(),41,
            unsafe.allocateInstance(Frame::class.java) as Frame,FRAME,CPU),token,0,END)
        val pose = CameraPoseEvidence(7,FRAME/1_000_000,0f,1.5f,0f,0f,0f,-1f,
            CameraImageProjection(64,64,96f,96f,32f,32f,1f,0f,0f,0f,1f,0f),horizontalPlaneCandidates=planes)
        val mapper = requireNotNull(FrozenImageToDepthTransform.create(FRAME,
            listOf(Point2(0f,0f),Point2(1f,0f),Point2(0f,1f),Point2(1f,1f)),Point2(.5f,.5f)))
        val snapshot = DepthFrameSnapshot(FRAME,
            DepthImage16(64,64,IntArray(4096){if(full || it in zeroCells) 0 else 2000}),
            ConfidenceImage8(64,64,ByteArray(4096){if(full) 0 else 245.toByte()}),
            if(full) DepthImage16(64,64,IntArray(4096){2000}) else null,
            rawDepthTimestampNs=rawTime,rawConfidenceTimestampNs=rawTime,fullDepthTimestampNs=if(full) CPU else null,
            cameraImageTimestampNs=CPU,cameraPoseEvidence=pose,
            semanticFrame=if(includeSemantic) SemanticFrameSnapshot(FRAME,CPU,7,SemanticImage8Snapshot.copyOf(SemanticImage(LABEL,10)),
                SemanticImage8Snapshot.copyOf(SemanticImage(SEMANTIC_CONFIDENCE,110))) else null)
        val measured = MeasuredBodySpaceInput(BodyMeasurementProvenance("declared fixture registration",
            BodyMeasurementClock.UNIX_EPOCH_NANOSECONDS,1,BodySpaceAxes.RIGHT_UP_BACK,"fixture common body reference mark",
            "explicit Euclidean/Hausdorff and mount lifetime bounds"),doubleArrayOf(0.0,0.0,0.0,1.0),
            doubleArrayOf(0.0,1.0,0.0),doubleArrayOf(-.4,0.0,-.4),doubleArrayOf(.4,1.7,.2),
            doubleArrayOf(-.4,.4,0.0,1.0,0.0),.01,0.0,.02,.03)
        val body = MeasuredBodySpace().also { it.bindOwner(OWNER) }
        val registration = requireNotNull(body.register(measured))
        val bodyKey = MeasuredBodyCaptureKey(OWNER,"original body lease",FRAME,CPU,7,START,END,1)
        val bodyCapture = requireNotNull(body.capture(registration,bodyKey,snapshot,mapper,NOW))
        val frozen = freeze(snapshot)
        val context = TactileProjectionContext(FRAME,null,null,null,null,null,null,null,START/1_000_000,NOW/1_000_000)
        val candidate: TactileSemanticMaskCandidate
        val binding = requireNotNull(binding())
        init {
            val bits = BitSet(4096)
            for(y in 0 until 64) for(x in 0 until 64) if(foreground(x,y)) bits.set(y*64+x)
            val ctor = InstanceMask::class.java.declaredConstructors.single().also { it.isAccessible=true }
            val mask = ctor.newInstance(64,64,0,0,64,64,bits.cardinality(),17,0f,0f,64f,64f,.9f,bits) as InstanceMask
            val classes = TactileSemanticKind.values().map { it.className }
            val model = ModelRuntimeConfig(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,"model/existing.tflite",768,
                classes,classes.toSet(),classes.associateWith { .25f },artifactSha256="d".repeat(64))
            val config = TwoModelRuntimeConfig("surface-fixture",null,model.key,null,model,null,null)
            val named = TactileNamedSemanticEvidence.fromResult(semantic,AndroidDetectionResult(
                listOf(DetectionCandidate(TactileSemanticKind.LINE.className,.9f,RectNorm(0f,0f,1f,1f))),
                timing=AndroidDetectorTiming(modelKey=model.key)),TactileSemanticCoordinates.SENSOR_ORIGINAL)
            candidate = TactileSemanticMaskSelector(config).select(named,TactileInstanceMaskEvidence(semantic,token,
                listOf(mask),TactileSemanticCoordinates.SENSOR_ORIGINAL),NOW).candidates.single()
        }
        fun freeze(snapshot: DepthFrameSnapshot) = requireNotNull(FrozenUnknownDepthCapture.freeze(token,snapshot,
            mapper.imagePixelsToDepthUvMatrix(64,64),FRAME))
        fun binding(capture: TactileSemanticCapture=semantic,version: Long=1,key: MeasuredBodyCaptureKey=bodyKey,
            error: BodyDepthObservationError?=ERROR) = TactileMeasuredBodyBinding.freeze(capture,version,body,bodyCapture,key,error)
        fun project(frozen: FrozenUnknownDepthCapture=this.frozen,binding: TactileMeasuredBodyBinding?=this.binding,
            key: MeasuredBodyCaptureKey=bodyKey,now: Long=NOW,context: TactileProjectionContext=this.context) =
            TactileMaskSurfaceProjector().projectWithMeasuredBody(candidate,frozen,now,context,binding,key)
    }

    private class SemanticImage(private val time: Long,first: Int): Image() {
        private val bytes = ByteArray(8) { (first+it).toByte() }
        override fun getWidth()=2
        override fun getHeight()=4
        override fun getFormat()=35
        override fun getTimestamp()=time
        override fun close()=Unit
        override fun getPlanes(): Array<Plane> = arrayOf(object: Plane() {
            override fun getBuffer(): ByteBuffer=ByteBuffer.wrap(bytes)
            override fun getRowStride()=2
            override fun getPixelStride()=1
        })
    }

    private companion object {
        const val FRAME=9_000_000_000L; const val CPU=8_900_000_000L
        const val START=100_000_000_000L; const val END=100_800_000_000L; const val NOW=100_050_000_000L
        const val LABEL=6_000_000_000L; const val SEMANTIC_CONFIDENCE=6_100_000_000L
        val OWNER=BodySpaceOwner(3,"measured mount",1)
        val ERROR=BodyDepthObservationError(.04,.001,"explicit depth/K/frozen registration metric and axial bounds")
        fun withSamples(s: TactileMaskSurface,samples: List<TactileSurfaceSample>,time: Long=s.depthTimestampNs) =
            TactileMaskSurface(s.candidate,s.source,time,s.depthMatchesCameraImage,s.pose,s.samplingStatus,s.samplingReason,
                s.depthWidth,s.depthHeight,s.captureContext,samples)
    }
}
